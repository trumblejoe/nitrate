package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.AddressCheck
import dev.nitrate.core.model.SavedServer
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.inUse
import dev.nitrate.core.model.looksLocal
import dev.nitrate.core.model.ok
import dev.nitrate.core.model.summary
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch

/**
 * Every server signed in to here, the primary first. Each has a local, VPN and remote address
 * (tried in that order, re-picked whenever the network changes), a test for each, and can be made
 * primary (opened at start) or switched to. [onSwitch] opens a server's account; [onAdd] signs in
 * to another server.
 */
@Composable
fun ServersScreen(container: AppContainer, onSwitch: (SavedServer) -> Unit, onAdd: () -> Unit) {
    var servers by remember { mutableStateOf<List<SavedServer>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { servers = container.server.servers() }
    val first = remember { FocusRequester() }
    LaunchedEffect(servers.isNotEmpty()) { if (servers.isNotEmpty()) runCatching { first.requestFocus() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Servers", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Nitrate tries each server's local address first, then VPN, then remote, and switches by itself when you leave home or come back. The primary server opens when Nitrate starts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(servers, key = { it.url }) { server ->
            ServerSection(container, server, if (server == servers.first()) Modifier.focusRequester(first) else Modifier, onChanged = { reload++ }, onSwitch = { onSwitch(server) })
        }
        item { OutlinedButton(onClick = onAdd) { Text("Add a server") } }
    }
}

@Composable
private fun ServerSection(container: AppContainer, server: SavedServer, headerModifier: Modifier, onChanged: () -> Unit, onSwitch: () -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember(server.url) { mutableStateOf(server.isCurrent) }
    val unset = server.local == null && server.vpn == null && server.remote == null
    var local by remember(server) { mutableStateOf(server.local ?: server.url.takeIf { unset && looksLocal(it) }.orEmpty()) }
    var vpn by remember(server) { mutableStateOf(server.vpn.orEmpty()) }
    var remote by remember(server) { mutableStateOf(server.remote ?: server.url.takeIf { unset && !looksLocal(it) }.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ListItem(
            selected = false,
            onClick = { open = !open },
            headlineContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name)
                    if (server.primary) Text("PRIMARY", style = MaterialTheme.typography.labelSmall, color = Color.Black, modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            },
            supportingContent = {
                Column {
                    Text(server.accounts.joinToString { it.userName }.ifEmpty { server.url })
                    server.inUse()?.let { Text("$it  ·  ${server.active}", color = MaterialTheme.colorScheme.primary) }
                }
            },
            trailingContent = { Text(if (open) "▴" else "▾") },
            modifier = headerModifier,
        )
        if (open) {
            AddressRow(container, server.url, "Local address", "On your home network, e.g. 192.168.1.20:8096", local) { local = it }
            AddressRow(container, server.url, "VPN address", "Through your VPN, e.g. 100.64.0.7:8096", vpn) { vpn = it }
            AddressRow(container, server.url, "Remote address", "From anywhere, e.g. https://jellyfin.example.com", remote) { remote = it }
            Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val state = container.saveServerAddresses(server.url, local, vpn, remote)
                            message = if (state == SessionState.VALID) "Saved" else "Saved, but no address answers right now"
                            saving = false
                            onChanged()
                        }
                    },
                ) { Text(if (saving) "Saving…" else "Save") }
                if (!server.primary) OutlinedButton(onClick = { scope.launch { container.server.setPrimaryServer(server.url); onChanged() } }) { Text("Make primary") }
                if (!server.isCurrent) OutlinedButton(onClick = onSwitch) { Text("Switch to") }
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun AddressRow(container: AppContainer, serverUrl: String, label: String, hint: String, value: String, onChange: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<AddressCheck?>(null) }
    var testing by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            TvTextField(
                value, { onChange(it); result = null }, hint,
                modifier = Modifier.width(560.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            OutlinedButton(
                enabled = value.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    scope.launch { result = container.server.testAddress(value, serverUrl); testing = false }
                },
            ) { Text(if (testing) "Testing…" else "Test connection") }
        }
        result?.let { r -> Text(r.summary(), style = MaterialTheme.typography.bodyMedium, color = if (r.ok) Color(0xFF4CC38A) else MaterialTheme.colorScheme.error) }
    }
}
