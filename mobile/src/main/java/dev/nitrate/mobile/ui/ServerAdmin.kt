package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nitrate.core.admin.ActivityEntry
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.ServerTask
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.admin.TaskState
import dev.nitrate.core.admin.UserAccount
import dev.nitrate.core.admin.UserPermissions
import dev.nitrate.core.model.formatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AdminTab(val label: String) { OVERVIEW("Overview"), TASKS("Tasks"), ACTIVITY("Activity"), DEVICES("Devices"), USERS("Users") }

/** Keeps a server list fresh while shown: loaded now, then every [everyMs]. */
@Composable
private fun <T> polled(everyMs: Long, key: Any? = null, load: suspend () -> T): Result<T>? =
    produceState<Result<T>?>(null, key) {
        while (true) {
            value = runCatching { load() }
            delay(everyMs)
        }
    }.value

/** Admin tools for the whole server. */
@Composable
fun ServerAdminScreen(actions: AppActions) {
    var tab by rememberSaveable { mutableStateOf(AdminTab.OVERVIEW) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String) = scope.launch { snack.showSnackbar(message) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column {
                    Text("Server", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(actions.server.serverUrl.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = ScreenPadding) {
                AdminTab.entries.forEach { t -> Tab(tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
            }
            when (tab) {
                AdminTab.OVERVIEW -> Overview(actions, ::notify) { tab = it }
                AdminTab.TASKS -> TasksPane(actions, ::notify)
                AdminTab.ACTIVITY -> ActivityPane(actions)
                AdminTab.DEVICES -> DevicesPane(actions, ::notify)
                AdminTab.USERS -> UsersPane(actions, ::notify)
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun Overview(actions: AppActions, notify: (String) -> Unit, go: (AdminTab) -> Unit) {
    val scope = rememberCoroutineScope()
    val sessions = polled(5_000) { actions.server.sessions() }?.getOrNull().orEmpty()
    val tasks = polled(3_000) { actions.server.tasks() }?.getOrNull().orEmpty()
    val playing = sessions.filter { it.nowPlaying != null }
    LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PillButton("Scan all libraries", Icons.Default.Refresh, primary = true, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch { runCatching { actions.server.scanAllLibraries() }.onSuccess { notify("Library scan started") }.onFailure { notify("Couldn't start scan: ${it.message}") } }
            })
        }
        item {
            Card(onClick = { go(AdminTab.DEVICES) }) {
                Text("${playing.size} playing · ${sessions.size} connected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                playing.take(3).forEach { s -> Text("${s.userName} · ${s.nowPlaying?.let { it.episodeLabel ?: it.name }} on ${s.deviceName}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
        }
        val running = tasks.filter { it.state != TaskState.IDLE }
        val failed = tasks.filter { it.lastResult == "Failed" }
        item {
            Card(onClick = { go(AdminTab.TASKS) }) {
                Text(if (running.isEmpty()) "No tasks running" else "${running.size} task(s) running", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                running.forEach { t -> TaskProgress(t) }
                if (failed.isNotEmpty()) Text("Last run failed: " + failed.joinToString { it.name }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TaskProgress(t: ServerTask) {
    Column {
        Text(t.name + (t.progress?.let { " · ${it.toInt()}%" } ?: ""), style = MaterialTheme.typography.bodySmall)
        val p = t.progress
        if (p != null) LinearProgressIndicator(progress = { (p / 100).toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun TasksPane(actions: AppActions, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val result = polled(2_000, tick) { actions.server.tasks() }
    val tasks = result?.getOrNull()
    if (tasks == null) {
        if (result?.isFailure == true) ErrorMessage("Couldn't load tasks: ${result.exceptionOrNull()?.message}") else Loading()
        return
    }
    LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tasks.groupBy { it.category ?: "Other" }.forEach { (category, list) ->
            item(key = "c-$category") { Text(category, style = MaterialTheme.typography.titleSmall, color = accentColor(), modifier = Modifier.padding(top = 8.dp)) }
            items(list, key = { it.id }) { t ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    t.state == TaskState.RUNNING -> "Running" + (t.progress?.let { " · ${it.toInt()}%" } ?: "")
                                    t.state == TaskState.CANCELLING -> "Stopping…"
                                    t.lastResult != null -> "Last run: ${t.lastResult}" + (t.lastRunEnded?.let { " · ${it.take(16).replace('T', ' ')}" } ?: "")
                                    else -> "Never run"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (t.lastResult == "Failed" && t.state == TaskState.IDLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (t.state == TaskState.IDLE) {
                            TextButton(onClick = { scope.launch { runCatching { actions.server.startTask(t.id) }.onFailure { notify("Couldn't start: ${it.message}") }; tick++ } }) { Text("Run") }
                        } else {
                            TextButton(onClick = { scope.launch { runCatching { actions.server.stopTask(t.id) }.onFailure { notify("Couldn't stop: ${it.message}") }; tick++ } }) { Text("Stop") }
                        }
                    }
                    if (t.state == TaskState.RUNNING) TaskProgress(t)
                    t.lastError?.takeIf { t.lastResult == "Failed" }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable
private fun ActivityPane(actions: AppActions) {
    val result = polled(10_000) { actions.server.activity(100) }
    val list = result?.getOrNull()
    if (list == null) {
        if (result?.isFailure == true) ErrorMessage("Couldn't load activity: ${result.exceptionOrNull()?.message}") else Loading()
        return
    }
    LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(list, key = { it.id }) { e: ActivityEntry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(
                    when (e.severity) {
                        "Error", "Critical" -> MaterialTheme.colorScheme.error
                        "Warning" -> Color(0xFFE8A33D)
                        else -> accentColor()
                    },
                ))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(e.name, style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(e.date.take(16).replace('T', ' '), e.overview).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DevicesPane(actions: AppActions, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val result = polled(4_000, tick) { actions.server.sessions() }
    val list = result?.getOrNull()
    var messaging by remember { mutableStateOf<ServerSession?>(null) }
    if (list == null) {
        if (result?.isFailure == true) ErrorMessage("Couldn't load devices: ${result.exceptionOrNull()?.message}") else Loading()
        return
    }
    LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { s ->
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val np = s.nowPlaying
                    if (np != null) {
                        Box(Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            actions.server.cardImage(np, CardShape.POSTER, 200)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = if (np != null) 12.dp else 0.dp)) {
                        Text("${s.userName ?: "Someone"} · ${s.deviceName ?: s.client}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(s.client, s.remoteAddress).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (np != null) {
                            Text(
                                (if (s.isPaused) "Paused: " else "Playing: ") + (np.episodeLabel?.let { "${np.displayTitle} $it" } ?: np.name) +
                                    (s.positionMs?.let { " · ${formatClock(it)}" } ?: "") + (s.playMethod?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = accentColor(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.supportsRemoteControl) TextButton(onClick = { messaging = s }) { Text("Message") }
                    if (s.nowPlaying != null && s.supportsMediaControl) {
                        val cmd = if (s.isPaused) SessionCommand.UNPAUSE else SessionCommand.PAUSE
                        TextButton(onClick = { scope.launch { runCatching { actions.server.sessionCommand(s.id, cmd) }.onFailure { notify("${it.message}") }; tick++ } }) { Text(cmd.label) }
                        TextButton(onClick = { scope.launch { runCatching { actions.server.sessionCommand(s.id, SessionCommand.STOP) }.onFailure { notify("${it.message}") }; tick++ } }) { Text("Stop") }
                    }
                }
            }
        }
    }
    messaging?.let { s ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { messaging = null },
            title = { Text("Message ${s.deviceName ?: s.userName}") },
            text = { OutlinedTextField(text, { text = it }, placeholder = { Text("Dinner's ready") }) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    messaging = null
                    scope.launch { runCatching { actions.server.sendMessage(s.id, "Message", text) }.onSuccess { notify("Sent") }.onFailure { notify("Couldn't send: ${it.message}") } }
                }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { messaging = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UsersPane(actions: AppActions, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val result = produceState<Result<List<UserAccount>>?>(null, tick) { value = runCatching { actions.server.users() } }.value
    var editing by remember { mutableStateOf<UserAccount?>(null) }
    var adding by remember { mutableStateOf(false) }
    val list = result?.getOrNull()
    if (list == null) {
        if (result?.isFailure == true) ErrorMessage("Couldn't load users: ${result.exceptionOrNull()?.message}") else Loading()
        return
    }
    LazyColumn(contentPadding = PaddingValues(ScreenPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(list, key = { it.id }) { u ->
            Card(onClick = { editing = u }) {
                Text(u.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        "Administrator".takeIf { u.permissions.isAdministrator },
                        "Disabled".takeIf { u.permissions.isDisabled },
                        "No password".takeIf { !u.hasPassword },
                        "No downloads".takeIf { !u.permissions.enableContentDownloading },
                        "Home network only".takeIf { !u.permissions.enableRemoteAccess },
                        u.lastActivity?.let { "Last seen ${it.take(10)}" } ?: u.lastLogin?.let { "Last sign-in ${it.take(10)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            PillButton("Add user", Icons.Default.Add, primary = false, modifier = Modifier.fillMaxWidth(), onClick = { adding = true })
        }
    }
    editing?.let { u ->
        UserDialog(actions, u, isSelf = u.id == actions.server.userId, onDone = { message -> editing = null; message?.let(notify); tick++ })
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var pw by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add user") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    adding = false
                    scope.launch { runCatching { actions.server.createUser(name.trim(), pw) }.onSuccess { notify("Added ${it.name}"); tick++ }.onFailure { notify("Couldn't add: ${it.message}") } }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

private val PERMISSIONS: List<Triple<String, (UserPermissions) -> Boolean, (UserPermissions, Boolean) -> UserPermissions>> = listOf(
    Triple("Administrator", { p -> p.isAdministrator }, { p, v -> p.copy(isAdministrator = v) }),
    Triple("Disabled", { p -> p.isDisabled }, { p, v -> p.copy(isDisabled = v) }),
    Triple("Hidden from sign-in screens", { p -> p.isHidden }, { p, v -> p.copy(isHidden = v) }),
    Triple("Access from outside home", { p -> p.enableRemoteAccess }, { p, v -> p.copy(enableRemoteAccess = v) }),
    Triple("Can play media", { p -> p.enableMediaPlayback }, { p, v -> p.copy(enableMediaPlayback = v) }),
    Triple("Video transcoding", { p -> p.enableVideoTranscoding }, { p, v -> p.copy(enableVideoTranscoding = v) }),
    Triple("Audio transcoding", { p -> p.enableAudioTranscoding }, { p, v -> p.copy(enableAudioTranscoding = v) }),
    Triple("Remuxing", { p -> p.enableRemuxing }, { p, v -> p.copy(enableRemuxing = v) }),
    Triple("Downloads", { p -> p.enableContentDownloading }, { p, v -> p.copy(enableContentDownloading = v) }),
    Triple("Delete media", { p -> p.enableContentDeletion }, { p, v -> p.copy(enableContentDeletion = v) }),
    Triple("Live TV", { p -> p.enableLiveTvAccess }, { p, v -> p.copy(enableLiveTvAccess = v) }),
    Triple("Manage collections", { p -> p.enableCollectionManagement }, { p, v -> p.copy(enableCollectionManagement = v) }),
    Triple("Manage subtitles", { p -> p.enableSubtitleManagement }, { p, v -> p.copy(enableSubtitleManagement = v) }),
    Triple("Control other users' sessions", { p -> p.enableRemoteControlOfOtherUsers }, { p, v -> p.copy(enableRemoteControlOfOtherUsers = v) }),
    Triple("All libraries", { p -> p.enableAllFolders }, { p, v -> p.copy(enableAllFolders = v) }),
)

@Composable
private fun UserDialog(actions: AppActions, user: UserAccount, isSelf: Boolean, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var perms by remember { mutableStateOf(user.permissions) }
    var password by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(user.name) },
        text = {
            LazyColumn {
                items(PERMISSIONS, key = { it.first }) { (label, get, set) ->
                    // Your own admin and enabled switches stay put, so you can't lock yourself out.
                    val locked = isSelf && (label == "Administrator" || label == "Disabled")
                    Row(Modifier.fillMaxWidth().clickable(enabled = !locked) { perms = set(perms, !get(perms)) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f), color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Switch(checked = get(perms), onCheckedChange = null, enabled = !locked)
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(password, { password = it }, label = { Text("New password") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(enabled = password.isNotEmpty(), onClick = {
                            scope.launch { runCatching { actions.server.setPassword(user.id, password) }.onSuccess { onDone("Password changed") }.onFailure { onDone("Couldn't change password: ${it.message}") } }
                        }) { Text("Set") }
                    }
                }
                if (!isSelf) item { TextButton(onClick = { confirmDelete = true }) { Text("Delete user", color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { runCatching { actions.server.setPermissions(user.id, perms) }.onSuccess { onDone("Saved") }.onFailure { onDone("Couldn't save: ${it.message}") } }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${user.name}?") },
        text = { Text("Their account, watch history and settings are removed from the server.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { runCatching { actions.server.deleteUser(user.id) }.onSuccess { onDone("Deleted ${user.name}") }.onFailure { onDone("Couldn't delete: ${it.message}") } }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}
