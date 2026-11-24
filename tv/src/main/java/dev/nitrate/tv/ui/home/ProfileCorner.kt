package dev.nitrate.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.nitrate.core.pages.DisplayProfiles
import kotlinx.coroutines.launch

/** Room the profile corner takes at the top end of every menu page (kept clear by `ThemedPage`). */
internal val CornerRoom: Dp = 124.dp

/** How much of the page's top end the profile corner takes (0 where there's no corner). */
internal val LocalCornerRoom = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** Who's signed in, for the profile corner: their name and their Jellyfin picture, if they have one. */
internal data class CornerAccount(val name: String, val imageUrl: String?) {
    /** What a screen reader says for the avatar. */
    val spoken get() = if (name.isBlank()) "Your account" else "$name, account"
    val initial get() = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** What the corner and its account panel do (from the menu's catalog; null = not offered). */
class CornerActions(
    val onSearch: (() -> Unit)?,
    val onSettings: (() -> Unit)?,
    val onProfiles: (() -> Unit)?,
    val onQuickSwitch: (() -> Unit)?,
    val onSignOut: (() -> Unit)?,
)

@Composable
internal fun rememberCornerAccount(): CornerAccount {
    val container = dev.nitrate.tv.ui.pages.LocalTvContainer.current ?: return CornerAccount("", null)
    val info by container.account.collectAsState()
    val key = container.server.currentAccountKey
    val saved by produceState<dev.nitrate.core.model.SavedAccount?>(null, key) {
        value = runCatching { container.server.savedAccounts() }.getOrNull()?.firstOrNull { it.key == key }
    }
    return CornerAccount(saved?.userName ?: info?.name.orEmpty(), saved?.imageUrl)
}

/**
 * The profile corner, top right of every menu page in every menu style: Search (a magnifier) and the
 * signed-in user's avatar (their picture, else their initial), which opens the account panel. Up from
 * the page's top reaches it ([avatarFocus]); Down or Back go back to the page, and Left from Search
 * or Right from the avatar to the menu when it's that way ([onKey] decides, true when it moved).
 */
@Composable
internal fun ProfileCorner(
    account: CornerAccount,
    onSearch: (() -> Unit)?,
    onAvatar: () -> Unit,
    searchFocus: FocusRequester,
    avatarFocus: FocusRequester,
    onKey: (Key) -> Unit,
    modifier: Modifier = Modifier,
) {
    var onSearchButton by remember { mutableStateOf(false) }
    Row(
        modifier
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                when (e.key) {
                    Key.DirectionDown -> { if (down) onKey(Key.DirectionDown); true }
                    Key.DirectionUp -> true
                    Key.DirectionLeft -> if (onSearchButton || onSearch == null) { if (down) onKey(Key.DirectionLeft); true } else false
                    Key.DirectionRight -> if (!onSearchButton) { if (down) onKey(Key.DirectionRight); true } else false
                    else -> false
                }
            }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onSearch != null) CornerButton(
            onClick = onSearch,
            spoken = "Search",
            modifier = Modifier.focusRequester(searchFocus).onFocusChanged { onSearchButton = it.isFocused },
        ) { tint -> Icon(Icons.Filled.Search, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp)) }
        CornerButton(onClick = onAvatar, spoken = account.spoken, modifier = Modifier.focusRequester(avatarFocus)) { _ ->
            Avatar(account, 46.dp)
        }
    }
}

@Composable
private fun CornerButton(onClick: () -> Unit, spoken: String, modifier: Modifier, content: @Composable (Color) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.45f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        modifier = modifier.size(46.dp).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = spoken },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content(if (focused) Color.Black else Color.White) }
    }
}

/** The user's picture in a circle, their initial underneath (shown while it loads, or without one). */
@Composable
internal fun Avatar(account: CornerAccount, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(account.initial, style = if (size > 50.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, color = Color.Black)
        if (account.imageUrl != null) coil3.compose.AsyncImage(account.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The account panel the avatar opens (a side panel; Back closes it and focus goes back to the
 * avatar, the corner's or the overlay pop-up's): who's signed in, Switch user, Settings, the display profile this TV uses (when the user
 * has profiles of their own to switch between) and Sign out, which easy mode asks about first.
 */
@Composable
internal fun AccountPanel(account: CornerAccount, actions: CornerActions, onChosen: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val container = dev.nitrate.tv.ui.pages.LocalTvContainer.current
    val easy = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.easyMode
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    // Choosing something closes the panel first ([onChosen]: the overlay's pop-up closes with it).
    fun then(action: (() -> Unit)?): () -> Unit = { (onChosen ?: onDismiss)(); action?.invoke() }
    dev.nitrate.tv.ui.lists.TvDialog(onDismiss, width = 480) {
        // Back closes it (taken as a key as well as the window's own Back).
        Column(
            Modifier.onKeyEvent { e ->
                if (e.key != Key.Back) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) onDismiss()
                true
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (asking) {
                Text("Sign out?", style = MaterialTheme.typography.headlineSmall)
                Text("You'll need your password to sign in again.", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(onClick = { asking = false }, modifier = Modifier.focusRequester(first)) { Text("Cancel") }
                    androidx.tv.material3.OutlinedButton(onClick = then(actions.onSignOut)) { Text("Sign out") }
                }
                LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                return@Column
            }
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(account, 64.dp)
                Column {
                    Text(account.name.ifBlank { "Your account" }, style = MaterialTheme.typography.headlineSmall)
                    container?.server?.serverUrl?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            actions.onProfiles?.let { profiles ->
                ListItem(
                    selected = false,
                    onClick = then(profiles),
                    onLongClick = actions.onQuickSwitch?.let { then(it) },
                    headlineContent = { Text("Switch user") },
                    supportingContent = { Text("Other users and servers") },
                    modifier = Modifier.focusRequester(first),
                )
            }
            actions.onSettings?.let { settings ->
                ListItem(
                    selected = false,
                    onClick = then(settings),
                    headlineContent = { Text("Settings") },
                    supportingContent = { Text("Picture and sound, display, your account and more") },
                    modifier = if (actions.onProfiles == null) Modifier.focusRequester(first) else Modifier,
                )
            }
            // The display profile this TV uses: only when there's one of the user's own to switch to.
            val user = container?.server?.userId
            if (container != null && user != null) {
                val store = container.displaySettings
                val mine by remember(user) { store.profiles(user) }.collectAsState(emptyList())
                val active by remember(user) { store.activeProfile(user) }.collectAsState(store.deviceKind.builtInId)
                if (mine.isNotEmpty()) {
                    Text("Display profile", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    (DisplayProfiles.BUILT_IN.filter { it.id == store.deviceKind.builtInId } + mine).forEach { p ->
                        ListItem(
                            selected = p.id == active,
                            onClick = { scope.launch { store.useProfile(user, p.id) } },
                            headlineContent = { Text(p.name) },
                            supportingContent = { Text(if (p.id == active) "Used on this TV" else "Use on this TV") },
                        )
                    }
                }
            }
            actions.onSignOut?.let { signOut ->
                ListItem(
                    selected = false,
                    onClick = if (easy) ({ asking = true }) else then(signOut),
                    headlineContent = { Text("Sign out") },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            LaunchedEffect(Unit) { repeat(10) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; kotlinx.coroutines.delay(32) } }
        }
    }
}
