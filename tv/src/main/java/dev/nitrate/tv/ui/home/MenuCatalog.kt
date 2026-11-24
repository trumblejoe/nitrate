package dev.nitrate.tv.ui.home

import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import dev.nitrate.core.pages.TvMenuStyle
import dev.nitrate.core.pages.MenuStops
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.pages.DisplayPrefs
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import dev.nitrate.core.pages.MenuGroups
import dev.nitrate.core.pages.MenuLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem

/** What each kind of stop does. */
class MenuActions(
    val onSearch: () -> Unit = {},
    val onHome: () -> Unit = {},
    val onLibrary: (MediaItem) -> Unit = {},
    val onPage: (String) -> Unit = {},
    val onPages: () -> Unit = {},
    /** Set when the household's channels are available (Companion plugin and the Channels plugin on). */
    val onChannels: (() -> Unit)? = null,
    val onComics: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onProfiles: () -> Unit = {},
    /** Long press on "Switch user": switch straight from the menu. */
    val onQuickSwitch: (() -> Unit)? = null,
    /** Back to the listening player; its stop shows only while music plays. */
    val onNowPlaying: () -> Unit = {},
    /** The page of stops kept in More. */
    val onMore: () -> Unit = {},
    /** A playlist added to the menu. */
    val onPlaylist: (String) -> Unit = {},
    /** Sign out (the profile corner's account panel; easy mode asks first). */
    val onSignOut: (() -> Unit)? = null,
)

/**
 * Every stop the menu can have, by [MenuStops] id, and the order it starts in. [moreDefaults] is
 * what the More page starts with (`MenuStops.defaultMore`).
 */
class MenuCatalog(
    val entries: Map<String, NavEntry>,
    val defaults: List<String>,
    val nowPlaying: NavEntry? = null,
    val moreDefaults: List<String> = emptyList(),
    /** Signing out, from the profile corner's account panel (null: not offered, e.g. in tests). */
    val signOut: (() -> Unit)? = null,
) {
    /** The same stops with [id] underlined as the page you're on. */
    fun current(id: String) = MenuCatalog(entries.mapValues { (k, e) -> e.copy(selected = k == id) }, defaults, nowPlaying, moreDefaults, signOut)

    /** Stop ids that exist, groups included. */
    fun available(prefs: DisplayPrefs): Set<String> = entries.keys + prefs.tvMenuGroups.map { MenuStops.group(it.id) }

    /**
     * The stops on the More page, in order (Easy mode: only the essential ones; with the overlay or
     * the rail, not Switch user, which is in the profile corner).
     */
    fun moreIds(prefs: DisplayPrefs): List<String> =
        MenuLayout.more(prefs.tvMoreStops, moreDefaults, available(prefs), prefs.tvMenuGroups)
            .filter { (!prefs.easyMode || MenuStops.essential(it)) && !(prefs.tvMenu.cornerStops && it in MenuStops.IN_CORNER) }

    /** The More page's stops as entries (groups with their members). */
    fun more(prefs: DisplayPrefs): List<NavEntry> = moreIds(prefs).mapNotNull { entry(it, prefs) }

    /** A stop's entry; a group's is built from its members (named without the group's words), selected when one of them is. */
    fun entry(id: String, prefs: DisplayPrefs): NavEntry? {
        if (!id.startsWith("group:")) return entries[id]
        val group = prefs.tvMenuGroups.firstOrNull { MenuStops.group(it.id) == id } ?: return null
        val members = group.stops.mapNotNull { m -> entries[m]?.let { it.copy(label = MenuGroups.memberLabel(group, it.label)) } }
        if (members.isEmpty()) return null
        return NavEntry(group.name, members.first().icon, selected = members.any { it.selected }, id = id, children = members, onClick = {})
    }

    /** The menu's stops as the user arranged them (linear, as stop ids), groups standing for their members and More for what's in it. */
    fun stopIds(prefs: DisplayPrefs): List<String> =
        MenuLayout.main(MenuStops.linear(MenuStops.arrange(prefs.tvMenuStops, available(prefs), defaults)), prefs.tvMenuGroups, moreIds(prefs))

    /**
     * The overlay's, rail's or bar's stops (Easy mode keeps only the essential ones; the overlay and
     * the rail leave out Search, Settings and Switch user, which are in the profile corner). A page
     * kept in More (Your pages, Collections…) marks More, so the menu opens there and shows where you are.
     */
    fun stops(prefs: DisplayPrefs): List<NavEntry> =
        MenuStops.shownIn(stopIds(prefs), prefs.tvMenu).filter { !prefs.easyMode || MenuStops.essential(it) }.mapNotNull { entry(it, prefs) }.let { shown ->
            val inMore = shown.none { it.selected } && more(prefs).any { it.selected }
            if (inMore) shown.map { if (it.id == MenuStops.MORE) it.copy(selected = true) else it } else shown
        }

    /**
     * The dial's spots as the user arranged them (null = empty): a member stands for its group (once),
     * More's stops leave their spot empty and More, groups nobody placed, take the first free ones;
     * Easy mode keeps only the essential ones.
     */
    fun dial(prefs: DisplayPrefs): List<NavEntry?> {
        val more = moreIds(prefs).toSet()
        val seen = mutableSetOf<String>()
        val spots = MenuStops.arrange(prefs.tvDialStops, available(prefs), defaults).map { id ->
            val shown = id?.let { s -> prefs.tvMenuGroups.firstOrNull { s in it.stops }?.let { MenuStops.group(it.id) } ?: s }
            shown?.takeIf { it !in more && seen.add(it) && (!prefs.easyMode || MenuStops.essential(it)) }
        }.toMutableList()
        val inMenu = stopIds(prefs).toSet()
        val missing = prefs.tvMenuGroups.map { MenuStops.group(it.id) }.filter { it !in seen && it in inMenu } +
            listOfNotNull(MenuStops.MORE.takeIf { more.isNotEmpty() && it !in seen })
        missing.forEach { id -> val free = spots.indexOf(null); if (free >= 0) spots[free] = id else spots += id }
        return spots.map { it?.let { id -> entry(id, prefs) } }
    }
}

