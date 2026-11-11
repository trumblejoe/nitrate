package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.nitrate.core.model.DiscoveredServer
import dev.nitrate.core.model.InvalidCredentialsException
import dev.nitrate.core.model.ServerUser
import dev.nitrate.core.model.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Centred, width-limited column so forms read well on tablets too. */
@Composable
private fun FormPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
fun ConnectScreen(actions: AppActions, onConnected: (serverUrl: String, serverName: String) -> Unit) {
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(true) }
    var searchRun by remember { mutableStateOf(0) }
    val found = remember { mutableStateListOf<DiscoveredServer>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(searchRun) {
        searching = true
        runCatching { actions.server.discoverServers().collect { s -> if (found.none { it.id == s.id }) found += s } }
        searching = false
    }

    fun connect(input: String) {
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { actions.server.findServer(input.trim()) }
                .onSuccess { onConnected(it.address, it.name) }
                .onFailure { error = it.message ?: "Could not reach server" }
            busy = false
        }
    }

    FormPage {
        Text("Nitrate", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Text("Connect to your Jellyfin server", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                found.isNotEmpty() -> "Found on your network"
                searching -> "Looking for servers on your network…"
                else -> "No servers found on your network"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        found.forEach { server ->
            Card(Modifier.fillMaxWidth().clickable { connect(server.address) }) {
                ListItem(headlineContent = { Text(server.name) }, supportingContent = { Text(server.address) })
            }
        }
        if (!searching && found.isEmpty()) OutlinedButton(onClick = { searchRun++ }) { Text("Search again") }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Server address") },
            placeholder = { Text("192.168.1.20:8096 or jellyfin.example.com") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect(address) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { connect(address) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Connecting…" else "Connect")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun LoginScreen(actions: AppActions, serverUrl: String, serverName: String, onLoggedIn: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quickConnect by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val users by produceState(emptyList<ServerUser>(), serverUrl) { value = actions.server.publicUsers(serverUrl) }
    val quickConnectAvailable by produceState(false, serverUrl) { value = actions.server.quickConnectEnabled(serverUrl) }

    fun signIn() {
        if (username.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { actions.server.login(serverUrl, username.trim(), password) }
                .onSuccess { onLoggedIn() }
                .onFailure { error = if (it is InvalidCredentialsException) "Wrong username or password" else it.message ?: "Sign-in failed" }
            busy = false
        }
    }

    if (quickConnect) {
        QuickConnect(actions, serverUrl, onLoggedIn) { quickConnect = false }
        return
    }

    FormPage {
        Text("Sign in", style = MaterialTheme.typography.headlineMedium)
        Text(serverName, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (users.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(users, key = { it.id }) { user ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            username = user.name
                            if (!user.hasPassword) signIn()
                        },
                    ) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                            if (user.imageUrl != null) {
                                AsyncImage(user.imageUrl, user.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Text(user.name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        Text(user.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { signIn() }))
        Button(onClick = ::signIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Signing in…" else "Sign in") }
        if (quickConnectAvailable) {
            OutlinedButton(onClick = { quickConnect = true }, modifier = Modifier.fillMaxWidth()) { Text("Use Quick Connect") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun QuickConnect(actions: AppActions, serverUrl: String, onLoggedIn: () -> Unit, onCancel: () -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        code = null
        runCatching {
            val request = actions.server.startQuickConnect(serverUrl)
            code = request.code
            while (!actions.server.quickConnectApproved(request.secret)) delay(3_000)
            actions.server.finishQuickConnect(serverUrl, request.secret)
        }.onSuccess { onLoggedIn() }.onFailure { error = it.message ?: "Quick Connect failed" }
    }
    FormPage {
        Text("Quick Connect", style = MaterialTheme.typography.headlineMedium)
        Text(
            "On a device where you're signed in, open Jellyfin, go to your profile, choose Quick Connect and enter:",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(code?.chunked(3)?.joinToString(" ") ?: "…", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if (error == null) Text("Waiting for approval…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (error != null) Button(onClick = { attempt++ }) { Text("Try again") }
            TextButton(onClick = onCancel) { Text("Use password instead") }
        }
    }
}

/** Shown when the server can't be reached; writing still works. Re-checks every 30 s. */
@Composable
fun OfflineScreen(actions: AppActions, onOnline: () -> Unit, onSignedOut: () -> Unit) {
    var checking by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun check() {
        checking = true
        when (actions.server.checkSession()) {
            SessionState.VALID -> onOnline()
            SessionState.EXPIRED, SessionState.NONE -> onSignedOut()
            SessionState.UNREACHABLE -> Unit
        }
        checking = false
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            check()
        }
    }
    if (writing) {
        WritingScreen(actions, onBack = { writing = false })
        return
    }
    FormPage {
        Text("Working offline", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Can't reach ${actions.server.serverUrl ?: "your server"}. Movies and shows come back when it does. " +
                "Your writing saves on this device and backs up once the server is back.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { writing = true }, modifier = Modifier.fillMaxWidth()) { Text("Writing") }
        OutlinedButton(onClick = { scope.launch { check() } }, enabled = !checking, modifier = Modifier.fillMaxWidth()) {
            Text(if (checking) "Checking…" else "Try again")
        }
    }
}
