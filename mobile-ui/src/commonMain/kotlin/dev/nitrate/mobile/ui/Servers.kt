package dev.nitrate.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.AddressCheck
import dev.nitrate.core.model.SavedServer
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.inUse
import dev.nitrate.core.model.looksLocal
import dev.nitrate.core.model.ok
import dev.nitrate.core.model.summary
import kotlinx.coroutines.launch

/**
 * Every server signed in to here, the primary first. Each has a local, VPN and remote address
 * (tried in that order, re-picked whenever the network changes), a test for each, and can be made
 * primary (opened at start) or switched to. New servers are added by signing in.
 */
@Composable
fun ServersScreen(actions: AppActions) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<SavedServer>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { servers = actions.server.servers() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Text("Servers", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Nitrate tries each server's local address first, then VPN, then remote, and switches by itself when you leave home or come back. The primary server opens when Nitrate starts.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        servers.forEach { server ->
            ServerCard(
                actions, server,
                onChanged = { reload++ },
                onSwitch = {
                    scope.launch {
                        val only = server.accounts.singleOrNull()
                        // A single unlocked account opens straight away; otherwise pick on the profiles screen.
                        if (only != null && !actions.container.profiles.hasPin(only.key) && actions.container.switchAccount(only.key) == SessionState.VALID) {
                            actions.nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
                        } else {
                            actions.nav.navigate(ProfilesRoute)
                        }
                    }
                },
            )
        }
        PillButton("Add a server", null, primary = false, onClick = { actions.nav.navigate(ConnectRoute) })
        Spacer(Modifier.padding(bottom = 32.dp))
    }
}

@Composable
private fun ServerCard(actions: AppActions, server: SavedServer, onChanged: () -> Unit, onSwitch: () -> Unit) {
    val scope = rememberCoroutineScope()
    var open by remember(server.url) { mutableStateOf(server.isCurrent) }
    // First time: the address signed in with goes where it belongs.
    val unset = server.local == null && server.vpn == null && server.remote == null
    var local by remember(server) { mutableStateOf(server.local ?: server.url.takeIf { unset && looksLocal(it) }.orEmpty()) }
    var vpn by remember(server) { mutableStateOf(server.vpn.orEmpty()) }
    var remote by remember(server) { mutableStateOf(server.remote ?: server.url.takeIf { unset && !looksLocal(it) }.orEmpty()) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)).animateContentSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().pressable({ open = !open }), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (server.primary) Text("PRIMARY", style = MaterialTheme.typography.labelSmall, color = contentOn(accentColor()), modifier = Modifier.background(accentColor(), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Text(server.accounts.joinToString { it.userName }.ifEmpty { server.url }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                server.inUse()?.let { Text("$it  ·  ${server.active}", style = MaterialTheme.typography.labelMedium, color = accentColor()) }
            }
            Text(if (open) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            AddressField(actions, server.url, "Local address", "On your home network, e.g. 192.168.1.20:8096", local) { local = it }
            AddressField(actions, server.url, "VPN address", "Through your VPN, e.g. 100.64.0.7:8096", vpn) { vpn = it }
            AddressField(actions, server.url, "Remote address", "From anywhere, e.g. https://jellyfin.example.com", remote) { remote = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            val state = actions.container.saveServerAddresses(server.url, local, vpn, remote)
                            message = if (state == SessionState.VALID) "Saved" else "Saved, but no address answers right now"
                            saving = false
                            onChanged()
                        }
                    },
                ) { Text(if (saving) "Saving…" else "Save") }
                if (!server.primary) TextButton(onClick = { scope.launch { actions.server.setPrimaryServer(server.url); onChanged() } }) { Text("Make primary") }
                if (!server.isCurrent) TextButton(onClick = onSwitch) { Text("Switch to") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** An address with its own Test connection and result. */
@Composable
private fun AddressField(actions: AppActions, serverUrl: String, label: String, hint: String, value: String, onChange: (String) -> Unit) {
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
                    scope.launch { result = actions.server.testAddress(value, serverUrl); testing = false }
                },
            ) { Text(if (testing) "Testing…" else "Test connection") }
            result?.let { r ->
                Text(r.summary(), color = if (r.ok) Color(0xFF4CC38A) else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }
    }
}
