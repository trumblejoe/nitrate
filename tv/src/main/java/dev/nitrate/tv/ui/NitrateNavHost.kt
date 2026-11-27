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

private val catalogJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@Composable
fun NitrateNavHost(container: AppContainer, requests: kotlinx.coroutines.flow.MutableStateFlow<TvRequest?> = kotlinx.coroutines.flow.MutableStateFlow(null)) {
    var start by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(Unit) {
        val state = container.server.restoreSession()
        start = when {
            container.shouldPickProfile(state) -> ProfilesRoute
            state == SessionState.VALID -> { container.onSignedIn(); HomeRoute }
            state == SessionState.UNREACHABLE -> OfflineRoute
            else -> ConnectRoute
        }
    }

    val startRoute = start
    if (startRoute == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), Alignment.Center) {
            Text("Nitrate", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val account by container.account.collectAsState()
    // Every "play this item" goes through plugin guards first; queues (playlists) don't.
    // Resuming after a break: the "Last time" recap first, when one is due.
    // Before a title starts from the top: the user's pre-rolls (verse, scene, intros), when switched on.
    val displayPrefs by androidx.compose.runtime.rememberUpdatedState(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current)
    val context = androidx.compose.ui.platform.LocalContext.current
    // Songs go to the listening player (which carries on while browsing), anything else to the player.
    fun listen(items: List<dev.nitrate.core.model.MediaItem>, start: Int = 0, shuffle: Boolean = false) {
        val listening = container.listening
        if (listening != null && items.isNotEmpty() && items.all { it.kind == dev.nitrate.core.model.ItemKind.AUDIO }) {
            listening.play(items, start, shuffle)
            nav.navigate(NowPlayingRoute) { launchSingleTop = true }
        } else nav.playQueue((if (shuffle) items.drop(start).shuffled() else items.drop(start)).map { it.id })
    }
    val gate = rememberPlaybackGate(container.plugins, container::groupStartMs, container::playTurnedDown) { item, fromStart, startMs ->
        scope.launch {
            // A book opens in the reader, from wherever Play was pressed.
            if (item.kind == dev.nitrate.core.model.ItemKind.BOOK) nav.navigate(ComicRoute(item.id))
            // A book carries on where it was left in the listening player.
            else if (item.kind == dev.nitrate.core.model.ItemKind.AUDIOBOOK && container.listening != null) {
                container.listening!!.play(listOf(item), startMs = if (fromStart || item.user.played) 0L else item.user.positionMs)
                nav.navigate(NowPlayingRoute) { launchSingleTop = true }
            }
            // A song plays in the listening player, its album queued around it.
            else if (item.kind == dev.nitrate.core.model.ItemKind.AUDIO && container.listening != null) {
                val album = item.albumId?.let { runCatching { container.server.albumTracks(it) }.getOrNull() }.orEmpty()
                listen(album.ifEmpty { listOf(item) }, album.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            }
            else if (startMs != null) nav.navigate(PlayerRoute(item.id, fromStart = false, startMs = startMs))
            else if (!fromStart && container.recapBefore(item) != null) nav.navigate(RecapRoute)
            else {
                val steps = if (displayPrefs.easyMode) emptyList() else dev.nitrate.tv.ui.preroll.planPrerolls(container, context, item, displayPrefs.prerolls, fromStart)
                if (steps.isEmpty()) nav.navigate(PlayerRoute(item.id, fromStart))
                else { dev.nitrate.tv.ui.preroll.PrerollPlan.steps = steps; nav.navigate(PrerollRoute(item.id, fromStart)) }
            }
        }
    }
    // The TV screens of plugins that are switched on.
    val enabledIds by container.plugins.enabledIds.collectAsState(emptySet())
    val enabledPlugins = TV_PLUGIN_UI.filter { it.pluginId in enabledIds }
    val pluginContext = remember(container) { PluginContext(container) }
    // Display choices belong to the signed-in user, known only after sign-in.
    val backEntry by nav.currentBackStackEntryAsState()
    val userId = remember(backEntry) { container.server.userId }
    // The main menu: the same stops on every top-level page (Home, libraries, custom pages).
    // Going somewhere from it starts from Home, so Back always leads home.
    val libraries = dev.nitrate.tv.ui.home.rememberLibraries(container)
    val customPages = rememberCustomPages(container)
    val fromHome: androidx.navigation.NavOptionsBuilder.() -> Unit = { popUpTo(HomeRoute); launchSingleTop = true }
    // A long press on "Switch user" in the menu: switch from right there.
    var quickSwitch by remember { mutableStateOf(false) }
    // The household's channels: the server's Companion plugin has them and the Channels plugin is on.
    val companion by container.companion.collectAsState()
    val channelsOn = companion?.features?.contains(dev.nitrate.core.channels.ChannelsPlugin.FEATURE) == true &&
        dev.nitrate.core.channels.ChannelsPlugin.id in enabledIds
    // Watch partners: the server's Companion plugin has them and the plugin is on.
    val partnersOn = companion?.features?.contains(dev.nitrate.core.partners.WatchPartners.FEATURE) == true &&
        dev.nitrate.core.partners.WatchPartnersPlugin.ID in enabledIds
    // The Watching together row's cards: handled (and the row shown) unless Watch groups is off here or the
    // server is known to lack it; before its answer this run the row shows at once, as it loaded.
    val togetherRowOn = dev.nitrate.core.partners.WatchPartnersPlugin.ID in enabledIds &&
        (companion == null || companion?.features?.contains(dev.nitrate.core.partners.WatchPartners.FEATURE) == true)
    // Playlists added to the menu (stored with the display settings).
    val storedPrefs by remember(userId) { userId?.let { container.displaySettings.prefs(it) } ?: kotlinx.coroutines.flow.flowOf(dev.nitrate.core.pages.DisplayPrefs()) }
        .collectAsState(dev.nitrate.core.pages.DisplayPrefs())
    val menuPlaylists = storedPrefs.menuPlaylists
    val menuCatalog = remember(libraries, customPages, nav, channelsOn, menuPlaylists) {
        dev.nitrate.tv.ui.home.menuCatalog(
            libraries, customPages, playlists = menuPlaylists, actions =
            dev.nitrate.tv.ui.home.MenuActions(
                onSearch = { nav.navigate(SearchRoute(), fromHome) },
                onHome = {
                    // Already home: nothing to do (popping to Home from Home pops nothing, and
                    // navigating would stack a second Home).
                    val atHome = nav.currentBackStackEntry?.destination?.hasRoute(HomeRoute::class) == true
                    if (!atHome && !nav.popBackStack(HomeRoute, inclusive = false)) nav.navigate(HomeRoute) { launchSingleTop = true }
                },
                onLibrary = { lib -> nav.navigate(LibraryRoute(lib.id), fromHome) },
                onPage = { id -> nav.navigate(CustomPageRoute(id), fromHome) },
                onPages = { nav.navigate(PagesRoute, fromHome) },
                onChannels = if (channelsOn) ({ nav.navigate(ChannelsRoute, fromHome) }) else null,
                onComics = { nav.navigate(CatalogsRoute, fromHome) },
                onSettings = { nav.navigate(SettingsHubRoute, fromHome) },
                onProfiles = { nav.navigate(ProfilesRoute) },
                onQuickSwitch = { quickSwitch = true },
                onNowPlaying = { nav.navigate(NowPlayingRoute) { launchSingleTop = true } },
                onMore = { nav.navigate(MoreRoute, fromHome) },
                onPlaylist = { id -> nav.navigate(PlaylistRoute(id), fromHome) },
                onSignOut = {
                    scope.launch {
                        container.signOut()
                        val next: Any = if (container.server.savedAccounts().isNotEmpty()) ProfilesRoute else ConnectRoute
                        nav.navigate(next) { popUpTo(0) { inclusive = true } }
                    }
                },
            ),
        )
    }
    // A playlist's page: on its own (PlaylistRoute) or as a Home tab (pinned), with pinning in its More.
    fun savePrefs(change: (dev.nitrate.core.pages.DisplayPrefs) -> dev.nitrate.core.pages.DisplayPrefs) {
        val user = userId ?: return
        scope.launch { container.displaySettings.savePrefs(user, change(container.displaySettings.prefs(user).first())) }
    }
    val playlistPage: @Composable (playlistId: String, header: (@Composable () -> Unit)?, onDeleted: () -> Unit) -> Unit = { playlistId, header, onDeleted ->
            // Entered from a watch group (PlaylistRoute.group): its parts open in the group.
            val openGroup = LocalOpenGroup.current
            val pinning = dev.nitrate.tv.ui.playlist.PlaylistPinning(
                onHome = playlistId in storedPrefs.homePlaylists,
                inMenu = playlistId in storedPrefs.menuPlaylists,
                setHome = { id, name, on -> savePrefs { p -> p.copy(homePlaylists = if (on) p.homePlaylists + (id to name) else p.homePlaylists - id) } },
                setMenu = { id, name, on -> savePrefs { p -> p.copy(menuPlaylists = if (on) p.menuPlaylists + (id to name) else p.menuPlaylists - id) } },
            )
            dev.nitrate.tv.ui.playlist.PlaylistScreen(
                repository = container.server,
                settings = container.displaySettings,
                playlists = container.playlists,
                playlistId = playlistId,
                onPlayQueue = { items -> listen(items) },
                onOpen = { item -> nav.openItem(item, openGroup ?: item.watchGroupId) },
                onDeleted = onDeleted,
                header = header,
                pinning = pinning,
                onContinue = { pl, next, rest ->
                    scope.launch {
                        if (container.recapBefore(next, pl) != null) nav.navigate(RecapRoute) else nav.playQueue(rest.map { it.id })
                    }
                },
                onStoryOrder = { id, on -> scope.launch { container.setRecapPlaylist(id, on) } },
                onPlayFrom = { pl, item ->
                    scope.launch {
                        container.playlists.startedFrom(pl.id, item.id)
                        // In story order, a "Last time" recap first when one is due.
                        if (pl.id in displayPrefs.recapPlaylists && container.recapBefore(item, pl) != null) nav.navigate(RecapRoute) else gate.play(item)
                    }
                },
            )
    }
    ProvideDisplayPrefs(container, userId) {
    NitrateTheme {
    androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.home.LocalMenuCatalog provides menuCatalog) {
    NavHost(
        navController = nav,
        startDestination = startRoute,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        composable<ProfilesRoute> {
            dev.nitrate.tv.ui.profiles.ProfilesScreen(
                container,
                onOpened = { nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } },
                onSignInAgain = { a -> nav.navigate(LoginRoute(a.serverUrl, a.serverName)) },
                onAdd = { nav.navigate(ConnectRoute) },
            )
        }
        composable<ConnectRoute> {
            ConnectScreen(
                repository = container.server,
                onConnected = { url, name -> nav.navigate(LoginRoute(url, name)) },
            )
        }
        composable<LoginRoute> { entry ->
            val route = entry.toRoute<LoginRoute>()
            LoginScreen(
                repository = container.server,
                serverUrl = route.serverUrl,
                serverName = route.serverName,
                onLoggedIn = {
                    scope.launch { container.onSignedIn() }
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable<HomeRoute> {
            dev.nitrate.tv.ui.partners.TogetherHost(container, togetherRowOn, onPlay = { item -> gate.play(item) }, onOpenShow = { id, group -> nav.navigate(DetailsRoute(id, group = group)) }) {
            HomeScreen(
                repository = container.server,
                displaySettings = container.displaySettings,
                onOpen = nav::openItem,
                onPlay = { item -> gate.play(item) },
                onSettings = { nav.navigate(SettingsHubRoute) },
                onOffline = { nav.navigate(OfflineRoute) { popUpTo(0) { inclusive = true } } },
                changes = container.serverChanges,
                dataCache = container.dataCache,
                customPages = customPages,
                onOpenPage = { id -> nav.navigate(CustomPageRoute(id)) },
                playlistPage = { id, header -> playlistPage(id, header) { savePrefs { p -> p.copy(homePlaylists = p.homePlaylists - id, menuPlaylists = p.menuPlaylists - id) } } },
                onSignOut = {
                    scope.launch {
                        container.signOut()
                        val next: Any = if (container.server.savedAccounts().isNotEmpty()) ProfilesRoute else ConnectRoute
                        nav.navigate(next) { popUpTo(0) { inclusive = true } }
                    }
                },
            )
            }
        }
        composable<DetailsRoute> { entry ->
            // Theme music while on details pages (on by default on TV).
            val themeOn = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.themeMusic ?: true
            val themeId = entry.toRoute<DetailsRoute>().itemId
            androidx.compose.runtime.DisposableEffect(themeId, themeOn) {
                if (themeOn) container.themeMusic.show(themeId) else container.themeMusic.stop()
                onDispose { container.themeMusic.hide(themeId) }
            }
            val route = entry.toRoute<DetailsRoute>()
            // Manage: admins, and metadata editors for titles in their libraries (the Companion says).
            val canManage by androidx.compose.runtime.produceState(account?.isAdmin == true, route.itemId, account) {
                value = container.canManageMetadata(route.itemId)
            }
            // Entered from a watch group: the page has the group's own marks (GroupMarks).
            dev.nitrate.tv.ui.partners.ProvideGroupMarks(container, route.group) {
            DetailsScreen(
                repository = container.server,
                itemId = route.itemId,
                seasonId = route.seasonId,
                // Opened from a watch group's card: its title's parts play (and open) from that card.
                onPlay = { item, fromStart -> gate.play(item, fromStart, origin = container.detailsOrigin(route.group, item)) },
                onPlayFrom = { item, at -> gate.play(item, startMs = at, origin = container.detailsOrigin(route.group, item)) },
                onOpen = { item -> nav.openItem(item, (container.detailsOrigin(route.group, item) as? dev.nitrate.core.partners.PlayOrigin.GroupCard)?.groupId) },
                onManage = if (canManage) { id -> nav.navigate(ManageItemRoute(id)) } else null,
                canCollect = account?.canManageCollections == true,
                onPlayQueue = { items -> listen(items) },
                onBrowse = { title, studio, year -> nav.navigate(PersonRoute("", title, studio, year)) },
                checkPlayback = { item -> container.checkPlayback(item.id, item.mediaKind == dev.nitrate.core.model.MediaKind.AUDIO, item) },
                trackSetup = { item -> container.trackSetup(item) },
                pickTrack = { item, setup, kind, index -> container.pickTrack(item, setup, kind, index) },
                applyPlaybackFix = { item, fix -> container.applyPlaybackFix(item.id, fix) },
                watchPartners = partnersOn,
                chat = container.detailsChat(route.group),
            )
            }
        }
        composable<ComicRoute> { entry ->
            dev.nitrate.tv.ui.comics.ComicReaderScreen(
                container, entry.toRoute<ComicRoute>().itemId, onExit = { nav.popBackStack() },
                onNext = { id -> nav.navigate(ComicRoute(id)) { popUpTo<ComicRoute> { inclusive = true } } },
            )
        }
        composable<RecapRoute> {
            dev.nitrate.tv.ui.player.RecapScreen(container, onPlayOn = { recap ->
                nav.popBackStack()
                if (recap.then.isNotEmpty()) nav.playQueue(listOf(recap.next.id) + recap.then) else nav.navigate(PlayerRoute(recap.next.id))
            }, onClip = { clip -> nav.navigate(PlayerRoute(clip.item.id, startMs = clip.startMs, clipEndMs = clip.endMs)) }, onBack = { nav.popBackStack() })
        }
        composable<PrerollRoute> { entry ->
            val route = entry.toRoute<PrerollRoute>()
            dev.nitrate.tv.ui.preroll.PrerollScreen(dev.nitrate.tv.ui.preroll.PrerollPlan.steps, dev.nitrate.tv.ui.components.LocalDisplayPrefs.current.prerolls) { videos ->
                nav.popBackStack()
                // The intros and own videos play in the player, then the title.
                if (videos.isEmpty()) nav.navigate(PlayerRoute(route.itemId, route.fromStart)) else nav.playQueue(videos.map { it.id } + route.itemId)
            }
        }
        composable<PrerollSettingsRoute> { dev.nitrate.tv.ui.preroll.PrerollSettingsScreen(container) }
        composable<WatchPartnersRoute> {
            // A group's title opens in that group: its marks, and plays counting for it.
            dev.nitrate.tv.ui.partners.WatchPartnersScreen(container) { id, group ->
                scope.launch {
                    val title = runCatching { container.server.getItem(id) }.getOrNull()
                    if (title == null) nav.navigate(DetailsRoute(id, group = group)) else nav.openItem(title, group)
                }
            }
        }
        composable<SettingsHubRoute> { TvSettingsHub(container, nav, scope) }
        composable<SettingsRoute> {
            PlaybackSettingsScreen(container.playbackSettings, container.capabilities, onAdvanced = { nav.navigate(AdvancedPlaybackRoute) })
        }
        composable<AdvancedPlaybackRoute> { PlaybackSettingsScreen(container.playbackSettings, container.capabilities, dev.nitrate.tv.ui.settings.PlaybackPage.ADVANCED) }
        composable<SubtitleSettingsRoute> { PlaybackSettingsScreen(container.playbackSettings, container.capabilities, dev.nitrate.tv.ui.settings.PlaybackPage.SUBTITLES) }
        composable<UpdatesRoute> { dev.nitrate.tv.ui.settings.UpdatesScreen(container.updater, container) }
        composable<OfflineRoute> {
            OfflineScreen(
                repository = container.server,
                screens = enabledPlugins.filterIsInstance<ScreenPlugin>().map { p -> p.screenLabel to { nav.navigate(PluginScreenRoute(p.pluginId)) } },
                onPlugins = { nav.navigate(PluginsRoute) },
                onServers = { nav.navigate(ServerAddressesRoute) },
                onOnline = {
                    container.settingsSync.pull()
                    container.backups.onSignedIn()
                    nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                },
                onSignedOut = { nav.navigate(ConnectRoute) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable<PagesRoute> {
            // A top-level stop like any other: in the menu, themed like the rest.
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.PAGES, overlay = false) {
                PagesScreen(container) { id, editNow -> nav.navigate(CustomPageRoute(id, editNow)) }
            }
        }
        composable<CustomPageRoute> { entry ->
            val route = entry.toRoute<CustomPageRoute>()
            // Laid out like Home: its own layout (full art lays the menu over it).
            val pagePrefs = dev.nitrate.core.pages.PageLooks.forPage(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current, dev.nitrate.core.pages.PageKeys.custom(route.pageId))
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.page(route.pageId), overlay = pagePrefs.tvArrangement.fullArt) { insets ->
                // The Watching together row works here too (its invites, start card and choices), as on Home.
                dev.nitrate.tv.ui.partners.TogetherHost(container, togetherRowOn, onPlay = { item -> gate.play(item) }, onOpenShow = { id, group -> nav.navigate(DetailsRoute(id, group = group)) }) {
                    CustomPageScreen(container, route.pageId, route.editNow, onOpen = nav::openItem, onPlay = { item -> gate.play(item) }, onDeleted = { nav.popBackStack() }, insets = insets)
                }
            }
        }
        composable<DisplaySettingsRoute> { DisplaySettingsScreen(container, onMenuStops = { nav.navigate(MenuStopsRoute) }, onShown = { nav.navigate(ShownSettingsRoute) }, onPages = { nav.navigate(PageSetupRoute) }, onEffects = { nav.navigate(EffectsSettingsRoute) }) }
        composable<EffectsSettingsRoute> { dev.nitrate.tv.ui.effects.EffectsSettingsScreen(container) }
        composable<PageSetupRoute> { dev.nitrate.tv.ui.pages.PageSetupScreen(container, onHome = { nav.navigate(HomeSetupRoute) }) { lib -> nav.navigate(LibraryTabsRoute(lib.id)) } }
        composable<HomeSetupRoute> { dev.nitrate.tv.ui.pages.HomeSetupScreen(container) }
        composable<LibraryTabsRoute> { entry -> dev.nitrate.tv.ui.pages.LibraryTabsScreen(container, entry.toRoute<LibraryTabsRoute>().libraryId) }
        composable<ShownSettingsRoute> { DisplaySettingsScreen(container, dev.nitrate.tv.ui.pages.DisplayPage.SHOWN) }
        composable<WhileWatchingRoute> { DisplaySettingsScreen(container, dev.nitrate.tv.ui.pages.DisplayPage.WATCHING, onPrerolls = { nav.navigate(PrerollSettingsRoute) }) }
        composable<LicensesRoute> { dev.nitrate.tv.ui.settings.LicensesScreen() }
        composable<SpeedCheckRoute> { dev.nitrate.tv.ui.settings.SpeedCheckScreen(container) }
        composable<QueryAnalyzerRoute> { dev.nitrate.tv.ui.settings.QueryAnalyzerScreen(container) }
        composable<StatsRoute> {
            val groups by container.partners.collectAsState()
            dev.nitrate.tv.ui.settings.StatsScreen(container.server, groups)
        }
        composable<TripsRoute> { dev.nitrate.tv.ui.settings.TripsScreen(container) { id -> nav.navigate(TripRoute(id)) } }
        composable<TripRoute> { entry -> dev.nitrate.tv.ui.settings.TripScreen(container, entry.toRoute<TripRoute>().tripId) { nav.popBackStack() } }
        composable<DiaryRoute> {
            val groups by container.partners.collectAsState()
            dev.nitrate.tv.ui.settings.DiaryScreen(container.server, groups)
        }
        composable<ServerSettingsRoute> { dev.nitrate.tv.ui.settings.ServerSettingsScreen(container) }
        composable<DisplayProfilesRoute> { dev.nitrate.tv.ui.settings.DisplayProfilesScreen(container) }
        composable<DeviceBackupRoute> { dev.nitrate.tv.ui.settings.DeviceBackupScreen(container.backups) }
        composable<ServerAddressesRoute> {
            dev.nitrate.tv.ui.settings.ServersScreen(
                container,
                onSwitch = { server ->
                    scope.launch {
                        // A single unlocked account opens straight away; otherwise pick on the profiles screen.
                        val only = server.accounts.singleOrNull()
                        if (only != null && !container.profiles.hasPin(only.key) && container.switchAccount(only.key) == dev.nitrate.core.model.SessionState.VALID) {
                            nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                        } else {
                            nav.navigate(ProfilesRoute)
                        }
                    }
                },
                onAdd = { nav.navigate(ConnectRoute) },
            )
        }
        composable<MenuStopsRoute> { dev.nitrate.tv.ui.settings.MenuStopsScreen(container) }
        composable<ManageItemRoute> { entry ->
            val route = entry.toRoute<ManageItemRoute>()
            ManageItemScreen(
                container.server, route.itemId, images = route.images, fixRun = if (route.run) container.imageFixRun else emptyList(),
                themePreview = { id, on -> if (on) container.themeMusic.show(id) else container.themeMusic.stop() },
                asEditor = account?.isAdmin != true,
                creditsCheck = account?.isAdmin == true && companion?.features?.contains(dev.nitrate.core.playback.CreditsReport.FEATURE) == true,
            )
        }
        composable<ServerAdminRoute> {
            ServerAdminScreen(container.server, metadataEditors = account?.isAdmin == true && companion?.features?.contains(dev.nitrate.core.admin.MetadataEditors.FEATURE) == true)
        }
        composable<HouseholdDevicesRoute> { dev.nitrate.tv.ui.settings.HouseholdDevicesScreen(container, onOpen = { nav.navigate(DetailsRoute(it)) }) }
        composable<GuideRoute> { dev.nitrate.tv.ui.settings.GuideScreen(admin = account?.isAdmin == true) }
        composable<TvCheckRoute> { dev.nitrate.tv.ui.settings.TvCheckScreen(container) }
        composable<ImagesOverviewRoute> {
            dev.nitrate.tv.ui.settings.ImagesOverviewScreen(container, onManage = { nav.navigate(ManageItemRoute(it, images = true)) }, onFixRun = { nav.navigate(ManageItemRoute(it, images = true, run = true)) })
        }
        composable<PlaybackOverviewRoute> {
            dev.nitrate.tv.ui.settings.PlaybackOverviewScreen(
                container,
                onOpen = { nav.navigate(DetailsRoute(it)) },
                // Test plays go straight to the player: no guards, recaps or pre-rolls.
                onTest = { ids -> ids.firstOrNull()?.let { nav.navigate(PlayerRoute(it, queue = ids.drop(1).joinToString(","), test = true)) } },
            )
        }
        composable<LibraryHealthRoute> {
            dev.nitrate.tv.ui.settings.LibraryHealthScreen(container, onOpen = { nav.navigate(DetailsRoute(it)) }, onManage = { nav.navigate(ManageItemRoute(it)) })
        }
        composable<SearchRoute> { entry ->
            val route = entry.toRoute<SearchRoute>()
            dev.nitrate.tv.ui.search.SearchScreen(
                repository = container.server,
                history = container.searchHistory,
                initialQuery = route.query,
                startWithVoice = route.voice,
                onOpen = nav::openItem,
                onPerson = { p -> nav.navigate(PersonRoute(p.id, p.name)) },
                areas = container::searchAreas,
                onPlay = { item -> scope.launch { gate.play(item) } },
                linesOn = companion?.features?.contains(dev.nitrate.core.lines.SpokenLines.FEATURE) == true,
                onPlayLine = { line ->
                    scope.launch {
                        runCatching { container.server.getItem(line.itemId) }.getOrNull()?.let { gate.play(it, startMs = dev.nitrate.core.lines.SpokenLines.startAt(line)) }
                    }
                },
            )
        }
        composable<PersonRoute> { entry ->
            val route = entry.toRoute<PersonRoute>()
            dev.nitrate.tv.ui.search.PersonScreen(container.server, dev.nitrate.core.model.Person(route.personId, route.name), onOpen = nav::openItem, studio = route.studio, year = route.year, displaySettings = container.displaySettings)
        }
        composable<CatalogsRoute> {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.COMICS, overlay = false) {
                dev.nitrate.tv.ui.comics.CatalogsScreen(container, onOpen = { id -> nav.navigate(CatalogFeedRoute(id)) })
            }
        }
        composable<CatalogFeedRoute> { entry ->
            val route = entry.toRoute<CatalogFeedRoute>()
            dev.nitrate.tv.ui.comics.CatalogFeedScreen(
                container, route.catalogId, route.target,
                onOpen = { target -> nav.navigate(CatalogFeedRoute(route.catalogId, target)) },
                onRead = { book -> nav.navigate(CatalogBookRoute(route.catalogId, catalogJson.encodeToString(dev.nitrate.core.catalogs.CatalogBook.serializer(), book))) },
            )
        }
        composable<CatalogBookRoute> { entry ->
            val route = entry.toRoute<CatalogBookRoute>()
            dev.nitrate.tv.ui.comics.CatalogReaderScreen(container, route.catalogId, catalogJson.decodeFromString(dev.nitrate.core.catalogs.CatalogBook.serializer(), route.book), onExit = { nav.popBackStack() })
        }
        composable<MoreRoute> { dev.nitrate.tv.ui.home.MoreScreen() }
        composable<ChannelsRoute> {
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.CHANNELS, overlay = false) {
                dev.nitrate.tv.ui.channels.ChannelsScreen(
                    container.server,
                    admin = account?.isAdmin == true,
                    onPlayQueue = { ids -> nav.playQueue(ids) },
                    onEdit = { id -> nav.navigate(ChannelEditorRoute(id.orEmpty())) },
                )
            }
        }
        composable<ChannelEditorRoute> { entry ->
            val id = entry.toRoute<ChannelEditorRoute>().channelId.ifEmpty { null }
            dev.nitrate.tv.ui.channels.ChannelEditorScreen(container.server, id) { nav.popBackStack() }
        }
        composable<PluginsRoute> { PluginsScreen(container.plugins, TV_PLUGIN_UI, pluginContext) }
        composable<PluginScreenRoute> { entry ->
            val id = entry.toRoute<PluginScreenRoute>().pluginId
            TV_PLUGIN_UI.filterIsInstance<ScreenPlugin>().firstOrNull { it.pluginId == id }?.Screen(pluginContext)
        }
        composable<AudioChannelTestRoute> { AudioChannelTestScreen(container.server) }
        composable<PlaybackTestRoute> {
            PlaybackTestScreen(store = container.playbackSettings, capabilities = container.capabilities, server = container.server)
        }
        composable<LibraryRoute> { entry ->
            val libraryId = entry.toRoute<LibraryRoute>().libraryId
            val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
            // Library pages are laid out like Home unless set otherwise; full art has the menu over it.
            val libraryFullArt = dev.nitrate.core.pages.PageLooks.forPage(prefs, dev.nitrate.core.pages.PageKeys.library(libraryId)).tvArrangement.fullArt
            dev.nitrate.tv.ui.home.MainMenu(dev.nitrate.core.pages.MenuStops.library(libraryId), overlay = libraryFullArt) { insets ->
                dev.nitrate.tv.ui.partners.TogetherHost(container, togetherRowOn, onPlay = { item -> gate.play(item) }, onOpenShow = { id, group -> nav.navigate(DetailsRoute(id, group = group)) }) {
                LibraryScreen(
                    fullArt = libraryFullArt,
                    insets = insets,
                    repository = container.server,
                    displaySettings = container.displaySettings,
                    libraryId = libraryId,
                    changes = container.serverChanges,
                    dataCache = container.dataCache,
                    onOpen = nav::openItem,
                    onPlay = { item -> gate.play(item) },
                    // Bulk editing: admins, and metadata editors whose libraries include this one.
                    admin = account?.canEditMetadataIn(libraryId) == true,
                )
                }
            }
        }
        composable<PlaylistRoute> { entry ->
            val route = entry.toRoute<PlaylistRoute>()
            val id = route.playlistId
            // Entered from a watch group: its marks on the parts, and each part opens in the group.
            dev.nitrate.tv.ui.partners.ProvideGroupMarks(container, route.group) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOpenGroup provides route.group) {
                    playlistPage(id, null) {
                        savePrefs { p -> p.copy(homePlaylists = p.homePlaylists - id, menuPlaylists = p.menuPlaylists - id) }
                        nav.popBackStack()
                    }
                }
            }
        }
        composable<NowPlayingRoute> {
            val listening = container.listening
            if (listening == null) LaunchedEffect(Unit) { nav.popBackStack() }
            else dev.nitrate.tv.ui.music.NowPlayingScreen(listening, container.server, onBack = { nav.popBackStack() }, bookmarkStore = container.bookmarks)
        }
        composable<PlayerRoute> { entry ->
            LaunchedEffect(Unit) {
                container.themeMusic.stop()
                // A video takes over from the music.
                container.listening?.pause()
            }
            // Leaving the player: what was watched with partners counts for them too.
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { container.afterWatching() } }
            val route = entry.toRoute<PlayerRoute>()
            PlayerScreen(
                repository = container.server,
                playbackSettings = container.playbackSettings,
                itemId = route.itemId,
                fromStart = route.fromStart,
                onExit = { nav.popBackStack() },
                panels = enabledPlugins.filterIsInstance<PlayerPanel>(),
                pluginContext = pluginContext,
                trackMemory = container.trackMemory,
                remote = container.remote,
                // Back from an extra: on from where it was left.
                startMs = entry.savedStateHandle.get<Long>(RESUME_AT) ?: route.startMs.takeIf { it >= 0 },
                clipEndMs = route.clipEndMs.takeIf { it >= 0 },
                // An extra plays on top; Back or its end comes back here, at the same spot.
                onPlayExtra = if (route.clipEndMs < 0 && !route.test) { extra, at ->
                    entry.savedStateHandle[RESUME_AT] = at
                    nav.navigate(PlayerRoute(extra.id, fromStart = true))
                } else null,
                audioIndex = route.audioIndex.takeIf { it != NO_TRACK },
                subtitleIndex = route.subtitleIndex.takeIf { it != NO_TRACK },
                describedAudio = container::prefersDescribedAudio,
                // Up Next (episodes, a film's collection) only outside queues and recap clips.
                onPlayNext = if (route.queue.isBlank() && route.clipEndMs < 0 && !route.test) { next -> nav.popBackStack(); gate.play(next, origin = container.upNextOrigin(next)) } else null,
                syncPlay = container.syncPlay.takeIf { !route.test },
                stillWatching = container.stillWatching,
                skipWatch = container.skipWatch,
                playlistNext = if (route.queue.isBlank() && route.clipEndMs < 0 && !route.test) { item -> container.playlists.contextOf(item) } else null,
                stillWatchingRewind = container::stillWatchingRewind,
                sendLog = { session -> container.sendPlaybackReport(session) },
                capabilities = container.capabilities,
                chat = container.chat,
                groupChat = if (route.clipEndMs < 0 && !route.test) {
                    val chatScope = rememberCoroutineScope()
                    remember(route.itemId) { container.timelineChat(chatScope, live = true) }
                } else null,
                watchGroups = container.takeIf { route.clipEndMs < 0 && !route.test },
                testPlay = if (route.test) { result -> scope.launch { container.saveTestPlay(result) } } else null,
                onEnded = {
                    val next = route.queue.split(',').filter(String::isNotBlank)
                    if (route.test && next.isNotEmpty()) {
                        // A sample test: on to the next kind of file.
                        nav.popBackStack()
                        nav.navigate(PlayerRoute(next.first(), queue = next.drop(1).joinToString(","), test = true))
                    } else if (next.isEmpty()) {
                        nav.popBackStack()
                    } else {
                        nav.popBackStack()
                        nav.playQueue(next)
                    }
                },
            )
        }
    }
    }
    if (quickSwitch) dev.nitrate.tv.ui.profiles.QuickSwitchDialog(
        container,
        onOpened = { nav.navigate(HomeRoute) { popUpTo(0) { inclusive = true } } },
        onSignInAgain = { a -> nav.navigate(LoginRoute(a.serverUrl, a.serverName)) },
        onDismiss = { quickSwitch = false },
    )
    PlaybackGateDialog(gate)
    // The first-run setup, once signed in; while it shows, the prompts it replaces wait.
    val setupActive by container.setup.active.collectAsState()
    val onHome = nav.currentBackStack.collectAsState().value.any { it.destination.hasRoute(HomeRoute::class) }
    if (onHome) dev.nitrate.tv.ui.setup.FirstRunSetupHost(container, menuCatalog) { a -> container.saveScope.launch { container.setup.finish() }; nav.navigate(LoginRoute(a.serverUrl, a.serverName)) }
    if (!setupActive) {
        dev.nitrate.tv.ui.components.UpdatePrompt(container.updater)
        dev.nitrate.tv.ui.settings.BackupPrompt(container.backups)
        dev.nitrate.tv.ui.player.SpeakersSetupPrompt(container)
    }
    if (companion?.features?.contains("together") == true) dev.nitrate.tv.ui.together.WatchInvitePrompt(container)
    // Watch parties: what the Companion sends as it happens; in the player only a quiet note, never taking focus.
    if (companion?.features?.contains("party") == true) {
        val inPlayer = nav.currentBackStackEntryAsState().value?.destination?.hasRoute(PlayerRoute::class) == true
        // Starting the party from inside a player: it closes (its place kept) for the party's part.
        dev.nitrate.tv.ui.partners.WatchPartyPrompt(container, inPlayer, displayPrefs.easyMode) { item -> if (inPlayer) nav.popBackStack(); gate.play(item) }
    }
    // Group Watch Sessions someone in a group started ("Tommy started a Group Watch Session · Join"); in the player only a quiet note.
    if (companion?.features?.contains(dev.nitrate.core.partners.GroupWatchSession.FEATURE) == true) {
        val inPlayer = nav.currentBackStackEntryAsState().value?.destination?.hasRoute(PlayerRoute::class) == true
        dev.nitrate.tv.ui.partners.SessionJoinPrompt(container, inPlayer, displayPrefs.easyMode)
        // Asks to watch together: over the player too, taking focus; "Start it" hosts it here (in place of a player open).
        dev.nitrate.tv.ui.partners.WatchAskPrompt(container) { item -> if (inPlayer) nav.popBackStack(); gate.play(item) }
    }
    // Schedule changes someone in a group asked for (Yes/No), and how they went; never over the player.
    if (companion?.features?.contains("partners") == true) {
        val inPlayer = nav.currentBackStackEntryAsState().value?.destination?.hasRoute(PlayerRoute::class) == true
        dev.nitrate.tv.ui.partners.ScheduleChangePrompt(container, inPlayer)
    }
    // "Also count it for Suzy, where it's next too?": once the player has closed (it never interrupts one).
    run {
        val inPlayer = nav.currentBackStackEntryAsState().value?.destination?.hasRoute(PlayerRoute::class) == true
        if (!inPlayer) dev.nitrate.tv.ui.partners.CountingPrompt(container)
    }
    LaunchedEffect(Unit) { container.updater.checkIfDue() }
    // What other apps ask of this TV: play something, show a message, change volume.
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The home screen and voice search: carried out once signed in (not over sign-in or the profile picker).
    val pendingRequest by requests.collectAsState()
    val currentEntry by nav.currentBackStackEntryAsState()
    LaunchedEffect(pendingRequest, currentEntry) {
        val request = pendingRequest ?: return@LaunchedEffect
        val signedIn = nav.currentBackStack.value.any { it.destination.hasRoute(HomeRoute::class) }
        // Not over the profile picker or a sign-in either: wait until one is chosen.
        val picking = currentEntry?.destination?.let { d -> d.hasRoute(ProfilesRoute::class) || d.hasRoute(ConnectRoute::class) || d.hasRoute(LoginRoute::class) } == true
        if (!signedIn || picking) return@LaunchedEffect
        requests.value = null
        when (request) {
            is TvRequest.Search -> nav.navigate(SearchRoute(request.query, request.voice))
            is TvRequest.Open -> nav.navigate(DetailsRoute(request.itemId))
            is TvRequest.Play -> runCatching { container.server.getItem(request.itemId) }.getOrNull()?.let { gate.play(it) }
        }
    }
    // The ambient screensaver: after a while with nothing pressed, outside the player.
    val ambientMinutes by remember(userId) {
        userId?.let { id -> container.displaySettings.prefs(id).map { it.tvAmbientMinutes } } ?: kotlinx.coroutines.flow.flowOf(0)
    }.collectAsState(0)
    var ambient by remember { mutableStateOf<List<dev.nitrate.core.ambient.AmbientSlide>?>(null) }
    val watching = currentEntry?.destination?.let { it.hasRoute(PlayerRoute::class) || it.hasRoute(RecapRoute::class) } == true
    val signedInNow = currentEntry != null && nav.currentBackStack.value.any { it.destination.hasRoute(HomeRoute::class) }
    // Leaving the app closes the screensaver, so coming back never finds it on (it used to come on
    // while the app was in the background and still be showing on return).
    val appVisible by dev.nitrate.tv.ui.ambient.TvIdle.visible.collectAsState()
    LaunchedEffect(appVisible) { if (!appVisible) ambient = null }
    LaunchedEffect(ambientMinutes, watching, signedInNow, appVisible) {
        if (!signedInNow || ambientMinutes == 0 || watching || !appVisible) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(10_000)
            if (ambient != null || !dev.nitrate.core.ambient.Ambient.due(dev.nitrate.tv.ui.ambient.TvIdle.idleMs(), ambientMinutes, watching)) continue
            val rows = runCatching { dev.nitrate.core.diagnostics.withQueryOrigin("Screensaver") { container.server.watchingRows(latest = true) } }.getOrNull() ?: continue
            val slides = dev.nitrate.core.ambient.Ambient.slides(rows) { container.server.backdropUrl(it) != null }
            // Still nothing pressed while Home's rows were fetched.
            if (slides.isNotEmpty() && dev.nitrate.tv.ui.ambient.TvIdle.visible.value && dev.nitrate.core.ambient.Ambient.due(dev.nitrate.tv.ui.ambient.TvIdle.idleMs(), ambientMinutes, watching)) {
                container.themeMusic.onAppHidden()
                ambient = slides
            }
        }
    }
    ambient?.let { slides ->
        dev.nitrate.tv.ui.ambient.AmbientScreen(
            slides, container.server,
            onOpen = { item -> dev.nitrate.tv.ui.ambient.TvIdle.press(); ambient = null; nav.navigate(DetailsRoute(item.id)) },
            onDismiss = { dev.nitrate.tv.ui.ambient.TvIdle.press(); ambient = null },
        )
    }
    // The home screen channel and Play Next row follow what's watched.
    val homeChannels = remember { dev.nitrate.tv.channels.HomeChannels(context, container.server) }
    LaunchedEffect(account?.id) {
        homeChannels.schedule()
        homeChannels.refreshSoon()
    }
    val leftPlayer = currentEntry?.destination?.hasRoute(PlayerRoute::class) == false
    LaunchedEffect(leftPlayer) { if (leftPlayer) homeChannels.refreshSoon() }
    // A Group Watch Session joined: its part plays (through the guards) from where this player starts, waiting for the countdown.
    LaunchedEffect(Unit) {
        container.groupSessions.openRequests.collect { open ->
            val item = runCatching { container.server.getItem(open.itemId) }.getOrNull() ?: return@collect
            // Joined from inside a player: it closes (its place kept) for the session's part.
            if (nav.currentBackStackEntry?.destination?.hasRoute(PlayerRoute::class) == true) nav.popBackStack()
            val groupId = container.groupSessions.current.value?.groupId
            gate.play(item.copy(watchGroupId = groupId ?: item.watchGroupId), startMs = open.startMs, origin = groupId?.let { dev.nitrate.core.partners.PlayOrigin.GroupCard(it) })
        }
    }
    // SyncPlay, kept for compatibility (an older app's group this TV joined): it started something while no player was open.
    LaunchedEffect(Unit) {
        container.syncPlay.openRequests.collect { q ->
            // In place of a player already open, so Back doesn't return to the old one.
            q.currentItemId?.let { nav.navigate(PlayerRoute(it, fromStart = false, startMs = q.startMs)) { popUpTo<PlayerRoute> { inclusive = true } } }
        }
    }
    LaunchedEffect(Unit) {
        container.remote.events.collect { e ->
            when (e) {
                is dev.nitrate.core.events.ServerEvent.Play -> e.itemIds.firstOrNull()?.let { first ->
                    nav.navigate(
                        PlayerRoute(
                            first, fromStart = e.startMs == null, queue = e.itemIds.drop(1).joinToString(","), startMs = e.startMs ?: -1,
                            audioIndex = e.audioIndex ?: NO_TRACK, subtitleIndex = e.subtitleIndex ?: NO_TRACK,
                        ),
                    ) {
                        popUpTo<PlayerRoute> { inclusive = true }
                    }
                }
                is dev.nitrate.core.events.ServerEvent.General -> when (e.name) {
                    "DisplayMessage" -> message = (e.arguments["Header"] ?: "Message") to e.arguments["Text"].orEmpty()
                    "VolumeUp", "VolumeDown", "ToggleMute", "Mute", "Unmute" -> {
                        val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        val dir = when (e.name) {
                            "VolumeUp" -> android.media.AudioManager.ADJUST_RAISE
                            "VolumeDown" -> android.media.AudioManager.ADJUST_LOWER
                            "Mute" -> android.media.AudioManager.ADJUST_MUTE
                            "Unmute" -> android.media.AudioManager.ADJUST_UNMUTE
                            else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                        }
                        audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI)
                    }
                    // The phone's arrow pad: pressed as if on this TV's own remote.
                    "MoveUp", "MoveDown", "MoveLeft", "MoveRight", "Select", "Back" -> {
                        val key = when (e.name) {
                            "MoveUp" -> android.view.KeyEvent.KEYCODE_DPAD_UP
                            "MoveDown" -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                            "MoveLeft" -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            "MoveRight" -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                            "Select" -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
                            else -> android.view.KeyEvent.KEYCODE_BACK
                        }
                        (context.findActivityOrNull())?.let { a ->
                            a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
                            a.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
                        }
                    }
                    "GoHome" -> nav.navigate(HomeRoute) { popUpTo(HomeRoute) { inclusive = true }; launchSingleTop = true }
                    // The phone remote's Go to, Show on TV and Type.
                    "GoToSearch" -> nav.navigate(SearchRoute()) { launchSingleTop = true }
                    "DisplayContent" -> e.arguments["ItemId"]?.let { id ->
                        when (e.arguments["ItemType"]) {
                            "CollectionFolder", "UserView" -> nav.navigate(LibraryRoute(id))
                            "Playlist" -> nav.navigate(PlaylistRoute(id))
                            else -> nav.navigate(DetailsRoute(id))
                        }
                    }
                    "SendString" -> e.arguments["String"]?.let { text ->
                        // No field in use: a search for it (in place of a search already open, so its words change).
                        if (!dev.nitrate.tv.ui.components.RemoteTyping.type(text)) nav.navigate(SearchRoute(text)) { popUpTo<SearchRoute> { inclusive = true } }
                    }
                    else -> Unit
                }
                else -> Unit
            }
        }
    }
    // A phone's remote: "Start a Group Watch Session" hosted here. It gathers longer than the usual minute
    // (the phone's Start now says when) and plays here, counting for the group, through the guards.
    LaunchedEffect(Unit) {
        container.remote.sessionStarts.collect { ask ->
            val group = container.partners.value?.groups?.firstOrNull { it.id == ask.groupId }
            val item = runCatching { container.server.getItem(ask.itemId) }.getOrNull()
            val error = if (item == null) "That title isn't on the server" else
                container.startWatchingNow(item, group, gatherMinutes = dev.nitrate.core.partners.GroupWatchSession.REMOTE_GATHER_MINUTES)
            if (error != null || item == null) {
                message = "Couldn't start the Group Watch Session" to (error ?: "")
            } else {
                // In place of a player already open (Back doesn't return to it).
                if (nav.currentBackStackEntry?.destination?.hasRoute(PlayerRoute::class) == true) nav.popBackStack()
                gate.play(item.copy(watchGroupId = ask.groupId))
            }
        }
    }
    message?.let { (header, text) ->
        dev.nitrate.tv.ui.lists.TvDialog({ message = null }, width = 560) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp)) {
                Text(header, style = MaterialTheme.typography.headlineSmall)
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
            }
        }
        LaunchedEffect(header, text) { kotlinx.coroutines.delay(10_000); message = null }
    }
    }
    }
}

