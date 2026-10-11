package staff.android.costs

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import staff.android.costs.core.*

enum class Route { Shop, Saved, Detail }
class GoodsModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GoodsApplication
    private val preferences = app.getSharedPreferences("shopping", 0)
    val repository = app.repository
    var route by mutableStateOf(Route.Shop)
    var selected by mutableStateOf(Catalog.featured.first())
    var saved by mutableStateOf(preferences.getStringSet("saved", PriceCodec.expectedIds)!!.toSet())
        private set
    var background by mutableStateOf(preferences.getBoolean("background", false))
        private set
    var labResult by mutableStateOf("")
    var labRunning by mutableStateOf(false)
    fun openSaved(refresh: Boolean = true) { route = Route.Saved; if (refresh) refresh() }
    fun refresh(force: Boolean = false) {
        viewModelScope.launch { try { repository.refresh(force) } catch (c: CancellationException) { throw c } catch (_: Exception) { } }
    }
    fun toggleSaved(id: String) { changeSaved(if (id in saved) saved - id else saved + id) }
    fun changeSaved(ids: Set<String>) {
        saved = ids.toSet(); preferences.edit().putStringSet("saved", saved).apply()
    }
    fun changeBackground(enabled: Boolean) {
        background = enabled; preferences.edit().putBoolean("background", enabled).apply()
        if (enabled) PriceWork.enable(app) else PriceWork.disable(app)
    }
    fun runCallers(eager: Boolean) {
        if (labRunning) return
        labRunning = true; labResult = "Running controlled callers…"
        viewModelScope.launch {
            try {
                coroutineScope {
                    List(3) { async {
                        if (eager) PriceCodec.expectedIds.forEach { app.transport.request("/prices/$it") }
                        else repository.refresh(force = true)
                    } }.awaitAll()
                }
                labResult = if (eager) "Three callers each fetched six rows. Inspect the server record."
                    else "Three callers joined the shared refresh. Inspect the server record."
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { labResult = "The fixture is unavailable. Start the local server and retry." }
            finally { labRunning = false }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[GoodsModel::class.java]
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            if (intent.getBooleanExtra("empty", false)) model.changeSaved(emptySet())
            if (intent.getStringExtra("route") == "saved") model.openSaved(intent.getBooleanExtra("auto", true))
        }
        setContent {
            var lab by remember { mutableStateOf(false) }
            val prices by model.repository.state.collectAsState()
            BackHandler(enabled = model.route != Route.Shop) { model.route = Route.Shop }
            BookWindow {
                when (model.route) {
                    Route.Shop -> ShopScreen(model, prices, onLab = { lab = true })
                    Route.Saved -> SavedScreen(model, prices, onLab = { lab = true })
                    Route.Detail -> DetailScreen(model, prices)
                }
                if (lab) CostLab(model, prices) { lab = false }
            }
        }
    }
}
