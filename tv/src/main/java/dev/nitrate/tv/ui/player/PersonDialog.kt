package dev.nitrate.tv.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.Person
import kotlinx.coroutines.launch

/**
 * Someone from the cast, over the video so the player stays where it is: photo, the part they play
 * here, when they were born, their biography (Up/Down scrolls it) and what else of theirs is in
 * the library. Back closes it, with focus still on their photo in the cast strip.
 */
@Composable
fun PersonDialog(server: MediaServer, person: Person, current: MediaItem?, onDismiss: () -> Unit) {
    // null while asking; a failed answer counts as no details.
    val answer by produceState<Result<MediaItem>?>(null, person.id) { value = runCatching { server.getItem(person.id) } }
    val details = answer?.getOrNull()
    val also by produceState<List<MediaItem>?>(null, person.id) {
        // Not the title playing (or its show) again.
        val skip = setOfNotNull(current?.id, current?.seriesId)
        value = runCatching { server.itemsWithPerson(person.id) }.getOrDefault(emptyList()).filter { it.id !in skip }
    }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(900.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(
                    Modifier.width(160.dp).height(213.dp).background(Color(0xFF1E232A), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(person.name), style = MaterialTheme.typography.headlineMedium, color = Color.White.copy(alpha = 0.45f))
                    server.personImageUrl(person)?.let { url ->
                        AsyncImage(url, person.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().background(Color.Transparent, RoundedCornerShape(10.dp)))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(person.name, style = MaterialTheme.typography.headlineMedium)
                    person.role?.takeIf { it.isNotBlank() }?.let { role ->
                        Text("as $role", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    details?.premiereDateMs?.let { born ->
                        Text("Born " + java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(born)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Biography(details, loaded = answer != null, focus = first)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Also in your library", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val list = also
                when {
                    list == null -> Text("Looking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    list.isEmpty() -> Text("Nothing else.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                        items(list, key = { it.id }) { item -> AlsoCard(item, server.imageUrl(item, ImageKind.PRIMARY, 300)) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** The biography, focusable so Up/Down scroll it with the remote. */
@Composable
private fun Biography(details: MediaItem?, loaded: Boolean, focus: FocusRequester) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .then(if (focused && scroll.maxValue > 0) Modifier.border(2.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp)) else Modifier)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    e.key == Key.DirectionDown && scroll.value < scroll.maxValue -> { scope.launch { scroll.animateScrollBy(160f) }; true }
                    e.key == Key.DirectionUp && scroll.value > 0 -> { scope.launch { scroll.animateScrollBy(-160f) }; true }
                    else -> false
                }
            }
            .focusable()
            .verticalScroll(scroll)
            .padding(8.dp),
    ) {
        Text(
            when {
                !loaded -> "Loading…"
                details?.overview.isNullOrBlank() -> "No biography on the server."
                else -> details!!.overview!!
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A title they're in: poster, name and year (just to see; the player stays). */
@Composable
private fun AlsoCard(item: MediaItem, imageUrl: String?) {
    Column(Modifier.width(96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            onClick = {},
            modifier = Modifier.width(96.dp).height(144.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), inset = (-3).dp)),
        ) {
            Box(Modifier.matchParentSize().background(Color(0xFF1E232A)), contentAlignment = Alignment.Center) {
                Text(item.name, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.padding(6.dp), maxLines = 3)
                if (imageUrl != null) AsyncImage(imageUrl, item.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            }
        }
        Text(item.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.productionYear?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
