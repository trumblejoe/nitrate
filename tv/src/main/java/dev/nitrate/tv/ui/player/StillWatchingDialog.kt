package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.playback.StillWatching
import kotlinx.coroutines.delay

/**
 * "Still watching?": episodes have played on with nothing pressed, so the next one waits. Keep
 * watching, go back to where the last press was (undoing what played since), or stop. No answer
 * for a while: back to the last press, and stop.
 */
@Composable
fun StillWatchingDialog(
    next: MediaItem,
    rewind: StillWatching.Rewind?,
    onKeepWatching: () -> Unit,
    onGoBack: () -> Unit,
    onStop: () -> Unit,
    onNoAnswer: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(next.id) {
        runCatching { focus.requestFocus() }
        delay(StillWatching.PROMPT_MS)
        onNoAnswer()
    }
    Dialog(onDismissRequest = onStop) {
        Column(
            Modifier.width(640.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Still watching?", style = MaterialTheme.typography.headlineMedium)
            Text("Up next: ${next.displayTitle}" + (if (next.isEpisode) " · S${next.seasonNumber ?: "?"}:E${next.indexNumber ?: "?"}" else ""), style = MaterialTheme.typography.bodyLarge)
            if (rewind != null) Text(
                "Nothing's been pressed since ${formatClock(rewind.back.positionMs)} into an earlier episode. If you dozed off, go back there" +
                    if (rewind.unwatch.isNotEmpty()) " and what played since is marked unwatched again." else ".",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onKeepWatching, modifier = Modifier.focusRequester(focus)) { Text("Keep watching") }
                if (rewind != null) OutlinedButton(onClick = onGoBack) { Text("Go back") }
                OutlinedButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}
