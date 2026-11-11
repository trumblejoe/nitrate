package dev.nitrate.tv.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.android.AppContainer
import dev.nitrate.android.storage.ProfileStore
import dev.nitrate.core.model.SavedAccount
import dev.nitrate.core.model.SessionState
import kotlinx.coroutines.launch

/**
 * "Who's watching?": every account signed in on this TV, across servers. Pick one to switch (a
 * locked profile asks for its PIN), add another, or manage PINs and remove accounts.
 */
@Composable
fun ProfilesScreen(
    container: AppContainer,
    onOpened: () -> Unit,
    onSignInAgain: (SavedAccount) -> Unit,
    onAdd: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val accounts by produceState<List<SavedAccount>?>(null, refresh) { value = container.server.savedAccounts() }
    val locked by container.profiles.locked.collectAsState(emptySet())
    val askAtStart by container.profiles.askAtStart.collectAsState(false)
    var editing by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf<SavedAccount?>(null) }
    var managing by remember { mutableStateOf<SavedAccount?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    fun open(account: SavedAccount) {
        message = null
        scope.launch {
            when (container.switchAccount(account.key)) {
                SessionState.VALID -> onOpened()
                SessionState.EXPIRED -> onSignInAgain(account)
                SessionState.UNREACHABLE -> message = "Can't reach ${account.serverName} right now."
                SessionState.NONE -> refresh++
            }
        }
    }

    val list = accounts ?: return
    val manyServers = list.map { it.serverUrl }.distinct().size > 1
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(if (editing) "Manage profiles" else "Who's watching?", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(message ?: if (editing) "Choose a profile to lock it with a PIN or remove it." else " ", color = if (message != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(36.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(32.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
            items(list, key = { it.key }) { account ->
                Tile(
                    name = account.userName,
                    detail = account.serverName.takeIf { manyServers },
                    imageUrl = account.imageUrl,
                    badge = when {
                        editing -> "Edit"
                        account.key in locked -> "PIN"
                        else -> null
                    },
                    modifier = if (account == list.firstOrNull { it.key == container.server.currentAccountKey } ?: list.first()) Modifier.focusRequester(first) else Modifier,
                    onClick = {
                        when {
                            editing -> managing = account
                            account.key in locked -> unlocking = account
                            else -> open(account)
                        }
                    },
                )
            }
            if (!editing) item { Tile(name = "Add account", detail = null, imageUrl = null, badge = null, initial = "+", onClick = onAdd) }
        }
        Spacer(Modifier.height(40.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Manage profiles") }
            if (editing) {
                ListItem(
                    selected = false,
                    onClick = { scope.launch { container.profiles.setAskAtStart(!askAtStart) } },
                    headlineContent = { Text("Ask who's watching every time Nitrate opens") },
                    trailingContent = { Switch(checked = askAtStart, onCheckedChange = null) },
                    modifier = Modifier.width(560.dp),
                )
            }
        }
    }
    LaunchedEffect(list.size) { runCatching { first.requestFocus() } }

    unlocking?.let { account ->
        PinPad("Enter ${account.userName}'s PIN", check = { container.profiles.check(account.key, it) }, onDone = { unlocking = null; open(account) }, onDismiss = { unlocking = null })
    }
    managing?.let { account ->
        ManageProfile(container, account, hasPin = account.key in locked, onChanged = { managing = null; refresh++ }, onDismiss = { managing = null })
    }
}

@Composable
private fun Tile(name: String, detail: String?, imageUrl: String?, badge: String?, onClick: () -> Unit, modifier: Modifier = Modifier, initial: String = name.take(1).uppercase()) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(140.dp)) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(CircleShape),
            // The focus ring follows the round avatar.
            border = CardDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary), shape = CircleShape)),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            modifier = modifier.size(120.dp),
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)), Alignment.Center) {
                Text(initial, style = MaterialTheme.typography.displaySmall)
                if (imageUrl != null) AsyncImage(imageUrl, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        badge?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(top = 4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun ManageProfile(container: AppContainer, account: SavedAccount, hasPin: Boolean, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // Changing or removing a PIN needs the current one first.
    var step by remember { mutableStateOf(if (hasPin) "verify" else "menu") }
    when (step) {
        "verify" -> PinPad("Enter ${account.userName}'s PIN", check = { container.profiles.check(account.key, it) }, onDone = { step = "menu" }, onDismiss = onDismiss)
        "new" -> PinPad("New PIN for ${account.userName}", confirm = true, check = { true }, onDone = { pin ->
            scope.launch { container.profiles.setPin(account.key, pin); onChanged() }
        }, onDismiss = onDismiss)
        else -> Dialog(onDismissRequest = onDismiss) {
            val focus = remember { FocusRequester() }
            Column(
                Modifier.width(560.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(account.userName, style = MaterialTheme.typography.headlineSmall)
                Text(account.serverName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (step == "confirm") {
                    Text("Remove ${account.userName} from this TV? You'll need the password to add it again.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            scope.launch {
                                if (account.key == container.server.currentAccountKey) container.signOut() else container.forgetAccount(account.key)
                                onChanged()
                            }
                        }, modifier = Modifier.focusRequester(focus)) { Text("Remove") }
                        OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                    }
                } else {
                    ListItem(selected = false, onClick = { step = "new" }, headlineContent = { Text(if (hasPin) "Change PIN" else "Lock with a PIN") }, modifier = Modifier.focusRequester(focus))
                    if (hasPin) ListItem(selected = false, onClick = { scope.launch { container.profiles.setPin(account.key, null); onChanged() } }, headlineContent = { Text("Remove PIN") })
                    ListItem(selected = false, onClick = { step = "confirm" }, headlineContent = { Text("Remove from this TV") })
                }
            }
            LaunchedEffect(step) { runCatching { focus.requestFocus() } }
        }
    }
}

/**
 * A number pad for the remote: 4–8 digits. [check] says whether a PIN is right (unlocking); with
 * [confirm], it's a new PIN entered twice.
 */
@Composable
fun PinPad(title: String, check: suspend (String) -> Boolean, onDone: (String) -> Unit, onDismiss: () -> Unit, confirm: Boolean = false) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val five = remember { FocusRequester() }
    fun submit() {
        if (!ProfileStore.isValid(pin)) {
            error = "Use 4 to 8 digits"
            return
        }
        if (confirm) {
            val earlier = again
            when {
                earlier == null -> { again = pin; pin = ""; error = null }
                earlier == pin -> onDone(pin)
                else -> { again = null; pin = ""; error = "Those didn't match. Try again." }
            }
            return
        }
        scope.launch { if (check(pin)) onDone(pin) else { error = "Wrong PIN"; pin = "" } }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (confirm && again != null) "Enter it again" else title, style = MaterialTheme.typography.headlineSmall)
            Text(if (pin.isEmpty()) " " else "●".repeat(pin.length), style = MaterialTheme.typography.headlineMedium)
            Text(error ?: " ", color = MaterialTheme.colorScheme.error)
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { key ->
                        val onClick: () -> Unit = when (key) {
                            "⌫" -> { { pin = pin.dropLast(1) } }
                            "OK" -> ::submit
                            else -> { { if (pin.length < 8) pin += key; error = null } }
                        }
                        Button(onClick = onClick, modifier = Modifier.width(88.dp).then(if (key == "5") Modifier.focusRequester(five) else Modifier)) {
                            Text(key, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { runCatching { five.requestFocus() } }
        }
    }
}
