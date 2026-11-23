package dev.nitrate.ios

import dev.nitrate.android.storage.PlaybackSettingsStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.music.Audiobooks
import dev.nitrate.core.music.ListenDownloads
import dev.nitrate.core.music.ListenQueue
import dev.nitrate.core.music.ListenRules
import dev.nitrate.core.music.ListenState
import dev.nitrate.core.music.ListenStream
import dev.nitrate.core.music.Listening
import dev.nitrate.core.music.Levelling
import dev.nitrate.core.music.Loudness
import dev.nitrate.core.music.Repeat
import dev.nitrate.core.music.Sleep
import dev.nitrate.mobile.platform.NowPlaying
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
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

/**
 * The listening player on iPhone and iPad, as Android's: one queue (core [ListenQueue], in playing
 * order) played by an `AVQueuePlayer` with the next song already loaded, so songs follow each other
 * with hardly a gap; the sign-in sent with every request; a song that fails as it is plays converted
 * instead, one that fails converted is skipped; loudness levelling as player volume; reports per
 * song; audiobooks with their speed, ±15/30 s, chapters (Next/Previous), sleep timer and resuming;
 * the lock screen and Control Center through [NowPlaying]. Playing on with the screen locked needs
 * the `audio` background mode (iosApp/project.yml).
 */
