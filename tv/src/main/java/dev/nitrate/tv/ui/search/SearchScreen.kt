package dev.nitrate.tv.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.storage.SearchHistoryStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.SearchResults
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.components.retained
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PagePadding = 58.dp

/**
 * Search on TV: type with the on-screen keyboard or press Speak (the remote's mic button also
 * lands here). Results update as you type, in rows per kind; recent searches are offered.
 */
@Composable
fun SearchScreen(
    repository: MediaServer,
    history: SearchHistoryStore,
    initialQuery: String = "",
    startWithVoice: Boolean = false,
    onOpen: (MediaItem) -> Unit,
    onPerson: (Person) -> Unit,
    /** "Search what people say" (Companion plugin): lines said, played from just before. */
    linesOn: Boolean = false,
    onPlayLine: (dev.nitrate.core.lines.SpokenLine) -> Unit = {},
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by retained<SearchResults?>("search-results") { null }
    var resultsFor by retained("search-results-for") { "" }
    var searching by remember { mutableStateOf(false) }
    val recent by history.recent.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val field = remember { FocusRequester() }
    val voiceAvailable = remember { context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !heard.isNullOrBlank()) query = heard
    }
    fun listen() = voice.launch(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title, show or person"),
    )

    // Search as the query settles.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        // Back from a result: what was shown is still right.
        if (q == resultsFor && results != null) return@LaunchedEffect
        delay(200)
        searching = true
        // Quick matches first (names and pictures, cheap for the server), then the full results.
        // A newer query cancels this one (keep what's shown until its results arrive).
        try { results = dev.nitrate.core.diagnostics.withQueryOrigin("Search") { repository.searchHints(q) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        results = try { dev.nitrate.core.diagnostics.withQueryOrigin("Search") { repository.search(q) }.also { resultsFor = q } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { results }
        searching = false
    }
    var lines by remember { mutableStateOf<List<dev.nitrate.core.lines.SpokenLine>>(emptyList()) }
    LaunchedEffect(query, linesOn) {
        lines = emptyList()
        val q = query.trim()
        if (!linesOn || q.length < dev.nitrate.core.lines.SpokenLines.MIN_QUERY) return@LaunchedEffect
        delay(350)
        lines = try { repository.searchLines(q) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
    }
    LaunchedEffect(Unit) {
        if (startWithVoice && voiceAvailable) listen() else runCatching { field.requestFocus() }
    }
    fun open(item: MediaItem) {
        scope.launch { history.add(query) }
        onOpen(item)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Column(Modifier.padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Search", style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvTextField(
                        query,
                        { query = it },
                        placeholder = "Movies, shows, episodes, people",
                        modifier = Modifier.width(620.dp).focusRequester(field),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { scope.launch { history.add(query) } }),
                    )
                    if (voiceAvailable) Button(onClick = ::listen) { Text("🎤  Speak") }
                }
                when {
                    query.trim().length < 2 && recent.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        recent.take(6).forEach { r -> FilterChip(selected = false, onClick = { query = r }) { Text(r) } }
                    }
                    searching && results == null -> Text("Searching…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    results?.isEmpty == true && lines.isEmpty() -> Text("Nothing found for “${query.trim()}”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val r = results ?: return@LazyColumn
        fun row(title: String, list: List<MediaItem>, wide: Boolean = false) {
            if (list.isEmpty()) return
            item(key = title) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = PagePadding))
                    LazyRow(contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        items(list, key = { it.id }) { item ->
                            MediaCard(
                                item = item,
                                imageUrl = if (wide) repository.imageUrl(item, ImageKind.THUMB, 240) ?: repository.backdropUrl(item) else repository.imageUrl(item, ImageKind.PRIMARY, 360),
                                width = if (wide) 260.dp else 140.dp,
                                aspectRatio = if (wide) 16f / 9f else 2f / 3f,
                                title = if (wide) item.seriesName ?: item.name else item.name,
                                subtitle = if (wide) item.episodeLabel else item.productionYear?.toString(),
                                onClick = { open(item) },
                            )
                        }
                    }
                }
            }
        }
        // Lines said, first when the search reads like a quote.
        val quoteFirst = dev.nitrate.core.lines.SpokenLines.looksLikeQuote(query)
        fun saidIn() {
            if (lines.isNotEmpty()) item(key = "lines") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Said in…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = PagePadding))
                    LazyRow(contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(lines, key = { "${it.itemId}-${it.startMs}" }) { line -> SaidInCard(line) { scope.launch { history.add(query) }; onPlayLine(line) } }
                    }
                }
            }
        }
        if (quoteFirst) saidIn()
        row("Movies", r.movies)
        row("Shows", r.shows)
        row("Episodes", r.episodes, wide = true)
        row("Collections", r.collections)
        if (!quoteFirst) saidIn()
        if (r.people.isNotEmpty()) item(key = "people") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("People", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = PagePadding))
                LazyRow(contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(r.people, key = { it.id }) { p ->
                        FilterChip(selected = false, onClick = { scope.launch { history.add(query) }; onPerson(p) }) { Text(p.name) }
                    }
                }
            }
        }
    }
}

