package staff.android.costs.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Collections

@Serializable data class Price(val id: String, val cents: Long, val available: Boolean)
@Serializable data class PriceDocument(val revision: Long, val prices: List<Price>, val editorial: String = "")
@Serializable data class Snapshot(val document: PriceDocument, val etag: String, val checkedAtMillis: Long)
data class PriceState(val snapshot: Snapshot? = null, val refreshing: Boolean = false, val error: String? = null)
enum class RefreshOutcome { FreshCache, Modified, NotModified }
data class Reply(val status: Int, val etag: String?, val body: ByteArray)
fun interface PriceTransport { suspend fun fetch(etag: String?): Reply }
interface SnapshotStorage { fun read(): Snapshot?; fun write(snapshot: Snapshot) }
class ProtocolFailure(message: String) : IOException(message)
class HttpFailure(val status: Int) : IOException("HTTP $status")

object PriceCodec {
    private val json = Json { ignoreUnknownKeys = true }
    val expectedIds: Set<String> = setOf("camera", "tea", "bag", "lamp", "mug", "headphones")
    const val maxBodyBytes = 256 * 1024
    fun decodeDocument(bytes: ByteArray): PriceDocument {
        require(bytes.size <= maxBodyBytes) { "Oversized document" }
        return validate(json.decodeFromString<PriceDocument>(bytes.decodeToString(throwOnInvalidSequence = true)))
    }
    fun validate(document: PriceDocument): PriceDocument {
        require(document.revision > 0)
        require(document.prices.size == expectedIds.size && document.prices.map { it.id }.toSet() == expectedIds)
        require(document.prices.all { it.cents in 0..100_000_000L })
        return document.copy(prices = Collections.unmodifiableList(document.prices.toList()))
    }
    fun validate(snapshot: Snapshot): Snapshot {
        require(snapshot.checkedAtMillis >= 0)
        require(snapshot.etag.length in 2..256 && !snapshot.etag.contains('\r') && !snapshot.etag.contains('\n'))
        require(snapshot.etag.matches(Regex("(?:W/)?\"[!#-~]*\""))) { "Invalid validator" }
        return snapshot.copy(document = validate(snapshot.document))
    }
    fun encode(snapshot: Snapshot): String = json.encodeToString(snapshot)
    fun decodeSnapshot(text: String): Snapshot = validate(json.decodeFromString<Snapshot>(text))
}

/** One process-owned flight. Cancelling a waiter does not cancel another consumer's refresh. */
class PriceRepository(
    private val scope: CoroutineScope,
    private val transport: PriceTransport,
    private val storage: SnapshotStorage,
    private val nowMillis: () -> Long,
    private val maxAgeMillis: Long = 5 * 60 * 1000L,
) {
    init { require(maxAgeMillis > 0) }
    private val guard = Mutex()
    private var flight: Deferred<RefreshOutcome>? = null
    private val mutable = MutableStateFlow(PriceState(storage.read()?.let(PriceCodec::validate)))
    val state: StateFlow<PriceState> = mutable.asStateFlow()

    fun isFresh(snapshot: Snapshot?, now: Long = nowMillis()): Boolean {
        if (snapshot == null || now < snapshot.checkedAtMillis) return false
        return now - snapshot.checkedAtMillis < maxAgeMillis
    }

    suspend fun refresh(force: Boolean = false): RefreshOutcome {
        val shared = guard.withLock {
            flight?.takeIf { it.isActive } ?: run {
                if (!force && isFresh(mutable.value.snapshot)) return RefreshOutcome.FreshCache
                scope.async(start = CoroutineStart.LAZY) { performRefresh() }.also {
                    flight = it
                    it.start()
                }
            }
        }
        return shared.await()
    }

    private suspend fun performRefresh(): RefreshOutcome {
        val previous = mutable.value.snapshot
        mutable.value = PriceState(previous, refreshing = true)
        try {
            val reply = transport.fetch(previous?.etag)
            val timestamp = nowMillis()
            val (candidate, outcome) = when (reply.status) {
                200 -> Snapshot(PriceCodec.decodeDocument(reply.body), reply.etag ?: throw ProtocolFailure("Missing validator"), timestamp) to RefreshOutcome.Modified
                304 -> {
                    val cached = previous ?: throw ProtocolFailure("304 without a cached representation")
                    if (reply.etag != null && reply.etag != cached.etag) throw ProtocolFailure("304 validator mismatch")
                    if (reply.body.isNotEmpty()) throw ProtocolFailure("304 unexpectedly contains a body")
                    cached.copy(checkedAtMillis = timestamp) to RefreshOutcome.NotModified
                }
                else -> throw HttpFailure(reply.status)
            }
            val accepted = PriceCodec.validate(candidate)
            currentCoroutineContext().ensureActive()
            storage.write(accepted) // Publish only after atomic persistence succeeds.
            mutable.value = PriceState(accepted)
            return outcome
        } catch (cancelled: CancellationException) {
            mutable.value = PriceState(previous)
            throw cancelled
        } catch (failure: Exception) {
            mutable.value = PriceState(previous, error = "Prices could not be checked. Your saved collection is still here.")
            throw failure
        }
    }
}

/** Identity entity bytes, no transparent HTTP cache; timeout settings are inactivity limits. */
class LocalPriceTransport(private val baseUrl: String = "http://127.0.0.1:8874") : PriceTransport {
    override suspend fun fetch(etag: String?): Reply = request("/prices", etag)
    suspend fun request(path: String, etag: String? = null): Reply = withContext(Dispatchers.IO) {
        val connection = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (etag != null) connection.setRequestProperty("If-None-Match", etag)
            val status = connection.responseCode
            val body = if (status == 304) byteArrayOf() else {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.use {
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > PriceCodec.maxBodyBytes) throw ProtocolFailure("Oversized response")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: byteArrayOf()
            }
            Reply(status, connection.getHeaderField("ETag"), body)
        } finally { connection.disconnect() }
    }
}
