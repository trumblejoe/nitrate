package dev.nitrate.tv.ui

import kotlinx.serialization.Serializable

// Every screen of the TV app, as navigation routes (NitrateNavHost shows them).

@Serializable object ConnectRoute
@Serializable data class LoginRoute(val serverUrl: String, val serverName: String)
@Serializable object HomeRoute
/** The listening player (music). */
@Serializable object NowPlayingRoute
@Serializable object OfflineRoute
/** [group]: opened from that watch group's card on Home's Watching together row (plays from the page count as from the card). */
@Serializable data class DetailsRoute(val itemId: String, val seasonId: String? = null, val group: String? = null)
@Serializable object SettingsRoute
@Serializable object SettingsHubRoute
@Serializable object AdvancedPlaybackRoute
@Serializable object SubtitleSettingsRoute
@Serializable object WhileWatchingRoute
@Serializable object PrerollSettingsRoute
@Serializable object ShownSettingsRoute
@Serializable object UpdatesRoute
@Serializable object PlaybackTestRoute
@Serializable object AudioChannelTestRoute
@Serializable object PluginsRoute
@Serializable data class PluginScreenRoute(val pluginId: String)
@Serializable data class LibraryRoute(val libraryId: String)
@Serializable data class PlaylistRoute(val playlistId: String, val group: String? = null)
@Serializable object PagesRoute
@Serializable data class CustomPageRoute(val pageId: String, val editNow: Boolean = false)
@Serializable object DisplaySettingsRoute
@Serializable object PageSetupRoute
@Serializable object HomeSetupRoute
@Serializable object EffectsSettingsRoute
@Serializable data class LibraryTabsRoute(val libraryId: String)
@Serializable object MenuStopsRoute
@Serializable object ServerAddressesRoute
@Serializable object LicensesRoute
@Serializable object DeviceBackupRoute
@Serializable object DisplayProfilesRoute
@Serializable object SpeedCheckRoute
@Serializable object QueryAnalyzerRoute
@Serializable object StatsRoute
@Serializable object DiaryRoute
@Serializable object ServerSettingsRoute
@Serializable data class ManageItemRoute(val itemId: String)
@Serializable object ServerAdminRoute
@Serializable object LibraryHealthRoute
@Serializable object PlaybackOverviewRoute

/** Settings → Tools → TV & receiver check (the Companion plugin's clips). */
@Serializable object TvCheckRoute

/** Settings → Tools → Checking how things play (the guide to the playback tools). */
@Serializable object GuideRoute

/** Settings → Tools → Household devices (admins). */
@Serializable object HouseholdDevicesRoute
@Serializable object ChannelsRoute
/** The menu's "More": the stops kept out of the menu itself. */
@Serializable object MoreRoute
@Serializable object WatchPartnersRoute
@Serializable data class ChannelEditorRoute(val channelId: String = "")
@Serializable object ProfilesRoute
@Serializable data class SearchRoute(val query: String = "", val voice: Boolean = false)
@Serializable data class PersonRoute(val personId: String, val name: String, val studio: String? = null, val year: Int? = null)

/** Asked of the app from outside: the home screen, voice search, the remote's search button. */
sealed interface TvRequest {
    data class Search(val query: String, val voice: Boolean) : TvRequest
    data class Open(val itemId: String) : TvRequest
    data class Play(val itemId: String) : TvRequest
}

/** [queue] is comma-separated item ids to play after [itemId]; [clipEndMs] >= 0: a recap clip from [startMs] to there, unreported. */
/** [audioIndex]/[subtitleIndex]: tracks asked for by another device ([NO_TRACK]: not asked; subtitles -1 = off). */
@Serializable data class PlayerRoute(
    val itemId: String, val fromStart: Boolean = false, val queue: String = "", val startMs: Long = -1, val clipEndMs: Long = -1,
    val audioIndex: Int = NO_TRACK, val subtitleIndex: Int = NO_TRACK,
    /** A test play from the playback overview; [queue] then holds the titles still to test. */
    val test: Boolean = false,
)

const val NO_TRACK = -2

/** Where the player was when an extra opened on top of it (saved on its back-stack entry). */
internal const val RESUME_AT = "resumeAt"

/** "Last time": the recap in [AppContainer.pendingRecap], before playing on. */
@Serializable object RecapRoute

/** Reading a comic from a Books library. */
@Serializable data class ComicRoute(val itemId: String)
@Serializable object CatalogsRoute
@Serializable data class CatalogFeedRoute(val catalogId: String, val target: String? = null)
@Serializable data class CatalogBookRoute(val catalogId: String, val book: String)

/** What plays before [itemId] ([dev.nitrate.tv.ui.preroll.PrerollPlan.steps]): a scene, a verse, then intros. */
@Serializable data class PrerollRoute(val itemId: String, val fromStart: Boolean = false)
