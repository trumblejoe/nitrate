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

/**
 * What's behind and in front of a page, kept for as long as the page frame shows (so switching tabs
 * never restarts it): the page's backdrop (the focused title's picture, reported by the page with
 * `PageBackdropSlot`, or the colour or gradient chosen for [pageKey]), then the back and middle of
 * its theme and the day's occasion (clear of [insets], where a menu sits; never in the player), the
 * page, and the theme's front over it. Menu pages get it from `MainMenu`; others (More from…) use it
 * directly.
 */
@Composable
fun ThemedPage(pageKey: String?, insets: MenuInsets = MenuInsets(), host: dev.nitrate.tv.ui.pages.CustomizeHost? = null, content: @Composable () -> Unit) {
    // The page's own layout (core PageLooks): everything inside reads it as the display prefs.
    val prefs = dev.nitrate.core.pages.PageLooks.forPage(dev.nitrate.tv.ui.components.LocalDisplayPrefs.current, pageKey)
    val theme = pageKey?.let { prefs.pageThemes[it] }
    val occasion = dev.nitrate.tv.ui.effects.rememberOccasion(prefs.effects)
    val themed = !prefs.easyMode
    val cornerRoom = LocalCornerRoom.current
    val clear = Modifier.padding(start = insets.start, top = insets.top, end = insets.end, bottom = insets.bottom)
    val behind = remember { dev.nitrate.tv.ui.effects.ThemeBehind() }
    val backdrop = pageKey?.let { prefs.pageBackdrops[it] } ?: dev.nitrate.core.effects.PageBackdrop.ARTWORK
    androidx.compose.runtime.CompositionLocalProvider(dev.nitrate.tv.ui.effects.LocalThemeBehind provides behind) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            if (behind.titles > 0 || !backdrop.followsTitle) dev.nitrate.tv.ui.effects.PageBackground(backdrop, behind.artwork, clean = behind.clean)
            if (themed) dev.nitrate.tv.ui.effects.EffectsLayer(theme, occasion, prefs.effects, clear, depths = dev.nitrate.tv.ui.effects.BEHIND)
            // The page's own layout, and the Customize panel held here so it outlives the page's
            // redraws (a row's new style, rows ↔ full-screen artwork).
            val customize = host ?: remember { dev.nitrate.tv.ui.pages.CustomizeHost() }
            androidx.compose.runtime.CompositionLocalProvider(
                dev.nitrate.tv.ui.components.LocalDisplayPrefs provides prefs,
                dev.nitrate.tv.ui.pages.LocalLayoutPage provides pageKey,
                dev.nitrate.tv.ui.pages.LocalCustomizeHost provides customize.takeIf { pageKey != null },
                // The clock and the menu's profile corner (Search, the avatar) both keep the top end clear.
                dev.nitrate.tv.ui.components.LocalClockRoom provides (if (prefs.tvClock) dev.nitrate.tv.ui.components.ClockRoom else 0.dp) + cornerRoom,
            ) { content() }
            // The clock, top end, clear of the menu; the page keeps that corner free (LocalClockRoom).
            if (prefs.tvClock) dev.nitrate.tv.ui.components.PageClock(Modifier.align(androidx.compose.ui.Alignment.TopEnd).then(clear).padding(top = 20.dp, end = 28.dp + cornerRoom))
            if (pageKey != null) dev.nitrate.tv.ui.pages.CustomizeLayer(customize, pageKey)
            if (themed) dev.nitrate.tv.ui.effects.EffectsLayer(theme, occasion, prefs.effects, clear, depths = dev.nitrate.tv.ui.effects.INFRONT)
        }
    }
}
