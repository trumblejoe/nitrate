package dev.nitrate.tv.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(
    repository: JellyfinRepository,
    onConnected: (serverUrl: String, serverName: String) -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun connect() {
        if (address.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { repository.findServer(address.trim()) }
                .onSuccess { server ->
                    val name = server.systemInfo.getOrNull()?.serverName ?: server.address
                    onConnected(server.address, name)
                }
                .onFailure { error = it.message ?: "Could not reach server" }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(520.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Nitrate", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Text("Connect to your Jellyfin server", style = MaterialTheme.typography.titleLarge)
            TvTextField(
                value = address,
                onValueChange = { address = it },
                placeholder = "Server address, e.g. 192.168.1.20:8096",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect() }),
            )
            Button(onClick = ::connect, enabled = !busy) {
                Text(if (busy) "Connecting…" else "Connect")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
