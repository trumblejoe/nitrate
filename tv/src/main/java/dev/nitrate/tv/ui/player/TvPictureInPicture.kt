package dev.nitrate.tv.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

private tailrec fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Android only takes ratios between 1:2.39 and 2.39:1. */
private fun clamp(w: Int, h: Int): Rational? {
    if (w <= 0 || h <= 0) return null
    val v = w.toFloat() / h
    return when {
        v > 2.39f -> Rational(239, 100)
        v < 1 / 2.39f -> Rational(100, 239)
        else -> Rational(w, h)
    }
}

/**
 * Picture-in-picture on the TV: pressing Home while a video plays (and [enabled]) keeps it going
 * in a corner window over the home screen or another app; choosing it brings the player back full
 * screen. On Android 12+ the system does it by itself (auto-enter while playing); before that,
 * leaving the app does it by hand. Returns whether the player is in the small window now, so the
 * screen can leave out its controls and pop-ups.
 */
@Composable
fun rememberTvPictureInPicture(player: Player, enabled: Boolean): State<Boolean> {
    val activity = LocalContext.current.activity()
    val inPip = remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity, player, enabled) {
        val a = activity
        if (a == null || Build.VERSION.SDK_INT < 26 || !a.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return@DisposableEffect onDispose { }
        }
        fun params(): PictureInPictureParams {
            val size = player.videoSize
            val b = PictureInPictureParams.Builder()
            clamp(size.width, size.height)?.let(b::setAspectRatio)
            if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(enabled && player.isPlaying).setSeamlessResizeEnabled(true)
            return b.build()
        }
        fun update() { runCatching { a.setPictureInPictureParams(params()) } }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = update()
            override fun onVideoSizeChanged(videoSize: VideoSize) = update()
        }
        player.addListener(listener)
        update()
        // Before Android 12: Home while playing goes to the small window by hand.
        val leaving = Runnable { if (enabled && player.isPlaying && Build.VERSION.SDK_INT < 31) runCatching { a.enterPictureInPictureMode(params()) } }
        a.addOnUserLeaveHintListener(leaving)
        val modes = Consumer<PictureInPictureModeChangedInfo> { inPip.value = it.isInPictureInPictureMode }
        a.addOnPictureInPictureModeChangedListener(modes)
        onDispose {
            player.removeListener(listener)
            a.removeOnUserLeaveHintListener(leaving)
            a.removeOnPictureInPictureModeChangedListener(modes)
            // Leaving the player: no small window from other pages.
            if (Build.VERSION.SDK_INT >= 31) runCatching { a.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
        }
    }
    return inPip
}

