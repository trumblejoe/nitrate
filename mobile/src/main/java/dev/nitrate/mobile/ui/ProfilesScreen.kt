package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.nitrate.android.storage.ProfileStore
import dev.nitrate.core.model.SavedAccount
import dev.nitrate.core.model.SessionState
import kotlinx.coroutines.launch

/**
 * "Who's watching?": every account signed in on this device, across servers. Pick one to switch
 * (a locked profile asks for its PIN), add another, or manage PINs and remove accounts.
 */
@Composable
fun ProfilesScreen(
    actions: AppActions,
    onOpened: () -> Unit,
    onSignInAgain: (SavedAccount) -> Unit,
    onAdd: () -> Unit,
) {
    val c = actions.container
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val accounts by produceState(emptyList<SavedAccount>(), refresh) { value = c.server.savedAccounts() }
    val locked by c.profiles.locked.collectAsState(emptySet())
    val askAtStart by c.profiles.askAtStart.collectAsState(false)
    var editing by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf<SavedAccount?>(null) }
    var managing by remember { mutableStateOf<SavedAccount?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun open(account: SavedAccount) {
        message = null
        scope.launch {
            when (c.switchAccount(account.key)) {
                SessionState.VALID -> onOpened()
                SessionState.EXPIRED -> onSignInAgain(account)
                SessionState.UNREACHABLE -> message = "Can't reach ${account.serverName} right now."
                SessionState.NONE -> refresh++
            }
        }
    }

    TintedBackdrop(null) {
        LazyVerticalGrid(
            GridCells.Adaptive(120.dp),
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Spacer(Modifier.height(40.dp))
                    Text(if (editing) "Manage profiles" else "Who's watching?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            items(accounts, key = { it.key }) { account ->
                Avatar(
                    account,
                    locked = account.key in locked,
                    current = account.key == c.server.currentAccountKey,
                    editing = editing,
                    showServer = accounts.map { it.serverUrl }.distinct().size > 1,
                    onClick = {
                        when {
                            editing -> managing = account
                            account.key in locked -> unlocking = account
                            else -> open(account)
                        }
                    },
                )
            }
            if (!editing) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable(onAdd)) {
                        Box(Modifier.size(96.dp).clip(CircleShape).border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape), Alignment.Center) {
                            Icon(Icons.Filled.Add, null, Modifier.size(40.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Add account", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editing) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Ask who's watching", style = MaterialTheme.typography.bodyLarge)
                                Text("Every time Nitrate opens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(askAtStart, { scope.launch { c.profiles.setAskAtStart(it) } })
                        }
                    }
                    PillButton(if (editing) "Done" else "Manage profiles", null, primary = editing, onClick = { editing = !editing })
                }
            }
        }
    }

    unlocking?.let { account ->
        PinDialog(
            title = "Enter ${account.userName}'s PIN",
            check = { pin -> c.profiles.check(account.key, pin) },
            onDone = { unlocking = null; open(account) },
            onDismiss = { unlocking = null },
        )
    }
    managing?.let { account ->
        ManageProfile(actions, account, hasPin = account.key in locked, onChanged = { managing = null; refresh++ }, onDismiss = { managing = null })
    }
}

@Composable
private fun Avatar(account: SavedAccount, locked: Boolean, current: Boolean, editing: Boolean, showServer: Boolean, onClick: () -> Unit) {
    val accent = accentColor()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressable(onClick)) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(accent.copy(alpha = 0.35f))
                    .border(if (current) 3.dp else 0.dp, if (current) accent else Color.Transparent, CircleShape),
                Alignment.Center,
            ) {
                Text(account.userName.take(1).uppercase(), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                if (account.imageUrl != null) AsyncImage(account.imageUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (locked || editing) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), Alignment.Center) {
                    Icon(if (editing) Icons.Filled.Edit else Icons.Filled.Lock, null, Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(account.userName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        if (showServer) Text(account.serverName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Set, change or remove a profile's PIN, or remove the account from this device. */
@Composable
private fun ManageProfile(actions: AppActions, account: SavedAccount, hasPin: Boolean, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val c = actions.container
    val scope = rememberCoroutineScope()
    // Changing or removing a PIN needs the current one first.
    var step by remember { mutableStateOf(if (hasPin) "verify" else "menu") }
    var then by remember { mutableStateOf("") }
    when (step) {
        "verify" -> PinDialog("Enter ${account.userName}'s PIN", check = { c.profiles.check(account.key, it) }, onDone = { step = "menu" }, onDismiss = onDismiss)
        "new" -> PinDialog("New PIN for ${account.userName}", confirm = true, check = { true }, onDone = { pin ->
            scope.launch { c.profiles.setPin(account.key, pin); onChanged() }
        }, onDismiss = onDismiss)
        "menu" -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(account.userName) },
            text = {
                Column {
                    Text(account.serverName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { step = "new" }) { Text(if (hasPin) "Change PIN" else "Lock with a PIN") }
                    if (hasPin) TextButton(onClick = { scope.launch { c.profiles.setPin(account.key, null); onChanged() } }) { Text("Remove PIN") }
                    TextButton(onClick = { then = "remove"; step = "confirm" }) { Text("Remove from this device", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        "confirm" -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Remove ${account.userName}?") },
            text = { Text("You'll need the password to add this account again. Its downloads stay on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        if (account.key == c.server.currentAccountKey) c.signOut() else c.forgetAccount(account.key)
                        onChanged()
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

/**
 * Asks for a 4–8 digit PIN. [check] says whether it's right (for unlocking); with [confirm],
 * it's a new PIN typed twice.
 */
@Composable
fun PinDialog(title: String, check: suspend (String) -> Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit, confirm: Boolean = false) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (!ProfileStore.isValid(pin)) {
            error = "Use 4 to 8 digits"
            return
        }
        if (confirm) {
            val first = again
            when {
                first == null -> { again = pin; pin = ""; error = null }
                first == pin -> onDone(pin)
                else -> { again = null; pin = ""; error = "Those didn't match. Try again." }
            }
            return
        }
        scope.launch {
            if (check(pin)) onDone(pin) else { error = "Wrong PIN"; pin = "" }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirm && again != null) "Type it again" else title) },
        text = {
            Column {
                OutlinedTextField(
                    pin,
                    { pin = it.filter(Char::isDigit).take(8); error = null },
                    singleLine = true,
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.width(200.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = ::submit) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
