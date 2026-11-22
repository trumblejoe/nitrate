package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.playback.HouseholdDevice
import dev.nitrate.core.playback.Household
import dev.nitrate.core.playback.agoLabel
import kotlinx.coroutines.launch

private val Amber = Color(0xFFFFB74D)

/**
 * Settings → Tools → Household devices (admins, Companion feature `devices`): every device's own
 * playback summary, sent after its overview, test plays or TV check: what it converts and why, what
 * Android reports for HDR, its tests and what its user saw at the TV and receiver. Titles converted
 * on several devices come first: they're worth a version every device plays as it is.
 */
@Composable
fun HouseholdDevicesScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<HouseholdDevice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<HouseholdDevice?>(null) }
    val first = remember { FocusRequester() }
    fun load() {
        scope.launch {
            runCatching { container.server.householdDevices() }
                .onSuccess { devices = it; error = null }
                .onFailure { error = "Couldn't read them: ${it.message ?: it::class.simpleName}. They need the Nitrate Companion plugin on the server." }
        }
    }
    LaunchedEffect(Unit) { load() }
    var trouble by remember { mutableStateOf(emptyList<dev.nitrate.core.companion.ProblemTitle>()) }
    LaunchedEffect(Unit) { trouble = container.problemTitles() }
    val now = System.currentTimeMillis()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Household devices", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Each device sends its own summary when its Library playback overview is read, after a test play and after the TV & receiver check. Only admins see this page.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (devices?.isEmpty() == true) Text("No device has sent a summary yet. Open Library playback overview on a device to send one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = ::load, modifier = Modifier.focusRequester(first)) { Text("Read again") }
            }
        }
        val list = devices.orEmpty()
        val common = Household.mostConverted(list)
        if (common.isNotEmpty()) {
            item { Text("Converted on several devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
            items(common.take(20), key = { "c" + it.first.itemId }) { (title, count) ->
                ListItem(
                    selected = false,
                    onClick = { onOpen(title.itemId) },
                    headlineContent = { Text(title.name) },
                    supportingContent = { Text(listOfNotNull("On $count devices", title.delivery, title.reason).joinToString(" · "), style = MaterialTheme.typography.bodySmall) },
                )
            }
        }
        if (trouble.isNotEmpty()) {
            item { Text("Titles with trouble (90 days)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
            items(trouble.take(20), key = { "p" + it.itemId }) { t ->
                ListItem(
                    selected = false,
                    onClick = { onOpen(t.itemId) },
                    headlineContent = { Text(t.name) },
                    supportingContent = {
                        Column {
                            Text(t.line, style = MaterialTheme.typography.bodySmall, color = if (t.failed > 0) Amber else Color.Unspecified)
                            if (t.reasons.isNotEmpty()) Text(t.reasons.take(3).joinToString("; "), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                )
            }
        }
        if (list.isNotEmpty()) item { Text("Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp)) }
        items(list, key = { it.userId + it.deviceId }) { d ->
            val s = d.summary
            ListItem(
                selected = false,
                onClick = { open = d },
                headlineContent = { Text("${d.userName} · ${d.deviceName}") },
                supportingContent = {
                    Column {
                        Text(listOfNotNull(s?.model, s?.app, agoLabel(d.atMs, now)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text(
                            listOfNotNull(
                                d.convertedCount?.let { "$it converted" + (s?.library?.let { l -> " in $l" } ?: "") },
                                s?.let { "HDR Android reports: ${it.displayHdr.joinToString().ifEmpty { "none" }}" },
                                s?.tests?.size?.takeIf { it > 0 }?.let { "$it test play${if (it == 1) "" else "s"}" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (d.problems.isNotEmpty()) Text("${d.problems.size} to look at", style = MaterialTheme.typography.bodySmall, color = Amber)
                    }
                },
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    open?.let { d ->
        dev.nitrate.tv.ui.lists.TvDialog(onDismiss = { open = null }, width = 900) {
            val s = d.summary
            Text("${d.userName} · ${d.deviceName}", style = MaterialTheme.typography.headlineSmall)
            // Everything scrolls in its own box, so it stays readable above the buttons.
            dev.nitrate.tv.ui.lists.ScrollBox(maxHeight = 440, takeFocus = true) {
            if (s == null) Text("This device's app sent a summary this one can't read: update Nitrate on it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            s?.let {
                Rows("Device", listOf("Model" to it.model, "App" to it.app, "Sent" to agoLabel(d.atMs, now), "HDR Android reports" to it.displayHdr.joinToString().ifEmpty { "none" }))
                Rows("Settings that decide it", it.settings.map { l -> l.label to l.value })
                if (it.counts.isNotEmpty()) Rows("Its library overview" + (it.library?.let { l -> " ($l)" } ?: ""), it.counts.map { l -> l.label to l.value })
                if (it.seen.isNotEmpty()) Rows("TV & receiver check", it.seen.map { l -> l.label to l.value })
                if (d.problems.isNotEmpty()) {
                    Text("To look at", style = MaterialTheme.typography.titleMedium, color = Amber, modifier = Modifier.padding(top = 8.dp))
                    d.problems.forEach { p -> Text(p, style = MaterialTheme.typography.bodyMedium) }
                }
                if (it.tests.isNotEmpty()) {
                    Text("Test plays", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    it.tests.forEach { t ->
                        Text("${t.name}: ${t.summary}", style = MaterialTheme.typography.bodyMedium, color = if (t.ok) MaterialTheme.colorScheme.onSurface else Amber)
                        (t.answers.map { a -> "${a.label} ${a.value}" } + t.differences).forEach { line -> Text("   $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (it.converted.isNotEmpty()) {
                    Text("Converted here (${it.converted.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    it.converted.take(60).forEach { c -> Text(listOfNotNull(c.name, c.reason).joinToString(": "), style = MaterialTheme.typography.bodySmall) }
                }
            }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                OutlinedButton(onClick = { open = null }) { Text("Close") }
                OutlinedButton(onClick = {
                    open = null
                    scope.launch { runCatching { container.server.forgetHouseholdDevice(d.userId, d.deviceId) }; load() }
                }) { Text("Forget this device") }
            }
        }
    }
}

@Composable
private fun Rows(title: String, rows: List<Pair<String, String>>) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    rows.forEach { (label, value) ->
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(260.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
