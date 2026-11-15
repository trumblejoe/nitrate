package dev.nitrate.tv.ui.partners

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.core.pages.isSpoiler
import dev.nitrate.core.partners.PartnerProgress
import dev.nitrate.core.partners.WatchPartners
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.lists.TvDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Where the user's watch partners are in the show on screen (empty: none). */
val LocalPartnerProgress = staticCompositionLocalOf<List<PartnerProgress>> { emptyList() }

/** Opens "Watch with…" for a show (null: no watch partners on this server). */
val LocalWatchWith = staticCompositionLocalOf<((MediaItem) -> Unit)?> { null }

/** Whether an episode still is blurred: unwatched by the user, or (on a shared show) by a partner. */
@Composable
fun spoilerStill(item: MediaItem): Boolean {
    val prefs = LocalDisplayPrefs.current
    val progress = LocalPartnerProgress.current
    return prefs.isSpoiler(item) || (progress.isNotEmpty() && WatchPartners.isSpoiler(prefs, item, progress))
}

/** Whether a description waits behind "Show": unwatched by the user, or (on a shared show) by a partner. */
@Composable
fun descriptionHidden(item: MediaItem): Boolean {
    val prefs = LocalDisplayPrefs.current
    val progress = LocalPartnerProgress.current
    return prefs.hidesDescription(item) || (progress.isNotEmpty() && WatchPartners.hidesDescription(prefs, item, progress))
}

/** Asks someone to watch a show together (a listed person, or anyone by exact user name); a partner just gets the show added. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchWithDialog(container: AppContainer, series: MediaItem, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>()) { value = runCatching { container.server.partnerPeople() }.getOrDefault(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val existing = info?.partners.orEmpty()
    fun send() {
        error = null
        scope.launch {
            val partner = existing.firstOrNull { it.userId == chosen }
            runCatching {
                if (partner != null) {
                    container.server.addPartnerShow(partner.id, series.id, series.name)
                    "${series.name} is now one you watch with ${partner.userName}."
                } else {
                    container.server.askPartner(chosen, typed.takeIf { chosen == null }, series.id, series.name)
                    "Sent. If that's someone on this server, they'll see it next time they open Nitrate."
                }
            }.onSuccess { result = it; container.refreshPartners() }.onFailure { error = it.message }
        }
    }
    TvDialog(onDismiss, width = 860) {
        Text("Watch ${series.name} with…", style = MaterialTheme.typography.headlineSmall)
        if (result != null) {
            Text(result!!)
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text("Done") }
        } else {
            Text("They'll be asked to accept. Episodes you then watch together count for both of you.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val everyone = (existing.map { it.userId to it.userName } + people.map { it.id to it.name }).distinctBy { it.first }
            if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                everyone.forEach { (id, name) -> FilterChip(chosen == id, { chosen = if (chosen == id) null else id; typed = "" }) { Text(name) } }
            }
            TvTextField(typed, { typed = it; if (it.isNotBlank()) chosen = null }, "Or their exact user name (for someone not listed)", modifier = Modifier.width(620.dp))
            Text("Nothing here tells you whether an account exists.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::send, enabled = chosen != null || typed.isNotBlank()) { Text("Send") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text("Cancel") }
            }
        }
    }
    LaunchedEffect(result) { delay(50); runCatching { focus.requestFocus() } }
}

/** Requests waiting for an answer, one at a time, when the app opens. */
@Composable
fun PartnerRequestPrompt(container: AppContainer) {
    val info by container.partners.collectAsState()
    val later by container.partnerRequestsLater.collectAsState()
    val scope = rememberCoroutineScope()
    val r = info?.incoming?.firstOrNull { it.id !in later } ?: return
    val focus = remember(r.id) { FocusRequester() }
    fun answer(accept: Boolean) = scope.launch {
        runCatching { if (accept) container.server.acceptPartner(r.id) else container.server.declinePartner(r.id) }
        container.refreshPartners()
    }
    TvDialog({ container.partnerRequestsLater.value = later + r.id }, width = 720) {
        Text("Watch ${r.seriesName} together?", style = MaterialTheme.typography.headlineSmall)
        Text("${r.fromName} would like to watch ${r.seriesName} with you. Episodes you watch together count for both of you, and Nitrate checks before either of you watches ahead.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { answer(true) }, modifier = Modifier.focusRequester(focus)) { Text("Accept") }
            OutlinedButton(onClick = { container.partnerRequestsLater.value = later + r.id }) { Text("Later") }
            OutlinedButton(onClick = { answer(false) }) { Text("Decline") }
        }
    }
    LaunchedEffect(r.id) { delay(50); runCatching { focus.requestFocus() } }
}

/** Settings → Watch partners: privacy, requests, partners and their shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchPartnersScreen(container: AppContainer, onOpenShow: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    var privacy by remember { mutableStateOf<dev.nitrate.core.partners.Privacy?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf<dev.nitrate.core.partners.WatchPartner?>(null) }
    var watching by remember { mutableStateOf(container.watchPartners.sessions.all()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        container.refreshPartners()
        privacy = runCatching { container.server.privacy() }.getOrNull()
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        error = null
        runCatching { block() }.onFailure { error = it.message }
        container.refreshPartners()
    }
    val clock = DateFormat.getTimeInstance(DateFormat.SHORT)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 58.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Watch partners", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Shows you watch with someone stay in step: Nitrate checks before either of you watches ahead, and episodes you watch together count for both. Ask someone from a show's page (Watch with…).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        item {
            val p = privacy
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(p?.isPrivate == true, { p?.let { act { privacy = container.server.setPrivacy(!it.isPrivate) } } }, enabled = p != null && !p.byAdmin, modifier = Modifier.focusRequester(first)) {
                    Text("Keep me private")
                }
                Text(
                    if (p?.byAdmin == true) "Your server's admin keeps your account private."
                    else "You won't appear in anyone's list of people or be found by name. Someone who knows your exact user name can still send you a request; only if you accept do they learn it's you.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val incoming = info?.incoming.orEmpty()
        if (incoming.isNotEmpty()) {
            item { Text("Asking you", style = MaterialTheme.typography.titleLarge) }
            incoming.forEach { r ->
                item(key = "in-${r.id}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.fromName}: ${r.seriesName}", modifier = Modifier.width(560.dp))
                        Button(onClick = { act { container.server.acceptPartner(r.id) } }) { Text("Accept") }
                        OutlinedButton(onClick = { act { container.server.declinePartner(r.id) } }) { Text("Decline") }
                    }
                }
            }
        }
        watching.forEach { s ->
            item(key = "w-${s.seriesId}") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Watching with ${WatchPartners.names(s.names)} until ${clock.format(Date(s.untilMs))}", modifier = Modifier.width(560.dp))
                    OutlinedButton(onClick = { container.stopTogether(s.seriesId); watching = container.watchPartners.sessions.all() }) { Text("Stop") }
                }
            }
        }
        item { Text("Your partners", style = MaterialTheme.typography.titleLarge) }
        val partners = info?.partners.orEmpty()
        if (partners.isEmpty()) item { Text(if (info == null) "Loading…" else "None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        partners.forEach { p ->
            item(key = "p-${p.id}") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.userName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(400.dp))
                        OutlinedButton(onClick = { ending = p }) { Text("Stop being partners") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.shows.forEach { s ->
                            OutlinedButton(onClick = { onOpenShow(s.seriesId) }) { Text(s.name) }
                            OutlinedButton(onClick = { act { container.server.removePartnerShow(p.id, s.seriesId) } }) { Text("Remove ${s.name}") }
                        }
                        if (p.shows.isEmpty()) Text("No shows together right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val outgoing = info?.outgoing.orEmpty()
        if (outgoing.isNotEmpty()) {
            item { Text("Waiting for an answer (up to 14 days)", style = MaterialTheme.typography.titleLarge) }
            outgoing.forEach { r ->
                item(key = "out-${r.id}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r.seriesName} with ${r.to}", modifier = Modifier.width(560.dp))
                        OutlinedButton(onClick = { act { container.server.cancelPartnerRequest(r.id) } }) { Text("Cancel") }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    ending?.let { p ->
        val focus = remember { FocusRequester() }
        TvDialog({ ending = null }) {
            Text("Stop being partners with ${p.userName}?", style = MaterialTheme.typography.headlineSmall)
            Text("Your shows go back to being kept separately. Nothing you've watched changes.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { ending = null }, modifier = Modifier.focusRequester(focus)) { Text("Keep") }
                Button(onClick = { ending = null; act { container.server.endPartnership(p.id) } }) { Text("Stop") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}
