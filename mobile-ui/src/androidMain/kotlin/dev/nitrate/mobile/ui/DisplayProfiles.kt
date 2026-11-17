package dev.nitrate.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.pages.DisplayProfile
import dev.nitrate.core.pages.DisplayProfiles
import kotlinx.coroutines.launch

/** Settings → Display: which display profile this device uses, and managing profiles. */
@Composable
fun DisplayProfileRow(actions: AppActions) {
    val user = actions.server.userId ?: return
    val store = actions.container.displaySettings
    val mine by store.profiles(user).collectAsState(emptyList())
    val active by store.activeProfile(user).collectAsState(store.deviceKind.builtInId)
    var open by remember { mutableStateOf(false) }
    val name = (DisplayProfiles.BUILT_IN + mine).firstOrNull { it.id == active }?.name ?: "?"
    ListItem(
        modifier = Modifier.clickable { open = true },
        headlineContent = { Text("Display profile: $name") },
        supportingContent = {
            Text(if (DisplayProfiles.isBuiltIn(active)) "Built-in; your first change starts a profile named after this device" else "Rows, pages and details on this device. Tap to switch or start another")
        },
        trailingContent = { Text("›", color = MaterialTheme.colorScheme.primary) },
    )
    if (open) DisplayProfilesDialog(actions, user, mine, active) { open = false }
}

@Composable
private fun DisplayProfilesDialog(actions: AppActions, user: String, mine: List<DisplayProfile>, active: String, onDismiss: () -> Unit) {
    val store = actions.container.displaySettings
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf<Pair<String, String>?>(null) } // (start from, suggested name)
    var renaming by remember { mutableStateOf<DisplayProfile?>(null) }
    var deleting by remember { mutableStateOf<DisplayProfile?>(null) }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${p.name}” everywhere?") },
            text = { Text("Every device using it goes back to its built-in profile. This can't be undone.") },
            confirmButton = { TextButton(onClick = { scope.launch { store.deleteProfile(user, p.id); deleting = null } }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } },
        )
        return
    }
    naming?.let { (from, suggested) ->
        NameDialog("Name the new profile", suggested, onDismiss = { naming = null }) { n ->
            scope.launch { store.createProfile(user, n, from); naming = null; onDismiss() }
        }
        return
    }
    renaming?.let { p ->
        NameDialog("Rename profile", p.name, onDismiss = { renaming = null }) { n -> scope.launch { store.renameProfile(user, p.id, n); renaming = null } }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display profile") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    Text(
                        "Each device uses one profile; your profiles follow you to your other devices. Choosing a built-in one starts a new profile from it, so the built-ins never change.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (mine.isNotEmpty()) item { Label("Your profiles") }
                items(mine, key = { "mine:" + it.id }) { p ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        modifier = Modifier.clickable { scope.launch { store.useProfile(user, p.id); onDismiss() } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { if (p.id == active) Icon(Icons.Default.Check, "Used here", tint = accentColor()) },
                        headlineContent = { Text(p.name) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = p })
                                    DropdownMenuItem(text = { Text("Make a copy") }, onClick = { menu = false; naming = p.id to "${p.name} copy" })
                                    DropdownMenuItem(text = { Text("Delete everywhere") }, onClick = { menu = false; deleting = p })
                                }
                            }
                        },
                    )
                }
                item { Label("Start from a built-in profile") }
                items(DisplayProfiles.BUILT_IN, key = { "builtin:" + it.id }) { p ->
                    ListItem(
                        modifier = Modifier.clickable { naming = p.id to "" },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { if (p.id == active) Icon(Icons.Default.Check, "Used here", tint = accentColor()) },
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("Nitrate's defaults; you'll name your copy") },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = accentColor(), modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("e.g. Phone, Kid's tablet") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
