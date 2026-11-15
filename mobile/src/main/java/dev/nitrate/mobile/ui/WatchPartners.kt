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
 * Asks someone to watch a show together: someone listed (people who aren't private), or anyone
 * by exact user name. A partner already can simply have the show added.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchWithDialog(actions: AppActions, series: MediaItem, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val info by actions.container.partners.collectAsState()
    val people by produceState(emptyList<dev.nitrate.core.writing.UserRef>()) { value = runCatching { actions.server.partnerPeople() }.getOrDefault(emptyList()) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val existing = info?.partners.orEmpty()
    fun send() {
        error = null
        scope.launch {
            val partner = existing.firstOrNull { it.userId == chosen }
            runCatching {
                if (partner != null) {
                    actions.server.addPartnerShow(partner.id, series.id, series.name)
                    "${series.name} is now one you watch with ${partner.userName}."
                } else {
                    actions.server.askPartner(chosen, typed.takeIf { chosen == null }, series.id, series.name)
                    "Sent. If that's someone on this server, they'll see it next time they open Nitrate."
                }
            }.onSuccess { result = it; actions.container.refreshPartners() }.onFailure { error = it.message }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch ${series.name} with…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (result != null) Text(result!!) else {
                    Text("They'll be asked to accept. Episodes you then watch together count for both of you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val everyone = (existing.map { it.userId to it.userName } + people.map { it.id to it.name }).distinctBy { it.first }
                    if (everyone.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        everyone.forEach { (id, name) -> FilterChip(chosen == id, { chosen = if (chosen == id) null else id; typed = "" }, label = { Text(name) }) }
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
        text = { Text("${r.fromName} would like to watch ${r.seriesName} with you. Episodes you watch together count for both of you, and Nitrate checks before either of you watches ahead.") },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text("Accept") } },
        dismissButton = {
            Row {
                TextButton(onClick = { container.partnerRequestsLater.value = later + r.id }) { Text("Later") }
                TextButton(onClick = { answer(false) }) { Text("Decline") }
            }
        },
    )
}

/** Settings → Watch partners: privacy, requests, partners and their shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WatchPartnersScreen(actions: AppActions) {
    val container = actions.container
    val scope = rememberCoroutineScope()
    val info by container.partners.collectAsState()
    var privacy by remember { mutableStateOf<dev.nitrate.core.partners.Privacy?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf<dev.nitrate.core.partners.WatchPartner?>(null) }
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
            "Shows you watch with someone stay in step: Nitrate checks before either of you watches ahead, and episodes you watch together count for both. Ask someone from a show's page (Watch with…).",
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
                    Text("${r.fromName}: ${r.seriesName}", modifier = Modifier.weight(1f))
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

        SectionTitleSmall("Your partners")
        val partners = info?.partners.orEmpty()
        if (partners.isEmpty()) Text(if (info == null) "Loading…" else "None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        partners.forEach { p ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.userName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { ending = p }) { Text("Stop being partners") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.shows.forEach { s ->
                        InputChip(
                            selected = false, onClick = { actions.nav.navigate(DetailsRoute(s.seriesId)) },
                            label = { Text(s.name) },
                            trailingIcon = { Text("✕", modifier = Modifier.padding(start = 4.dp).pressable({ act { container.server.removePartnerShow(p.id, s.seriesId) } })) },
                        )
                    }
                    if (p.shows.isEmpty()) Text("No shows together right now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    ending?.let { p ->
        AlertDialog(
            onDismissRequest = { ending = null },
            title = { Text("Stop being partners with ${p.userName}?") },
            text = { Text("Your shows go back to being kept separately. Nothing you've watched changes.") },
            confirmButton = { TextButton(onClick = { ending = null; act { container.server.endPartnership(p.id) } }) { Text("Stop") } },
            dismissButton = { TextButton(onClick = { ending = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SectionTitleSmall(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
