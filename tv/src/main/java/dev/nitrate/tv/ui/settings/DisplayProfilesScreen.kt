package dev.nitrate.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.backup.Difference
import dev.nitrate.core.pages.DisplayProfile
import dev.nitrate.core.pages.DisplayProfiles
import dev.nitrate.core.pages.MenuStops
import dev.nitrate.tv.ui.components.TvTextField
import dev.nitrate.tv.ui.home.MenuActions
import dev.nitrate.tv.ui.home.MenuCatalog
import dev.nitrate.tv.ui.home.menuCatalog
import dev.nitrate.tv.ui.lists.TvDialog
import dev.nitrate.tv.ui.pages.rememberCustomPages
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What importing [from]'s radial menu would change, spot by spot. */
internal fun dialDifferences(current: List<String?>, from: List<String?>, catalog: MenuCatalog): List<Difference> =
    (0 until maxOf(current.size, from.size)).mapNotNull { i ->
        val a = current.getOrNull(i)?.let { catalog.entries[it]?.label } ?: "Empty"
        val b = from.getOrNull(i)?.let { catalog.entries[it]?.label } ?: "Empty"
        if (a == b) null else Difference("Radial menu", spotName(i, dial = true), a, b)
    }

/**
 * Settings → Display profile: which profile this TV uses, starting a new one (from a built-in
 * one, which always asks for a name so the built-ins never change, or from one of yours), renaming
 * or deleting one, and taking just the radial menu from another profile.
 */
