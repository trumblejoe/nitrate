package dev.nitrate.tv.ui.home

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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.MediaItem

/** One stop in the side menu. */
data class NavEntry(val label: String, val icon: ImageVector, val selected: Boolean = false, val onClick: () -> Unit)

/** Standard icons for the menu; kept here so screens don't each pick their own. */
object NavIcons {
    val search = Icons.Filled.Search
    val home = Icons.Filled.Home
    val pages = Icons.AutoMirrored.Filled.List
    val together = Icons.Filled.Face
    val settings = Icons.Filled.Settings
    val profile = Icons.Filled.AccountCircle

    fun library(item: MediaItem): ImageVector = when (item.collection) {
        CollectionKind.TV_SHOWS -> Icons.Filled.DateRange
        CollectionKind.MUSIC, CollectionKind.MUSIC_VIDEOS -> Icons.Filled.Favorite
        CollectionKind.LIVE_TV -> Icons.Filled.Notifications
        CollectionKind.BOX_SETS, CollectionKind.PLAYLISTS -> Icons.AutoMirrored.Filled.List
        CollectionKind.PHOTOS, CollectionKind.HOME_VIDEOS -> Icons.Filled.Star
        else -> Icons.Filled.PlayArrow
    }
}

/**
 * The TV's main menu: a rail of icons down the left that opens with labels when focused (press
 * left from the first card). [top] entries sit at the top, [bottom] ones below them after a gap.
 */
@Composable
fun NavRail(top: List<NavEntry>, bottom: List<NavEntry>, content: @Composable () -> Unit) {
    val state = rememberDrawerState(DrawerValue.Closed)
    NavigationDrawer(
        drawerState = state,
        drawerContent = {
            Column(
                Modifier.fillMaxHeight().padding(vertical = 24.dp, horizontal = 8.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                (top + listOf(null) + bottom).forEach { entry ->
                    if (entry == null) Spacer(Modifier.height(20.dp))
                    else NavigationDrawerItem(
                        selected = entry.selected,
                        onClick = entry.onClick,
                        leadingContent = { Icon(entry.icon, contentDescription = null) },
                    ) { Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        },
        content = content,
    )
}
