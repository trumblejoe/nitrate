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
        if (Build.VERSION.SDK_INT < 23) return@LaunchedEffect
        // Turned off (or to seamless only) during playback: back to the TV's own mode.
        if (match != FrameRateMatch.ALWAYS) {
            if (activity.window.attributes.preferredDisplayModeId != 0) activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
            return@LaunchedEffect
        }
        val rate = fps ?: return@LaunchedEffect
        val display = activity.windowManager.defaultDisplay ?: return@LaunchedEffect
        val current = display.mode.let { DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val modes = display.supportedModes.map { DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        // What was there to choose from goes in the playback log (Send log), to see why a TV didn't switch.
        fun hz(v: Float) = "%.3f".format(v).trimEnd('0').trimEnd('.')
        session.note("Refresh rate: video ${hz(rate)} fps, display ${current.width}x${current.height} @ ${hz(current.refreshRate)} Hz (${DisplayModes.cadence(rate, current.refreshRate).name.lowercase()}); " +
            "modes offered: " + modes.groupBy { "${it.width}x${it.height}" }.entries.joinToString("; ") { (size, ms) -> "$size @ " + ms.map { hz(it.refreshRate) }.distinct().joinToString("/") })
        val target = DisplayModes.best(modes, current, rate)
        if (target == null) {
            session.note(if (DisplayModes.cadence(rate, current.refreshRate) != DisplayModes.Cadence.JUDDER) "Refresh rate: already suits the video, no switch"
                else "Refresh rate: no mode at ${current.width}x${current.height} suits ${hz(rate)} fps, no switch")
            if (DisplayModes.cadence(rate, current.refreshRate) == DisplayModes.Cadence.JUDDER) {
                session.tell("This TV offers no refresh rate that suits ${hz(rate)} fps at ${current.width}x${current.height}" + if (isFireTv()) ". On Fire TV, check Match Original Frame Rate in Settings → Display & Sounds → Display." else ".")
            }
            return@LaunchedEffect
        }
        session.note("Refresh rate: asking for ${target.width}x${target.height} @ ${hz(target.refreshRate)} Hz (mode ${target.id})")
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
        val switched = withTimeoutOrNull(4_000) { changed.await() } != null || display.mode.modeId == target.id
        manager?.unregisterDisplayListener(listener)
        val now = display.mode
        session.note("Refresh rate: " + if (switched) "switched to ${hz(now.refreshRate)} Hz" else "the system kept ${now.physicalWidth}x${now.physicalHeight} @ ${hz(now.refreshRate)} Hz")
        if (!switched) {
            // Fire TV only lets apps change the rate with its own setting on.
            session.tell(if (isFireTv()) "The TV stayed at ${hz(now.refreshRate)} Hz. On Fire TV, turn on Match Original Frame Rate in Settings → Display & Sounds → Display."
                else "The TV stayed at ${hz(now.refreshRate)} Hz: this device or TV didn't allow the change.")
        }
        if (wasPlaying) player.playWhenReady = true
    }
    DisposableEffect(activity) {
        onDispose {
            // Back to the TV's own choice.
            if (Build.VERSION.SDK_INT >= 23) activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }
}

/** The TV's refresh rate now, followed as it changes (null before Android 6). */
@Composable
fun rememberDisplayHz(): Float? {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return null
    if (Build.VERSION.SDK_INT < 23) return null
    val hz = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(activity.windowManager.defaultDisplay?.mode?.refreshRate) }
    DisposableEffect(activity) {
        val manager = activity.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { hz.value = activity.windowManager.defaultDisplay?.mode?.refreshRate }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterDisplayListener(listener) }
    }
    return hz.value
}

/** Amazon's Fire TV devices, which gate refresh-rate changes behind their own setting. */
private fun isFireTv() = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
