package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.data.PlaybackPlan
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

/** One choice in a track list; [index] is the Jellyfin stream index (-1 for "Off"). */
data class TrackChoice(val index: Int, val title: String, val detail: String?)

fun PlaybackPlan.audioChoices(): List<TrackChoice> =
    source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.AUDIO }.map { it.toChoice() }

fun PlaybackPlan.subtitleChoices(): List<TrackChoice> =
    listOf(TrackChoice(-1, "Off", null)) +
        source.mediaStreams.orEmpty().filter { it.type == MediaStreamType.SUBTITLE }.map { it.toChoice() }

private fun MediaStream.toChoice(): TrackChoice {
    val flags = buildList {
        if (isDefault) add("Default")
        if (isForced) add("Forced")
        if (isExternal) add("External")
        codec?.let { add(it.uppercase()) }
        channelLayout?.let { add(it) }
    }
    return TrackChoice(index, displayTitle ?: language ?: "Track $index", flags.joinToString(" · ").ifEmpty { null })
}

@Composable
fun TrackDialog(
    title: String,
    choices: List<TrackChoice>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
            if (choices.isEmpty()) Text("No tracks available", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(choices, key = { it.index }) { choice ->
                    val isSelected = choice.index == selected
                    ListItem(
                        selected = isSelected,
                        onClick = { onSelect(choice.index) },
                        headlineContent = { Text(choice.title) },
                        supportingContent = choice.detail?.let { { Text(it) } },
                        trailingContent = if (isSelected) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null,
                        modifier = if (isSelected || (selected == null && choice == choices.first())) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
