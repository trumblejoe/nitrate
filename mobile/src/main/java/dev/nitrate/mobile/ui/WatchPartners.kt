package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.core.pages.isSpoiler
import dev.nitrate.core.partners.PartnerProgress
import dev.nitrate.core.partners.WatchPartners
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Where the user's watch partners are in the show on screen (empty: none). */
val LocalPartnerProgress = staticCompositionLocalOf<List<PartnerProgress>> { emptyList() }

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

/** Whether this server has watch partners (the Companion plugin). */
@Composable
fun partnersAvailable(actions: AppActions): Boolean {
    val companion by actions.container.companion.collectAsState()
    val enabled by actions.container.plugins.enabledIds.collectAsState(emptySet())
    return companion?.features?.contains(WatchPartners.FEATURE) == true && dev.nitrate.core.partners.WatchPartnersPlugin.ID in enabled
}

/** The partners' progress on a show, for its details pages. */
@Composable
fun rememberPartnerProgress(actions: AppActions, seriesId: String?): List<PartnerProgress> {
    val available = partnersAvailable(actions)
    val partners by actions.container.partners.collectAsState()
    return produceState(emptyList<PartnerProgress>(), seriesId, available, partners) {
        value = if (seriesId == null || !available) emptyList() else runCatching { actions.server.partnerProgress(seriesId) }.getOrDefault(emptyList())
    }.value
}

/**
 * Invites someone to watch a show together: into one of the user's groups for it, or as a new
 * group. Someone listed (people who aren't private), or anyone by exact user name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchWithDialog(actions: AppActions, series: MediaItem, groupId: String? = null, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val info by actions.container.partners.collectAsState()
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>()) { value = runCatching { actions.server.partnerPeople() }.getOrDefault(emptyList()) }
    val groups = info?.on(series.id).orEmpty()
    var target by remember { mutableStateOf(groupId) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val inGroup = groups.firstOrNull { it.id == target }?.others?.map { it.id }.orEmpty().toSet()
    // People known from other groups (private ones too: they already shared a group), then everyone listed.
    val known = info?.groups.orEmpty().flatMap { it.others }
    val everyone = (known + people).distinctBy { it.id }.filter { it.id !in inGroup }
    fun send() {
        error = null
        scope.launch {
            runCatching { actions.server.askPartner(chosen, typed.takeIf { chosen == null }, series.id, series.name, target) }
                .onSuccess {
                    result = "Sent. If that's someone on this server, they'll see it next time they open Nitrate."
                    actions.container.refreshPartners()
                }
                .onFailure { error = it.message }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch ${series.name} with…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (result != null) Text(result!!) else {
                    if (groups.isNotEmpty()) {
                        Text("Invite into", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(target == null, { target = null }, label = { Text("A new group") })
                            groups.forEach { g -> FilterChip(target == g.id, { target = g.id }, label = { Text("With ${g.label}") }) }
                        }
                    }
                    Text(
                        "They'll be asked to accept, and see who's in the group first. Everyone in a group sees each other's progress in the show.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        everyone.forEach { u -> FilterChip(chosen == u.id, { chosen = if (chosen == u.id) null else u.id; typed = "" }, label = { Text(u.name) }) }
                    }
                    OutlinedTextField(
                        typed, { typed = it; if (it.isNotBlank()) chosen = null },
                        label = { Text("Or their exact user name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("For someone not listed. Nothing here tells you whether the account exists.") },
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            if (result != null) TextButton(onClick = onDismiss) { Text("Done") }
            else TextButton(onClick = ::send, enabled = chosen != null || typed.isNotBlank()) { Text("Send") }
        },
        dismissButton = { if (result == null) TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Requests waiting for an answer, one at a time, when the app opens. */
@Composable
fun PartnerRequestPrompt(actions: AppActions) {
    val container = actions.container
    if (!partnersAvailable(actions)) return
    val info by container.partners.collectAsState()
    val later by container.partnerRequestsLater.collectAsState()
    val scope = rememberCoroutineScope()
    val r = info?.incoming?.firstOrNull { it.id !in later } ?: return
    fun answer(accept: Boolean) = scope.launch {
        runCatching { if (accept) container.server.acceptPartner(r.id) else container.server.declinePartner(r.id) }
        container.refreshPartners()
    }
    AlertDialog(
        onDismissRequest = { container.partnerRequestsLater.value = later + r.id },
        title = { Text("Watch ${r.seriesName} together?") },
        text = { Text(inviteText(r)) },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text("Accept") } },
        dismissButton = {
            Row {
                TextButton(onClick = { container.partnerRequestsLater.value = later + r.id }) { Text("Later") }
                TextButton(onClick = { answer(false) }) { Text("Decline") }
            }
        },
    )
}

