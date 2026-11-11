package dev.nitrate.tv.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch
import dev.nitrate.core.model.DiscoveredServer

@Composable
fun ConnectScreen(
    repository: MediaServer,
    onConnected: (serverUrl: String, serverName: String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(true) }
    var searchRun by remember { mutableStateOf(0) }
    val found = remember { mutableStateListOf<DiscoveredServer>() }
    val firstServer = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Broadcast discovery: servers on the same network answer within a few seconds.
    LaunchedEffect(searchRun) {
        searching = true
        runCatching {
            repository.discoverServers().collect { server ->
                if (found.none { it.id == server.id }) {
                    found += server
                    if (found.size == 1) runCatching { firstServer.requestFocus() }
                }
            }
        }
        searching = false
    }

    fun connect(input: String) {
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { repository.findServer(input.trim()) }
                .onSuccess { server ->
                    val name = server.name
                    onConnected(server.address, name)
                }
                .onFailure { error = it.message ?: "Could not reach server" }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Nitrate", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Text("Connect to your Jellyfin server", style = MaterialTheme.typography.titleLarge)

            Text(
                when {
                    found.isNotEmpty() -> "Found on your network"
                    searching -> "Looking for servers on your network…"
                    else -> "No servers found on your network"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            found.forEachIndexed { i, server ->
                ListItem(
                    selected = false,
                    onClick = { connect(server.address) },
                    headlineContent = { Text(server.name) },
                    supportingContent = { Text(server.address) },
                    modifier = if (i == 0) Modifier.focusRequester(firstServer) else Modifier,
                )
            }
            if (!searching && found.isEmpty()) {
                OutlinedButton(onClick = { searchRun++ }) { Text("Search again") }
            }

            Text("Or enter an address", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            TvTextField(
                value = address,
                onValueChange = { address = it },
                placeholder = "e.g. 192.168.1.20:8096 or jellyfin.example.com",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect(address) }),
            )
            Button(onClick = { connect(address) }, enabled = !busy) {
                Text(if (busy) "Connecting…" else "Connect")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
