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
import dev.nitrate.core.playback.TrackChoice

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
