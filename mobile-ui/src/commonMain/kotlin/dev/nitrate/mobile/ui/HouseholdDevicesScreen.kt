package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.playback.Household
import dev.nitrate.core.playback.HouseholdDevice
import dev.nitrate.core.playback.agoLabel
import kotlinx.coroutines.launch

private val Amber = Color(0xFFFFB74D)

/**
 * Settings → Tools → Household devices (admins, Companion feature `devices`): every device's own
 * playback summary; titles converted on several devices first.
 */
@Composable
fun HouseholdDevicesScreen(actions: AppActions) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<HouseholdDevice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<HouseholdDevice?>(null) }
    fun load() {
        scope.launch {
            runCatching { actions.server.householdDevices() }
                .onSuccess { devices = it; error = null }
                .onFailure { error = "Couldn't read them: ${it.message ?: it::class.simpleName}. They need the Nitrate Companion plugin on the server." }
        }
    }
    LaunchedEffect(Unit) { load() }
    val now = dev.nitrate.android.nowMs()
    val list = devices.orEmpty()
    val common = Household.mostConverted(list)

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Household devices", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Each device sends its own summary when its Library playback overview is read, after a test play and after the TV & receiver check. Only admins see this page.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (devices?.isEmpty() == true) Text("No device has sent a summary yet. Open Library playback overview on a device to send one.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = ::load) { Text("Read again") }
            }
        }
        if (common.isNotEmpty()) {
            item { SectionHeader("Converted on several devices") }
            items(common.take(20), key = { "c" + it.first.itemId }) { (title, count) ->
                Column(Modifier.fillMaxWidth().clickable { actions.nav.navigate(DetailsRoute(title.itemId)) }.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    Text(title.name, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull("On $count devices", title.delivery, title.reason).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (list.isNotEmpty()) item { SectionHeader("Devices") }
        items(list, key = { it.userId + it.deviceId }) { d ->
            val s = d.summary
            Column(Modifier.fillMaxWidth().clickable { open = d }.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                Text("${d.userName} · ${d.deviceName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(s?.model, s?.app, agoLabel(d.atMs, now)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(
                        d.convertedCount?.let { "$it converted" },
                        s?.let { "HDR Android reports: ${it.displayHdr.joinToString().ifEmpty { "none" }}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (d.problems.isNotEmpty()) Text("${d.problems.size} to look at", style = MaterialTheme.typography.bodySmall, color = Amber)
            }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }

    open?.let { d ->
        val s = d.summary
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("${d.userName} · ${d.deviceName}") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s == null) Text("This device's app sent a summary this one can't read: update Nitrate on it.")
                    s?.let {
                        @Composable
                        fun group(title: String, lines: List<String>) {
                            if (lines.isEmpty()) return
                            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                            lines.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
                        }
                        group("Device", listOf("Model: ${it.model}", "App: ${it.app}", "Sent ${agoLabel(d.atMs, now)}", "HDR Android reports: ${it.displayHdr.joinToString().ifEmpty { "none" }}"))
                        group("Settings that decide it", it.settings.map { l -> "${l.label}: ${l.value}" })
                        group("Its library overview" + (it.library?.let { l -> " ($l)" } ?: ""), it.counts.map { l -> "${l.label}: ${l.value}" })
                        group("TV & receiver check", it.seen.map { l -> "${l.label}: ${l.value}" })
                        if (d.problems.isNotEmpty()) {
                            Text("To look at", style = MaterialTheme.typography.titleSmall, color = Amber, modifier = Modifier.padding(top = 8.dp))
                            d.problems.forEach { p -> Text(p, style = MaterialTheme.typography.bodySmall) }
                        }
                        group("Test plays", it.tests.flatMap { t -> listOf("${t.name}: ${t.summary}") + (t.answers.map { a -> "   ${a.label} ${a.value}" } + t.differences.map { x -> "   $x" }) })
                        group("Converted here (${it.converted.size})", it.converted.take(60).map { c -> listOfNotNull(c.name, c.reason).joinToString(": ") })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    open = null
                    scope.launch { runCatching { actions.server.forgetHouseholdDevice(d.userId, d.deviceId) }; load() }
                }) { Text("Forget this device") }
            },
        )
    }
}
