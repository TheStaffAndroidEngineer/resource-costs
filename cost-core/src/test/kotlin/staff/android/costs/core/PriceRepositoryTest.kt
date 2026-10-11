package staff.android.costs.core

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Contract tests authored independently of the repository implementation. */
@OptIn(ExperimentalCoroutinesApi::class)
class PriceRepositoryTest {
    private fun document(revision: Long = 1, cents: Long = 12_900) = PriceDocument(
        revision, PriceCodec.expectedIds.sorted().map { Price(it, cents, true) }, "A useful price document",
    )
    private fun snapshot(at: Long = 1_000, revision: Long = 1) =
        Snapshot(document(revision), "\"prices-$revision\"", at)
    private fun body(document: PriceDocument = document()) = Json.encodeToString(document).encodeToByteArray()
    private fun modified(revision: Long = 1) = Reply(200, "\"prices-$revision\"", body(document(revision)))

    private class MemoryStorage(initial: Snapshot? = null) : SnapshotStorage {
        var stored = initial
        val writes = mutableListOf<Snapshot>()
        var writeFailure: IOException? = null
        override fun read() = stored
        override fun write(snapshot: Snapshot) {
            writeFailure?.let { throw it }
            writes += snapshot
            stored = snapshot
        }
    }

    private fun TestScope.owner() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test fun freshnessUsesAStrictFiveMinuteBoundaryAndRejectsBackwardClockAge() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val repository = PriceRepository(scope, PriceTransport { error("No transport expected") }, MemoryStorage(), { 0 })
            val saved = snapshot(1_000)
            assertFalse(repository.isFresh(null, 1_000))
            assertFalse(repository.isFresh(saved, 999))
            assertTrue(repository.isFresh(saved, 1_000))
            assertTrue(repository.isFresh(saved, 300_999))
            assertFalse(repository.isFresh(saved, 301_000))
            assertFalse(repository.isFresh(saved, Long.MAX_VALUE))
            assertThrows(IllegalArgumentException::class.java) {
                PriceRepository(scope, PriceTransport { modified() }, MemoryStorage(), { 0 }, 0)
            }
        } finally { scope.cancel() }
    }

    @Test fun aFreshNonforcedReadHasNoExchangeOrPersistenceWrite() = runTest {
        val scope = owner()
        try {
            val storage = MemoryStorage(snapshot())
            var calls = 0
            val repository = PriceRepository(scope, PriceTransport { calls++; modified() }, storage, { 1_001 })
            assertEquals(RefreshOutcome.FreshCache, repository.refresh())
            assertEquals(0, calls)
            assertTrue(storage.writes.isEmpty())
            assertEquals(snapshot(), repository.state.value.snapshot)
        } finally { scope.cancel() }
    }

    @Test fun overlappingForcedCallersShareOneConditionalExchangeAndOneCommit() = runTest {
        val scope = owner()
        try {
            val storage = MemoryStorage(snapshot())
            val response = CompletableDeferred<Reply>()
            val validators = mutableListOf<String?>()
            val repository = PriceRepository(scope, PriceTransport { validators += it; response.await() }, storage, { 2_000 })
            val first = async { repository.refresh(force = true) }
            val second = async { repository.refresh(force = true) }
            runCurrent()
            assertEquals(listOf("\"prices-1\""), validators)
            assertEquals(PriceState(snapshot(), refreshing = true), repository.state.value)
            response.complete(Reply(304, "\"prices-1\"", byteArrayOf()))
            assertEquals(RefreshOutcome.NotModified, first.await())
            assertEquals(RefreshOutcome.NotModified, second.await())
            assertEquals(listOf(snapshot().copy(checkedAtMillis = 2_000)), storage.writes)
            assertFalse(repository.state.value.refreshing)
        } finally { scope.cancel() }
    }

    @Test fun anAutomaticCallerJoinsAnExistingForcedFlightEvenWhileOldSnapshotIsFresh() = runTest {
        val scope = owner()
        try {
            val response = CompletableDeferred<Reply>()
            var calls = 0
            val repository = PriceRepository(scope, PriceTransport { calls++; response.await() }, MemoryStorage(snapshot()), { 2_000 })
            val forced = async { repository.refresh(true) }
            runCurrent()
            val automatic = async { repository.refresh() }
            runCurrent()
            assertFalse(automatic.isCompleted)
            response.complete(modified(2))
            assertEquals(RefreshOutcome.Modified, forced.await())
            assertEquals(RefreshOutcome.Modified, automatic.await())
            assertEquals(1, calls)
        } finally { scope.cancel() }
    }

    @Test fun overlappingFailuresAreSharedAndALaterAttemptCanRetry() = runTest {
        val scope = owner()
        try {
            val release = CompletableDeferred<Unit>()
            val expected = IOException("fixture unavailable")
            var calls = 0
            val storage = MemoryStorage(snapshot())
            val repository = PriceRepository(scope, PriceTransport {
                calls++
                if (calls == 1) { release.await(); throw expected }
                modified(2)
            }, storage, { 2_000 })
            val first = async { runCatching { repository.refresh(true) } }
            val second = async { runCatching { repository.refresh(true) } }
            runCurrent()
            release.complete(Unit)
            for (failure in listOf(first.await().exceptionOrNull(), second.await().exceptionOrNull())) {
                assertTrue(failure is IOException)
                assertEquals(expected.message, failure!!.message)
            }
            assertEquals(1, calls)
            assertEquals(snapshot(), repository.state.value.snapshot)
            assertNotNull(repository.state.value.error)
            assertTrue(storage.writes.isEmpty())
            assertEquals(RefreshOutcome.Modified, repository.refresh(true))
            assertEquals(2, calls)
            assertNull(repository.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun cancellingOneWaiterDoesNotCancelTheProcessOwnedFlightOrOtherWaiter() = runTest {
        val scope = owner()
        try {
            val response = CompletableDeferred<Reply>()
            var calls = 0
            val storage = MemoryStorage()
            val repository = PriceRepository(scope, PriceTransport { calls++; response.await() }, storage, { 2_000 })
            val first = async { repository.refresh() }
            val second = async { repository.refresh() }
            runCurrent()
            first.cancelAndJoin()
            assertTrue(repository.state.value.refreshing)
            response.complete(modified())
            assertEquals(RefreshOutcome.Modified, second.await())
            assertEquals(1, calls)
            assertEquals(1, storage.writes.size)
        } finally { scope.cancel() }
    }

    @Test fun cancellingTheOwnerBeforeTransportCompletesPreservesThePreviousCommit() = runTest {
        val scope = owner()
        val storage = MemoryStorage(snapshot())
        val response = CompletableDeferred<Reply>()
        val repository = PriceRepository(scope, PriceTransport { response.await() }, storage, { 2_000 })
        val caller = async { runCatching { repository.refresh(true) } }
        runCurrent()
        scope.cancel()
        assertTrue(caller.await().exceptionOrNull() is CancellationException)
        assertTrue(storage.writes.isEmpty())
        assertEquals(PriceState(snapshot()), repository.state.value)
    }

    @Test fun aChangedResponseReplacesBodyValidatorAndCheckedAtTogether() = runTest {
        val scope = owner()
        try {
            val storage = MemoryStorage(snapshot())
            val validators = mutableListOf<String?>()
            val replacement = document(2, 11_500)
            val repository = PriceRepository(scope, PriceTransport {
                validators += it
                Reply(200, "\"prices-2\"", body(replacement))
            }, storage, { 9_000 })
            assertEquals(RefreshOutcome.Modified, repository.refresh(true))
            val expected = Snapshot(replacement, "\"prices-2\"", 9_000)
            assertEquals(listOf("\"prices-1\""), validators)
            assertEquals(listOf(expected), storage.writes)
            assertEquals(expected, repository.state.value.snapshot)
            assertEquals(11_500L, repository.state.value.snapshot!!.document.prices.first().cents)
        } finally { scope.cancel() }
    }

    @Test fun anUnchangedReplyMayOmitItsValidatorAndAdvancesOnlySuccessfulCheckTime() = runTest {
        val scope = owner()
        try {
            val storage = MemoryStorage(snapshot())
            val repository = PriceRepository(scope, PriceTransport { Reply(304, null, byteArrayOf()) }, storage, { 400_000 })
            assertEquals(RefreshOutcome.NotModified, repository.refresh())
            assertEquals(snapshot().copy(checkedAtMillis = 400_000), repository.state.value.snapshot)
            assertEquals(1, storage.writes.size)
        } finally { scope.cancel() }
    }

    @Test fun invalidConditionalResponsesCannotCreateOrReplaceAStoredRepresentation() = runTest {
        for ((initial, reply) in listOf(
            null to Reply(304, null, byteArrayOf()),
            snapshot() to Reply(304, "\"different\"", byteArrayOf()),
            snapshot() to Reply(304, "\"prices-1\"", byteArrayOf(1)),
        )) {
            val scope = owner()
            try {
                val storage = MemoryStorage(initial)
                val repository = PriceRepository(scope, PriceTransport { reply }, storage, { 2_000 })
                assertTrue(runCatching { repository.refresh(true) }.exceptionOrNull() is ProtocolFailure)
                assertEquals(initial, repository.state.value.snapshot)
                assertEquals(initial, storage.stored)
                assertTrue(storage.writes.isEmpty())
                assertNotNull(repository.state.value.error)
            } finally { scope.cancel() }
        }
    }

    @Test fun malformedOrIncompleteModifiedResponsesPreserveAllPreviousState() = runTest {
        val replies = listOf(
            Reply(200, null, body()),
            Reply(200, "invalid", body()),
            Reply(200, "\"prices-2\"", "{malformed".encodeToByteArray()),
            Reply(200, "\"prices-2\"", body(document().copy(prices = document().prices.dropLast(1)))),
            Reply(200, "\"prices-2\"", ByteArray(PriceCodec.maxBodyBytes + 1)),
        )
        for (reply in replies) {
            val scope = owner()
            try {
                val storage = MemoryStorage(snapshot())
                val repository = PriceRepository(scope, PriceTransport { reply }, storage, { 2_000 })
                assertTrue(runCatching { repository.refresh(true) }.isFailure)
                assertEquals(snapshot(), repository.state.value.snapshot)
                assertEquals(snapshot(), storage.stored)
                assertTrue(storage.writes.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test fun persistenceFailurePreventsBothModifiedAndNotModifiedPublication() = runTest {
        for (reply in listOf(modified(2), Reply(304, null, byteArrayOf()))) {
            val scope = owner()
            try {
                val failure = IOException("disk unavailable")
                val storage = MemoryStorage(snapshot()).also { it.writeFailure = failure }
                val repository = PriceRepository(scope, PriceTransport { reply }, storage, { 2_000 })
                val observed = runCatching { repository.refresh(true) }.exceptionOrNull()
                assertTrue(observed is IOException)
                assertEquals(failure.message, observed!!.message)
                assertEquals(snapshot(), repository.state.value.snapshot)
                assertEquals(snapshot(), storage.stored)
                assertTrue(storage.writes.isEmpty())
            } finally { scope.cancel() }
        }
    }

    @Test fun aHttpFailureRetainsItsStatusWithoutManufacturingAnEmptySnapshot() = runTest {
        val scope = owner()
        try {
            val storage = MemoryStorage()
            val repository = PriceRepository(scope, PriceTransport { Reply(503, null, byteArrayOf()) }, storage, { 2_000 })
            val failure = runCatching { repository.refresh() }.exceptionOrNull()
            assertEquals(503, (failure as HttpFailure).status)
            assertNull(repository.state.value.snapshot)
            assertNotNull(repository.state.value.error)
            assertFalse(repository.state.value.refreshing)
            assertTrue(storage.writes.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun validationCopiesThePriceListAndRejectsInvalidRestoredMetadata() {
        val source = document().prices.toMutableList()
        val accepted = PriceCodec.validate(snapshot().copy(document = document().copy(prices = source)))
        source.clear()
        assertEquals(6, accepted.document.prices.size)
        assertThrows(UnsupportedOperationException::class.java) { (accepted.document.prices as MutableList).clear() }
        for (invalid in listOf(snapshot(-1), snapshot().copy(etag = "\"bad\r\nheader\""), snapshot().copy(etag = "bare"))) {
            assertThrows(IllegalArgumentException::class.java) { PriceCodec.validate(invalid) }
        }
        assertEquals(accepted, PriceCodec.decodeSnapshot(PriceCodec.encode(accepted)))
    }

    @Test fun documentValidationRequiresExactlyTheSupportedProductsAndSanePrices() {
        val valid = document()
        val bad = listOf(
            valid.copy(revision = 0),
            valid.copy(prices = valid.prices.dropLast(1) + valid.prices.first()),
            valid.copy(prices = valid.prices.map { if (it.id == "camera") it.copy(id = "unknown") else it }),
            valid.copy(prices = valid.prices.map { it.copy(cents = -1) }),
            valid.copy(prices = valid.prices.map { it.copy(cents = 100_000_001) }),
        )
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { PriceCodec.validate(it) } }
        assertTrue(runCatching { PriceCodec.decodeDocument(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
    }
}
