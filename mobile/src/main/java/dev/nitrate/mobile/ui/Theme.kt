package dev.nitrate.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Warm amber accent, like light through nitrate film. Follows the system's light/dark setting. */
private val Dark = darkColorScheme(
    primary = Color(0xFFE8A33D),
    onPrimary = Color(0xFF1E1404),
    primaryContainer = Color(0xFF4A3410),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFF8FA6C8),
    secondaryContainer = Color(0xFF3A3020),
    onSecondaryContainer = Color(0xFFFFDDB0),
    tertiary = Color(0xFF8FA6C8),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFECE6DC),
    surface = Color(0xFF0B0D12),
    onSurface = Color(0xFFECE6DC),
    surfaceVariant = Color(0xFF232836),
    onSurfaceVariant = Color(0xFFB9B2A6),
    surfaceContainer = Color(0xFF161A22),
    surfaceContainerHigh = Color(0xFF1D222C),
    outline = Color(0xFF3A4152),
)

private val Light = lightColorScheme(
    primary = Color(0xFF8A5300),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF2C1700),
    secondary = Color(0xFF4F6280),
    secondaryContainer = Color(0xFFF3E1C4),
    onSecondaryContainer = Color(0xFF2C1700),
    tertiary = Color(0xFF4F6280),
    background = Color(0xFFFAF8F4),
    onBackground = Color(0xFF1B1A17),
    surface = Color(0xFFFAF8F4),
    onSurface = Color(0xFF1B1A17),
    surfaceVariant = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFF5D574D),
    surfaceContainer = Color(0xFFF1ECE4),
    surfaceContainerHigh = Color(0xFFEBE5DC),
    outline = Color(0xFFCFC6B8),
)

@Composable
fun NitrateTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
