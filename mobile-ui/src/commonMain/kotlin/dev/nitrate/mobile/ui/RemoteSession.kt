package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.nitrate.core.partners.GroupWatchSession
import dev.nitrate.core.partners.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the remote looks at the Group Watch Session again. */
private const val SESSION_LOOK_MS = 3_000L

/**
 * The remote's Group Watch Session card, for a TV running Nitrate (with the Companion's sessions and
 * a watch group): start one hosted on the TV before anything plays (who, then what, chosen on
 * this phone), and while it gathers or plays, who's in and who it waits for, Start now and End
 * for everyone (its starter: this account, whichever device).
 */
@Composable
fun RemoteSessionCard(actions: AppActions, sessionId: String, deviceName: String) {
    val container = actions.container
    if (!container.sessionsAvailable()) return
    val info by container.partners.collectAsState()
    val groups = info?.groups.orEmpty()
    if (groups.isEmpty()) return
    var tick by remember { mutableIntStateOf(0) }
    val live by produceState<GroupWatchSession?>(null, tick) {
        while (true) {
            value = runCatching { container.server.watchSessions() }.getOrNull()?.firstOrNull { it.live && it.joined }
            delay(SESSION_LOOK_MS)
        }
    }
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun act(block: suspend () -> Unit) = scope.launch {
        problem = runCatching { block() }.exceptionOrNull()?.let { it.message ?: "That didn't work" }
        tick++
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.08f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Group Watch Session", style = MaterialTheme.typography.labelLarge, color = accentColor())
        val s = live
        if (s == null) {
            Text(
                "Start one on $deviceName: pick who, then what. It waits for them before playing, and you start it from here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!picking) {
                Button(onClick = { picking = true }) { Text("Start a Group Watch Session") }
            } else {
                Text("Who with?", fontWeight = FontWeight.SemiBold)
                groups.forEach { g ->
                    OutlinedButton(onClick = {
                        picking = false
                        ChooseForTv.start(sessionId, deviceName, g.id, g.label)
                        actions.nav.popBackStack()
                    }, modifier = Modifier.fillMaxWidth()) { Text("${g.label} · ${g.seriesName}") }
                }
                TextButton(onClick = { picking = false }) { Text("Cancel") }
            }
        } else {
            val group = groups.firstOrNull { it.id == s.groupId }
            Text(s.what, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(SessionStatus.line(s, group), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ending) {
                Text("End it for everyone?", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ending = false; act { container.server.endWatchSession(s.id) } }) { Text("End it") }
                    TextButton(onClick = { ending = false }) { Text("Keep it going") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (SessionStatus.canStartNow(s)) Button(onClick = { act { container.server.startWatchSessionNow(s.id) } }) { Text("Start now") }
                    if (SessionStatus.canEnd(s)) OutlinedButton(onClick = { ending = true }) { Text("End for everyone") }
                }
            }
        }
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
