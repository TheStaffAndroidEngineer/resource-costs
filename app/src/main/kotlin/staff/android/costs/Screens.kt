package staff.android.costs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import staff.android.costs.core.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

@Composable fun BookWindow(content: @Composable () -> Unit) {
    CommonTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 960.dp).fillMaxWidth().semantics { testTagsAsResourceId = true }) { content() }
            }
        }
    }
}
@Composable fun Brand(action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_common_goods), null, Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp))
        Text("Common Goods", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}
@Composable fun ProductPhoto(product: Product, modifier: Modifier) {
    val context = LocalContext.current
    val store = remember(context.applicationContext) { ProductImages.get(context) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, product.imageKey) { value = store.load(product.imageKey) }
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), product.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("Your collection", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
fun priceLabel(product: Product, state: PriceState): String {
    val value = state.snapshot?.document?.prices?.firstOrNull { it.id == product.id } ?: return "Check the latest price"
    return NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply { maximumFractionDigits = 0 }.format(value.cents / 100.0)
}
@Composable fun ShopScreen(model: GoodsModel, state: PriceState, onLab: () -> Unit) {
    val c = LocalConfiguration.current
    val columns = if (c.fontScale >= 1.5f) 1 else if (c.screenWidthDp >= 700) 3 else 2
    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("shop"),
        contentPadding = PaddingValues(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Brand("Lab", onLab) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Good things,\nevery day.", style = MaterialTheme.typography.displaySmall)
                Text("A considered collection for everyday living.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { model.openSaved() }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 52.dp).testTag("open_saved")) { Text("Saved collection · ${model.saved.size}") }
            }
        }
        items(Catalog.featured, key = { it.id }) { product ->
            Column(Modifier.padding(horizontal = 12.dp)) {
                ProductPhoto(product, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(product.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                Text(priceLabel(product, state), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { model.selected = product; model.route = Route.Detail }, contentPadding = PaddingValues(0.dp)) { Text("Explore ${product.title}") }
            }
        }
    }
}
@Composable fun SavedScreen(model: GoodsModel, state: PriceState, onLab: () -> Unit) {
    val products = Catalog.featured.filter { it.id in model.saved }
    LazyColumn(Modifier.fillMaxSize().testTag("saved_screen"), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Brand("Shop") { model.route = Route.Shop } }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Worth keeping.", style = MaterialTheme.typography.displaySmall)
                Text("Your favourites, ready for another look.", style = MaterialTheme.typography.bodyLarge)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (state.snapshot == null) "Check before you choose" else "Your saved prices", style = MaterialTheme.typography.titleMedium)
                        Text(state.snapshot?.let { "Last checked ${SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH).format(Date(it.checkedAtMillis))}. Prices can change." }
                            ?: "Check the current collection prices when you’re ready.", Modifier.testTag("freshness"))
                        if (state.refreshing) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("refresh_loading"))
                            Text("Checking prices…")
                        }
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("refresh_error")) }
                        Button(onClick = { model.refresh(force = true) }, enabled = !state.refreshing,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("refresh_prices")) { Text(if (state.error != null) "Try again" else "Refresh prices") }
                    }
                }
            }
        }
        if (products.isEmpty()) item {
            Column(Modifier.padding(20.dp).testTag("saved_empty"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Make room for your favourites", style = MaterialTheme.typography.headlineSmall)
                Text("Save something from the collection and find it here when you’re ready.")
                OutlinedButton(onClick = { model.route = Route.Shop }) { Text("Explore the collection") }
            }
        }
        items(products, key = { it.id }) { product ->
            val large = LocalConfiguration.current.fontScale >= 1.5f
            if (large) Column(Modifier.padding(horizontal = 20.dp)) {
                ProductPhoto(product, Modifier.fillMaxWidth().aspectRatio(1.5f))
                SavedProductText(product, model, state, Modifier.padding(top = 12.dp))
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                ProductPhoto(product, Modifier.size(116.dp))
                SavedProductText(product, model, state, Modifier.weight(1f).padding(start = 18.dp))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep prices up to date", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(model.background, onCheckedChange = model::changeBackground, modifier = Modifier.testTag("background_refresh"))
                }
                Text("Optional checks when connected and the battery is not low. Timing can vary; refresh here before deciding.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onLab, modifier = Modifier.testTag("open_lab")) { Text("Lab") }
            }
        }
    }
}
@Composable fun SavedProductText(product: Product, model: GoodsModel, state: PriceState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(product.title, style = MaterialTheme.typography.titleMedium)
        Text(priceLabel(product, state), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("price_${product.id}"))
        TextButton(onClick = { model.selected = product; model.route = Route.Detail }, contentPadding = PaddingValues(0.dp)) { Text("View ${product.title}") }
    }
}
@Composable fun DetailScreen(model: GoodsModel, state: PriceState) {
    val product = model.selected
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("detail")) {
        Brand("Back") { model.route = Route.Saved }
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("THE EVERYDAY COLLECTION", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ProductPhoto(product, Modifier.widthIn(max = 504.dp).fillMaxWidth().aspectRatio(1.2f)) }
            Text(product.title, style = MaterialTheme.typography.headlineLarge)
            Text(priceLabel(product, state), style = MaterialTheme.typography.titleLarge)
            Text(product.description, style = MaterialTheme.typography.bodyLarge)
            Text("Prices shown are from your last successful check.")
            Button(onClick = { model.toggleSaved(product.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (product.id in model.saved) "Remove from saved" else "Save for later") }
        }
    }
}
@Composable fun CostLab(model: GoodsModel, state: PriceState, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Resource investigation") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Snapshot revision: ${state.snapshot?.document?.revision ?: "none"}. Validator: ${state.snapshot?.etag ?: "none"}.")
            Text("Both routes request the same six products. Fixture records count HTTP messages and entity bytes, not radio energy.")
            Button(onClick = { model.runCallers(true) }, enabled = !model.labRunning) { Text("Reload every card") }
            Button(onClick = { model.runCallers(false) }, enabled = !model.labRunning) { Text("Run three callers") }
            Text(model.labResult, Modifier.testTag("lab_result"))
            if (BuildConfig.DEBUG) {
                TextButton(onClick = { model.changeSaved(emptySet()) }) { Text("Clear saved selection") }
                TextButton(onClick = { model.changeSaved(PriceCodec.expectedIds) }) { Text("Restore saved selection") }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}