@Composable
fun DisplayProfilesScreen(container: AppContainer) {
    val user = container.server.userId ?: return
    val store = container.displaySettings
    val scope = rememberCoroutineScope()
    val mine by store.profiles(user).collectAsState(emptyList())
    val active by store.activeProfile(user).collectAsState(store.deviceKind.builtInId)
    var naming by remember { mutableStateOf<Pair<String, String>?>(null) } // (start from, suggested name)
    var renaming by remember { mutableStateOf<DisplayProfile?>(null) }
    var importing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    val activeName = (DisplayProfiles.BUILT_IN + mine).firstOrNull { it.id == active }?.name ?: "?"
    val onBuiltIn = DisplayProfiles.isBuiltIn(active)

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 58.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column(Modifier.padding(bottom = 12.dp).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Display profile", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "How rows, pages, menus and item details look. Each device uses one profile; your profiles follow you to every device, " +
                        "so a new TV can pick up this one. Custom pages, series languages and spoiler hiding are the same everywhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "This TV uses: $activeName" + if (onBuiltIn) " (built-in; your first change starts a profile of your own)" else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (mine.isNotEmpty()) item { Section("Your profiles") }
        items(mine, key = { "mine:" + it.id }) { p ->
            ListItem(
                selected = p.id == active,
                onClick = { scope.launch { store.useProfile(user, p.id); status = "This TV now uses ${p.name}" } },
                headlineContent = { Text(p.name) },
                supportingContent = { Text(if (p.id == active) "Used on this TV" else "Use on this TV") },
                modifier = Modifier.widthIn(max = 760.dp).then(if (p.id == mine.first().id) Modifier.focusRequester(first) else Modifier),
            )
        }
        if (!onBuiltIn) {
            item { Section("This profile") }
            item { Action("Rename “$activeName”") { renaming = mine.firstOrNull { it.id == active } } }
            item { Action("Import the radial menu from another profile", "Just the dial's stops; shows the differences first") { importing = true } }
            item {
                Action("Delete “$activeName”", "From every device; they go back to their built-in profile") { deleting = true }
            }
        }
        item { Section("Start a new profile") }
        items(DisplayProfiles.BUILT_IN + mine, key = { "from:" + it.id }) { p ->
            val builtIn = DisplayProfiles.isBuiltIn(p.id)
            ListItem(
                selected = false,
                onClick = { naming = p.id to if (builtIn) "" else "${p.name} copy" },
                headlineContent = { Text(if (builtIn) "From the built-in ${p.name} profile" else "As a copy of ${p.name}") },
                supportingContent = { Text(if (builtIn) "Nitrate's defaults for a ${p.name.lowercase()}; you'll name it" else "You'll name the copy") },
                modifier = Modifier.widthIn(max = 760.dp).then(if (mine.isEmpty() && p.id == DisplayProfiles.BUILT_IN.first().id) Modifier.focusRequester(first) else Modifier),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    naming?.let { (from, suggested) ->
        NameDialog("Name the new profile", suggested, onDismiss = { naming = null }) { name ->
            scope.launch { store.createProfile(user, name, from); naming = null; status = "This TV now uses $name" }
        }
    }
    renaming?.let { p ->
        NameDialog("Rename profile", p.name, onDismiss = { renaming = null }) { name ->
            scope.launch { store.renameProfile(user, p.id, name); renaming = null }
        }
    }
    if (deleting) {
        val confirm = remember { FocusRequester() }
        TvDialog({ deleting = false }) {
            Text("Delete “$activeName” everywhere?", style = MaterialTheme.typography.headlineSmall)
            Text("Every device using it goes back to its built-in profile. This can't be undone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ListItem(selected = false, onClick = { deleting = false }, headlineContent = { Text("Keep it") }, modifier = Modifier.focusRequester(confirm))
            ListItem(selected = false, onClick = { val n = activeName; scope.launch { store.deleteProfile(user, active); deleting = false; status = "Deleted $n" } }, headlineContent = { Text("Delete") })
        }
        LaunchedEffect(Unit) { runCatching { confirm.requestFocus() } }
    }
    if (importing) {
        DialImport(container, user, mine.filter { it.id != active }, onDone = { msg -> importing = false; msg?.let { status = it } })
    }
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
private fun Action(label: String, detail: String? = null, onClick: () -> Unit) = ListItem(
    selected = false,
    onClick = onClick,
    headlineContent = { Text(label) },
    supportingContent = detail?.let { { Text(it) } },
    modifier = Modifier.widthIn(max = 760.dp),
)

/** Asks for a profile name; a name is required. */
@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    TvDialog(onDismiss) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        TvTextField(name, { name = it }, placeholder = "e.g. Living room, Bedroom TV", modifier = Modifier.focusRequester(field))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Save") }
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
}

/** Picks a profile, shows how its radial menu differs from this one's, and copies just that. */
@Composable
private fun DialImport(container: AppContainer, user: String, others: List<DisplayProfile>, onDone: (String?) -> Unit) {
    val store = container.displaySettings
    val scope = rememberCoroutineScope()
    val libraries = dev.nitrate.tv.ui.home.rememberLibraries(container)
    val pages = rememberCustomPages(container)
    val catalog = remember(libraries, pages) { menuCatalog(libraries, pages, MenuActions(), current = "") }
    var chosen by remember { mutableStateOf<Pair<DisplayProfile, List<String?>>?>(null) }
    val first = remember { FocusRequester() }
    val current by store.prefs(user).collectAsState(null)

    val pick = chosen
    val now = current
    if (pick != null && now != null) {
        val mineDial = MenuStops.arrange(now.tvDialStops, catalog.entries.keys, catalog.defaults)
        DifferencesPanel(
            intro = "The radial menu from ${pick.first.name}. Nothing else in this profile changes.",
            differences = dialDifferences(mineDial, pick.second, catalog),
            onApply = {
                scope.launch {
                    store.savePrefs(user, store.prefs(user).first().copy(tvDialStops = pick.second))
                    onDone("Radial menu imported from ${pick.first.name}")
                }
            },
            onBack = { chosen = null },
        )
        return
    }
    TvDialog({ onDone(null) }) {
        Text("Import the radial menu from…", style = MaterialTheme.typography.headlineSmall)
        if (others.isEmpty()) Text("You have no other profiles yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        others.forEachIndexed { i, p ->
            ListItem(
                selected = false,
                onClick = {
                    scope.launch {
                        val theirs = store.prefsOf(user, p.id).first().tvDialStops
                        chosen = p to MenuStops.arrange(theirs, catalog.entries.keys, catalog.defaults)
                    }
                },
                headlineContent = { Text(p.name) },
                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            )
        }
        ListItem(selected = false, onClick = { onDone(null) }, headlineContent = { Text("Cancel") }, modifier = if (others.isEmpty()) Modifier.focusRequester(first) else Modifier)
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
