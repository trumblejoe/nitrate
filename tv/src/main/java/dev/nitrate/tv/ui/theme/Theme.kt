package dev.nitrate.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Warm amber accent, like light through nitrate film, on near-black. */
val NitrateAmber = Color(0xFFE8A33D)
val NitrateBackground = Color(0xFF0B0D12)
val NitrateSurface = Color(0xFF161A22)

private val NitrateColors = darkColorScheme(
    primary = NitrateAmber,
    onPrimary = Color(0xFF1E1404),
    secondary = Color(0xFF8FA6C8),
    background = NitrateBackground,
    onBackground = Color(0xFFECE6DC),
    surface = NitrateSurface,
    onSurface = Color(0xFFECE6DC),
    surfaceVariant = Color(0xFF232836),
    onSurfaceVariant = Color(0xFFB9B2A6),
    border = Color(0xFF3A4152),
)

/** Amber on near-black, or the user's accent and true-black choices. */
@Composable
fun NitrateTheme(content: @Composable () -> Unit) {
    val prefs = dev.nitrate.tv.ui.components.LocalDisplayPrefs.current
    var scheme = NitrateColors
    prefs.accent.argb?.let { scheme = scheme.copy(primary = Color(it)) }
    if (prefs.trueBlack) scheme = scheme.copy(background = Color.Black, surface = Color(0xFF0E0E10), surfaceVariant = Color(0xFF1A1A1E))
    MaterialTheme(colorScheme = scheme) {
        // Text outside a tv Surface (most screens, and dialogs) would otherwise default to black.
        androidx.compose.runtime.CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides scheme.onSurface, content = content)
    }
}
