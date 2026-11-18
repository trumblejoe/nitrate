package dev.nitrate.tv.ui.effects

import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.nitrate.core.effects.EffectClip

/** The effect clips this build ships (`assets/fx`), read once. */
@Composable
internal fun rememberShippedClips(): Set<String> {
    val context = LocalContext.current
    return remember {
        runCatching { context.assets.list("fx")?.filter { it.endsWith(".mp4") }?.map { it.removeSuffix(".mp4") }?.toSet() }.getOrNull() ?: emptySet()
    }
}

/**
 * A film clip looping silently along the bottom of the screen, fading out towards its top edge so it
 * melts into the page. A TextureView (not a SurfaceView), so Compose can fade and mask it like any
 * other layer. The player is let go when the scene leaves (and so before any player screen opens).
 */
@Composable
fun ClipBand(clip: EffectClip, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(clip.name) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri("asset:///fx/${clip.name}.mp4"))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(
        modifier.fillMaxWidth().fillMaxHeight(clip.band)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color.Black.copy(alpha = 0.85f), 1f to Color.Black), blendMode = BlendMode.DstIn)
            },
    ) {
        AndroidView(factory = { TextureView(it).also { view -> player.setVideoTextureView(view) } }, Modifier.fillMaxSize())
    }
}
