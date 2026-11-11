package dev.nitrate.mobile.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

// Android-only player pieces. An iOS build provides its own (AVPlayer / VLCKit) behind the same calls.

/** Media3's player view with its touch controls; [onControlsVisible] follows them. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: ExoPlayer, onControlsVisible: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                keepScreenOn = true
                // Nitrate's own Subtitles button goes through the server when needed.
                setShowSubtitleButton(false)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { onControlsVisible(it == android.view.View.VISIBLE) })
            }
        },
        modifier = modifier,
    )
}

/** Full screen and landscape while playing; restores the previous state afterwards. */
@Composable
fun FullscreenPlayback() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousOrientation = activity.requestedOrientation
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previousOrientation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
