package dev.nitrate.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.AddressCheck
import dev.nitrate.core.model.ServerAddresses
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.inUse
import dev.nitrate.core.model.ok
import dev.nitrate.core.model.summary
import kotlinx.coroutines.launch

/**
 * The server's address at home and away. Nitrate tries the local one first and falls back to the
 * remote one, and moves between them by itself when the network changes.
 */
@Composable
fun ServerAddressesScreen(actions: AppActions) {
    val scope = rememberCoroutineScope()
    var addresses by remember { mutableStateOf<ServerAddresses?>(null) }
    var local by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        actions.server.serverAddresses()?.let { a ->
            addresses = a
            local = a.local.orEmpty()
            remote = a.remote.orEmpty()
            // First time: the address signed in with goes where it belongs.
            if (a.local == null && a.remote == null) {
                if (dev.nitrate.core.model.looksLocal(a.signedInWith)) local = a.signedInWith else remote = a.signedInWith
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Text("Server addresses", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Nitrate tries the local address first, then the remote one, and switches by itself when you leave home or come back.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        addresses?.let { Text(it.inUse() + (it.active?.let { a -> "  ·  $a" } ?: ""), color = accentColor(), style = MaterialTheme.typography.labelLarge) }
        AddressField(actions, "Local address", "On your home network, e.g. 192.168.1.20:8096", local) { local = it }
        AddressField(actions, "Remote address", "From anywhere, e.g. https://jellyfin.example.com", remote) { remote = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val state = actions.container.saveServerAddresses(local, remote)
                        addresses = actions.server.serverAddresses()
                        message = if (state == SessionState.VALID) "Saved. ${addresses?.inUse().orEmpty()}" else "Saved, but neither address answers right now."
                        saving = false
                    }
                },
            ) { Text(if (saving) "Saving…" else "Save") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.padding(bottom = 32.dp))
    }
}

/** An address with its own Test button and result. */
@Composable
private fun AddressField(actions: AppActions, label: String, hint: String, value: String, onChange: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<AddressCheck?>(null) }
    var testing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); result = null },
            label = { Text(label) },
            placeholder = { Text(hint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                enabled = value.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    scope.launch { result = actions.server.testAddress(value); testing = false }
                },
            ) { Text(if (testing) "Testing…" else "Test connection") }
            result?.let { r ->
                Text(r.summary(), color = if (r.ok) Color(0xFF4CC38A) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}
