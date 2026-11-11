package dev.nitrate.mobile.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.runtime.remember
import androidx.media3.ui.AspectRatioFrameLayout
import dev.nitrate.android.playback.applySubtitleStyle
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
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

/** What the player's touch gestures ask for; the screen draws the feedback. */
interface PlayerGestures {
    /** Double tap on the left (negative) or right third. */
    fun onSeek(deltaMs: Long)

    /** Double tap in the middle. */
    fun onTogglePlay()

    /** Vertical swipe on the left half, as a fraction of the screen height (up is positive). */
    fun onBrightness(delta: Float)

    /** Vertical swipe on the right half. */
    fun onVolume(delta: Float)

    /** Pinch out (true) to fill the screen, in (false) to fit. */
    fun onZoom(fill: Boolean)

    /** Dragging the seek bar: where it is and where the bar sits on screen, or null when released. */
    fun onScrub(scrub: Scrub?)
}

/** A seek-bar drag: [x] is the thumb's position and [barTop] the bar's top edge, in window pixels. */
data class Scrub(val positionMs: Long, val x: Float, val barTop: Float)

/**
 * Media3's player view with its touch controls; [onControlsVisible] follows them. Gestures are
 * read on the view itself, so the control buttons keep their own taps.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    player: ExoPlayer,
    onControlsVisible: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    gestures: PlayerGestures? = null,
    fill: Boolean = false,
    subtitleStyle: dev.nitrate.core.playback.SubtitleStyle = dev.nitrate.core.playback.SubtitleStyle(),
    /** Hide the player's own controls (e.g. in picture-in-picture). */
    hideControls: Boolean = false,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                keepScreenOn = true
                // Nitrate's own Subtitles button goes through the server when needed.
                setShowSubtitleButton(false)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { onControlsVisible(it == android.view.View.VISIBLE) })
                if (gestures != null) attachGestures(this, gestures)
            }
        },
        update = { view ->
            view.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.applySubtitleStyle(subtitleStyle)
            view.useController = !hideControls
        },
        modifier = modifier,
    )
}

@OptIn(UnstableApi::class)
@SuppressLint("ClickableViewAccessibility")
private fun attachGestures(view: PlayerView, gestures: PlayerGestures) {
    var swiping: Boolean? = null // true = brightness (left), false = volume (right)
    var lastY = 0f
    var scaling = false
    val scale = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }
    })
    var pinchStartSpan = 0f
    val taps = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (view.isControllerFullyVisible) view.hideController() else view.showController()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val third = view.width / 3f
            when {
                e.x < third -> gestures.onSeek(-SEEK_MS)
                e.x > third * 2 -> gestures.onSeek(SEEK_MS)
                else -> gestures.onTogglePlay()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val start = e1 ?: return false
            if (scaling) return false
            if (swiping == null) {
                // Only clearly vertical swipes, away from the top and bottom edges (system gestures, seek bar).
                val edge = view.height * 0.12f
                if (kotlin.math.abs(dy) < kotlin.math.abs(dx) || start.y < edge || start.y > view.height - edge) return false
                swiping = start.x < view.width / 2f
                lastY = start.y
            }
            val delta = (lastY - e2.y) / view.height
            lastY = e2.y
            if (swiping == true) gestures.onBrightness(delta) else gestures.onVolume(delta)
            return true
        }
    })
    view.setOnTouchListener { _, event ->
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) pinchStartSpan = spanOf(event)
            // The overall pinch decides: fingers apart fills the screen, together fits the picture.
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount == 2 && pinchStartSpan > 0f) {
                val end = spanOf(event)
                if (kotlin.math.abs(end - pinchStartSpan) > view.width * 0.08f) gestures.onZoom(end > pinchStartSpan)
                pinchStartSpan = 0f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                swiping = null
                scaling = false
            }
        }
        if (event.pointerCount > 1) true else taps.onTouchEvent(event)
    }
    // Scrubbing the seek bar, for thumbnail previews.
    view.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.addListener(object : TimeBar.OnScrubListener {
        private fun report(bar: View, position: Long) {
            val loc = IntArray(2).also { bar.getLocationInWindow(it) }
            val duration = view.player?.duration?.takeIf { it > 0 } ?: return
            val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
            gestures.onScrub(Scrub(position, loc[0] + bar.paddingLeft + fraction * (bar.width - bar.paddingLeft - bar.paddingRight), loc[1].toFloat()))
        }

        override fun onScrubStart(timeBar: TimeBar, position: Long) = report(timeBar as View, position)
        override fun onScrubMove(timeBar: TimeBar, position: Long) = report(timeBar as View, position)
        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) = gestures.onScrub(null)
    })
}

private fun spanOf(e: MotionEvent): Float {
    if (e.pointerCount < 2) return 0f
    return kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
}

private const val SEEK_MS = 10_000L

/** Screen brightness for this window (0..1), or the system's when not overridden. */
@Composable
fun rememberBrightnessControl(): BrightnessControl {
    val activity = LocalContext.current.findActivity()
    val control = remember(activity) { BrightnessControl(activity) }
    DisposableEffect(control) { onDispose { control.restore() } }
    return control
}

class BrightnessControl internal constructor(private val activity: Activity?) {
    private val original = activity?.window?.attributes?.screenBrightness ?: -1f
    private var current = original.takeIf { it >= 0 } ?: systemBrightness()

    private fun systemBrightness(): Float = activity?.let {
        runCatching { Settings.System.getInt(it.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrNull()
    } ?: 0.5f

    /** Changes brightness by [delta]; returns the new level. */
    fun adjust(delta: Float): Float {
        current = (current + delta).coerceIn(0.01f, 1f)
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = current } }
        return current
    }

    internal fun restore() {
        activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = original } }
    }
}

/** Media volume, as a 0..1 level. */
class VolumeControl(context: Context) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private var level = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max

    fun adjust(delta: Float): Float {
        level = (level + delta).coerceIn(0f, 1f)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
        return level
    }
}

/**
 * Full screen while playing, and landscape unless [lockLandscape] is off (writing beside the
 * video can be done upright, with room for the keyboard). Restores everything afterwards.
 */
@Composable
fun FullscreenPlayback(lockLandscape: Boolean = true) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousOrientation = activity.requestedOrientation
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = previousOrientation
        }
    }
    DisposableEffect(activity, lockLandscape) {
        // Upright is allowed only while writing, and respects the user's rotation lock.
        activity.requestedOrientation = if (lockLandscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        onDispose { }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
