package dev.nitrate.tv.ui

import androidx.compose.foundation.layout.fillMaxWidth
import dev.nitrate.android.sendPlaybackReport
import dev.nitrate.android.playbackReport
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberCoroutineScope
import dev.nitrate.tv.ui.admin.ServerAdminScreen
import dev.nitrate.tv.ui.admin.ManageItemScreen
import dev.nitrate.tv.ui.pages.rememberCustomPages
import dev.nitrate.tv.ui.pages.PagesScreen
import dev.nitrate.tv.ui.pages.DisplaySettingsScreen
import dev.nitrate.tv.ui.pages.CustomPageScreen
import dev.nitrate.tv.ui.theme.NitrateTheme
import dev.nitrate.tv.ui.components.ProvideDisplayPrefs
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.SessionState
import dev.nitrate.tv.ui.home.OfflineScreen
import dev.nitrate.tv.ui.connect.ConnectScreen
import dev.nitrate.tv.ui.details.DetailsScreen
import androidx.navigation.NavController
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.ItemKind
import dev.nitrate.tv.ui.home.HomeScreen
import dev.nitrate.tv.ui.settings.AudioChannelTestScreen
import dev.nitrate.tv.ui.settings.PluginsScreen
import dev.nitrate.tv.plugins.core.PlaybackGateDialog
import dev.nitrate.tv.plugins.core.PluginContext
import dev.nitrate.tv.plugins.writing.TV_PLUGIN_UI
import dev.nitrate.tv.plugins.core.PlayerPanel
import dev.nitrate.tv.plugins.core.ScreenPlugin
import dev.nitrate.tv.plugins.core.rememberPlaybackGate
import androidx.compose.runtime.collectAsState
import dev.nitrate.tv.ui.settings.PlaybackSettingsScreen
import dev.nitrate.tv.ui.settings.PlaybackTestScreen
import dev.nitrate.tv.ui.library.LibraryScreen
import dev.nitrate.tv.ui.playlist.PlaylistScreen
import dev.nitrate.tv.ui.login.LoginScreen
import dev.nitrate.tv.ui.player.PlayerScreen
import kotlinx.serialization.Serializable

