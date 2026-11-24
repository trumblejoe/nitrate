package dev.nitrate.tv.ui.together

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/**
 * A watch-together invite from an older Nitrate app (checked every 30 s while the app is open; TVs
 * show no notifications), kept for compatibility: Join puts this TV in that session (a Group Watch
 * Session's, else the older app's SyncPlay group), Not now turns it down. Nitrate's own "Watch now
 * together" sends no invites: the group's apps are told at once (the join prompt).
 */
@Composable
fun WatchInvitePrompt(container: dev.nitrate.android.AppContainer) {
    val companion by container.companion.collectAsState()
    LaunchedEffect(companion) {
        while (true) {
            container.refreshInvites()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val invites by container.watchInvites.collectAsState()
    val scope = rememberCoroutineScope()
    val invite = invites.firstOrNull() ?: return
    val focus = remember(invite.id) { FocusRequester() }
    dev.nitrate.tv.ui.lists.TvDialog({ scope.launch { container.dismissInvite(invite) } }, width = 640) {
        Text("Watch now together?", style = MaterialTheme.typography.headlineSmall)
        Text(invite.text + ". Joining puts this TV in their session: what they play, plays here.")
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.tv.material3.Button(onClick = { scope.launch { runCatching { container.joinInvite(invite) } } }, modifier = Modifier.focusRequester(focus)) { Text("Join") }
            OutlinedButton(onClick = { scope.launch { container.dismissInvite(invite) } }) { Text("Not now") }
        }
    }
    LaunchedEffect(invite.id) { kotlinx.coroutines.delay(50); runCatching { focus.requestFocus() } }
}
