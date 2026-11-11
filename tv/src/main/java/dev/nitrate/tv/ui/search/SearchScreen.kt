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
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<SearchResults?>(null) }
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
        delay(350)
        searching = true
        results = runCatching { repository.search(q) }.getOrNull()
        searching = false
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
                    results?.isEmpty == true -> Text("Nothing found for “${query.trim()}”.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        row("Movies", r.movies)
        row("Shows", r.shows)
        row("Episodes", r.episodes, wide = true)
        row("Collections", r.collections)
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

/** Everything a person appears in, as a grid of cards. */
@Composable
fun PersonScreen(repository: MediaServer, person: Person, onOpen: (MediaItem) -> Unit) {
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    LaunchedEffect(person.id) { items = runCatching { repository.itemsWithPerson(person.id) }.getOrDefault(emptyList()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(PagePadding)) {
        item { Text(person.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 16.dp)) }
        val list = items
        if (list == null) item { Text("Loading…") } else item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(list, key = { it.id }) { item ->
                    MediaCard(item = item, imageUrl = repository.imageUrl(item, ImageKind.PRIMARY, 360), width = 140.dp, aspectRatio = 2f / 3f, title = item.name, subtitle = item.productionYear?.toString(), onClick = { onOpen(item) })
                }
            }
        }
    }
}