/** What an invite means, including exactly who learns the user's name and progress by accepting. */
fun inviteText(r: dev.nitrate.core.partners.IncomingRequest): String {
    val others = r.willSee.filter { it != r.fromName }
    val into = if (others.isEmpty()) "${r.fromName} would like to watch ${r.seriesName} with you."
    else "${r.fromName} invites you to watch ${r.seriesName} with ${WatchPartners.names(others)}."
    return "$into If you accept, ${WatchPartners.names(r.willSee)} will see your name and your progress in it, and episodes you watch together count for everyone."
}

/** Settings → Watch partners: privacy, invites, and the user's groups. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchPartnersScreen(actions: AppActions) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    var privacy by remember { mutableStateOf<dev.nitrate.core.partners.Privacy?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var leaving by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var inviting by remember { mutableStateOf<dev.nitrate.core.partners.WatchGroup?>(null) }
    var watching by remember { mutableStateOf(container.watchPartners.sessions.all()) }
    LaunchedEffect(Unit) {
        container.refreshPartners()
        privacy = runCatching { container.server.privacy() }.getOrNull()
    }
    fun act(block: suspend () -> Unit) = scope.launch {
        error = null
        runCatching { block() }.onFailure { error = it.message }
        container.refreshPartners()
    }
    val when_ = DateFormat.getTimeInstance(DateFormat.SHORT)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Watch partners", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Watch a show with a group, and everyone in it sees each other's progress. Nitrate checks before you watch ahead alone, and episodes you watch together count for the whole group. One show can have several groups. Start one from a show's page (Watch with…).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        privacy?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep me private", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (p.byAdmin) "Your server's admin keeps your account private."
                        else "You won't appear in anyone's list of people or be found by name. Someone who knows your exact user name can still send you a request; only if you accept do they learn it's you.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(p.isPrivate, { on -> act { privacy = container.server.setPrivacy(on) } }, enabled = !p.byAdmin)
            }
        }

        val incoming = info?.incoming.orEmpty()
        if (incoming.isNotEmpty()) {
            SectionTitleSmall("Asking you")
            incoming.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.fromName}: ${r.seriesName}")
                        Text("Would see you: ${WatchPartners.names(r.willSee)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { act { container.server.acceptPartner(r.id) } }) { Text("Accept") }
                    TextButton(onClick = { act { container.server.declinePartner(r.id) } }) { Text("Decline") }
                }
            }
        }

        watching.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Watching with ${WatchPartners.names(s.names)} until ${when_.format(Date(s.untilMs))}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { container.stopTogether(s.seriesId); watching = container.watchPartners.sessions.all() }) { Text("Stop") }
            }
        }

        SectionTitleSmall("Your groups")
        val groups = info?.groups.orEmpty()
        if (groups.isEmpty()) Text(if (info == null) "Loading…" else "None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        groups.forEach { g ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(g.seriesName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.pressable({ actions.nav.navigate(DetailsRoute(g.seriesId)) }))
                Text("With ${g.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { inviting = g }) { Text("Invite someone") }
                    TextButton(onClick = { leaving = g }) { Text("Leave") }
                }
            }
        }

        val outgoing = info?.outgoing.orEmpty()
        if (outgoing.isNotEmpty()) {
            SectionTitleSmall("Waiting for an answer")
            outgoing.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.seriesName} with ${r.to}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { act { container.server.cancelPartnerRequest(r.id) } }) { Text("Cancel") }
                }
            }
            Text("Requests wait up to 14 days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
    leaving?.let { g ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Leave ${g.seriesName} with ${g.label}?") },
            text = { Text(if (g.others.size == 1) "The group ends. Nothing you've watched changes." else "The others carry on without you. Nothing you've watched changes.") },
            confirmButton = { TextButton(onClick = { leaving = null; act { container.server.leaveGroup(g.id) } }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Stay") } },
        )
    }
    inviting?.let { g ->
        WatchWithDialog(actions, MediaItem(g.seriesId, g.seriesName, dev.nitrate.core.model.ItemKind.SERIES), g.id) { inviting = null }
    }
}

@Composable
private fun SectionTitleSmall(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
