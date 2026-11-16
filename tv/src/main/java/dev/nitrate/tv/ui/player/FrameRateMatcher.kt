package dev.nitrate.tv.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import dev.nitrate.android.playback.PlaybackSession
import dev.nitrate.core.playback.DisplayMode
import dev.nitrate.core.playback.DisplayModes
import dev.nitrate.core.playback.FrameRateMatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Matches the TV's refresh rate to the video ([FrameRateMatch.ALWAYS]): once the frame rate is
 * known, switches to the display mode at the same resolution whose rate is a whole multiple of it,
 * holding playback while the TV changes mode (a second or two of blank screen), and goes back to
 * the TV's own mode on leaving the player. Seamless-only matching is left to the player.
 */
@Composable
fun FrameRateMatcher(session: PlaybackSession, match: FrameRateMatch) {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return
    val fps by session.videoFrameRate.collectAsState()
    LaunchedEffect(match) { session.setFrameRateMatch(match) }
    LaunchedEffect(fps, match) {
        val rate = fps ?: return@LaunchedEffect
        if (match != FrameRateMatch.ALWAYS || Build.VERSION.SDK_INT < 23) return@LaunchedEffect
        val display = activity.windowManager.defaultDisplay ?: return@LaunchedEffect
        val current = display.mode.let { DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val modes = display.supportedModes.map { DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val target = DisplayModes.best(modes, current, rate) ?: return@LaunchedEffect
        val player = session.player
        val wasPlaying = player.playWhenReady
        player.playWhenReady = false
        val changed = CompletableDeferred<Unit>()
        val manager = activity.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { if (displayId == display.displayId && display.mode.modeId == target.id) changed.complete(Unit) }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = target.id }
        // Some TVs never report the change: carry on after a few seconds either way.
        withTimeoutOrNull(4_000) { changed.await() }
        manager?.unregisterDisplayListener(listener)
        if (wasPlaying) player.playWhenReady = true
    }
    DisposableEffect(activity) {
        onDispose {
            // Back to the TV's own choice.
            if (Build.VERSION.SDK_INT >= 23) activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
