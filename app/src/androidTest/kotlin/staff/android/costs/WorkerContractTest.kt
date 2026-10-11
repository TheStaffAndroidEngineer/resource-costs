package staff.android.costs

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import staff.android.costs.core.*

/** Framework-controlled tests do not establish OS scheduling latency or battery savings. */
@RunWith(AndroidJUnit4::class)
@SuppressLint("RestrictedApi")
class WorkerContractTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var testManager: WorkManager? = null

    private fun factory(refresh: suspend () -> RefreshOutcome) = object : WorkerFactory() {
        override fun createWorker(appContext: Context, workerClassName: String, parameters: WorkerParameters): ListenableWorker? =
            if (workerClassName == PriceWorker::class.java.name) PriceWorker(appContext, parameters, refresh) else null
    }

    private fun worker(attempt: Int = 0, refresh: suspend () -> RefreshOutcome): PriceWorker =
        TestListenableWorkerBuilder<PriceWorker>(context, runAttemptCount = attempt)
            .setWorkerFactory(factory(refresh)).build()

    @After fun releaseTestManager() {
        testManager?.let {
            it.cancelAllWork().result.get(10, TimeUnit.SECONDS)
            WorkManagerTestInitHelper.closeWorkDatabase()
            // Restore the application's original manager for other instrumentation classes.
            WorkManagerImpl.setDelegate(null)
        }
        testManager = null
    }

    @Test fun everySuccessfulRepositoryOutcomeCompletesThisWorkerAttempt() = runBlocking {
        for (outcome in RefreshOutcome.entries) {
            var calls = 0
            val result = worker { calls++; outcome }.doWork()
            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, calls)
        }
    }

    @Test fun rateLimitServerAndIoFailuresRetryBelowTheAttemptCap() = runBlocking {
        for (attempt in listOf(0, 2)) {
            for (failure in listOf(HttpFailure(429), HttpFailure(503), IOException("connection failed"))) {
                assertEquals(ListenableWorker.Result.retry(), worker(attempt) { throw failure }.doWork())
            }
        }
    }

    @Test fun theFourthTransientAttemptEndsThisCycleRatherThanRetryingForever() = runBlocking {
        for (failure in listOf(HttpFailure(429), HttpFailure(503), IOException("connection failed"))) {
            assertEquals(ListenableWorker.Result.failure(), worker(3) { throw failure }.doWork())
        }
    }

    @Test fun nonRetryableHttpAndInvalidRepresentationsFailThisAttempt() = runBlocking {
        for (failure in listOf(HttpFailure(401), HttpFailure(404), ProtocolFailure("bad validator"), IllegalArgumentException("bad document"))) {
            assertEquals(ListenableWorker.Result.failure(), worker { throw failure }.doWork())
        }
    }

    @Test fun cancellationPropagatesInsteadOfBecomingRetryOrFailure() = runBlocking {
        val observed = runCatching { worker { throw CancellationException("worker stopped") }.doWork() }.exceptionOrNull()
        assertTrue(observed is CancellationException)
        assertEquals("worker stopped", observed!!.message)
    }

    private fun initialize(refresh: suspend () -> RefreshOutcome): WorkManager {
        val configuration = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerCoroutineContext(Dispatchers.Unconfined)
            .setWorkerFactory(factory(refresh))
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        return WorkManager.getInstance(context).also { testManager = it }
    }

    private fun info(manager: WorkManager, id: UUID) = manager.getWorkInfoById(id).get(10, TimeUnit.SECONDS)!!
    private fun unique(manager: WorkManager) = manager.getWorkInfosForUniqueWork(PriceWork.name).get(10, TimeUnit.SECONDS)
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() >= deadline) fail(description)
            SystemClock.sleep(10)
        }
    }

    @Test fun uniqueKeepRetainsOnePeriodicIdAndDeclaredConstraintsUntilDisabled() {
        val calls = AtomicInteger()
        val manager = initialize { calls.incrementAndGet(); RefreshOutcome.FreshCache }
        PriceWork.enable(context)
        val first = unique(manager).single()
        assertEquals(NetworkType.CONNECTED, first.constraints.requiredNetworkType)
        assertTrue(first.constraints.requiresBatteryNotLow())
        assertEquals(TimeUnit.MINUTES.toMillis(15), first.periodicityInfo!!.repeatIntervalMillis)
        PriceWork.enable(context)
        assertEquals(first.id, unique(manager).single().id)
        assertEquals(0, calls.get())
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(first.id)
        awaitCondition("First constrained execution should finish") {
            calls.get() == 1 && info(manager, first.id).state == WorkInfo.State.ENQUEUED
        }
        assertFalse(info(manager, first.id).state.isFinished)
        PriceWork.disable(context)
        awaitCondition("Disable should cancel the unique schedule") { info(manager, first.id).state == WorkInfo.State.CANCELLED }
    }

    @Test fun periodicFailureReturnsToTheScheduleAndTheNextOpportunityCanRun() {
        val calls = AtomicInteger()
        val manager = initialize {
            if (calls.incrementAndGet() == 1) throw ProtocolFailure("reject this response")
            RefreshOutcome.NotModified
        }
        PriceWork.enable(context)
        val id = unique(manager).single().id
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(id)
        awaitCondition("Failure must reset the periodic cycle") {
            calls.get() == 1 && info(manager, id).state == WorkInfo.State.ENQUEUED
        }
        assertEquals(0, info(manager, id).runAttemptCount)
        assertFalse(info(manager, id).state.isFinished)
        driver.setPeriodDelayMet(id)
        driver.setAllConstraintsMet(id)
        awaitCondition("The next controlled opportunity should run") {
            calls.get() == 2 && info(manager, id).state == WorkInfo.State.ENQUEUED
        }
        assertEquals(id, unique(manager).single().id)
    }
}
