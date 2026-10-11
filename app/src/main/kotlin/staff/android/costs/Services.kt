package staff.android.costs

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import kotlinx.coroutines.*
import staff.android.costs.core.*
import java.io.File
import java.util.concurrent.TimeUnit

class AtomicSnapshotStorage(context: Context) : SnapshotStorage {
    private val file = AtomicFile(File(context.filesDir, "prices.json"))
    override fun read(): Snapshot? = try { file.openRead().bufferedReader().use { PriceCodec.decodeSnapshot(it.readText()) } } catch (_: Exception) { null }
    override fun write(snapshot: Snapshot) {
        val output = file.startWrite()
        try {
            output.write(PriceCodec.encode(snapshot).toByteArray())
            output.fd.sync()
            file.finishWrite(output)
        }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        // AtomicFile can log a rename failure without throwing; verify the accepted pair.
        val readback = file.openRead().bufferedReader().use { PriceCodec.decodeSnapshot(it.readText()) }
        if (readback != snapshot) throw java.io.IOException("Snapshot readback mismatch")
    }
}

class GoodsApplication : Application() {
    val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val transport = LocalPriceTransport()
    val repository by lazy { PriceRepository(processScope, transport, AtomicSnapshotStorage(this), System::currentTimeMillis) }
}

/** Optional periodic validation. The OS chooses eligible execution times. */
object PriceWork {
    const val name = "common-goods-price-refresh"
    fun enable(context: Context) {
        val request = PeriodicWorkRequestBuilder<PriceWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun disable(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(name) }
}

class PriceWorker(context: Context, parameters: WorkerParameters, private val refresh: suspend () -> RefreshOutcome) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, {
        (context.applicationContext as GoodsApplication).repository.refresh()
    })
    override suspend fun doWork(): Result {
        fun transientFailure() = if (runAttemptCount < 3) Result.retry() else Result.failure()
        return try { refresh(); Result.success() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: HttpFailure) { if (failure.status == 429 || failure.status in 500..599) transientFailure() else Result.failure() }
        catch (_: ProtocolFailure) { Result.failure() }
        catch (_: java.io.IOException) { transientFailure() }
        catch (_: IllegalArgumentException) { Result.failure() }
    }
}