class IosListening(
    private val server: MediaServer,
    private val settings: PlaybackSettingsStore,
    private val downloads: ListenDownloads? = null,
) : Listening {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(ListenState())
    override val state: StateFlow<ListenState> = _state.asStateFlow()

    private val player = AVQueuePlayer()
    private val queue = ListenQueue()
    private var repeat = Repeat.OFF

    private val converted = HashSet<String>()
    private val sessions = HashMap<String, String>()
    private val albumGains = HashMap<String, Double?>()
    private val localSongs = HashSet<String>()
    /** Each loaded AVPlayerItem's song. */
    private val loaded = HashMap<AVPlayerItem, String>()
    /** The song queued after the one on: its position and its item. */
    private var preloaded: Pair<Int, AVPlayerItem>? = null
    private var reported: Pair<String, String>? = null
    private var reporter: Job? = null
    private var leftAt: Pair<String, Long>? = null
    private var bookSpeed = 1f
    private var speed = 1f
    private var wantsPlaying = false

    private var sleeper: Job? = null
    /** Pause when the position reaches this (end of chapter); [Long.MAX_VALUE]: at the song's end. */
    private var sleepAtMs: Long? = null

    private val controls = object : NowPlaying.Controls {
        override fun play() { if (!player.isPlaying()) toggle() }
        override fun pause() = this@IosListening.pause()
        override fun toggle() = this@IosListening.toggle()
        override fun next() = this@IosListening.next()
        override fun previous() = this@IosListening.previous()
        override fun seekTo(ms: Long) = this@IosListening.seekTo(ms)
        override fun skipBy(ms: Long) = this@IosListening.skipBy(ms)
        override val skipMs: Pair<Long, Long>? get() = if (Audiobooks.isBook(queue.current)) Audiobooks.BACK_MS to Audiobooks.FORWARD_MS else null
    }

    init {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, null, NSOperationQueue.mainQueue) { note ->
            (note?.`object` as? AVPlayerItem)?.let(::ended)
        }
        center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, null, NSOperationQueue.mainQueue) { note ->
            (note?.`object` as? AVPlayerItem)?.takeIf { it == player.currentItem }?.let { failed() }
        }
        // A levelling change applies to the song playing.
        scope.launch { settings.settings.map { it.levelling }.distinctUntilChanged().collect { level() } }
        // The state, the sleep timer's chapter end, failures the notifications miss, and the lock screen.
        scope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                if (!_state.value.active) continue
                if (player.currentItem?.status == AVPlayerItemStatusFailed) failed()
                sleepAtMs?.let { at -> if (at != Long.MAX_VALUE && positionMs() >= at - 150) fallAsleep() }
                // Apple's player drops to 1× on its own after a stall: put the book's speed back.
                if (player.isPlaying() && speed != 1f && player.rate != speed) player.rate = speed
                publish(lockScreen = ++tick % 10 == 0)
            }
        }
    }

    override fun positionMs(): Long {
        if (player.currentItem == null) return 0
        val s = CMTimeGetSeconds(player.currentTime())
        return if (s.isNaN()) 0 else (s * 1000).toLong()
    }

    override fun play(items: List<MediaItem>, startIndex: Int, shuffle: Boolean, startMs: Long) {
        val playable = items.filter { it.kind == ItemKind.AUDIO || it.kind == ItemKind.AUDIOBOOK }
        if (playable.isEmpty()) return
        reportStopped()
        converted.clear(); sessions.clear()
        val start = items.getOrNull(startIndex)?.let { s -> playable.indexOfFirst { it.id == s.id } }?.coerceAtLeast(0) ?: 0
        queue.set(playable, start, shuffle)
        val books = playable.filter(Audiobooks::isBook)
        speed = if (books.isEmpty()) 1f else bookSpeed
        // Books' chapters (rows don't carry them).
        books.filter { it.chapters.isEmpty() }.forEach { book ->
            scope.launch { runCatching { server.getItem(book.id) }.getOrNull()?.let { full -> replaceSong(full.copy(user = book.user)) } }
        }
        NowPlaying.take(this, controls)
        _state.value = _state.value.copy(problem = null)
        load(queue.position, startMs.coerceAtLeast(0), play = true)
    }

    override fun playNext(items: List<MediaItem>) {
        if (!_state.value.active) return play(items)
        queue.insertNext(items)
        refreshNext()
        publish()
    }

    override fun addToQueue(items: List<MediaItem>) {
        if (!_state.value.active) return play(items)
        queue.append(items)
        refreshNext()
        publish()
    }

    override fun toggle() {
        if (!_state.value.active) return
        if (player.isPlaying()) { pause(); return }
        if (player.currentItem == null) load(queue.position, 0, play = true) else start()
    }

    override fun pause() {
        if (!_state.value.active) return
        wantsPlaying = false
        player.pause()
        reportProgress()
        publish(lockScreen = true)
    }

    override fun next() {
        chapters()?.let { c -> Audiobooks.nextChapterStart(c, positionMs())?.let { return seekTo(it) } }
        queue.nextPosition(repeat, manual = true)?.let { load(it, 0, play = true) }
    }

    override fun previous() {
        chapters()?.let { c -> return seekTo(Audiobooks.previousChapterStart(c, positionMs())) }
        val back = queue.previousPosition(repeat)
        if (positionMs() > ListenRules.RESTART_AFTER_MS || back == null) seekTo(0) else load(back, 0, play = true)
    }

    override fun skipBy(ms: Long) {
        val duration = _state.value.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        seekTo((positionMs() + ms).coerceIn(0, duration))
    }

    override fun setSpeed(speed: Float) {
        bookSpeed = speed
        this.speed = speed
        if (player.isPlaying()) player.rate = speed
        publish(lockScreen = true)
    }

    override fun setSleep(sleep: Sleep?) {
        sleeper?.cancel()
        sleepAtMs = null
        val endsAt = (sleep as? Sleep.After)?.let { dev.nitrate.android.nowMs() + it.minutes * 60_000L }
        _state.value = _state.value.copy(sleep = sleep, sleepEndsAtMs = endsAt)
        when (sleep) {
            null -> Unit
            is Sleep.After -> sleeper = scope.launch { delay(sleep.minutes * 60_000L); fallAsleep() }
            Sleep.EndOfChapter -> {
                val c = chapters()
                val duration = _state.value.durationMs.takeIf { it > 0 } ?: queue.current?.runtimeMs ?: Long.MAX_VALUE
                sleepAtMs = if (c != null) Audiobooks.chapterEnd(c, positionMs(), duration) else Long.MAX_VALUE
            }
        }
    }

    private fun fallAsleep() {
        sleepAtMs = null
        pause()
        _state.value = _state.value.copy(sleep = null, sleepEndsAtMs = null)
    }

    override fun seekTo(ms: Long) {
        if (player.currentItem == null) return
        player.seekToTime(CMTimeMakeWithSeconds(ms.coerceAtLeast(0) / 1000.0, 1000))
        publish(lockScreen = true)
    }

    override fun skipTo(index: Int) = load(index, 0, play = true)

    override fun setShuffle(on: Boolean) {
        queue.setShuffle(on)
        refreshNext()
        publish()
    }

    override fun setRepeat(repeat: Repeat) {
        this.repeat = repeat
        refreshNext()
        publish()
    }

    override fun remove(index: Int) {
        val wasOn = index == queue.position
        queue.remove(index)
        if (queue.size == 0) return stop()
        if (wasOn) load(queue.position, 0, play = wantsPlaying) else refreshNext()
        publish()
    }

    override fun move(from: Int, to: Int) {
        queue.move(from, to)
        refreshNext()
        publish()
    }

    override fun stop() {
        setSleep(null)
        reportStopped()
        wantsPlaying = false
        player.pause()
        player.removeAllItems()
        loaded.clear()
        preloaded = null
        queue.clear()
        _state.value = ListenState()
        NowPlaying.release(this)
    }

    // ---- Loading songs into the player ----

    /** Plays the song at [position] from [startMs], the one after it loaded behind it. */
    private fun load(position: Int, startMs: Long, play: Boolean) {
        if (position !in 0 until queue.size) return
        reportStopped()
        queue.moveTo(position)
        val song = queue.current ?: return
        player.removeAllItems()
        loaded.clear()
        preloaded = null
        val item = itemFor(song)
        player.insertItem(item, afterItem = null)
        if (startMs > 0) player.seekToTime(CMTimeMakeWithSeconds(startMs / 1000.0, 1000))
        enqueueNext()
        level()
        reportSwitch()
        if (play) start() else publish(lockScreen = true)
    }

    private fun start() {
        wantsPlaying = true
        player.play()
        if (speed != 1f) player.rate = speed
        reportProgress()
        publish(lockScreen = true)
    }

    /** The song after the one on, loaded behind it (not with Repeat one, which plays the same again). */
    private fun enqueueNext() {
        if (repeat == Repeat.ONE) return
        val next = queue.nextPosition(repeat) ?: return
        val song = queue.at(next) ?: return
        val item = itemFor(song)
        player.insertItem(item, afterItem = player.currentItem)
        preloaded = next to item
    }

    /** The queue or its order changed: what's loaded behind the song on is loaded again. */
    private fun refreshNext() {
        preloaded?.let { (_, item) -> player.removeItem(item); loaded.remove(item) }
        preloaded = null
        if (player.currentItem != null) enqueueNext()
    }

    private fun itemFor(song: MediaItem): AVPlayerItem {
        sessions.getOrPut(song.id) { newSession() }
        // Downloaded: from the device, online or not.
        val local = downloads?.file(song.id)?.takeIf { song.id !in converted }
        if (local != null) localSongs += song.id else localSongs -= song.id
        val convert = local == null && (song.id in converted || !ListenStream.direct(song))
        if (convert) converted += song.id
        val url = local?.let { NSURL.fileURLWithPath(it) } ?: NSURL.URLWithString(server.listenUrl(song.id, convert, sessions.getValue(song.id)))!!
        // The sign-in is read per request, so switching accounts never uses a stale one.
        val asset = AVURLAsset(uRL = url, options = mapOf<Any?, Any?>(HEADER_FIELDS to mapOf("Authorization" to server.authorizationHeader())))
        return AVPlayerItem(asset = asset).also { loaded[it] = song.id }
    }

    /** A song finished: on to the next (as loaded behind it when it's still the right one), or the end. */
    private fun ended(item: AVPlayerItem) {
        val id = loaded[item] ?: return
        if (item != player.currentItem && preloaded?.second != player.currentItem) {
            // Not the song on (an old item's late notice).
            if (loaded[player.currentItem ?: return] != id) return
        }
        leftAt = id to (_state.value.durationMs.takeIf { it > 0 } ?: positionMs())
        loaded.remove(item)
        if (sleepAtMs == Long.MAX_VALUE) {
            // "End of chapter" with none: stop as this one ends.
            sleepAtMs = null
            _state.value = _state.value.copy(sleep = null, sleepEndsAtMs = null)
            wantsPlaying = false
            queue.nextPosition(repeat)?.let { load(it, 0, play = false) }
            return
        }
        val next = queue.nextPosition(repeat)
        when {
            next == null -> { reportStopped(); wantsPlaying = false; publish(lockScreen = true) }
            repeat == Repeat.ONE -> load(next, 0, play = true)
            preloaded?.first == next && player.currentItem == preloaded?.second -> {
                // AVQueuePlayer moved on by itself.
                reportStopped()
                preloaded = null
                queue.moveTo(next)
                enqueueNext()
                level()
                reportSwitch()
                publish(lockScreen = true)
            }
            else -> load(next, 0, play = true)
        }
    }

    /** The song on failed: as it is → converted from the same spot; converted → skipped. */
    private fun failed() {
        val song = queue.current ?: return
        if (song.id !in converted) {
            converted += song.id
            load(queue.position, positionMs(), play = true)
            return
        }
        _state.value = _state.value.copy(problem = "${song.name} couldn't be played, so it was skipped.")
        val next = queue.nextPosition(repeat, manual = true)
        if (next != null && next != queue.position) load(next, 0, play = true) else { wantsPlaying = false; player.pause(); publish(lockScreen = true) }
    }

    private fun replaceSong(full: MediaItem) {
        // Only details (chapters) change: the queue keeps its order and shuffle.
        queue.replace(full)
        publish()
    }

    /** The current book's chapters, when what's playing is a book that has some. */
    private fun chapters() = queue.current?.takeIf(Audiobooks::isBook)?.chapters?.takeIf { it.isNotEmpty() }

    private fun AVQueuePlayer.isPlaying() = timeControlStatus == AVPlayerTimeControlStatusPlaying || (wantsPlaying && timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate)

    private fun publish(lockScreen: Boolean = true) {
        val song = queue.current
        val seconds = player.currentItem?.let { CMTimeGetSeconds(it.duration) }
        val duration = seconds?.takeIf { !it.isNaN() && it > 0 }?.let { (it * 1000).toLong() } ?: (song?.runtimeMs ?: 0)
        _state.value = _state.value.copy(
            queue = queue.playing(),
            index = if (queue.size == 0) -1 else queue.position,
            playing = player.isPlaying(),
            buffering = player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
            durationMs = duration,
            shuffle = queue.shuffle,
            repeat = repeat,
            speed = speed,
        )
        if (lockScreen && song != null) NowPlaying.update(this, NowPlaying.Info(
            title = song.name,
            artist = song.artists.joinToString(", ").ifEmpty { null },
            album = song.album,
            durationMs = duration,
            positionMs = positionMs(),
            playing = player.isPlaying(),
            rate = speed,
            artworkUrl = server.imageUrl(song, ImageKind.PRIMARY, 600),
        ))
    }

    /** The song's volume from its (or its album's) loudness; books aren't levelled. */
    private fun level() {
        val song = queue.current ?: return
        if (Audiobooks.isBook(song)) { player.volume = 1f; return }
        scope.launch {
            val mode = settings.current().levelling
            val albumGain = if (mode == Levelling.ALBUM) song.albumId?.let { id ->
                albumGains.getOrPut(id) { runCatching { server.getItem(id).normalizationGain }.getOrNull() }
            } else null
            if (queue.current?.id == song.id) player.volume = Loudness.volume(Loudness.gainDb(song, albumGain, mode))
        }
    }

    // ---- Reports: started, how it's going every few seconds, stopped. ----

    private fun reportSwitch() {
        val id = queue.current?.id ?: return
        val session = sessions.getOrPut(id) { newSession() }
        reported = id to session
        scope.launch { server.reportListen(id, session, positionMs(), paused = !wantsPlaying, converted = id in converted, start = true) }
        reporter?.cancel()
        reporter = scope.launch {
            while (isActive) {
                delay(ListenRules.REPORT_EVERY_MS)
                if (player.isPlaying()) reportProgress()
            }
        }
    }

    private fun reportProgress() {
        val (id, session) = reported ?: return
        if (queue.current?.id != id) return
        scope.launch { server.reportListen(id, session, positionMs(), paused = !player.isPlaying(), converted = id in converted, start = false) }
    }

    private fun reportStopped() {
        val (id, session) = reported ?: return
        reported = null
        reporter?.cancel()
        // Where it was left (skipped part way, or played to its end): a skipped song isn't counted as played.
        val at = leftAt?.takeIf { it.first == id }?.second ?: if (queue.current?.id == id) positionMs() else 0
        leftAt = null
        server.reportListenStopped(id, session, at)
        // A downloaded song also keeps its play on the device, for the server when it's next reachable.
        if (id in localSongs) queue.playing().firstOrNull { it.id == id }?.let { song ->
            val duration = song.runtimeMs ?: 0
            scope.launch { runCatching { downloads?.played(id, at, duration, ended = duration > 0 && at >= duration - 2_000) } }
        }
        // A song played again later is a new play.
        sessions[id] = newSession()
    }

    private fun newSession() = NSUUID().UUIDString.replace("-", "").lowercase()

    private companion object {
        /** AVURLAsset's (long-standing, unlisted) option for request headers, used by most players. */
        const val HEADER_FIELDS = "AVURLAssetHTTPHeaderFieldsKey"
    }
}
