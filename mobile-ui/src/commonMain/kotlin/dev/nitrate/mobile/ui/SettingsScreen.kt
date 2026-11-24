package dev.nitrate.mobile.ui

import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.CardTitles
import dev.nitrate.core.pages.AccentMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nitrate.core.episodes.EpisodeOrder.Scope
import dev.nitrate.core.episodes.PreviousEpisodePlugin
import dev.nitrate.core.episodes.PreviousEpisodeSettings
import dev.nitrate.core.playback.CapabilityRules
import dev.nitrate.core.playback.EffectiveCapability
import dev.nitrate.core.playback.MaxResolution
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.capabilityLabel
import dev.nitrate.core.playback.formatBitrate
import dev.nitrate.core.playback.SoundMode
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.nitrate.core.playback.soundMode
import dev.nitrate.core.playback.withSoundMode
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import dev.nitrate.core.plugins.settings
import dev.nitrate.core.plugins.update
import dev.nitrate.core.writing.WritingPlugin
import dev.nitrate.core.writing.WritingSettings
import kotlinx.coroutines.launch

/** Settings subpages, opened from the Settings tab ([SettingsPageRoute.page]). */
enum class SettingsPage(val title: String) {
    SOUND("Picture & sound"),
    ADVANCED("Picture & sound: Advanced"),
    SUBTITLES("Subtitles"),
    DISPLAY("Display"),
    SHOWN("What's shown"),
    WATCHING("While watching"),
    NOTIFICATIONS("Notifications"),
    BACKUP("Device backup"),
    REMOTE("Control a device"),
    UPDATES("Updates"),
    PLUGINS("Plugins"),
}

/**
 * The Settings tab: the settings in groups (the settings themselves, account and servers, your
 * watching, tools, about), each opening its own page.
 */
@Composable
fun SettingsScreen(actions: AppActions, onSignedOut: () -> Unit, modifier: Modifier = Modifier, initialQuery: String = "") {
    val c = actions.container
    val scope = rememberCoroutineScope()
    val account by c.account.collectAsState()
    val companion by c.companion.collectAsState()
    // Admins: a Companion update waiting for a server restart shows at the very top.
    val companionUpdates by c.companionUpdates.collectAsState()
    LaunchedEffect(account?.isAdmin, companion) { c.refreshCompanionUpdates() }
    val easy = LocalDisplayPrefs.current.easyMode
    // Two-pane Settings (wide windows) shows the page beside the list instead.
    val pane = LocalSettingsPane.current
    fun open(page: SettingsPage) = pane?.invoke(page) ?: actions.nav.navigate(SettingsPageRoute(page.name))
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialQuery) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        // Any setting by name or the words around it (core SettingsSearch); the groups below while nothing's typed.
        item {
            androidx.compose.material3.OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Search settings") },
                singleLine = true,
                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({
                    androidx.compose.material3.IconButton(onClick = { query = "" }) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Close, "Clear search") }
                }) else null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (query.isNotBlank()) {
            val found = dev.nitrate.core.settings.SettingsSearch.find(query, tv = false, admin = account?.isAdmin == true)
            if (found.isEmpty()) item { Text("No setting matches \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            found.forEach { e -> item(key = "found/${e.place}/${e.title}") { Link(e.title, e.place.path) { openPlace(actions, e.place, ::open) } } }
            return@LazyColumn
        }
        companionUpdates?.takeIf { it.restartNeeded }?.let { u ->
            item { Link("Server update waiting: Nitrate Companion ${u.waiting}", u.summary + " Tap to restart the server.") { open(SettingsPage.UPDATES) } }
        }
        item { Link("Picture & sound", "Sound, speakers, audio sync; Advanced for formats and fixes") { open(SettingsPage.SOUND) } }
        item { Link("Subtitles", "Size, colour, background and styled subtitles") { open(SettingsPage.SUBTITLES) } }
        item { Link("Display", "Look, colours, display profile, what's shown about titles") { open(SettingsPage.DISPLAY) } }
        item { Link("While watching", "Scenes in the credits, recaps, spoilers, audio description, theme music, \"Still watching?\"") { open(SettingsPage.WATCHING) } }
        item { Link("Notifications", "New episodes and new seasons") { open(SettingsPage.NOTIFICATIONS) } }

        group("Account & servers")
        item {
            ListItem(
                headlineContent = { Text(actions.server.serverUrl ?: "Not signed in") },
                supportingContent = { Text("Server") },
                trailingContent = {
                    // Easy mode asks first.
                    var asking by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { if (easy) asking = true else scope.launch { c.signOut(); onSignedOut() } }) { Text("Sign out") }
                    if (asking) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { asking = false },
                        title = { Text("Sign out?") },
                        text = { Text("You'll need your password to sign in again.") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { asking = false; scope.launch { c.signOut(); onSignedOut() } }) { Text("Sign out") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { asking = false }) { Text("Cancel") } },
                    )
                },
            )
        }
        item { Link("Switch account", "Other users and servers, PINs for profiles") { actions.nav.navigate(ProfilesRoute) } }
        item { Link("Servers", "Local, VPN and remote addresses; add, switch and choose the primary") { actions.nav.navigate(ServerAddressesRoute) } }
        item { ServerSettingsRow(actions) }
        item { QuickConnectRow(actions) }
        item { Link("Device backup", "This phone's settings on your server; restore another device's") { open(SettingsPage.BACKUP) } }
        item { Link("Set up this device again", "Copy another device's settings and your speakers, a step at a time") { c.setup.rerun() } }

        group("Your watching")
        item { Link("Your year", "Films, episodes and songs, top shows, genres and artists") { actions.nav.navigate(StatsRoute) } }
        if (companion?.features?.contains(dev.nitrate.core.diary.Diary.FEATURE) == true) item {
            Link("Watch diary", "Every film and episode you finish, with your ratings and notes; export for Letterboxd") { actions.nav.navigate(DiaryRoute) }
        }
        item { if (partnersAvailable(actions)) Link("Watch groups", "What you watch with others, invites, and keeping your account private") { actions.nav.navigate(WatchPartnersRoute) } }
        item { Link("Control a device", "Play, pause, seek and change volume on your TV or another app") { open(SettingsPage.REMOTE) } }

        group("Tools")
        item { Link("Checking how things play", "A guide to these tools: which to use, how, and what to do about washed-out HDR, missing surround or stutter") { actions.nav.navigate(GuideRoute) } }
        item { Link("Advanced playback settings", "The formats this phone plays (Always, Never), learned failures, direct play, resolution limit: what the tools' fixes change") { open(SettingsPage.ADVANCED) } }
        item { Link("Library playback overview", "Every film and episode you can see, and how each plays on this phone: as it is, converted, and why") { actions.nav.navigate(PlaybackOverviewRoute) } }
        item { SpeedCheckRow(actions) }
        item { Link("Query analyzer", "Every request Nitrate makes to your server: which page asked, what it cost, what was shared, and hints for making pages lighter") { actions.nav.navigate(QueryAnalyzerRoute) } }
        item { DeviceReportRow(actions) }
        if (account?.isAdmin == true) {
            item { Link("Household devices", "How every device in the house plays the library, its tests and what its users saw at their TVs") { actions.nav.navigate(HouseholdDevicesRoute) } }
            item { Link("Library health", "Missing artwork and details, duplicates, missing episodes, what this phone always converts") { actions.nav.navigate(LibraryHealthRoute) } }
            item { Link("Manage server", "Libraries, tasks, activity, devices and users") { actions.nav.navigate(ServerAdminRoute) } }
        }

        group("About")
        item { Link("Updates", "Nitrate ${c.updater.versionName}; check now or when Nitrate opens") { open(SettingsPage.UPDATES) } }
        item { Link("Plugins", "Built-in extras, on or off for this phone") { open(SettingsPage.PLUGINS) } }
        item { Link("Open-source licences", "FFmpeg, Media3, Kotlin and the rest Nitrate is built with") { actions.nav.navigate(LicensesRoute) } }
    }
}

