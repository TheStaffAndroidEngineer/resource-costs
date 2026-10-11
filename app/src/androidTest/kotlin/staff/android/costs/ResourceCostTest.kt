package staff.android.costs

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import staff.android.costs.core.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

@RunWith(AndroidJUnit4::class)
class ResourceCostTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<GoodsApplication>()
    private fun admin(path: String, body: String = "{}"): JSONObject {
        val c = URI("http://127.0.0.1:8874/admin/$path").toURL().openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 5000; c.readTimeout = 5000
            if (path != "records") {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            check(c.responseCode == 200)
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    private suspend fun records(expected: Int): JSONArray = withTimeout(5000) {
        while (true) {
            val records = admin("records").getJSONArray("records")
            if (records.length() == expected) return@withTimeout records
            check(records.length() < expected) { "More requests than expected: ${records.length()} > $expected" }
            delay(20)
        }
        error("unreachable")
    }

    @Test fun realHttpCampaignPreservesPricesAndSeparatesRequestsFromEntityBytes() = runBlocking<Unit> {
        admin("reset")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var stored: Snapshot? = null
        val storage = object : SnapshotStorage {
            override fun read() = stored
            override fun write(snapshot: Snapshot) { stored = snapshot }
        }
        val transport = LocalPriceTransport()
        val repository = PriceRepository(scope, transport, storage, System::currentTimeMillis)
        val stages = JSONArray()
        var seen = 0
        suspend fun stage(name: String, requestCount: Int, action: suspend () -> Unit) {
            val rx = TrafficStats.getUidRxBytes(Process.myUid())
            val tx = TrafficStats.getUidTxBytes(Process.myUid())
            action()
            val afterRx = TrafficStats.getUidRxBytes(Process.myUid())
            val afterTx = TrafficStats.getUidTxBytes(Process.myUid())
            val all = records(seen + requestCount)
            val own = JSONArray()
            for (i in seen until all.length()) own.put(all.getJSONObject(i))
            val output = JSONObject().put("name", name).put("requests", requestCount).put("records", own)
                .put("uid", Process.myUid()).put("uidRxBefore", rx).put("uidRxAfter", afterRx)
                .put("uidTxBefore", tx).put("uidTxAfter", afterTx)
                .put("snapshot", repository.state.value.snapshot?.let(PriceCodec::encode))
            stages.put(output); seen += requestCount
        }
        try {
            stage("legacy_three_callers_six_rows", 18) {
                coroutineScope { List(3) { async { PriceCodec.expectedIds.forEach { assertEquals(200, transport.request("/prices/$it").status) } } }.awaitAll() }
            }
            admin("config", "{\"delay_ms\":250}")
            stage("shared_cold_three_callers", 1) {
                coroutineScope { List(3) { async { assertEquals(RefreshOutcome.Modified, repository.refresh(true)) } }.awaitAll() }
            }
            assertEquals(1299900L, repository.state.value.snapshot!!.document.prices.first { it.id == "camera" }.cents)
            stage("fresh_automatic_three_callers", 0) {
                coroutineScope { List(3) { async { assertEquals(RefreshOutcome.FreshCache, repository.refresh()) } }.awaitAll() }
            }
            val first = repository.state.value.snapshot!!
            stage("unchanged_forced_three_callers", 1) {
                coroutineScope { List(3) { async { assertEquals(RefreshOutcome.NotModified, repository.refresh(true)) } }.awaitAll() }
            }
            assertEquals(first.document, repository.state.value.snapshot!!.document)
            assertTrue(repository.state.value.snapshot!!.checkedAtMillis >= first.checkedAtMillis)
            admin("config", "{\"revision\":2}")
            stage("changed_forced", 1) { assertEquals(RefreshOutcome.Modified, repository.refresh(true)) }
            assertEquals(1199900L, repository.state.value.snapshot!!.document.prices.first { it.id == "camera" }.cents)
            val good = repository.state.value.snapshot!!
            admin("config", "{\"status\":503}")
            stage("failed_shared_validation", 1) {
                coroutineScope { List(3) { async { assertTrue(runCatching { repository.refresh(true) }.exceptionOrNull() is HttpFailure) } }.awaitAll() }
            }
            assertEquals(good, repository.state.value.snapshot)
            admin("config", "{\"status\":200,\"revision\":3,\"malformed\":true}")
            stage("malformed_changed_document", 1) { assertTrue(runCatching { repository.refresh(true) }.isFailure) }
            assertEquals(good, repository.state.value.snapshot)
            assertEquals(good, stored)
            admin("config", "{\"revision\":2,\"malformed\":false}")
            stage("recovery_validation", 1) { assertEquals(RefreshOutcome.NotModified, repository.refresh(true)) }
            assertNull(repository.state.value.error)
            File(app.filesDir, "network-campaign.json").writeText(JSONObject().put("configuration", "leanDebug / API36 emulator / identity HTTP / no representative energy claim")
                .put("clock", "System.currentTimeMillis").put("stages", stages).toString(2))
        } finally { scope.cancel(); admin("config", "{\"status\":200,\"delay_ms\":0,\"malformed\":false}") }
    }

    @Test fun atomicStorageRestoresTheValidatorAndRepresentationTogether() = runBlocking<Unit> {
        admin("reset")
        val context = app.createDeviceProtectedStorageContext()
        File(context.filesDir, "prices.json").delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = PriceRepository(scope, LocalPriceTransport(), AtomicSnapshotStorage(context), System::currentTimeMillis)
            assertEquals(RefreshOutcome.Modified, first.refresh(true))
            val restored = PriceRepository(scope, LocalPriceTransport(), AtomicSnapshotStorage(context), System::currentTimeMillis)
            assertEquals(first.state.value.snapshot, restored.state.value.snapshot)
            assertEquals(RefreshOutcome.NotModified, restored.refresh(true))
            assertEquals(1299900L, restored.state.value.snapshot!!.document.prices.first { it.id == "camera" }.cents)
        } finally { scope.cancel(); File(context.filesDir, "prices.json").delete() }
    }

    @Test fun cachedPricesSurviveFailureAndChangedPricesAppearAfterRetry() = runBlocking<Unit> {
        admin("reset"); app.repository.refresh(true)
        ui.onNodeWithTag("open_saved").performClick()
        ui.onNodeWithTag("price_camera").assertTextEquals("₹12,999")
        val previous = app.repository.state.value.snapshot
        admin("config", "{\"status\":503}")
        ui.onNodeWithTag("refresh_prices").performClick()
        ui.waitUntil(10000) { app.repository.state.value.error != null }
        ui.onNodeWithTag("refresh_error").assertExists()
        ui.onNodeWithTag("price_camera").assertTextEquals("₹12,999")
        assertEquals(previous, app.repository.state.value.snapshot)
        admin("config", "{\"status\":200,\"revision\":2}")
        ui.onNodeWithTag("refresh_prices").performClick()
        ui.waitUntil(10000) { app.repository.state.value.snapshot?.document?.revision == 2L }
        ui.onNodeWithTag("price_camera").assertTextEquals("₹11,999")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("saved_screen").assertExists()
        ui.onNodeWithTag("price_camera").assertTextEquals("₹11,999")
    }
}
