package dev.nitrate.mobile.platform

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Rational
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/** The player that background playback and the media notification control. */
object BackgroundPlayback {
    @Volatile
    internal var player: Player? = null
    private var controller: ListenableFuture<MediaController>? = null

    /** Keeps [player] going with a media notification while the app is in the background. */
    fun start(context: Context, player: Player) {
        this.player = player
        if (controller != null) return
        // Connecting a controller starts the service, which shows the notification while playing.
        controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    }

    /** Back in the app, or playback ended: the notification and service go away. */
    fun stop() {
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        player = null
    }
}

/** Hosts the media session for background audio: notification, lock screen and headset controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val player = BackgroundPlayback.player ?: return null
        val existing = session
        if (existing != null && existing.player === player) return existing
        existing?.release()
        return MediaSession.Builder(this, player).build().also { session = it }
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Swiping the app away stops playback rather than leaving audio running.
        session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

/** Whether the app is showing as a picture-in-picture window. */
@Composable
fun rememberPictureInPicture(): State<Boolean> {
    val activity = LocalContext.current.findActivity() as? ComponentActivity
    val state = remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { state.value = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return state
}

/**
 * Lets the player shrink into a corner window: automatically when leaving the app on Android 12+
 * (while [enabled] and playing), or right away with [enterNow].
 */
class PictureInPicture(private val activity: Activity?) {
    fun update(enabled: Boolean, playing: Boolean, aspect: Rational?) {
        val a = activity ?: return
        if (Build.VERSION.SDK_INT < 26) return
        val builder = PictureInPictureParams.Builder().apply { aspect?.let { setAspectRatio(clamp(it)) } }
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(enabled && playing).setSeamlessResizeEnabled(true)
        runCatching { a.setPictureInPictureParams(builder.build()) }
    }

    fun enterNow(aspect: Rational?): Boolean {
        val a = activity ?: return false
        if (Build.VERSION.SDK_INT < 26) return false
        val builder = PictureInPictureParams.Builder().apply { aspect?.let { setAspectRatio(clamp(it)) } }
        return runCatching { a.enterPictureInPictureMode(builder.build()) }.getOrDefault(false)
    }

    /** Android only accepts ratios between 1:2.39 and 2.39:1. */
    private fun clamp(r: Rational): Rational {
        val v = r.toFloat()
        return when {
            v > 2.39f -> Rational(239, 100)
            v < 1 / 2.39f -> Rational(100, 239)
            else -> r
        }
    }

    fun disable() = update(enabled = false, playing = false, aspect = null)
}

@Composable
fun rememberPictureInPictureController(): PictureInPicture {
    val activity = LocalContext.current.findActivity()
    return remember(activity) { PictureInPicture(activity) }
}
