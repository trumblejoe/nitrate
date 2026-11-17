package dev.nitrate.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.InvalidCredentialsException
import dev.nitrate.core.model.QuickConnectRequest
import dev.nitrate.core.model.ServerAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Find the server, then sign in with a password or Quick Connect. */
@Composable
internal fun ConnectScreen(container: dev.nitrate.android.AppContainer, onSignedIn: () -> Unit) {
    val server = container.server
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<ServerAddress?>(null) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf<QuickConnectRequest?>(null) }

    // Quick Connect: wait for approval from another signed-in device.
    LaunchedEffect(quick) {
        val request = quick ?: return@LaunchedEffect
        val url = found?.address ?: return@LaunchedEffect
        while (true) {
            delay(3_000)
            if (runCatching { server.quickConnectApproved(request.secret) }.getOrDefault(false)) {
                runCatching { server.finishQuickConnect(url, request.secret) }
                    .onSuccess { onSignedIn() }
                    .onFailure { error = it.message; quick = null }
                return@LaunchedEffect
            }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Nitrate", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Accent, modifier = Modifier.padding(top = 48.dp, bottom = 12.dp))
        val field = Modifier.fillMaxWidth().widthIn(max = 480.dp)
        val current = found
        if (current == null) {
            Text("Your Jellyfin server", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                address, { address = it; error = null },
                label = { Text("Address") },
                placeholder = { Text("192.168.1.5 or jellyfin.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = field,
            )
            Button(
                enabled = address.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { server.findServer(address.trim()) }
                            .onSuccess { found = it }
                            .onFailure { error = "Couldn't reach a Jellyfin server there: ${it.message}" }
                        busy = false
                    }
                },
                modifier = field,
            ) { Text(if (busy) "Looking…" else "Connect") }
        } else {
            Text(current.name, style = MaterialTheme.typography.titleLarge)
            Text(current.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val request = quick
            if (request != null) {
                Text("On a device that's signed in, open Quick Connect and enter:", modifier = field)
                Text(request.code, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = Accent)
                TextButton(onClick = { quick = null }) { Text("Cancel") }
            } else {
                OutlinedTextField(user, { user = it; error = null }, label = { Text("User name") }, singleLine = true, modifier = field)
                OutlinedTextField(
                    password, { password = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = field,
                )
                Button(
                    enabled = user.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { server.login(current.address, user.trim(), password) }
                                .onSuccess { onSignedIn() }
                                .onFailure { error = if (it is InvalidCredentialsException) "Wrong user name or password." else "Couldn't sign in: ${it.message}" }
                            busy = false
                        }
                    },
                    modifier = field,
                ) { Text(if (busy) "Signing in…" else "Sign in") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                if (!server.quickConnectEnabled(current.address)) error("Quick Connect is off on this server")
                                server.startQuickConnect(current.address)
                            }.onSuccess { quick = it }.onFailure { error = it.message }
                        }
                    },
                    modifier = field,
                ) { Text("Use Quick Connect") }
                TextButton(onClick = { found = null; error = null }) { Text("Another server") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = field) }
    }
}
