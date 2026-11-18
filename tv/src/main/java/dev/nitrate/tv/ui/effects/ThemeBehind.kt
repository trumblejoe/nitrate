package dev.nitrate.tv.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The back and middle of the page's theme, offered by `MainMenu` to its page. A page with a backdrop
 * of its own (the rows page, full art) draws it just over that backdrop with [ThemeBehindSlot], so the
 * theme sits between the title's picture and the cards; any other page gets it drawn under the whole
 * page instead ([claims] says whether a page has taken it).
 */
class ThemeBehind {
    var draw: @Composable (Modifier) -> Unit by mutableStateOf({ _ -> })
    var claims by mutableIntStateOf(0)
    /** Whether there's a theme or occasion showing (the shade over a scene only goes on then). */
    var active by mutableStateOf(false)
}

val LocalThemeBehind = staticCompositionLocalOf<ThemeBehind?> { null }

/**
 * Where a page draws its theme's back layers: right after its own backdrop, before its text and
 * cards. A soft shade at the top left keeps the focused title's name readable over a busy scene.
 */
@Composable
fun ThemeBehindSlot(modifier: Modifier = Modifier) {
    val behind = LocalThemeBehind.current ?: return
    DisposableEffect(behind) {
        behind.claims++
        onDispose { behind.claims-- }
    }
    Box(modifier.fillMaxSize()) {
        behind.draw(Modifier.fillMaxSize())
        if (behind.active) Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x59000000), Color.Transparent), center = Offset(0f, 0f), radius = 1400f)))
    }
}
