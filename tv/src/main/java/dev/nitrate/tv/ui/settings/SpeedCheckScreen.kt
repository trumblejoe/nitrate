package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.diagnostics.Rating
import dev.nitrate.core.diagnostics.SpeedReport
import dev.nitrate.core.diagnostics.SpeedStep
import kotlinx.coroutines.launch

/** Colour for a step's rating. */
internal fun ratingColor(r: Rating): Color = when (r) {
    Rating.GOOD -> Color(0xFF7BD88F)
    Rating.OK -> Color(0xFFE8C35A)
    Rating.SLOW, Rating.FAILED -> Color(0xFFFF7A6B)
}

private const val TOTAL_STEPS = 7

/**
 * Settings → Server speed check: times typical requests from this TV, shows each result as it
 * comes in and what they add up to, and can save the report to the server's logs.
 */
@Composable
fun SpeedCheckScreen(container: AppContainer, startNow: Boolean = false) {
    val scope = rememberCoroutineScope()
    val steps = remember { mutableStateListOf<SpeedStep>() }
    var report by remember { mutableStateOf<SpeedReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    fun run() {
        if (running) return
        steps.clear(); report = null; saved = null; running = true
        scope.launch {
            report = runCatching {
                container.speedCheck { s -> val i = steps.indexOfFirst { it.id == s.id }; if (i >= 0) steps[i] = s else steps += s }
            }.getOrNull()
            running = false
        }
    }
    LaunchedEffect(Unit) { if (startNow) run() }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(bottom = 12.dp).widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Server speed check", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Times what Nitrate asks your server for, from this TV, to show whether slowness is the network, the server or this device. Takes about half a minute.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { run() }, enabled = !running, modifier = Modifier.focusRequester(first)) {
                        Text(if (running) "Checking… ${steps.size} of $TOTAL_STEPS" else if (report == null) "Run check" else "Run again")
                    }
                    report?.let { r ->
                        OutlinedButton(onClick = {
                            saved = "Saving…"
                            scope.launch { saved = container.sendSpeedReport(r).fold({ "Saved on your server as $it (Dashboard → Logs)" }, { "Couldn't save: ${it.message}" }) }
                        }) { Text("Save to server logs") }
                    }
                }
                saved?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        report?.let { r ->
            item { Text("What it shows", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp)) }
            items(r.findings) { f -> Text("•  $f", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 900.dp).padding(vertical = 2.dp)) }
            item { Text("Each step", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
        }
        items(steps, key = { it.id }) { s ->
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = { Text("${s.label}:  ${s.result}") },
                supportingContent = { Text(listOfNotNull(s.detail, s.extra).joinToString(" · ")) },
                trailingContent = { Text(s.rating.label, color = ratingColor(s.rating), style = MaterialTheme.typography.titleMedium) },
                modifier = Modifier.widthIn(max = 900.dp),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
