package dev.nitrate.tv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val AUTO_RETRY_MS = 30_000L

/**
 * Home while the server can't be reached. Media needs the server, but plugin pages such as
 * Writing work offline. Checks again every 30 s and returns to Home once the server answers.
 */
@Composable
fun OfflineScreen(
    repository: MediaServer,
    screens: List<Pair<String, () -> Unit>>,
    onPlugins: () -> Unit,
    onOnline: () -> Unit,
    onSignedOut: () -> Unit,
) {
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }

    suspend fun check() {
        checking = true
        when (repository.checkSession()) {
            SessionState.VALID -> onOnline()
            SessionState.EXPIRED, SessionState.NONE -> onSignedOut()
            SessionState.UNREACHABLE -> Unit
        }
        checking = false
    }

    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        while (true) {
            delay(AUTO_RETRY_MS)
            check()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Working offline", style = MaterialTheme.typography.displaySmall)
            Text(
                "Can't reach ${repository.serverUrl ?: "your server"}. Movies and shows come back when it does; " +
                    "Nitrate checks every 30 seconds. Your writing saves on this TV and backs up once the server is back.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val (firstLabel, firstAction) = screens.firstOrNull() ?: (null to null)
                if (firstLabel != null && firstAction != null) {
                    Button(onClick = firstAction, modifier = Modifier.focusRequester(first)) { Text(firstLabel) }
                }
                for ((label, action) in screens.drop(1)) OutlinedButton(onClick = action) { Text(label) }
                OutlinedButton(
                    onClick = { scope.launch { check() } },
                    enabled = !checking,
                    modifier = if (screens.isEmpty()) Modifier.focusRequester(first) else Modifier,
                ) { Text(if (checking) "Checking…" else "Try again") }
                OutlinedButton(onClick = onPlugins) { Text("Plugins") }
            }
        }
    }
}