/**
 * Everything a person appears in (or, with [studio] or [year], everything from that studio or year):
 * one row laid out as Home's rows are, its look a press away (Fast-forward/Rewind, held OK) and kept for
 * every More from page, over the background and theme set for them in Settings → Display → Pages.
 */
@Composable
fun PersonScreen(
    repository: MediaServer,
    person: Person,
    onOpen: (MediaItem) -> Unit,
    studio: String? = null,
    year: Int? = null,
    displaySettings: dev.nitrate.android.storage.DisplaySettingsStore? = null,
    onPlay: (MediaItem) -> Unit = onOpen,
) {
    var items by retained<List<MediaItem>?>("person/${person.id}/$studio/$year") { null }
    LaunchedEffect(person.id, studio, year) {
        if (items != null) return@LaunchedEffect
        items = runCatching {
            when {
                studio != null -> repository.queryItems(dev.nitrate.core.pages.Browse.byStudio(studio))
                year != null -> repository.queryItems(dev.nitrate.core.pages.Browse.byYear(year))
                else -> repository.itemsWithPerson(person.id)
            }
        }.getOrDefault(emptyList())
    }
    val user = repository.userId
    val overrides by remember(user, displaySettings) {
        if (user != null && displaySettings != null) displaySettings.overrides(user) else kotlinx.coroutines.flow.flowOf(emptyMap())
    }.collectAsState(emptyMap())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val header: @Composable () -> Unit = {
        Text(person.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = PagePadding, top = 24.dp, bottom = 8.dp))
    }
    // The row's look through the Customize panel (holding OK): a rows model that only saves looks
    // (the page has one row, so nothing to move, hide or add).
    val moreFromRows = displaySettings?.let { store ->
        androidx.lifecycle.viewmodel.compose.viewModel(key = "more-from") {
            dev.nitrate.android.viewmodel.RowsViewModel(dev.nitrate.core.pages.PageKeys.MORE_FROM, { emptyList() }, store, repository)
        }
    }
    dev.nitrate.tv.ui.home.ThemedPage(dev.nitrate.core.pages.PageKeys.MORE_FROM) {
        val list = items
        when {
            list == null -> Column { header(); Text("Loading…", Modifier.padding(start = PagePadding)) }
            list.isEmpty() -> Column { header(); Text("Nothing here.", Modifier.padding(start = PagePadding)) }
            else -> androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.pages.LocalPageRows provides moreFromRows) {
                dev.nitrate.tv.ui.rows.RowsPage(
                    rows = listOf(dev.nitrate.core.rows.MediaRow(MORE_FROM_ROW, person.name, list, dev.nitrate.core.rows.RowDisplay(dev.nitrate.core.rows.RowStyle.POSTER))),
                    overrides = overrides, repository = repository, onOpen = onOpen, onPlay = onPlay,
                    onSaveDisplay = { row, display -> if (user != null && displaySettings != null) scope.launch { displaySettings.save(user, row, display) } },
                    header = header, editButton = false,
                )
            }
        }
    }
}

/** The More from pages' one row: its look is kept under this key for them all. */
private const val MORE_FROM_ROW = "more-from/titles"

/** A line someone says (OK: play from just before it). */
@Composable
private fun SaidInCard(line: dev.nitrate.core.lines.SpokenLine, onPlay: () -> Unit) {
    androidx.tv.material3.Card(onClick = onPlay, modifier = Modifier.width(420.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            line.before?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Text("“${line.text}”", style = MaterialTheme.typography.titleMedium, maxLines = 3)
            line.after?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            Text(
                listOfNotNull(line.title, line.where, dev.nitrate.core.model.formatClock(line.startMs)).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp), maxLines = 1,
            )
        }
    }
}
