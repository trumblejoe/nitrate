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
import kotlinx.coroutines.flow.first
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
    val currentMatch by androidx.compose.runtime.rememberUpdatedState(match)
    var handled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    LaunchedEffect(match) { match?.let(session::setFrameRateMatch) }
    /**
     * Switches the TV for [rate] if a better mode exists. [pause]: playback is under way (the file's own
     * rate, or the choice changed mid-film), so it's held while the TV changes.
     */
    suspend fun switch(rate: Float, pause: Boolean) {
        // Each rate once (the gate before playing, then the same rate from the plan and the file).
        if (rate == handled) return
        handled = rate
        ModeHold.claim()
        val display = activity.windowManager.defaultDisplay ?: return@switch
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
            return@switch
        }
        session.note("Refresh rate: asking for ${target.width}x${target.height} @ ${hz(target.refreshRate)} Hz (mode ${target.id})")
        val player = session.player
        val wasPlaying = pause && player.playWhenReady
        if (pause) player.playWhenReady = false
        // What the sound output takes untouched now, to know when the receiver is back after the switch.
        val surroundBefore = passthroughEncodings(activity)
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
                // The receiver's abilities come back after the picture: wait for them, so sound set up
                // next is passed through as before (not decoded for the moment the link was down).
                // As long as the device check measured on this TV (plus a margin), or the usual wait;
                // not at all if it found the receiver never takes surround back after a switch.
                val measured = session.deviceCheck()?.refresh?.takeIf { it.switched && it.surroundBefore.isNotEmpty() }
                val measuredBack = measured?.surroundBackMs
                val wait = when {
                    measured == null -> PASSTHROUGH_WAIT_MS
                    measuredBack == null -> 0L
                    else -> (measuredBack - SETTLE_MS + 1_000).coerceIn(1_000, 8_000)
                }
                val waited = awaitPassthrough(activity, surroundBefore, wait)
                if (surroundBefore.isNotEmpty()) session.note(if (waited != null) "Refresh rate: surround passthrough back after ${waited + SETTLE_MS} ms" else "Refresh rate: surround passthrough not back after ${PASSTHROUGH_WAIT_MS + SETTLE_MS} ms")
                // Sound already playing (a switch mid-film) may have gone over to decoding: put it back.
                if (pause) session.recheckAudioOutput()
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
    // Before anything plays (the server's answer is in, nothing is set up yet): switch first, so the
    // sound is set up once the receiver is back, passed through rather than decoded.
    DisposableEffect(session) {
        session.beforePlay = gate@{ p ->
            if (Build.VERSION.SDK_INT < 23) return@gate
            val m = currentMatch ?: kotlinx.coroutines.withTimeoutOrNull(1_500) {
                androidx.compose.runtime.snapshotFlow { currentMatch }.first { it != null }
            }
            if (m != FrameRateMatch.ALWAYS) return@gate
            val fps = p.source.streams.firstOrNull { it.kind == dev.nitrate.core.model.StreamKind.VIDEO }?.frameRate?.takeIf { it > 0 } ?: return@gate
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { switch(kotlin.math.round(fps * 1000) / 1000, pause = false) }
        }
        onDispose { session.beforePlay = null }
    }
    LaunchedEffect(rate, match) {
        if (Build.VERSION.SDK_INT < 23 || match == null) return@LaunchedEffect
        // Turned off (or to seamless only) during playback: back to the TV's own mode at once.
        if (match != FrameRateMatch.ALWAYS) {
            ModeHold.resetNow(activity)
            handled = null
            return@LaunchedEffect
        }
        ModeHold.claim()
        // Usually already done before playing; this catches the file's own rate and a change mid-film.
        rate?.let { switch(it, pause = true) }
    }
    // Leaving the player: the TV keeps the rate a little while, in case another video follows.
    DisposableEffect(activity) { onDispose { ModeHold.release(activity) } }
    return switching
}

/** How long, after the picture, to wait for the receiver's surround abilities to come back. */
private const val PASSTHROUGH_WAIT_MS = 4_000L

/** The surround formats the sound output takes untouched now. */
internal fun passthroughEncodings(context: Context): Set<Int> = runCatching {
    val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context, androidx.media3.common.AudioAttributes.DEFAULT, null)
    listOf(androidx.media3.common.C.ENCODING_AC3, androidx.media3.common.C.ENCODING_E_AC3, androidx.media3.common.C.ENCODING_DTS, androidx.media3.common.C.ENCODING_DOLBY_TRUEHD)
        .filter { caps.supportsEncoding(it) }.toSet()
}.getOrDefault(emptySet())

/** Waits until the output takes [before] untouched again; ms waited, or null if it didn't within [PASSTHROUGH_WAIT_MS]. */
internal suspend fun awaitPassthrough(context: Context, before: Set<Int>, waitMs: Long = PASSTHROUGH_WAIT_MS): Long? {
    if (before.isEmpty()) return 0
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < waitMs) {
        if (passthroughEncodings(context).containsAll(before)) return System.currentTimeMillis() - start
        kotlinx.coroutines.delay(200)
    }
    return null
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
internal fun isFireTv() = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}


/**
 * The device check's refresh-rate test: switches the TV to suit a film (23.976 or 24 fps), times
 * how long the change takes, and checks whether the sound output takes surround formats untouched
 * again afterwards (and when), then puts the TV back. Null before Android 6.
 */
internal suspend fun refreshCheck(activity: Activity): dev.nitrate.core.playback.RefreshCheck? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
    if (Build.VERSION.SDK_INT < 23) return@withContext null
    val display = activity.windowManager.defaultDisplay ?: return@withContext null
    fun mode(m: android.view.Display.Mode) = DisplayMode(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate)
    val current = mode(display.mode)
    val modes = display.supportedModes.map(::mode)
    val target = DisplayModes.best(modes, current, 23.976f) ?: DisplayModes.best(modes, current, 24f)
        ?: return@withContext dev.nitrate.core.playback.RefreshCheck(current.refreshRate, current.refreshRate, switched = false, surroundBefore = passthroughEncodings(activity))
    val manager = activity.getSystemService(DisplayManager::class.java)
    suspend fun switchTo(modeId: Int, expect: Int): Long? {
        val changed = CompletableDeferred<Unit>()
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) { if (displayId == display.displayId && display.mode.modeId == expect) changed.complete(Unit) }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        val start = System.currentTimeMillis()
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = modeId }
        val ok = withTimeoutOrNull(5_000) { changed.await() } != null || display.mode.modeId == expect
        manager?.unregisterDisplayListener(listener)
        return if (ok) System.currentTimeMillis() - start else null
    }
    val before = passthroughEncodings(activity)
    val took = switchTo(target.id, target.id)
    var back: Long? = null
    if (took != null) {
        kotlinx.coroutines.delay(SETTLE_MS)
        back = awaitPassthrough(activity, before)?.plus(SETTLE_MS)
    }
    // Back to how it was.
    switchTo(0, current.id)
    kotlinx.coroutines.delay(SETTLE_MS)
    dev.nitrate.core.playback.RefreshCheck(
        fromHz = current.refreshRate, toHz = target.refreshRate, switched = took != null,
        blackMs = took?.plus(SETTLE_MS), surroundBefore = before, surroundBackMs = back,
    )
}
