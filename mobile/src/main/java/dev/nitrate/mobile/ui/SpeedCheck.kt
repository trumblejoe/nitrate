package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.diagnostics.Rating
import dev.nitrate.core.diagnostics.SpeedReport
import dev.nitrate.core.diagnostics.SpeedStep
import kotlinx.coroutines.launch

private const val TOTAL_STEPS = 7

private fun ratingColor(r: Rating): Color = when (r) {
    Rating.GOOD -> Color(0xFF7BD88F)
    Rating.OK -> Color(0xFFE8C35A)
    Rating.SLOW, Rating.FAILED -> Color(0xFFFF7A6B)
}

/** Settings row: opens the server speed check. */
@Composable
fun SpeedCheckRow(actions: AppActions) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable { open = true },
        headlineContent = { Text("Server speed check") },
        supportingContent = { Text("Is slowness the network, the server or this device? Times it and shows you") },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
    if (open) SpeedCheckDialog(actions) { open = false }
}

/**
 * Times what Nitrate asks the server for, from this device, showing each result as it comes in
 * and what they add up to; the report can be saved to the server's logs.
 */
@Composable
fun SpeedCheckDialog(actions: AppActions, startNow: Boolean = true, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val steps = remember { mutableStateListOf<SpeedStep>() }
    var report by remember { mutableStateOf<SpeedReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    fun run() {
        if (running) return
        steps.clear(); report = null; saved = null; running = true
        scope.launch {
            report = runCatching {
                actions.container.speedCheck { s -> val i = steps.indexOfFirst { it.id == s.id }; if (i >= 0) steps[i] = s else steps += s }
            }.getOrNull()
            running = false
        }
    }
    LaunchedEffect(Unit) { if (startNow) run() }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Server speed check") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Checking… ${steps.size} of $TOTAL_STEPS", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { steps.size / TOTAL_STEPS.toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
                report?.let { r ->
                    item { Text("What it shows", style = MaterialTheme.typography.labelLarge, color = accentColor()) }
                    items(r.findings) { f -> Text("•  $f", style = MaterialTheme.typography.bodyMedium) }
                    item { Text("Each step", style = MaterialTheme.typography.labelLarge, color = accentColor(), modifier = Modifier.padding(top = 8.dp)) }
                }
                items(steps, key = { it.id }) { s ->
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.label, style = MaterialTheme.typography.titleSmall)
                            Text(s.rating.label, color = ratingColor(s.rating), style = MaterialTheme.typography.titleSmall)
                        }
                        Text(s.result, style = MaterialTheme.typography.bodyMedium)
                        Text(listOfNotNull(s.detail, s.extra).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                saved?.let { item { Text(it, color = accentColor(), modifier = Modifier.padding(top = 6.dp)) } }
            }
        },
        confirmButton = {
            Row {
                report?.let { r ->
                    TextButton(onClick = {
                        saved = "Saving…"
                        scope.launch { saved = actions.container.sendSpeedReport(r).fold({ "Saved on your server as $it (Dashboard → Logs)" }, { "Couldn't save: ${it.message}" }) }
                    }) { Text("Save to server logs") }
                }
                TextButton(onClick = { run() }, enabled = !running) { Text("Run again") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !running) { Text("Close") } },
    )
}
