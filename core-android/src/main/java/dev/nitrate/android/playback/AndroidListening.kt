package dev.nitrate.android.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.nitrate.android.storage.PlaybackSettingsStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.music.ListenRules
import dev.nitrate.core.music.ListenState
import dev.nitrate.core.music.ListenStream
import dev.nitrate.core.music.Listening
import dev.nitrate.core.music.Loudness
import dev.nitrate.core.music.Repeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import dev.nitrate.core.model.MediaItem as Item

/**
 * The listening player on Android: one ExoPlayer playlist, so songs follow each other without gaps
 * (Media3 trims encoder padding from MP3, AAC and FLAC), the FFmpeg decoders for formats the device
 * lacks, music audio focus (pausing for calls, ducking for navigation, pausing when headphones come
 * out), loudness levelling as player volume, and reports to the server per song. A song that fails
 * as it is plays converted instead; one that fails converted is skipped.
 *
 * [onPlayer] hands the player to whatever keeps it going in the background with a media
 * notification (the phone's service) whenever playing starts.
 */
@OptIn(UnstableApi::class)
class AndroidListening(
    private val context: Context,
    private val server: MediaServer,
    private val settings: PlaybackSettingsStore,
    /** Downloaded songs, played from the device (null: always from the server). */
    private val downloads: dev.nitrate.core.music.ListenDownloads? = null,
) : Listening {
    /** Called with the player whenever listening starts, for the background service and its notification. */
    var onPlayer: ((Player) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(ListenState())
    override val state: StateFlow<ListenState> = _state.asStateFlow()

    /** The songs by id, with what the player knows of each (converted, its play session). */
    private val songs = HashMap<String, Item>()
    private val converted = HashSet<String>()
    private val sessions = HashMap<String, String>()
    private val albumGains = HashMap<String, Double?>()
    private var reported: Pair<String, String>? = null
    private val leftAt = HashMap<String, Long>()
    private val localSongs = HashSet<String>()
    private var reporter: Job? = null

    private var authHeader = server.authorizationHeader()

    val player: ExoPlayer by lazy {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        // The sign-in is read per request, so switching accounts never leaves a stale one.
        val signed = androidx.media3.datasource.ResolvingDataSource.Factory(http) { spec ->
            spec.withAdditionalHeaders(mapOf("Authorization" to authHeader))
        }
        ExoPlayer.Builder(context)
            .setRenderersFactory(
                DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                    // Nitrate's FFmpeg only for audio the device has no decoder for.
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, signed)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        // Where a song was left (skipped part way, or played to its end), for its "stopped" report:
        // a skipped song mustn't be counted as played.
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val left = oldPosition.mediaItem?.mediaId ?: return
            if (left != newPosition.mediaItem?.mediaId) leftAt[left] = oldPosition.positionMs
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            level()
            reportSwitch()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            reportProgress()
            if (isPlaying) onPlayer?.invoke(player)
        }

        override fun onPlayerError(error: PlaybackException) {
            val index = player.currentMediaItemIndex
            val id = player.currentMediaItem?.mediaId ?: return
            val song = songs[id]
            if (song != null && id !in converted) {
                // As it is didn't work here: the same song converted, from where it was.
                converted += id
                val at = player.currentPosition
                player.replaceMediaItem(index, mediaItem(song))
                player.seekTo(index, at)
                player.prepare()
                player.play()
                return
            }
            // Converted didn't work either: on to the next one.
            _state.value = _state.value.copy(problem = "${song?.name ?: "A song"} couldn't be played, so it was skipped.")
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    init {
        // A levelling change applies to the song playing.
        scope.launch { settings.settings.map { it.levelling }.distinctUntilChanged().collect { level() } }
    }

    override fun positionMs(): Long = if (_state.value.active) player.currentPosition else 0

    override fun play(items: List<Item>, startIndex: Int, shuffle: Boolean, startMs: Long) {
        val playable = items.filter { it.kind == dev.nitrate.core.model.ItemKind.AUDIO || it.kind == dev.nitrate.core.model.ItemKind.AUDIOBOOK }
        if (playable.isEmpty()) return
        reportStopped()
        authHeader = server.authorizationHeader()
        songs.clear(); converted.clear(); sessions.clear()
        playable.forEach { remember(it) }
        val start = items.getOrNull(startIndex)?.let { s -> playable.indexOfFirst { it.id == s.id } }?.coerceAtLeast(0) ?: 0
        player.shuffleModeEnabled = shuffle
        player.setMediaItems(playable.map(::mediaItem), if (shuffle) C.INDEX_UNSET else start, startMs.coerceAtLeast(0))
        // Books: their chapters (rows don't carry them) and their own speed; music plays at 1×.
        val books = playable.filter(dev.nitrate.core.music.Audiobooks::isBook)
        player.setPlaybackSpeed(if (books.isEmpty()) 1f else bookSpeed)
        books.forEach { book ->
            if (book.chapters.isEmpty()) scope.launch {
                runCatching { server.getItem(book.id) }.getOrNull()?.let { full -> songs[book.id] = full.copy(user = book.user); publish() }
            }
        }
        if (shuffle) player.seekTo(player.currentTimeline.getFirstWindowIndex(true), 0)
        player.prepare()
        player.play()
        _state.value = _state.value.copy(problem = null)
        publish()
    }

    override fun playNext(items: List<Item>) {
        if (!_state.value.active) return play(items)
        items.forEach(::remember)
        player.addMediaItems(player.currentMediaItemIndex + 1, items.map(::mediaItem))
    }

    override fun addToQueue(items: List<Item>) {
        if (!_state.value.active) return play(items)
        items.forEach(::remember)
        player.addMediaItems(items.map(::mediaItem))
    }

    override fun toggle() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0); player.prepare(); player.play() } }
    override fun pause() { if (_state.value.active) player.pause() }
    override fun next() {
        // In a book: the next chapter.
        chapters()?.let { c -> dev.nitrate.core.music.Audiobooks.nextChapterStart(c, player.currentPosition)?.let { return player.seekTo(it) } }
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    override fun previous() {
        chapters()?.let { c -> return player.seekTo(dev.nitrate.core.music.Audiobooks.previousChapterStart(c, player.currentPosition)) }
        if (player.currentPosition > ListenRules.RESTART_AFTER_MS || !player.hasPreviousMediaItem()) player.seekTo(0) else player.seekToPreviousMediaItem()
    }

    override fun skipBy(ms: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + ms).coerceIn(0, duration))
    }

    /** The speed books play at, kept for the next book. */
    private var bookSpeed = 1f

    override fun setSpeed(speed: Float) {
        bookSpeed = speed
        player.setPlaybackSpeed(speed)
        publish()
    }

    private var sleeper: Job? = null
    private var sleepMessage: androidx.media3.exoplayer.PlayerMessage? = null

    override fun setSleep(sleep: dev.nitrate.core.music.Sleep?) {
        sleeper?.cancel()
        sleepMessage?.cancel()
        sleepMessage = null
        val endsAt = (sleep as? dev.nitrate.core.music.Sleep.After)?.let { System.currentTimeMillis() + it.minutes * 60_000L }
        _state.value = _state.value.copy(sleep = sleep, sleepEndsAtMs = endsAt)
        fun fallAsleep() {
            player.pause()
            sleepMessage = null
            _state.value = _state.value.copy(sleep = null, sleepEndsAtMs = null)
        }
        when (sleep) {
            null -> Unit
            is dev.nitrate.core.music.Sleep.After -> sleeper = scope.launch { delay(sleep.minutes * 60_000L); fallAsleep() }
            dev.nitrate.core.music.Sleep.EndOfChapter -> {
                // The player itself stops right at the chapter's (or song's) end: no polling to fall behind.
                val c = chapters()
                val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: songs[player.currentMediaItem?.mediaId]?.runtimeMs ?: Long.MAX_VALUE
                val end = if (c != null) dev.nitrate.core.music.Audiobooks.chapterEnd(c, player.currentPosition, duration) else duration
                if (end == Long.MAX_VALUE) {
                    // Length not known yet: stop as this item ends instead.
                    val item = player.currentMediaItemIndex
                    sleeper = scope.launch { while (isActive && player.currentMediaItemIndex == item && player.playbackState != Player.STATE_ENDED) delay(250); fallAsleep() }
                } else {
                    sleepMessage = player.createMessage { _, _ -> fallAsleep() }
                        .setPosition(player.currentMediaItemIndex, (end - 100).coerceAtLeast(0))
                        .setLooper(android.os.Looper.getMainLooper())
                        .setDeleteAfterDelivery(true)
                        .send()
                }
            }
        }
    }

    /** The current book's chapters, when what's playing is a book that has some. */
    private fun chapters(): List<dev.nitrate.core.model.Chapter>? =
        player.currentMediaItem?.mediaId?.let(songs::get)?.takeIf(dev.nitrate.core.music.Audiobooks::isBook)?.chapters?.takeIf { it.isNotEmpty() }

    override fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))
    override fun skipTo(index: Int) {
        val window = windowAt(index) ?: return
        player.seekTo(window, 0)
        player.play()
    }

    override fun setShuffle(on: Boolean) { player.shuffleModeEnabled = on; publish() }

    override fun setRepeat(repeat: Repeat) {
        player.repeatMode = when (repeat) { Repeat.OFF -> Player.REPEAT_MODE_OFF; Repeat.ALL -> Player.REPEAT_MODE_ALL; Repeat.ONE -> Player.REPEAT_MODE_ONE }
        publish()
    }

    override fun remove(index: Int) { windowAt(index)?.let { player.removeMediaItem(it) } }

    override fun move(from: Int, to: Int) {
        val a = windowAt(from) ?: return
        val b = windowAt(to) ?: return
        player.moveMediaItem(a, b)
    }

    override fun stop() {
        setSleep(null)
        reportStopped()
        player.stop()
        player.clearMediaItems()
        songs.clear()
        _state.value = ListenState()
    }

    private fun remember(item: Item) { songs[item.id] = item; sessions.getOrPut(item.id) { UUID.randomUUID().toString().replace("-", "") } }

    private fun mediaItem(item: Item): MediaItem {
        // Downloaded: from the device, online or not.
        val local = downloads?.file(item.id)?.takeIf { item.id !in converted }
        if (local != null) localSongs += item.id else localSongs -= item.id
        val convert = local == null && (item.id in converted || !ListenStream.direct(item))
        if (convert) converted += item.id
        val art = server.imageUrl(item, ImageKind.PRIMARY, 600)
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(local?.let { Uri.fromFile(java.io.File(it)) } ?: Uri.parse(server.listenUrl(item.id, convert, sessions.getOrPut(item.id) { UUID.randomUUID().toString().replace("-", "") })))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.name)
                    .setArtist(item.artists.joinToString(", ").ifEmpty { null })
                    .setAlbumTitle(item.album)
                    .setArtworkUri(art?.let(Uri::parse))
                    .setMediaType(if (dev.nitrate.core.music.Audiobooks.isBook(item)) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
    }

    /** The queue as the user sees it (shuffled order when shuffling), mapped to the player's windows. */
    private fun order(): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
        while (i != C.INDEX_UNSET && out.size < timeline.windowCount) {
            out += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
        }
        return out
    }

    private fun windowAt(index: Int): Int? = order().getOrNull(index)

    private fun publish() {
        val windows = order()
        val queue = windows.mapNotNull { w -> player.getMediaItemAt(w).mediaId.let(songs::get) }
        val index = windows.indexOf(player.currentMediaItemIndex)
        _state.value = _state.value.copy(
            queue = queue,
            index = if (queue.isEmpty()) -1 else index,
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: (_state.value.current?.runtimeMs ?: 0),
            shuffle = player.shuffleModeEnabled,
            repeat = when (player.repeatMode) { Player.REPEAT_MODE_ALL -> Repeat.ALL; Player.REPEAT_MODE_ONE -> Repeat.ONE; else -> Repeat.OFF },
            speed = player.playbackParameters.speed,
        )
    }

    /** The song's volume from its (or its album's) loudness. */
    private fun level() {
        val song = player.currentMediaItem?.mediaId?.let(songs::get) ?: return
        // A book is one voice throughout: no levelling.
        if (dev.nitrate.core.music.Audiobooks.isBook(song)) { player.volume = 1f; return }
        scope.launch {
            val mode = settings.current().levelling
            val albumGain = if (mode == dev.nitrate.core.music.Levelling.ALBUM) song.albumId?.let { id ->
                albumGains.getOrPut(id) { runCatching { server.getItem(id).normalizationGain }.getOrNull() }
            } else null
            // Still the same song (a quick skip may have moved on).
            if (player.currentMediaItem?.mediaId == song.id) player.volume = Loudness.volume(Loudness.gainDb(song, albumGain, mode))
        }
    }

    // ---- Reports: started, how it's going every few seconds, stopped. ----

    private fun reportSwitch() {
        reportStopped()
        val id = player.currentMediaItem?.mediaId ?: return
        val session = sessions[id] ?: return
        reported = id to session
        scope.launch { server.reportListen(id, session, player.currentPosition, paused = !player.playWhenReady, converted = id in converted, start = true) }
        reporter?.cancel()
        reporter = scope.launch {
            while (isActive) {
                delay(ListenRules.REPORT_EVERY_MS)
                if (player.isPlaying) reportProgress()
            }
        }
    }

    private fun reportProgress() {
        val (id, session) = reported ?: return
        if (player.currentMediaItem?.mediaId != id) return
        scope.launch { server.reportListen(id, session, player.currentPosition, paused = !player.isPlaying, converted = id in converted, start = false) }
    }

    private fun reportStopped() {
        val (id, session) = reported ?: return
        reported = null
        reporter?.cancel()
        val at = leftAt.remove(id) ?: if (player.currentMediaItem?.mediaId == id) player.currentPosition else 0
        server.reportListenStopped(id, session, at)
        // A downloaded song also keeps its play on the device, for the server when it's next reachable.
        if (id in localSongs) songs[id]?.let { song ->
            val duration = song.runtimeMs ?: 0
            scope.launch { runCatching { downloads?.played(id, at, duration, ended = duration > 0 && at >= duration - 2_000) } }
        }
        // A song played again later is a new play.
        sessions[id] = UUID.randomUUID().toString().replace("-", "")
    }
}
