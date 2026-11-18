package dev.nitrate.tv.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
 * Matches the TV's refresh rate to the video ([FrameRateMatch.ALWAYS]). The TV goes black while it
 * changes mode (its own re-sync, a second or more that no app can shorten), so this keeps that to
 * as few, and as well-placed, moments as it can:
 * - it switches before the video starts, from the frame rate the server reports, instead of
 *   cutting to black once the first frames are on screen (the file's own rate, when it differs,
 *   follows later);
 * - playback waits, behind a quiet "Matching the TV…" screen, until the TV has changed and settled
 *   ([SETTLE_MS]), so nothing is missed;
 * - the TV keeps the rate between players for [KEEP_MS] (the next episode, an extra, Up Next), so
 *   a run of episodes switches once rather than back and forth.
 * [match] is null until the settings have loaded. Returns whether it's switching now, for that screen.
 */
@Composable
fun FrameRateMatcher(session: PlaybackSession, match: FrameRateMatch?): Boolean {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return false
    val fileFps by session.videoFrameRate.collectAsState()
    val plan by session.plan.collectAsState()
    val serverFps = plan?.source?.streams?.firstOrNull { it.kind == dev.nitrate.core.model.StreamKind.VIDEO }?.frameRate?.takeIf { it > 0 }
    // Rounded so the same rate from the server and then the file doesn't run it twice.
    val rate = (fileFps ?: serverFps)?.let { kotlin.math.round(it * 1000) / 1000 }
    var switching by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(match) { match?.let(session::setFrameRateMatch) }
    LaunchedEffect(rate, match) {
        if (Build.VERSION.SDK_INT < 23 || match == null) return@LaunchedEffect
        // Turned off (or to seamless only) during playback: back to the TV's own mode at once.
        if (match != FrameRateMatch.ALWAYS) {
            ModeHold.resetNow(activity)
            return@LaunchedEffect
        }
        ModeHold.claim()
        if (rate == null) return@LaunchedEffect
        val display = activity.windowManager.defaultDisplay ?: return@LaunchedEffect
        val current = display.mode.let { DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val modes = display.supportedModes.map { DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        // What was there to choose from goes in the playback log (Send log), to see why a TV didn't switch.
        fun hz(v: Float) = "%.3f".format(v).trimEnd('0').trimEnd('.')
        session.note("Refresh rate: video ${hz(rate)} fps (${if (fileFps != null) "file" else "server"}), display ${current.width}x${current.height} @ ${hz(current.refreshRate)} Hz (${DisplayModes.cadence(rate, current.refreshRate).name.lowercase()}); " +
            "modes offered: " + modes.groupBy { "${it.width}x${it.height}" }.entries.joinToString("; ") { (size, ms) -> "$size @ " + ms.map { hz(it.refreshRate) }.distinct().joinToString("/") })
        val target = DisplayModes.best(modes, current, rate)
        if (target == null) {
            val judder = DisplayModes.cadence(rate, current.refreshRate) == DisplayModes.Cadence.JUDDER
            session.note(if (!judder) "Refresh rate: already suits the video, no switch" else "Refresh rate: no mode at ${current.width}x${current.height} suits ${hz(rate)} fps, no switch")
            if (judder) session.tell("This TV offers no refresh rate that suits ${hz(rate)} fps at ${current.width}x${current.height}" + if (isFireTv()) ". On Fire TV, check Match Original Frame Rate in Settings → Display & Sounds → Display." else ".")
            return@LaunchedEffect
        }
        session.note("Refresh rate: asking for ${target.width}x${target.height} @ ${hz(target.refreshRate)} Hz (mode ${target.id})")
        val player = session.player
        val wasPlaying = player.playWhenReady
        player.playWhenReady = false
        switching = true
        try {
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
            if (switched) {
                // Android reports the change before the TV shows a picture again: let it settle.
                kotlinx.coroutines.delay(SETTLE_MS)
            } else {
                // Fire TV only lets apps change the rate with its own setting on.
                session.tell(if (isFireTv()) "The TV stayed at ${hz(now.refreshRate)} Hz. On Fire TV, turn on Match Original Frame Rate in Settings → Display & Sounds → Display."
                    else "The TV stayed at ${hz(now.refreshRate)} Hz: this device or TV didn't allow the change.")
            }
        } finally {
            switching = false
            if (wasPlaying) player.playWhenReady = true
        }
    }
    // Leaving the player: the TV keeps the rate a little while, in case another video follows.
    DisposableEffect(activity) { onDispose { ModeHold.release(activity) } }
    return switching
}

/** After Android reports the new mode, how long the TV takes to show a picture again. */
private const val SETTLE_MS = 1_200L

/** How long the TV keeps a matched rate after the player closes (next episode, an extra). */
private const val KEEP_MS = 15_000L

/** The matched rate, kept across players and handed back to the TV a while after the last one. */
private object ModeHold {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun claim() {
        pending?.let(main::removeCallbacks)
        pending = null
    }

    fun release(activity: Activity) {
        claim()
        val reset = Runnable { pending = null; resetNow(activity) }
        pending = reset
        main.postDelayed(reset, KEEP_MS)
    }

    fun resetNow(activity: Activity) {
        claim()
        if (Build.VERSION.SDK_INT >= 23 && activity.window.attributes.preferredDisplayModeId != 0) {
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 0 }
        }
    }
}

/**
 * What shows while the TV changes its refresh rate: dark, the title and a quiet line, instead of a
 * frozen first frame before the black.
 */
@Composable
fun MatchingRefreshRate(title: String?) {
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            title?.let { androidx.tv.material3.Text(it, style = androidx.tv.material3.MaterialTheme.typography.headlineSmall, color = androidx.compose.ui.graphics.Color.White) }
            androidx.tv.material3.Text(
                "Matching the TV to this video…", style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f), modifier = androidx.compose.ui.Modifier.padding(top = 8.dp),
            )
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
