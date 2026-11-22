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
import androidx.compose.runtime.collectAsState
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
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.diagnostics.QueryLog
import dev.nitrate.core.diagnostics.QueryOutcome
import dev.nitrate.core.diagnostics.QuerySummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Settings → Tools → Query analyzer: every request this TV made to the server this run, which
 * page or feature made it, what it cost and what makes it costly, with what sharing saved. "Record
 * a visit" narrows it to what's asked between starting and stopping (open a page in between); the
 * summary can be saved in the server's logs. With the Companion plugin, the server's own times too.
 */
@Composable
fun QueryAnalyzerScreen(container: AppContainer) {
    val log = container.queryLog
    val scope = rememberCoroutineScope()
    val all by (log?.records ?: remember { MutableStateFlow(emptyList()) }).collectAsState()
    val recording by (log?.recording ?: remember { MutableStateFlow(null) }).collectAsState()
    var everything by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var names by remember { mutableStateOf(emptyMap<String, String>()) }
    val first = remember { FocusRequester() }
    var household by remember { mutableStateOf(emptyList<dev.nitrate.core.diagnostics.HouseholdQuery>()) }
    LaunchedEffect(Unit) { household = container.householdQueries() }
    var trends by remember { mutableStateOf(emptyList<dev.nitrate.core.companion.QueryTrend>()) }
    LaunchedEffect(Unit) { trends = container.queryTrends() }
    LaunchedEffect(Unit) {
        names = container.queryNames()
        if (container.fetchServerQueryTimes()) message = "Server times added (Nitrate Companion)"
    }

    val shown = recording?.takeIf { !everything }?.let { r -> all.filter(r::covers) } ?: all
    val readable = remember(shown, names) { shown.map { it.copy(origin = QueryLog.readable(it.origin, names)) } }
    val summary = remember(readable) { QuerySummary.of(readable) }
    val title = recording?.takeIf { !everything }?.let { "Visit: ${it.label}" } ?: "This run"

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(bottom = 12.dp).widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Query analyzer", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Every request this TV made to your server since Nitrate started: which page or feature asked, how long it took and what makes it costly. " +
                        "\"Shared\" requests were answered by one already made (pages asking the same thing share one answer). " +
                        "To see one page, press Record a visit, open the page, come back and press Stop.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (log == null) Text("This server connection doesn't record requests.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                    val r = recording
                    Button(
                        onClick = {
                            when {
                                r == null || !r.recording -> { log?.startRecording("visit ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())}"); everything = false; message = "Recording: open a page, then come back and press Stop" }
                                else -> { log?.stopRecording(); message = null }
                            }
                        },
                        modifier = Modifier.focusRequester(first),
                    ) { Text(if (r?.recording == true) "Stop recording" else "Record a visit") }
                    if (r != null) OutlinedButton(onClick = { everything = !everything }) { Text(if (everything) "Show the visit" else "Show everything") }
                    OutlinedButton(onClick = {
                        message = "Saving…"
                        scope.launch {
                            message = container.sendQueryReport(title, shown).fold({ "Saved on your server as $it (Dashboard → Logs)" }, { "Couldn't save: ${it.message}" })
                        }
                    }) { Text("Save to server logs") }
                    if (container.serverTimesQueries()) OutlinedButton(onClick = {
                        scope.launch { message = if (container.fetchServerQueryTimes()) "Server times added" else "The server didn't answer" }
                    }) { Text("Server times") }
                    OutlinedButton(onClick = { log?.clear(); message = "Cleared" }) { Text("Clear") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text("$title: ${summary.headline}", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (summary.groups.isNotEmpty()) item { QueryHeading("Costliest requests") }
        items(summary.groups.take(60), key = { "g" + it.shape }) { g ->
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = { Text(g.shape, maxLines = 2) },
                supportingContent = {
                    Column {
                        Text(g.counts)
                        Text("From: " + g.origins.take(4).joinToString("; "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        g.hints.forEach { Text("•  $it", color = HintColor) }
                    }
                },
                modifier = Modifier.widthIn(max = 1000.dp),
            )
        }
        if (summary.origins.isNotEmpty()) item { QueryHeading("By page or feature") }
        items(summary.origins, key = { "o" + it.origin }) { o ->
            ListItem(selected = false, onClick = {}, headlineContent = { Text(o.origin) }, supportingContent = { Text(o.counts) }, modifier = Modifier.widthIn(max = 1000.dp))
        }
        if (household.isNotEmpty()) item {
            Column {
                QueryHeading("Across the household (last 2 hours)")
                Text("Everyone's Nitrate requests by kind, as the server timed them. No names, titles or search words.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(household.take(25), key = { "h" + it.shape }) { h ->
            ListItem(selected = false, onClick = {}, headlineContent = { Text(h.shape, maxLines = 2) }, supportingContent = { Text(h.counts) }, modifier = Modifier.widthIn(max = 1000.dp))
        }
        if (trends.isNotEmpty()) item {
            Column {
                QueryHeading("Over time")
                Text("Each kind of request's typical time over the last 7 days against the 7 before, from the server's own timing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(trends.take(25), key = { "t" + it.shape }) { t ->
            ListItem(
                selected = false, onClick = {},
                headlineContent = { Text(t.shape, maxLines = 2) },
                supportingContent = { Text(t.line(), color = if ((t.change() ?: 0) >= 25) HintColor else Color.Unspecified) },
                modifier = Modifier.widthIn(max = 1000.dp),
            )
        }
        if (readable.isNotEmpty()) item { QueryHeading("Latest requests") }
        items(readable.takeLast(40).reversed(), key = { "r" + it.id }) { r ->
            ListItem(
                selected = false,
                onClick = {},
                headlineContent = { Text("${r.method} ${r.endpoint}", maxLines = 1) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            r.origin,
                            r.outcome.label,
                            "${r.ms} ms",
                            r.serverMs?.let { "server $it ms" },
                            r.bytes?.let { QuerySummary.size(it) },
                            r.status?.takeIf { it >= 400 }?.let { "status $it" },
                        ).joinToString(" · "),
                    )
                },
                trailingContent = { if (r.outcome == QueryOutcome.FETCHED && r.hints.isNotEmpty()) Text("${r.hints.size} hint" + if (r.hints.size > 1) "s" else "", color = HintColor) },
                modifier = Modifier.widthIn(max = 1000.dp),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

private val HintColor = Color(0xFFE8C35A)

@Composable
private fun QueryHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}
