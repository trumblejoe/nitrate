package dev.nitrate.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.nitrate.android.AppContainer
import dev.nitrate.core.pages.DisplayPrefs
import kotlinx.coroutines.flow.flowOf

/** The signed-in user's display preferences (synced with their other devices). */
val LocalDisplayPrefs = compositionLocalOf { DisplayPrefs() }

/** Where the user stands in each show they watch with a group, by series id (empty without any). */
val LocalGroupStandings = compositionLocalOf { emptyMap<String, dev.nitrate.core.partners.KeepUp>() }

/** The line about [item]'s show and the user's group, if it's a shared show. */
@Composable
fun groupStanding(item: dev.nitrate.core.model.MediaItem): dev.nitrate.core.partners.Standing? {
    val series = if (item.kind == dev.nitrate.core.model.ItemKind.SERIES) item.id else item.seriesId ?: return null
    val k = LocalGroupStandings.current[series] ?: return null
    return dev.nitrate.core.partners.WatchPartners.standing(item, k)
}

@Composable
fun ProvideDisplayPrefs(container: AppContainer, userId: String?, content: @Composable () -> Unit) {
    val flow = remember(userId) { userId?.let { container.displaySettings.prefs(it) } ?: flowOf(DisplayPrefs()) }
    val prefs by flow.collectAsState(DisplayPrefs())
    // Easy mode: bigger text (layouts keep their size; TV screens are laid out for a fixed width).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaled = if (prefs.easyMode) androidx.compose.ui.unit.Density(density.density, density.fontScale * 1.2f) else density
    val standings by container.groupStandings.collectAsState()
    CompositionLocalProvider(LocalDisplayPrefs provides prefs, LocalGroupStandings provides standings, androidx.compose.ui.platform.LocalDensity provides scaled) { content() }
}