/** Opens the page a found setting lives on. */
internal fun openPlace(actions: AppActions, place: dev.nitrate.core.settings.SettingsPlace, page: (SettingsPage) -> Unit) {
    when (place) {
        dev.nitrate.core.settings.SettingsPlace.SOUND -> page(SettingsPage.SOUND)
        dev.nitrate.core.settings.SettingsPlace.SOUND_ADVANCED -> page(SettingsPage.ADVANCED)
        dev.nitrate.core.settings.SettingsPlace.SUBTITLES -> page(SettingsPage.SUBTITLES)
        dev.nitrate.core.settings.SettingsPlace.DISPLAY_SHOWN -> page(SettingsPage.SHOWN)
        dev.nitrate.core.settings.SettingsPlace.WATCHING, dev.nitrate.core.settings.SettingsPlace.BEFORE_IT_PLAYS -> page(SettingsPage.WATCHING)
        dev.nitrate.core.settings.SettingsPlace.NOTIFICATIONS -> page(SettingsPage.NOTIFICATIONS)
        dev.nitrate.core.settings.SettingsPlace.BACKUP -> page(SettingsPage.BACKUP)
        dev.nitrate.core.settings.SettingsPlace.SETUP -> actions.container.setup.rerun()
        dev.nitrate.core.settings.SettingsPlace.REMOTE -> page(SettingsPage.REMOTE)
        dev.nitrate.core.settings.SettingsPlace.UPDATES -> page(SettingsPage.UPDATES)
        dev.nitrate.core.settings.SettingsPlace.PLUGINS -> page(SettingsPage.PLUGINS)
        dev.nitrate.core.settings.SettingsPlace.PROFILES -> actions.nav.navigate(ProfilesRoute)
        dev.nitrate.core.settings.SettingsPlace.SERVERS -> actions.nav.navigate(ServerAddressesRoute)
        dev.nitrate.core.settings.SettingsPlace.STATS -> actions.nav.navigate(StatsRoute)
        dev.nitrate.core.settings.SettingsPlace.DIARY -> actions.nav.navigate(DiaryRoute)
        dev.nitrate.core.settings.SettingsPlace.PARTNERS -> actions.nav.navigate(WatchPartnersRoute)
        dev.nitrate.core.settings.SettingsPlace.CATALOGS -> actions.nav.navigate(CatalogsRoute)
        dev.nitrate.core.settings.SettingsPlace.GUIDE -> actions.nav.navigate(GuideRoute)
        dev.nitrate.core.settings.SettingsPlace.OVERVIEW -> actions.nav.navigate(PlaybackOverviewRoute)
        dev.nitrate.core.settings.SettingsPlace.QUERY_ANALYZER -> actions.nav.navigate(QueryAnalyzerRoute)
        dev.nitrate.core.settings.SettingsPlace.HOUSEHOLD -> actions.nav.navigate(HouseholdDevicesRoute)
        dev.nitrate.core.settings.SettingsPlace.HEALTH -> actions.nav.navigate(LibraryHealthRoute)
        dev.nitrate.core.settings.SettingsPlace.MANAGE_SERVER -> actions.nav.navigate(ServerAdminRoute)
        dev.nitrate.core.settings.SettingsPlace.LICENCES -> actions.nav.navigate(LicensesRoute)
        // The phone's Display page holds what the TV splits into several.
        else -> page(SettingsPage.DISPLAY)
    }
}