/**
 * Where selecting an item goes. Seasons open their series with that season selected. A details
 * page remembers the watch group whose card opened it ([group]; a Watching together card's own
 * [MediaItem.watchGroupId]), so what plays from it counts as from that card.
 */
fun NavController.openItem(item: MediaItem, group: String? = item.watchGroupId) {
    val seriesId = item.seriesId
    when {
        item.kind == ItemKind.SEASON && seriesId != null ->
            navigate(DetailsRoute(seriesId, seasonId = item.id, group = group))
        item.kind == ItemKind.LIBRARY ->
            navigate(LibraryRoute(item.id))
        item.kind == ItemKind.PLAYLIST -> navigate(PlaylistRoute(item.id, group = group))
        else -> navigate(DetailsRoute(item.id, group = group))
    }
}

/** Plays the first id, queueing the rest. Queue items always start from the beginning. */
fun NavController.playQueue(ids: List<String>) {
    val first = ids.firstOrNull() ?: return
    navigate(PlayerRoute(first, fromStart = true, queue = ids.drop(1).joinToString(",")))
}


/** The activity behind [this] context, if any. */
private tailrec fun android.content.Context.findActivityOrNull(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}

/** The watch group a playlist page was entered from (its parts open in it), or null. */
internal val LocalOpenGroup = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }
