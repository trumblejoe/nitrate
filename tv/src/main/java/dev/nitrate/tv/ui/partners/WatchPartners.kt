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

/** Invites someone to watch a show together: into one of the user's groups for it, or a new group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchWithDialog(container: AppContainer, series: MediaItem, groupId: String? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>()) { value = runCatching { container.server.partnerPeople() }.getOrDefault(emptyList()) }
    val groups = info?.on(series.id).orEmpty()
    var target by remember { mutableStateOf(groupId) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val inGroup = groups.firstOrNull { it.id == target }?.others?.map { it.id }.orEmpty().toSet()
    val everyone = (info?.groups.orEmpty().flatMap { it.others } + people).distinctBy { it.id }.filter { it.id !in inGroup }
    fun send() {
        error = null
        scope.launch {
            runCatching { container.server.askPartner(chosen, typed.takeIf { chosen == null }, series.id, series.name, target) }
                .onSuccess { result = "Sent. If that's someone on this server, they'll see it next time they open Nitrate."; container.refreshPartners() }
                .onFailure { error = it.message }
        }
    }
    TvDialog(onDismiss, width = 900) {
        Text("Watch ${series.name} with…", style = MaterialTheme.typography.headlineSmall)
        if (result != null) {
            Text(result!!)
            Button(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) { Text("Done") }
        } else {
            if (groups.isNotEmpty()) {
                Text("Invite into", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(target == null, { target = null }) { Text("A new group") }
                    groups.forEach { g -> FilterChip(target == g.id, { target = g.id }) { Text("With ${g.label}") } }
                }
            }
            Text("They'll be asked to accept, and see who's in the group first. Everyone in a group sees each other's progress in the show.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                everyone.forEach { u -> FilterChip(chosen == u.id, { chosen = if (chosen == u.id) null else u.id; typed = "" }) { Text(u.name) } }
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

/** What an invite means, including exactly who learns the user's name and progress by accepting. */
fun inviteText(r: dev.nitrate.core.partners.IncomingRequest): String {
    val others = r.willSee.filter { it != r.fromName }
    val into = if (others.isEmpty()) "${r.fromName} would like to watch ${r.seriesName} with you."
    else "${r.fromName} invites you to watch ${r.seriesName} with ${WatchPartners.names(others)}."
    return "$into If you accept, ${WatchPartners.names(r.willSee)} will see your name and your progress in it, and episodes you watch together count for everyone."
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
        Text(inviteText(r))
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
    var leaving by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var inviting by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
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
                    "Watch a show with a group, and everyone in it sees each other's progress. Nitrate checks before you watch ahead alone, and episodes you watch together count for the whole group. One show can have several groups. Start one from a show's page (Watch with…).",
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
                        Column(Modifier.width(560.dp)) {
                            Text("${r.fromName}: ${r.seriesName}")
                            Text("Would see you: ${WatchPartners.names(r.willSee)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
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
        item { Text("Your groups", style = MaterialTheme.typography.titleLarge) }
        val groups = info?.groups.orEmpty()
        if (groups.isEmpty()) item { Text(if (info == null) "Loading…" else "None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        groups.forEach { g ->
            item(key = "g-${g.id}") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(480.dp)) {
                        Text(g.seriesName, style = MaterialTheme.typography.titleMedium)
                        Text("With ${g.label}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { onOpenShow(g.seriesId) }) { Text("Open") }
                    OutlinedButton(onClick = { inviting = g }) { Text("Invite someone") }
                    OutlinedButton(onClick = { leaving = g }) { Text("Leave") }
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
    leaving?.let { g ->
        val focus = remember { FocusRequester() }
        TvDialog({ leaving = null }) {
            Text("Leave ${g.seriesName} with ${g.label}?", style = MaterialTheme.typography.headlineSmall)
            Text(if (g.others.size == 1) "The group ends. Nothing you've watched changes." else "The others carry on without you. Nothing you've watched changes.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { leaving = null }, modifier = Modifier.focusRequester(focus)) { Text("Stay") }
                Button(onClick = { leaving = null; act { container.server.leaveGroup(g.id) } }) { Text("Leave") }
            }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
    inviting?.let { g ->
        WatchWithDialog(container, MediaItem(g.seriesId, g.seriesName, dev.nitrate.core.model.ItemKind.SERIES), g.id) { inviting = null }
    }
}
