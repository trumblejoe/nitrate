package dev.nitrate.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.diagnostics.QueryLog
import dev.nitrate.core.diagnostics.QuerySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val HintColor = Color(0xFFFFB74D)

/**
 * Settings → Tools → Query analyzer: every request this device made to the server this run, which
 * page or feature made it, what it cost and what makes it costly, and what sharing saved. "Record a
 * visit" narrows it to what's asked until Stop (open a page in between); the summary can be saved in
 * the server's logs or copied. With the Companion plugin, the server's own times too.
 */
@Composable
fun QueryAnalyzerScreen(actions: AppActions) {
    val container = actions.container
    val log = container.queryLog
    val scope = rememberCoroutineScope()
    val platformActions = dev.nitrate.mobile.platform.rememberPlatformActions()
    val all by (log?.records ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val recording by (log?.recording ?: remember { MutableStateFlow(null) }).collectAsState()
    var everything by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var names by remember { mutableStateOf(emptyMap<String, String>()) }
    LaunchedEffect(Unit) {
        names = container.queryNames()
        if (container.fetchServerQueryTimes()) message = "Server times added (Nitrate Companion)"
    }
    val shown = recording?.takeIf { !everything }?.let { r -> all.filter(r::covers) } ?: all
    val readable = remember(shown, names) { shown.map { it.copy(origin = QueryLog.readable(it.origin, names)) } }
    val summary = remember(readable) { QuerySummary.of(readable) }
    val title = recording?.takeIf { !everything }?.let { "Visit: ${it.label}" } ?: "This run"

    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Query analyzer", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Every request this phone made to your server since Nitrate opened: which page or feature asked, how long it took and what makes it costly. " +
                        "\"Shared\" requests were answered by one already made. To see one page, tap Record a visit, open the page, come back and tap Stop.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (log == null) Text("This server connection doesn't record requests.")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val r = recording
                    Button(onClick = {
                        if (r == null || !r.recording) {
                            log?.startRecording("visit at ${dev.nitrate.mobile.platform.formatTime(dev.nitrate.android.nowMs())}")
                            everything = false
                            message = "Recording: open a page, then come back and tap Stop"
                        } else { log?.stopRecording(); message = null }
                    }) { Text(if (r?.recording == true) "Stop recording" else "Record a visit") }
                    if (r != null) OutlinedButton(onClick = { everything = !everything }) { Text(if (everything) "Show the visit" else "Show everything") }
                    OutlinedButton(onClick = {
                        message = "Saving…"
                        scope.launch { message = container.sendQueryReport(title, shown).fold({ "Saved on your server as $it (Dashboard → Logs)" }, { "Couldn't save: ${it.message}" }) }
                    }) { Text("Save to server logs") }
                    OutlinedButton(onClick = { platformActions.copy(summary.render(title), "Nitrate query analyzer"); message = "Copied" }) { Text("Copy") }
                    if (container.serverTimesQueries()) OutlinedButton(onClick = {
                        scope.launch { message = if (container.fetchServerQueryTimes()) "Server times added" else "The server didn't answer" }
                    }) { Text("Server times") }
                    OutlinedButton(onClick = { log?.clear(); message = "Cleared" }) { Text("Clear") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = accentColor()) }
                Text("$title: ${summary.headline}", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Costliest", "By page", "Latest").forEachIndexed { i, label -> FilterChip(view == i, { view = i }, label = { Text(label) }) }
                }
            }
        }
        when (view) {
            0 -> items(summary.groups.take(80), key = { "g" + it.shape }) { g ->
                Column(Modifier.fillMaxWidth().copyOnLongPress(g.shape).padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    Text(g.shape, style = MaterialTheme.typography.titleSmall, maxLines = 3)
                    Text(g.counts, style = MaterialTheme.typography.bodySmall)
                    Text("From: " + g.origins.take(4).joinToString("; "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    g.hints.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = HintColor) }
                }
            }
            1 -> items(summary.origins, key = { "o" + it.origin }) { o ->
                Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    Text(o.origin, style = MaterialTheme.typography.titleSmall)
                    Text(o.counts, style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> items(readable.takeLast(80).reversed(), key = { "r" + it.id }) { r ->
                Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp)) {
                    Text("${r.method} ${r.endpoint}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        listOfNotNull(r.origin, r.outcome.label, "${r.ms} ms", r.serverMs?.let { "server $it ms" }, r.bytes?.let { QuerySummary.size(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    r.hints.forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = HintColor) }
                }
            }
        }
        item { Spacer(Modifier.padding(bottom = NavBarClearance)) }
    }
}
