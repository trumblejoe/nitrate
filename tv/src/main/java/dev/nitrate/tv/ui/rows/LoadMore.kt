package dev.nitrate.tv.ui.rows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.MediaRow
import kotlinx.coroutines.launch

/** How many more titles a row asks for at a time, and how near its end focus must be. */
internal const val MORE_BATCH = 24
private const val MORE_AHEAD = 5

/**
 * A row's "Load more" ([dev.nitrate.core.rows.RowDisplay.loadMore]): the row with what's been
 * fetched so far added to its end, and what to call when a card takes focus, so that coming within
 * a few cards of the end fetches the next batch ([MediaServer.moreOfRow]). A row that reloads starts
 * again from its own items; one whose server says nothing more is left alone.
 */
@Composable
fun rememberLoadMore(row: MediaRow, on: Boolean, repository: MediaServer): LoadedRow {
    val more = row.more
    if (!on || more == null) return LoadedRow(row, { _ -> }, complete = true)
    val first = row.items.map { it.id }
    val extra = remember(row.key, first) { mutableStateListOf<MediaItem>() }
    var loading by remember(row.key, first) { mutableStateOf(false) }
    var ended by remember(row.key, first) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val all = if (extra.isEmpty()) row else row.copy(items = row.items + extra)
    val onFocus: (MediaItem) -> Unit = { item ->
        val at = all.items.indexOfFirst { it.id == item.id }
        if (at >= 0 && at >= all.items.size - MORE_AHEAD && !loading && !ended) {
            loading = true
            scope.launch {
                val next = runCatching { repository.moreOfRow(more, all.items.size, MORE_BATCH) }.getOrDefault(emptyList())
                val known = all.items.map { it.id }.toSet()
                val fresh = next.filter { it.id !in known }
                extra.addAll(fresh)
                if (next.size < MORE_BATCH || fresh.isEmpty()) ended = true
                loading = false
            }
        }
    }
    return LoadedRow(all, onFocus, complete = ended)
}

/** A row as loaded so far, what to call as its cards take focus, and whether there's no more to load. */
data class LoadedRow(val row: MediaRow, val onFocus: (MediaItem) -> Unit, val complete: Boolean)
