package dev.nitrate.tv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.MediaServer
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.nitrate.core.model.InvalidCredentialsException
import dev.nitrate.core.model.ServerUser

private const val QUICK_CONNECT_POLL_MS = 3_000L

@Composable
fun LoginScreen(
    repository: MediaServer,
    serverUrl: String,
    serverName: String,
    onLoggedIn: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quickConnect by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val users by produceState(emptyList<ServerUser>(), serverUrl) { value = repository.publicUsers(serverUrl) }
    val quickConnectAvailable by produceState(false, serverUrl) { value = repository.quickConnectEnabled(serverUrl) }

    fun signIn() {
        if (username.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { repository.login(serverUrl, username.trim(), password) }
                .onSuccess { onLoggedIn() }
                .onFailure {
                    error = if (it is InvalidCredentialsException) "Wrong username or password" else it.message ?: "Sign-in failed"
                }
            busy = false
        }
    }

    if (quickConnect) {
        QuickConnectPanel(repository, serverUrl, onLoggedIn, onCancel = { quickConnect = false })
        return
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Sign in", style = MaterialTheme.typography.displaySmall)
            Text(serverName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (quickConnectAvailable) {
                Button(onClick = { quickConnect = true }) { Text("Sign in with Quick Connect") }
            }

            if (users.isNotEmpty()) {
                // Picking a user fills the name and jumps to the password field.
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(users, key = { it.id }) { user ->
                        UserTile(user, user.imageUrl) {
                            username = user.name.orEmpty()
                            if (user.hasPassword) runCatching { passwordFocus.requestFocus() } else signIn()
                        }
                    }
                }
            }

            TvTextField(
                value = username,
                onValueChange = { username = it },
                placeholder = "Username",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            TvTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password",
                isPassword = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { signIn() }),
                modifier = Modifier.focusRequester(passwordFocus),
            )
            Button(onClick = ::signIn, enabled = !busy) { Text(if (busy) "Signing in…" else "Sign in") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun UserTile(user: ServerUser, imageUrl: String?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(CircleShape),
            border = CardDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = CircleShape)),
            modifier = Modifier.size(88.dp),
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                if (imageUrl != null) {
                    AsyncImage(imageUrl, user.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(user.name.orEmpty().take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        Text(user.name.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Shows a code to approve from the web UI or a phone, then signs in once the server reports approval. */
@Composable
private fun QuickConnectPanel(
    repository: MediaServer,
    serverUrl: String,
    onLoggedIn: () -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val cancelFocus = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        error = null
        code = null
        runCatching {
            val request = repository.startQuickConnect(serverUrl)
            code = request.code
            while (!repository.quickConnectApproved(request.secret)) delay(QUICK_CONNECT_POLL_MS)
            repository.finishQuickConnect(serverUrl, request.secret)
        }.onSuccess { onLoggedIn() }
            .onFailure { error = it.message ?: "Quick Connect failed" }
    }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Quick Connect", style = MaterialTheme.typography.displaySmall)
            Text(
                "On a device where you're already signed in, open Jellyfin, go to your profile, choose Quick Connect and enter this code:",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                code?.chunked(3)?.joinToString(" ") ?: "…",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 6.sp,
            )
            if (error == null) Text("Waiting for approval…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error != null) Button(onClick = { attempt++ }) { Text("Try again") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.focusRequester(cancelFocus)) { Text("Use password instead") }
            }
        }
    }
}
