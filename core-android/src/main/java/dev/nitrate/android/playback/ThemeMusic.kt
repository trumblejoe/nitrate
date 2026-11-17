package dev.nitrate.android.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.nitrate.core.MediaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A title's theme music, played quietly behind its details page (as Jellyfin's own apps do).
 * Shared by every details page: going from a series to a season or an episode keeps the same
 * song playing; leaving the details pages, or starting playback, fades it out.
 */
@OptIn(UnstableApi::class)
class AndroidThemeMusic(context: Context, private val server: MediaServer) : ThemeMusic {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var playingSong: String? = null
    private var owner: String? = null
    private var stopJob: Job? = null
    private var startJob: Job? = null
    private val songs = mutableMapOf<String, String?>()

    /** A details page for [itemId] is showing: play its theme (or its series'), if it has one. */
    override fun show(itemId: String) {
        owner = itemId
        stopJob?.cancel()
        startJob?.cancel()
        startJob = scope.launch {
            val song = if (songs.containsKey(itemId)) songs[itemId] else runCatching { server.themeSongs(itemId).firstOrNull()?.id }.getOrNull().also { songs[itemId] = it }
            if (song == null) { fadeOut(); return@launch }
            if (song == playingSong && player?.isPlaying == true) return@launch
            start(song)
        }
    }

    /** The details page for [itemId] went away: stop soon, unless another details page takes over. */
    override fun hide(itemId: String) {
        if (owner != itemId) return
        owner = null
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(HANDOVER_MS)
            if (owner == null) fadeOut()
        }
    }

    /** Something else needs the sound (playback starting): fade out now. */
    override fun stop() {
        owner = null
        startJob?.cancel()
        stopJob?.cancel()
        scope.launch { fadeOut() }
    }

    private suspend fun start(songId: String) {
        val p = player ?: ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(appContext).setDataSourceFactory(
                    DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to server.authorizationHeader())),
                ),
            )
            .build().also {
                // Never takes audio focus from (or ducks) anything else.
                it.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                player = it
            }
        if (playingSong != null && p.isPlaying) fadeOut()
        playingSong = songId
        p.setMediaItem(MediaItem.fromUri(server.audioStreamUrl(songId)))
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = 0f
        p.prepare()
        p.play()
        // Fade in to a quiet level.
        for (step in 1..FADE_STEPS) {
            p.volume = VOLUME * step / FADE_STEPS
            delay(FADE_IN_MS / FADE_STEPS)
        }
    }

    private suspend fun fadeOut() {
        val p = player ?: return
        if (p.isPlaying) {
            val from = p.volume
            for (step in FADE_STEPS - 1 downTo 0) {
                p.volume = from * step / FADE_STEPS
                delay(FADE_OUT_MS / FADE_STEPS)
            }
        }
        p.stop()
        playingSong = null
    }

    private var backgrounded: String? = null

    /** Nitrate went out of view: quiet until it's back (then the page still open plays again). */
    override fun onAppHidden() {
        backgrounded = owner
        stop()
    }

    override fun onAppShown() {
        backgrounded?.let(::show)
        backgrounded = null
    }

    /** Lets go of the player (the app is closing, or signing out). */
    override fun release() {
        stop()
        scope.launch {
            delay(FADE_OUT_MS + 50)
            player?.release()
            player = null
        }
    }

    companion object {
        const val VOLUME = 0.3f
        const val FADE_STEPS = 12
        const val FADE_IN_MS = 1_500L
        const val FADE_OUT_MS = 700L
        /** Long enough to open the next details page without the music stopping. */
        const val HANDOVER_MS = 700L
    }
}
