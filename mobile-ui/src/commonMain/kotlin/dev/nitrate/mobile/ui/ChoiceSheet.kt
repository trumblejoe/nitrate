package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A sheet of choices from the bottom: what a page's More (or a long press) offers, the same way on
 * every page. Each choice is a label with an optional line under it; choosing closes the sheet first
 * (unless [onChoose] says otherwise).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChoiceSheet(
    title: String,
    onDismiss: () -> Unit,
    /** What choosing does to the sheet before the choice runs: closes it, unless the choice replaces it (a dialog of its own). */
    onChoose: () -> Unit = onDismiss,
    content: @Composable (choice: @Composable (label: String, note: String?, onClick: () -> Unit) -> Unit) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            content { label, note, onClick ->
                Column(Modifier.fillMaxWidth().pressable({ onChoose(); onClick() }).padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
