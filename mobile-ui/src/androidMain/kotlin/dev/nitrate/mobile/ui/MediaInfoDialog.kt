package dev.nitrate.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Two overlapping sheets: "copy". */
val CopyIcon: ImageVector by lazy {
    ImageVector.Builder("Copy", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            // Back sheet: an L along the top and left.
            moveTo(16f, 1f); horizontalLineTo(4f); curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f); verticalLineTo(17f)
            horizontalLineTo(4f); verticalLineTo(3f); horizontalLineTo(16f); close()
            // Front sheet, as an outline.
            moveTo(19f, 5f); horizontalLineTo(8f); curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f); verticalLineTo(21f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f); horizontalLineTo(19f); curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f); curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f); close()
            moveTo(19f, 21f); horizontalLineTo(8f); verticalLineTo(7f); horizontalLineTo(19f); close()
        }
    }.build()
}

/**
 * Extended media information as text: scrollable, selectable, and copied whole with the copy
 * button, for troubleshooting. [text] null while it's being worked out.
 */
@Composable
fun MediaInfoDialog(text: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Media info", modifier = Modifier.weight(1f))
                IconButton(onClick = { text?.let { copyToClipboard(context, it, "Nitrate media info"); copied = true } }, enabled = text != null) {
                    Icon(CopyIcon, "Copy media info")
                }
            }
        },
        text = {
            Column {
                if (copied) Text("Copied", style = MaterialTheme.typography.labelMedium, color = accentColor(), modifier = Modifier.padding(bottom = 6.dp))
                if (text == null) {
                    Text("Reading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    SelectionContainer {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