/** Settings → the hub: the settings in groups, the search box, and signing out (easy mode asks first). */
@Composable
internal fun TvSettingsHub(container: AppContainer, nav: androidx.navigation.NavHostController, scope: kotlinx.coroutines.CoroutineScope) {
    val account by container.account.collectAsState()
    val companion by container.companion.collectAsState()
    // Admins: a Companion update waiting for a server restart shows at the very top.
    val companionUpdates by container.companionUpdates.collectAsState()
    androidx.compose.runtime.LaunchedEffect(account?.isAdmin, companion) { container.refreshCompanionUpdates() }
    val enabledIds by container.plugins.enabledIds.collectAsState(emptySet())
    val enabledPlugins = TV_PLUGIN_UI.filter { it.pluginId in enabledIds }
    // Watch partners: the server's Companion plugin has them and the plugin is on.
    val partnersOn = companion?.features?.contains(dev.nitrate.core.partners.WatchPartners.FEATURE) == true &&
        dev.nitrate.core.partners.WatchPartnersPlugin.ID in enabledIds
        val easy = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.easyMode
        var askSignOut by remember { mutableStateOf(false) }
        val signOut: () -> Unit = {
            scope.launch {
                container.signOut()
                val next: Any = if (container.server.savedAccounts().isNotEmpty()) ProfilesRoute else ConnectRoute
                nav.navigate(next) { popUpTo(0) { inclusive = true } }
            }
        }
        var reportMessage by remember { mutableStateOf<String?>(null) }
        // Only after Nitrate closed on an error: what went wrong (kept on this TV), to save in the server's logs.
        var crashKept by remember { mutableStateOf(container.lastCrash() != null) }
        dev.nitrate.tv.ui.settings.SettingsHubScreen(
            listOfNotNull(
                companionUpdates?.takeIf { it.restartNeeded }?.let { u ->
                    dev.nitrate.tv.ui.settings.SettingsSection("Server update waiting", listOf(
                        dev.nitrate.tv.ui.settings.SettingsLink("Nitrate Companion ${u.waiting}", u.summary + " Open to restart the server.") { nav.navigate(UpdatesRoute) },
                    ))
                },
                dev.nitrate.tv.ui.settings.SettingsSection(null, listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Picture & sound", "Sound, speakers, audio sync, frame rate; Advanced for formats and fixes") { nav.navigate(SettingsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Subtitles", "Size, colour, background and styled subtitles") { nav.navigate(SubtitleSettingsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Display", "Home and menu, colours, screensaver, what's shown about titles") { nav.navigate(DisplaySettingsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("While watching", "Before it plays, scenes in the credits, recaps, spoilers, audio description, theme music, \"Still watching?\"") { nav.navigate(WhileWatchingRoute) },
                )),
                dev.nitrate.tv.ui.settings.SettingsSection("Account & servers", listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Switch user", "Other users and servers, profile PINs") { nav.navigate(ProfilesRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Servers", "Local, VPN and remote addresses; add, switch and choose the primary") { nav.navigate(ServerAddressesRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Server-side settings", "Languages, subtitles and permissions set on your Jellyfin server, not on this TV") { nav.navigate(ServerSettingsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Display profile", "Which profile this TV uses; new, rename, import a radial menu") { nav.navigate(DisplayProfilesRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Device backup", "This TV's settings on your server; restore another device's") { nav.navigate(DeviceBackupRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Set up this device again", "Copy another device's settings, your speakers and the menu, a step at a time") { container.setup.rerun() },
                    dev.nitrate.tv.ui.settings.SettingsLink("Sign out", container.server.serverUrl) { if (easy) askSignOut = true else signOut() },
                )),
                dev.nitrate.tv.ui.settings.SettingsSection("Your watching", listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Comic catalogs", "Read from an OPDS server (Komga, Kavita, Calibre…) or CLU; add \"Comics\" to the menu in Display → Menu stops") { nav.navigate(CatalogsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Your year", "What you've watched and listened to: top shows, genres and artists") { nav.navigate(StatsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Packing for a trip", "Plan what your phones, tablets and laptop download before you go") { nav.navigate(TripsRoute) },
                ) + (if (companion?.features?.contains(dev.nitrate.core.diary.Diary.FEATURE) == true) listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Watch diary", "Every film and episode you finish, with your ratings and notes") { nav.navigate(DiaryRoute) },
                ) else emptyList()) + (if (partnersOn) listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Watch groups", "What you watch with others, invites, and keeping your account private") { nav.navigate(WatchPartnersRoute) },
                ) else emptyList()) +
                    enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> dev.nitrate.tv.ui.settings.SettingsLink(p.screenLabel) { nav.navigate(PluginScreenRoute(p.pluginId)) } }),
                dev.nitrate.tv.ui.settings.SettingsSection("Tools", listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Checking how things play", "A guide to these tools: which to use, how, and what to do about washed-out HDR, missing surround or stutter") { nav.navigate(GuideRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Advanced playback settings", "Formats: which this TV plays and which the server converts (Automatic, This device, Server converts), Dolby Vision profile 7, tunneling: what the tools' fixes change") { nav.navigate(AdvancedPlaybackRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Playback test", "Plays short samples to find what this TV really plays") { nav.navigate(PlaybackTestRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Audio channel test", "A voice from each speaker in turn") { nav.navigate(AudioChannelTestRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("TV & receiver check", "Clips that should make the TV show HDR or Dolby Vision and the receiver its sound format: say what you see") { nav.navigate(TvCheckRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Library playback overview", "Every film and episode you can see, and how each plays on this TV: as it is, converted, and why") { nav.navigate(PlaybackOverviewRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Server speed check", "Is slowness the network, the server or this TV? Times it and shows you") { nav.navigate(SpeedCheckRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Query analyzer", "Every request Nitrate makes to your server: which page asked, what it cost, what was shared, and hints for making pages lighter") { nav.navigate(QueryAnalyzerRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Send device report", "What this TV plays and how it's set, saved in your server's logs for troubleshooting") {
                        reportMessage = "Sending…"
                        scope.launch {
                            reportMessage = container.sendPlaybackReport().fold(
                                { name -> "Device report saved on your server as $name (Jellyfin Dashboard → Logs)" },
                                { e -> "Couldn't send the device report: ${e.message ?: e::class.simpleName}" },
                            )
                        }
                    },
                ) + (if (crashKept) listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Nitrate closed unexpectedly", "Save what went wrong last time in your server's logs, for a bug report") {
                        reportMessage = "Sending…"
                        scope.launch {
                            reportMessage = container.sendLastCrash().fold(
                                { name -> container.clearLastCrash(); crashKept = false; "What went wrong is saved on your server as $name (Jellyfin Dashboard → Logs)" },
                                { e -> "Couldn't send it: ${e.message ?: e::class.simpleName}" },
                            )
                        }
                    },
                ) else emptyList()) + (if (account?.isAdmin == true) listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Household devices", "How every device in the house plays the library, its tests and what its users saw at their TVs") { nav.navigate(HouseholdDevicesRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Library health", "Missing artwork and details, duplicates, missing episodes, what this TV always converts") { nav.navigate(LibraryHealthRoute) },
                ) + (if (companion?.features?.contains(dev.nitrate.core.admin.ImageGuide.FEATURE) == true) listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Library images overview", "Every title's pictures: how many of each kind, the space they take, and which are missing, small or the wrong shape") { nav.navigate(ImagesOverviewRoute) },
                ) else emptyList()) + listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Manage server", "Libraries, tasks, activity, devices and users") { nav.navigate(ServerAdminRoute) },
                ) else emptyList())),
                dev.nitrate.tv.ui.settings.SettingsSection("About", listOf(
                    dev.nitrate.tv.ui.settings.SettingsLink("Updates", "Nitrate ${container.updater.versionName}; check now or when Nitrate opens" + if (companionUpdates != null) "; the server's Nitrate Companion" else "") { nav.navigate(UpdatesRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Plugins", "Built-in extras, on or off") { nav.navigate(PluginsRoute) },
                    dev.nitrate.tv.ui.settings.SettingsLink("Open-source licences", "FFmpeg, Media3, Kotlin and the rest Nitrate is built with") { nav.navigate(LicensesRoute) },
                )),
            ),
            message = reportMessage,
            admin = account?.isAdmin == true,
            onFound = { place ->
                when (place) {
                    dev.nitrate.core.settings.SettingsPlace.SOUND -> nav.navigate(SettingsRoute)
                    dev.nitrate.core.settings.SettingsPlace.SOUND_ADVANCED -> nav.navigate(AdvancedPlaybackRoute)
                    dev.nitrate.core.settings.SettingsPlace.SUBTITLES -> nav.navigate(SubtitleSettingsRoute)
                    dev.nitrate.core.settings.SettingsPlace.DISPLAY, dev.nitrate.core.settings.SettingsPlace.DISPLAY_LAYOUT -> nav.navigate(DisplaySettingsRoute)
                    dev.nitrate.core.settings.SettingsPlace.DISPLAY_SHOWN -> nav.navigate(ShownSettingsRoute)
                    dev.nitrate.core.settings.SettingsPlace.DISPLAY_EFFECTS -> nav.navigate(EffectsSettingsRoute)
                    dev.nitrate.core.settings.SettingsPlace.DISPLAY_PAGES -> nav.navigate(PageSetupRoute)
                    dev.nitrate.core.settings.SettingsPlace.MENU_STOPS -> nav.navigate(MenuStopsRoute)
                    dev.nitrate.core.settings.SettingsPlace.DISPLAY_PROFILE -> nav.navigate(DisplayProfilesRoute)
                    dev.nitrate.core.settings.SettingsPlace.WATCHING -> nav.navigate(WhileWatchingRoute)
                    dev.nitrate.core.settings.SettingsPlace.BEFORE_IT_PLAYS -> nav.navigate(PrerollSettingsRoute)
                    dev.nitrate.core.settings.SettingsPlace.PROFILES -> nav.navigate(ProfilesRoute)
                    dev.nitrate.core.settings.SettingsPlace.SERVERS -> nav.navigate(ServerAddressesRoute)
                    dev.nitrate.core.settings.SettingsPlace.SERVER_SETTINGS -> nav.navigate(ServerSettingsRoute)
                    dev.nitrate.core.settings.SettingsPlace.BACKUP -> nav.navigate(DeviceBackupRoute)
                    dev.nitrate.core.settings.SettingsPlace.SETUP -> container.setup.rerun()
                    dev.nitrate.core.settings.SettingsPlace.STATS -> nav.navigate(StatsRoute)
                    dev.nitrate.core.settings.SettingsPlace.TRIPS -> nav.navigate(TripsRoute)
                    dev.nitrate.core.settings.SettingsPlace.DIARY -> nav.navigate(DiaryRoute)
                    dev.nitrate.core.settings.SettingsPlace.PARTNERS -> nav.navigate(WatchPartnersRoute)
                    dev.nitrate.core.settings.SettingsPlace.CATALOGS -> nav.navigate(CatalogsRoute)
                    dev.nitrate.core.settings.SettingsPlace.GUIDE -> nav.navigate(GuideRoute)
                    dev.nitrate.core.settings.SettingsPlace.PLAYBACK_TEST -> nav.navigate(PlaybackTestRoute)
                    dev.nitrate.core.settings.SettingsPlace.AUDIO_TEST -> nav.navigate(AudioChannelTestRoute)
                    dev.nitrate.core.settings.SettingsPlace.TV_CHECK -> nav.navigate(TvCheckRoute)
                    dev.nitrate.core.settings.SettingsPlace.OVERVIEW -> nav.navigate(PlaybackOverviewRoute)
                    dev.nitrate.core.settings.SettingsPlace.SPEED_CHECK -> nav.navigate(SpeedCheckRoute)
                    dev.nitrate.core.settings.SettingsPlace.QUERY_ANALYZER -> nav.navigate(QueryAnalyzerRoute)
                    dev.nitrate.core.settings.SettingsPlace.HOUSEHOLD -> nav.navigate(HouseholdDevicesRoute)
                    dev.nitrate.core.settings.SettingsPlace.HEALTH -> nav.navigate(LibraryHealthRoute)
                    dev.nitrate.core.settings.SettingsPlace.IMAGES -> nav.navigate(ImagesOverviewRoute)
                    dev.nitrate.core.settings.SettingsPlace.MANAGE_SERVER -> nav.navigate(ServerAdminRoute)
                    dev.nitrate.core.settings.SettingsPlace.UPDATES -> nav.navigate(UpdatesRoute)
                    dev.nitrate.core.settings.SettingsPlace.PLUGINS -> nav.navigate(PluginsRoute)
                    dev.nitrate.core.settings.SettingsPlace.LICENCES -> nav.navigate(LicensesRoute)
                    // Phone only.
                    dev.nitrate.core.settings.SettingsPlace.NOTIFICATIONS, dev.nitrate.core.settings.SettingsPlace.REMOTE -> Unit
                }
            },
        )
        // Easy mode asks first.
        if (askSignOut) androidx.compose.ui.window.Dialog(onDismissRequest = { askSignOut = false }) {
            val yes = remember { androidx.compose.ui.focus.FocusRequester() }
            Column(
                Modifier.width(520.dp).background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(28.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                Text("Sign out?", style = MaterialTheme.typography.headlineSmall)
                Text("You'll need your password to sign in again.", style = MaterialTheme.typography.bodyLarge)
                androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(onClick = { askSignOut = false }, modifier = Modifier.focusRequester(yes)) { Text("Cancel") }
                    androidx.tv.material3.OutlinedButton(onClick = { askSignOut = false; signOut() }) { Text("Sign out") }
                }
            }
            LaunchedEffect(Unit) { runCatching { yes.requestFocus() } }
        }
}
