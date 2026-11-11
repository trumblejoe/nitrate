package dev.nitrate.tv.ui.login

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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.ui.components.TvTextField
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

@Composable
fun LoginScreen(
    repository: JellyfinRepository,
    serverUrl: String,
    serverName: String,
    onLoggedIn: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun signIn() {
        if (username.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { repository.login(serverUrl, username.trim(), password) }
                .onSuccess { onLoggedIn() }
                .onFailure {
                    error = if (it is InvalidStatusException && it.status == 401) {
                        "Wrong username or password"
                    } else {
                        it.message ?: "Sign-in failed"
                    }
                }
            busy = false
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Sign in", style = MaterialTheme.typography.displaySmall)
            Text(serverName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            )
            Button(onClick = ::signIn, enabled = !busy) {
                Text(if (busy) "Signing in…" else "Sign in")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
