package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.SearchResults
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Search as you type, across movies, shows, episodes, collections and people. */
@Composable
fun SearchScreen(actions: AppActions, modifier: Modifier = Modifier, initialQuery: String = "", autoFocus: Boolean = true) {
    val history = actions.container.searchHistory
    val recent by history.recent.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<Result<SearchResults>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(280) // Wait for a pause in typing.
        loading = true
        // A newer query cancels this one: not an error to show.
        results = try { Result.success(actions.server.search(query)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        loading = false
    }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun openFromResults(item: MediaItem, key: String) {
        scope.launch { history.add(query) }
        actions.open(item, key)
    }

    TintedBackdrop(null, modifier, strength = 0.22f) {
        LazyColumn(contentPadding = PaddingValues(bottom = NavBarClearance + 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Column(Modifier.padding(horizontal = ScreenPadding)) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Text("Search", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 12.dp, bottom = 14.dp))
                    SearchField(query, { query = it }, focus, loading) { scope.launch { history.add(query) } }
                }
            }
            val r = results?.getOrNull()
            when {
                query.isBlank() -> if (recent.isNotEmpty()) item {
                    Column {
                        SectionHeader("Recent", trailing = { TextButton(onClick = { scope.launch { history.clear() } }) { Text("Clear") } })
                        FlowRow(Modifier.padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recent.forEach { q ->
                                Text(
                                    q,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .pressable({ query = q }).padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                } else item {
                    Text("Find movies, shows, episodes and people.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = ScreenPadding))
                }
                results?.isFailure == true -> item { Text("Couldn't search: ${results?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = ScreenPadding)) }
                r == null -> Unit
                r.isEmpty -> item { Text("Nothing matches “$query”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = ScreenPadding)) }
                else -> {
                    if (r.movies.isNotEmpty()) item { PosterResults("Movies", r.movies, actions, ::openFromResults) }
                    if (r.shows.isNotEmpty()) item { PosterResults("Shows", r.shows, actions, ::openFromResults) }
                    if (r.people.isNotEmpty()) item { PeopleResults(r.people, actions) { scope.launch { history.add(query) } } }
                    if (r.collections.isNotEmpty()) item { PosterResults("Collections", r.collections, actions, ::openFromResults) }
                    if (r.episodes.isNotEmpty()) {
                        item { SectionHeader("Episodes", Modifier.padding(bottom = 0.dp)) }
                        items(r.episodes.take(20), key = { "ep-${it.id}" }) { ep -> EpisodeResult(ep, actions) { openFromResults(ep, "search-${ep.id}") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, focus: FocusRequester, loading: Boolean, onSubmit: () -> Unit) {
    val accent = accentColor()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (query.isNotEmpty()) accent.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = if (query.isNotEmpty()) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Titles, people, episodes", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                query,
                onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        when {
            loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = accent)
            query.isNotEmpty() -> IconButton(onClick = { onChange("") }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.Close, "Clear") }
        }
    }
}

@Composable
private fun PosterResults(title: String, items: List<MediaItem>, actions: AppActions, open: (MediaItem, String) -> Unit) {
    Column {
        SectionHeader(title)
        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                val key = "search-${item.id}"
                MediaCard(item, actions.server.cardImage(item, CardShape.POSTER), CardShape.POSTER, onClick = { open(item, key) }, width = 116.dp,
                    title = item.name, subtitle = item.productionYear?.toString(), sharedKey = key)
            }
        }
    }
}

@Composable
private fun PeopleResults(people: List<Person>, actions: AppActions, onOpen: () -> Unit) {
    Column {
        SectionHeader("People")
        LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(people, key = { it.id }) { person -> PersonBubble(person, actions) { onOpen(); actions.nav.navigate(PersonRoute(person.id, person.name)) } }
        }
    }
}

@Composable
fun PersonBubble(person: Person, actions: AppActions, onClick: () -> Unit) {
    Column(Modifier.width(84.dp).pressable(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).border(2.dp, accentColor().copy(alpha = 0.5f), CircleShape), Alignment.Center) {
            val url = actions.server.personImageUrl(person)
            if (url != null) AsyncImage(url, person.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(person.name.split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), fontWeight = FontWeight.Bold)
        }
        Text(person.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        person.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun EpisodeResult(ep: MediaItem, actions: AppActions, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(horizontal = ScreenPadding, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            actions.server.cardImage(ep, CardShape.WIDE, 240)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(ep.seriesName.orEmpty(), style = MaterialTheme.typography.labelMedium, color = accentColor(), maxLines = 1)
            Text(ep.episodeLabel.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Everything a person appears in, newest first. */
@Composable
fun PersonScreen(actions: AppActions, personId: String, name: String) {
    var items by remember { mutableStateOf<Result<List<MediaItem>>?>(null) }
    LaunchedEffect(personId) { items = runCatching { actions.server.itemsWithPerson(personId) } }
    TintedBackdrop(null, strength = 0.25f) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(110.dp),
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val list = items?.getOrNull()
            when {
                items == null -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) { CircularProgressIndicator() } }
                list == null -> item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { Text("Couldn't load: ${items?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error) }
                else -> items(list.size, key = { list[it].id }) { i ->
                    val item = list[i]
                    MediaCard(item, actions.server.cardImage(item, CardShape.POSTER), CardShape.POSTER, onClick = { actions.open(item, "person-${item.id}") },
                        title = item.name, subtitle = item.productionYear?.toString(), sharedKey = "person-${item.id}")
                }
            }
        }
    }
}
