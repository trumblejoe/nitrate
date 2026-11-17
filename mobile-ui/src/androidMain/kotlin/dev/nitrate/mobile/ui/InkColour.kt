package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nitrate.core.rows.InkPalette
import dev.nitrate.core.rows.InkTint
import dev.nitrate.core.rows.RowDisplay

/** What a row's ink colour is called in menus. */
fun RowDisplay.inkColourLabel(): String = when (inkTint) {
    InkTint.SMART -> "Smart"
    InkTint.ACCENT -> "Accent"
    InkTint.CUSTOM -> InkPalette.SWATCHES.firstOrNull { it.second == inkArgb }?.first ?: "Custom"
}

/**
 * Chooses how a Cressida row's ink is coloured: Smart (picked per card so it doesn't clash with
 * the artwork), the accent, or a colour of your own from swatches or the hue and brightness sliders.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InkColourDialog(display: RowDisplay, onSave: (RowDisplay) -> Unit, onDismiss: () -> Unit) {
    var tint by remember { mutableStateOf(display.inkTint) }
    var argb by remember { mutableStateOf(display.inkArgb) }
    var hue by remember { mutableFloatStateOf(InkPalette.hue(display.inkArgb).toFloat()) }
    var light by remember { mutableFloatStateOf(0.9f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ink colour") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkTint.entries.forEach { t -> FilterChip(tint == t, { tint = t }, label = { Text(t.label) }) }
                }
                when (tint) {
                    InkTint.SMART -> Text(
                        "Each card gets its own ink, chosen from its artwork so it stands out without clashing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InkTint.ACCENT -> Text("The app's accent colour.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    InkTint.CUSTOM -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            InkPalette.SWATCHES.forEach { (name, colour) ->
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).background(Color(colour.toInt()))
                                        .border(if (argb == colour) 3.dp else 1.dp, if (argb == colour) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
                                        .pressable({ argb = colour; hue = InkPalette.hue(colour).toFloat() }),
                                )
                            }
                        }
                        Text("Your own", style = MaterialTheme.typography.labelLarge)
                        Box(
                            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                                .background(Brush.horizontalGradient((0..6).map { Color(InkPalette.hsv(it * 60.0 % 360, 0.6, 0.95).toInt()) })),
                        )
                        Slider(hue, { hue = it; argb = InkPalette.hsv(it.toDouble(), 0.6, light.toDouble()) }, valueRange = 0f..359f)
                        Text("Brightness", style = MaterialTheme.typography.labelMedium)
                        Slider(light, { light = it; argb = InkPalette.hsv(hue.toDouble(), 0.6, it.toDouble()) }, valueRange = 0.15f..1f)
                        Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(8.dp)).background(Color(argb.toInt())))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(display.copy(inkTint = tint, inkArgb = argb)) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