/**
 * The menu's stops for these libraries, custom pages and the playlists added to it ([playlists]:
 * id → name); [current] is underlined.
 */
fun menuCatalog(libraries: List<MediaItem>, pages: List<CustomPage>, actions: MenuActions, current: String = MenuStops.HOME, playlists: Map<String, String> = emptyMap()): MenuCatalog {
    val list = buildList {
        add(NavEntry("Search", NavIcons.search, id = MenuStops.SEARCH, onClick = actions.onSearch))
        add(NavEntry("Home", NavIcons.home, id = MenuStops.HOME, onClick = actions.onHome))
        libraries.forEach { lib -> add(NavEntry(lib.name, NavIcons.library(lib), id = MenuStops.library(lib.id)) { actions.onLibrary(lib) }) }
        pages.forEach { p -> add(NavEntry(p.title, NavIcons.pages, id = MenuStops.page(p.id)) { actions.onPage(p.id) }) }
        playlists.forEach { (id, name) -> add(NavEntry(name, NavIcons.playlist, id = MenuStops.playlist(id)) { actions.onPlaylist(id) }) }
        actions.onChannels?.let { add(NavEntry("Channels", NavIcons.channels, id = MenuStops.CHANNELS, onClick = it)) }
        add(NavEntry("Your pages", NavIcons.pages, id = MenuStops.PAGES, onClick = actions.onPages))
        add(NavEntry("Comics", NavIcons.comics, id = MenuStops.COMICS, onClick = actions.onComics))
        add(NavEntry("More", NavIcons.more, id = MenuStops.MORE, onClick = actions.onMore))
        add(NavEntry("Settings", NavIcons.settings, id = MenuStops.SETTINGS, onClick = actions.onSettings))
        add(NavEntry("Switch user", NavIcons.profile, id = MenuStops.PROFILES, onLongClick = actions.onQuickSwitch, onClick = actions.onProfiles))
    }.map { if (it.id == current) it.copy(selected = true) else it }
    val listsLike = libraries.filter { it.collection == CollectionKind.BOX_SETS || it.collection == CollectionKind.PLAYLISTS }.map { it.id }.toSet()
    return MenuCatalog(
        list.associateBy { it.id },
        // Pinned pages are Home's tabs, so they aren't stops by default (they can still be added in Menu stops).
        MenuStops.defaults(libraries.map { it.id }, emptyList()) + playlists.keys.map(MenuStops::playlist),
        nowPlaying = NavEntry("Now playing", NavIcons.music, id = NOW_PLAYING_STOP, onClick = actions.onNowPlaying),
        moreDefaults = MenuStops.defaultMore(list.map { it.id }) { it in listsLike },
        signOut = actions.onSignOut,
    )
}

/** The menu stop that leads back to the listening player (not one the user arranges). */
const val NOW_PLAYING_STOP = "now-playing"

const val CUSTOMIZE_STOP = "customize"

/** The app's menu, provided once by the nav host so every top-level page shows the same one. */
val LocalMenuCatalog = androidx.compose.runtime.staticCompositionLocalOf<MenuCatalog?> { null }
