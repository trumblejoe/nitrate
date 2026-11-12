package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.AddressCheck
import dev.nitrate.core.model.ServerAddresses
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.inUse
import dev.nitrate.core.model.looksLocal
import dev.nitrate.core.model.ok
import dev.nitrate.core.model.summary
import kotlinx.coroutines.launch

/**
 * The server's address at home and away, each with a test. Nitrate tries the local one first and
 * the remote one after, and moves between them by itself when the network changes.
 */
@Composable
fun ServerAddressesScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var addresses by remember { mutableStateOf<ServerAddresses?>(null) }
    var local by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        container.server.serverAddresses()?.let { a ->
            addresses = a
            local = a.local.orEmpty()
            remote = a.remote.orEmpty()
            if (a.local == null && a.remote == null) {
                if (looksLocal(a.signedInWith)) local = a.signedInWith else remote = a.signedInWith
            }
        }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 58.dp, vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Server addresses", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Nitrate tries the local address first, then the remote one, and switches by itself when you leave home or come back.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        addresses?.let { Text(it.inUse() + (it.active?.let { a -> "  ·  $a" } ?: ""), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        AddressRow(container, "Local address", "On your home network, e.g. 192.168.1.20:8096", local, Modifier.focusRequester(first)) { local = it }
        AddressRow(container, "Remote address", "From anywhere, e.g. https://jellyfin.example.com", remote) { remote = it }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val state = container.saveServerAddresses(local, remote)
                        addresses = container.server.serverAddresses()
                        message = if (state == SessionState.VALID) "Saved. ${addresses?.inUse().orEmpty()}" else "Saved, but neither address answers right now."
                        saving = false
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save") }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun AddressRow(container: AppContainer, label: String, hint: String, value: String, fieldModifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<AddressCheck?>(null) }
    var testing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            dev.nitrate.tv.ui.components.TvTextField(
                value, { onChange(it); result = null }, hint,
                modifier = Modifier.width(560.dp).then(fieldModifier),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            OutlinedButton(
                enabled = value.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    scope.launch { result = container.server.testAddress(value); testing = false }
                },
            ) { Text(if (testing) "Testing…" else "Test connection") }
        }
        result?.let { r -> Text(r.summary(), style = MaterialTheme.typography.bodyMedium, color = if (r.ok) Color(0xFF4CC38A) else MaterialTheme.colorScheme.error) }
    }
}
