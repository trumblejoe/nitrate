package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.playback.TrackChoice

/**
 * The player, per platform: Media3 on Android, Apple's player on iOS. Plays [itemId] from its
 * resume point (or the start, or [startMs]); [local] plays a download.
 */
@Composable
expect fun PlayerScreen(
    actions: AppActions,
    itemId: String,
    fromStart: Boolean,
    onExit: () -> Unit,
    onEnded: () -> Unit,
    local: Boolean = false,
    /** Start here instead of the resume point (e.g. handed over from another device). */
    startMs: Long? = null,
    /** Plays the following episode; null turns Up Next off (e.g. playlists, which queue their own). */
    onPlayNext: ((MediaItem) -> Unit)? = null,
    /** A recap clip: plays from [startMs] to here, unreported, then ends. */
    clipEndMs: Long? = null,
)

@Composable
fun TrackPicker(title: String, choices: List<TrackChoice>, selected: Int?, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (choices.isEmpty()) Text("No tracks available")
            LazyColumn {
                items(choices, key = { it.index }) { choice ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(choice.index) },
                        leadingContent = { RadioButton(selected = choice.index == selected, onClick = { onSelect(choice.index) }) },
                        headlineContent = { Text(choice.title) },
                        supportingContent = choice.detail?.let { { Text(it) } },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
