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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nitrate.android.viewmodel.RowsState
import dev.nitrate.android.viewmodel.RowsViewModel
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.pages.PageKeys
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
private fun rememberCustomPages(actions: AppActions): List<CustomPage> {
    val user = actions.server.userId
    val flow = remember(user) { user?.let { actions.container.displaySettings.customPages(it) } ?: flowOf(emptyList()) }
    return flow.collectAsState(emptyList()).value
}

/** Custom pages pinned to Home, as a row of chips under the hero. */
@Composable
fun PinnedPages(actions: AppActions) {
    val pages = rememberCustomPages(actions).filter { it.pinned }
    if (pages.isEmpty()) return
    LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pages, key = { it.id }) { page ->
            Text(
                page.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.10f))
                    .border(1.dp, accentColor().copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .pressable({ actions.nav.navigate(CustomPageRoute(page.id)) }).padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

/** "Your pages" in the Library tab: open, create, rename, pin or delete custom pages. */
@Composable
fun CustomPagesSection(actions: AppActions) {
    val pages = rememberCustomPages(actions)
    var creating by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Your pages", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pages, key = { it.id }) { page -> PageTile(page.title, onClick = { actions.nav.navigate(CustomPageRoute(page.id)) }) }
            item(key = "new") { PageTile("New page", onClick = { creating = true }, isNew = true) }
        }
    }
    if (creating) {
        PageNameDialog("New page", "", onDismiss = { creating = false }) { title ->
            creating = false
            val user = actions.server.userId ?: return@PageNameDialog
            val page = CustomPage(newId(), title)
            actions.scope.launch {
                val store = actions.container.displaySettings
                store.saveCustomPages(user, pages + page)
                actions.nav.navigate(CustomPageRoute(page.id, editNow = true))
            }
        }
    }
}

@Composable
private fun PageTile(title: String, onClick: () -> Unit, isNew: Boolean = false) {
    val accent = accentColor()
    Box(
        Modifier.width(170.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(16.dp))
            .background(if (isNew) Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.06f))) else Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f))))
            .border(1.dp, if (isNew) Color.White.copy(alpha = 0.2f) else accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .pressable(onClick).padding(14.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (isNew) Icon(Icons.Default.Add, null, modifier = Modifier.align(Alignment.TopStart))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PageNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, placeholder = { Text("e.g. Friday night") }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A page made of the user's own rows. */
@Composable
fun CustomPageScreen(actions: AppActions, pageId: String, editNow: Boolean) {
    val pages = rememberCustomPages(actions)
    val page = pages.firstOrNull { it.id == pageId }
    val c = actions.container
    val vm = viewModel(key = "custom-$pageId") { RowsViewModel(PageKeys.custom(pageId), { emptyList() }, c.displaySettings, c.server, c.serverChanges) }
    val state by vm.state.collectAsStateWithLifecycle()
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(editNow) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val user = actions.server.userId
    val scope = rememberCoroutineScope()
    fun savePages(list: List<CustomPage>) {
        user ?: return
        scope.launch { c.displaySettings.saveCustomPages(user, list) }
    }

    val title = page?.title ?: ""
    if (editing) PageEditor(title, vm, actions, libraryId = null, onDone = { editing = false })

    TintedBackdrop(null, strength = 0.28f) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Column {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                        IconButton(onClick = { actions.nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, "Edit rows") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                                DropdownMenuItem(
                                    text = { Text("Show on Home") },
                                    trailingIcon = { Switch(checked = page?.pinned == true, onCheckedChange = null) },
                                    onClick = { menu = false; savePages(pages.map { if (it.id == pageId) it.copy(pinned = !it.pinned) else it }) },
                                )
                                DropdownMenuItem(text = { Text("Delete page", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = true })
                            }
                        }
                    }
                }
            }
            when (val s = state) {
                RowsState.Loading -> item { Loading(Modifier.padding(top = 80.dp)) }
                is RowsState.Error -> item { ErrorMessage(s.message, vm::refresh) }
                is RowsState.Loaded -> {
                    if (s.rows.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(ScreenPadding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("This page is empty. Add rows from filters, genres, collections or playlists.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PillButton("Add rows", Icons.Default.Add, primary = true, onClick = { editing = true })
                        }
                    }
                    items(s.rows, key = { it.key }) { row -> CinematicRow(row, overrides[row.key] ?: row.defaultDisplay, actions) }
                }
            }
        }
    }

    if (renaming && page != null) PageNameDialog("Rename page", page.title, onDismiss = { renaming = false }) { name ->
        renaming = false
        savePages(pages.map { if (it.id == pageId) it.copy(title = name) else it })
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete “$title”?") },
        text = { Text("Removes the page and its rows. Nothing on your server is affected.") },
        confirmButton = {
            TextButton(onClick = {
                deleting = false
                user?.let { scope.launch { c.displaySettings.deleteCustomPage(it, pageId) } }
                actions.nav.popBackStack()
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}
