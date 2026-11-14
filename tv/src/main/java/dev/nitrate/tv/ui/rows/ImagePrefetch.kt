package dev.nitrate.tv.ui.rows

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Fetches pictures before they're on screen (into the disk cache, not memory) so a card's picture
 * is ready when it scrolls in. Each address is asked for once per app run.
 */
object ImagePrefetch {
    private val asked = object : LinkedHashMap<String, Unit>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 4000
    }

    fun enqueue(context: Context, urls: List<String?>) {
        val fresh = synchronized(asked) { urls.filterNotNull().filter { asked.put(it, Unit) == null } }
        if (fresh.isEmpty()) return
        val loader = SingletonImageLoader.get(context)
        // What matters is the download into the disk cache; decoding a tiny copy keeps the CPU free.
        fresh.forEach { url ->
            loader.enqueue(ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).size(64, 64).build())
        }
    }
}

/** The picture a row card shows for [item] in [display]: the same address the card asks for. */
internal fun MediaServer.rowCardImages(item: MediaItem, display: RowDisplay): List<String?> = when (display.style) {
    RowStyle.POSTER -> listOf(imageUrl(item, ImageKind.PRIMARY, display.size.posterHeightDp * 2))
    RowStyle.WIDE -> listOf(wideImage(this, item))
    RowStyle.SHOWCASE -> listOf(backdropUrls(item).firstOrNull() ?: backdropUrl(item), wideImage(this, item))
    else -> listOf(artwork(item))
}

/** Within a row: the next [ahead] cards past the last one on screen. */
@Composable
fun <T> PrefetchAlongRow(state: LazyListState, items: List<T>, ahead: Int = 6, urls: (T) -> List<String?>) {
    val context = LocalContext.current
    val urlsFor by rememberUpdatedState(urls)
    LaunchedEffect(state, items) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                val from = (last + 1).coerceAtMost(items.size)
                ImagePrefetch.enqueue(context, items.subList(from, (from + ahead).coerceAtMost(items.size)).flatMap(urlsFor))
            }
    }
}

/**
 * Down the page: the first [perRow] cards of the [rowsAhead] rows below the last one on screen,
 * and the full-screen backdrop of each one's first title (what focus lands on moving down).
 */
@Composable
fun PrefetchRows(
    state: LazyListState,
    rows: List<MediaRow>,
    overrides: Map<String, RowDisplay>,
    server: MediaServer,
    rowsAhead: Int = 3,
    perRow: Int = 8,
) {
    val context = LocalContext.current
    LaunchedEffect(state, rows, overrides) {
        val keys = rows.map { it.key }
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull { it.key in keys }?.key }
            .distinctUntilChanged()
            .collect { lastKey ->
                val last = keys.indexOf(lastKey)
                rows.drop(last + 1).take(rowsAhead).forEach { row ->
                    val display = overrides[row.key] ?: row.defaultDisplay
                    ImagePrefetch.enqueue(context, row.items.take(perRow).flatMap { server.rowCardImages(it, display) })
                    row.items.firstOrNull()?.let { ImagePrefetch.enqueue(context, listOf(server.backdropUrl(it))) }
                }
            }
    }
}
