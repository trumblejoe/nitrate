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

/** The user's watch groups and invites (null: none known, or no watch partners). */
val LocalPartnersInfo = compositionLocalOf<dev.nitrate.core.partners.PartnersInfo?> { null }

/** The line about [item]'s show and the user's group (or an invite to watch it), if there is one. */
@Composable
fun groupStanding(item: dev.nitrate.core.model.MediaItem): dev.nitrate.core.partners.Standing? =
    dev.nitrate.core.partners.TogetherRow.line(item, LocalPartnersInfo.current, LocalGroupStandings.current)

@Composable
fun ProvideDisplayPrefs(container: AppContainer, userId: String?, content: @Composable () -> Unit) {
    val flow = remember(userId) { userId?.let { container.displaySettings.prefs(it) } ?: flowOf(DisplayPrefs()) }
    val prefs by flow.collectAsState(DisplayPrefs())
    // Easy mode: bigger text (layouts keep their size; TV screens are laid out for a fixed width).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaled = if (prefs.easyMode) androidx.compose.ui.unit.Density(density.density, density.fontScale * 1.2f) else density
    val standings by container.groupStandings.collectAsState()
    val partners by container.partners.collectAsState()
    CompositionLocalProvider(LocalDisplayPrefs provides prefs, LocalGroupStandings provides standings, LocalPartnersInfo provides partners, androidx.compose.ui.platform.LocalDensity provides scaled) { content() }
}
