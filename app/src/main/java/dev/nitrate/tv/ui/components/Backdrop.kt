package dev.nitrate.tv.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.tv.material3.MaterialTheme
import coil3.compose.AsyncImage

/**
 * Full-bleed artwork with gradients so text on the left and rows at the bottom stay readable.
 * Cross-fades when [url] changes.
 */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier, strength: Float = 1f) {
    val bg = MaterialTheme.colorScheme.background
    Crossfade(targetState = url, animationSpec = tween(400), label = "backdrop", modifier = modifier) { target ->
        if (target != null) {
            AsyncImage(
                model = target,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to bg.copy(alpha = 0.92f * strength),
                            0.55f to bg.copy(alpha = 0.35f * strength),
                            1f to Color.Transparent,
                        ),
                    )
                    drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to bg.copy(alpha = 0.95f)))
                },
            )
        }
    }
}
