package dev.nitrate.core.downloads

import dev.nitrate.core.MediaServer
import dev.nitrate.core.episodes.UpNext
import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.MediaStream
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.model.subtitleMimeType
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Background work on the platform (WorkManager on Android). */
interface DownloadScheduler {
    /** Run [DownloadManager.runQueue] when the network allows. */
    fun schedule(wifiOnly: Boolean)

    /** Run [DownloadManager.syncWatchState] once any network is available. */
    fun scheduleSync() {}

    /** Keep smart downloads topped up every few hours; [enabled] false stops it. */
    fun scheduleSmartRefresh(enabled: Boolean, wifiOnly: Boolean) {}
}

/** What [DownloadManager.runQueue] tells the background job. */
enum class QueueResult {
    /** Nothing left to download. */
    DONE,

    /** Lost the connection: try again when the network is back. */
    RETRY,
}

/**
 * Offline downloads for the signed-in user: the original file or a smaller server transcode,
 * plus text subtitles and artwork. Playing a download records watch state on the device, which
 * [syncWatchState] sends to the server once it's reachable. Platform-neutral: files go through
 * kotlinx-io and the network through Ktor, so iOS can use it as-is.
 */
@OptIn(ExperimentalTime::class)
class DownloadManager(
    private val server: MediaServer,
    private val store: DownloadStore,
    engine: HttpClientEngine,
    /** Directory that holds one folder per download. */
    private val root: Path,
    private val scheduler: DownloadScheduler,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** The user's track choices per show or title, for downloads asked for without picking. */
    private val trackMemory: dev.nitrate.core.playback.TrackMemoryStore? = null,
    /** The user's audio-description preference (DisplayPrefs.describedAudio). */
    private val describedAudio: suspend () -> Boolean = { false },
) {
    private val http = HttpClient(engine) {
        expectSuccess = true
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            // A server may take a while to start a transcode; after that data flows steadily.
            socketTimeoutMillis = 120_000
        }
    }

    private var active: Pair<DownloadRecord.Key, Job>? = null

    /** Bytes written so far for the running download; the record is only saved every few seconds. */
    private val liveBytes = MutableStateFlow<Pair<DownloadRecord.Key, Long>?>(null)

    val settings: Flow<DownloadSettings> = store.data.map { it.settings }

    /** This user's downloads, newest first, with live progress. */
    val downloads: Flow<List<DownloadRecord>> = combine(store.data, liveBytes) { data, live ->
        mine(data).sortedByDescending { it.createdAtMs }.map { r ->
            if (live != null && live.first == r.key && r.state == DownloadState.DOWNLOADING) r.copy(bytesDownloaded = live.second) else r
        }
    }

    private fun mine(data: DownloadsData) = data.records.filter { it.userId == server.userId && it.serverUrl == server.serverUrl }

    private fun record(itemId: String) = mine(store.data.value).firstOrNull { it.itemId == itemId }

    fun find(itemId: String): DownloadRecord? = record(itemId)

    suspend fun updateSettings(transform: (DownloadSettings) -> DownloadSettings) = store.update { it.copy(settings = transform(it.settings)) }

    // ---- Choosing and queueing ----

    /** Sizes for each quality, for the download picker. */
    suspend fun options(item: MediaItem, hevc: Boolean): List<DownloadOption> =
        DownloadSizing.options(server.mediaSource(item.id), item.runtimeMs, hevc)

    /**
     * Queues [items] (movies or episodes) not already downloaded, with the [tracks] picked (exact
     * tracks only for a single title). Returns how many were added.
     */
    suspend fun enqueue(items: List<MediaItem>, quality: DownloadQuality, hevc: Boolean, tracks: TrackRequest? = null): Int =
        enqueue(items, quality, hevc, auto = false, tracks = tracks)

    private suspend fun enqueue(items: List<MediaItem>, quality: DownloadQuality, hevc: Boolean, auto: Boolean, tracks: TrackRequest? = null): Int {
        val user = server.userId ?: return 0
        val url = server.serverUrl ?: return 0
        val existing = mine(store.data.value).map { it.itemId }.toSet()
        val fresh = items.filter { it.isPlayable && it.id !in existing }.distinctBy { it.id }
        if (fresh.isEmpty()) return 0
        val created = now()
        store.update { data ->
            data.copy(records = data.records + fresh.mapIndexed { i, item ->
                DownloadRecord(
                    item.id, url, user, item, quality, hevc, createdAtMs = created + i, auto = auto,
                    audioIndex = tracks?.audioIndex?.takeIf { fresh.size == 1 }, subtitleIndex = tracks?.subtitleIndex?.takeIf { fresh.size == 1 },
                    burnInSubtitles = tracks?.burnIn == true && !quality.isOriginal,
                )
            })
        }
        scheduler.schedule(store.data.value.settings.wifiOnly)
        return fresh.size
    }

    // ---- Smart downloads ----

    /** This user's smart download rules. */
    val rules: Flow<List<SmartRule>> = store.data.map { d -> d.rules.filter { it.userId == server.userId && it.serverUrl == server.serverUrl } }

    /** Turns on smart downloads for [series] and fills it right away. */
    suspend fun setSmartRule(series: MediaItem, keep: Int, quality: DownloadQuality, hevc: Boolean) {
        val user = server.userId ?: return
        val url = server.serverUrl ?: return
        val rule = SmartRule(url, user, series.id, series.name, keep, quality, hevc)
        store.update { d -> d.copy(rules = d.rules.filterNot { it.matches(series.id) } + rule) }
        scheduler.scheduleSmartRefresh(true, store.data.value.settings.wifiOnly)
        refreshSmart()
    }

    /** Turns smart downloads off for a show. Episodes already downloaded stay. */
    suspend fun removeSmartRule(seriesId: String) {
        store.update { d -> d.copy(rules = d.rules.filterNot { it.matches(seriesId) }) }
        if (store.data.value.rules.isEmpty()) scheduler.scheduleSmartRefresh(false, false)
    }

    private fun SmartRule.matches(seriesId: String) = this.seriesId == seriesId && userId == server.userId && serverUrl == server.serverUrl

    /**
     * Brings every show with a rule up to date: deletes episodes the rule downloaded once they're
     * watched (here or elsewhere), then queues the next unwatched ones. Needs the server; returns
     * how many episodes were queued.
     */
    suspend fun refreshSmart(): Int {
        var queued = 0
        for (rule in rules.first()) {
            val episodes = try {
                server.allEpisodes(rule.seriesId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            // Local watch state not yet synced counts too.
            val localPlayed = mine(store.data.value).filter { it.item.user.played }.map { it.itemId }.toSet()
            val withLocal = episodes.map { if (it.id in localPlayed) it.copy(user = it.user.copy(played = true)) else it }
            val watched = withLocal.filter { it.user.played }.map { it.id }.toSet()
            if (rule.deleteWatched) {
                mine(store.data.value)
                    .filter { it.auto && it.item.seriesId == rule.seriesId && it.itemId in watched && !it.pendingSync }
                    .forEach { remove(it.itemId) }
            }
            queued += enqueue(UpNext.upcoming(withLocal, rule.keep), rule.quality, rule.hevc, auto = true)
        }
        // enqueue() already scheduled the queue for anything new.
        return queued
    }

    /** The next downloaded episode after [current], for Up Next without a connection. */
    fun nextDownloaded(current: MediaItem): MediaItem? {
        val series = current.seriesId ?: return null
        val episodes = mine(store.data.value).filter { it.state == DownloadState.COMPLETED && it.item.seriesId == series }.map { it.item }
        return UpNext.after(current, (episodes + current).distinctBy { it.id })?.takeIf { next -> episodes.any { it.id == next.id } }
    }

    // ---- Controls ----

    suspend fun pause(itemId: String) {
        setState(itemId, DownloadState.PAUSED)
        cancelActive(itemId)
    }

    suspend fun resume(itemId: String) {
        setState(itemId, DownloadState.QUEUED)
        scheduler.schedule(store.data.value.settings.wifiOnly)
    }

    /** Deletes the download and its files, stopping it if it's running. */
    suspend fun remove(itemId: String) {
        val r = record(itemId) ?: return
        cancelActive(itemId)
        deleteDir(dirFor(r))
        store.update { d -> d.copy(records = d.records.filterNot { it.key == r.key }) }
    }

    private suspend fun cancelActive(itemId: String) {
        val (key, job) = active ?: return
        if (key.itemId == itemId && key.userId == server.userId) {
            job.cancel()
            job.join()
        }
    }

    private suspend fun setState(itemId: String, state: DownloadState) = edit(itemId) { it.copy(state = state, error = null) }

    private suspend fun edit(itemId: String, change: (DownloadRecord) -> DownloadRecord) {
        val key = record(itemId)?.key ?: return
        edit(key, change)
    }

    private suspend fun edit(key: DownloadRecord.Key, change: (DownloadRecord) -> DownloadRecord) =
        store.update { d -> d.copy(records = d.records.map { if (it.key == key) change(it) else it }) }

    // ---- Running the queue (called from the platform's background job) ----

    /** Downloads queued items one at a time until none are left. */
    suspend fun runQueue(): QueueResult = coroutineScope {
        val tried = mutableSetOf<DownloadRecord.Key>()
        // Each item is tried once per run, so a failing one can't loop forever.
        while (true) {
            // A download interrupted by the app being stopped is still DOWNLOADING: pick it up again.
            val next = mine(store.data.value)
                .filter { (it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING) && it.key !in tried }
                .minByOrNull { it.createdAtMs } ?: return@coroutineScope QueueResult.DONE
            tried += next.key
            var offline = false
            val job = launch { offline = !download(next) }
            active = next.key to job
            job.join()
            active = null
            liveBytes.value = null
            if (offline) return@coroutineScope QueueResult.RETRY
        }
        @Suppress("UNREACHABLE_CODE")
        QueueResult.DONE
    }

    /** Returns false when the network went away (the item stays queued). */
    private suspend fun download(start: DownloadRecord): Boolean {
        val key = start.key
        val dir = dirFor(start)
        edit(key) { it.copy(state = DownloadState.DOWNLOADING, error = null) }
        return try {
            SystemFileSystem.createDirectories(dir)
            // Full metadata for showing it offline (rows give only a summary), keeping local watch state.
            val item = server.getItem(start.itemId).let { if (start.pendingSync) it.copy(user = start.item.user) else it }
            edit(key) { it.copy(item = item) }
            val source = server.mediaSource(start.itemId)
            // The tracks picked when it was asked for, else the show's (or title's) remembered
            // choice, the server's picks for this user, the file's flags.
            val (audioIndex, subtitleIndex) = if (start.audioIndex != null || start.subtitleIndex != null) {
                val fallback = dev.nitrate.core.playback.TrackSelection.initial(source, null, runCatching { describedAudio() }.getOrDefault(false))
                (start.audioIndex ?: fallback.first) to (start.subtitleIndex ?: fallback.second)
            } else {
                val choice = runCatching { trackMemory?.get(dev.nitrate.core.playback.TrackSelection.memoryKey(item)) }.getOrNull()
                dev.nitrate.core.playback.TrackSelection.initial(source, choice, runCatching { describedAudio() }.getOrDefault(false))
            }
            val audio = source.streams.firstOrNull { it.kind == StreamKind.AUDIO && it.index == audioIndex }
                ?: source.streams.firstOrNull { it.kind == StreamKind.AUDIO && it.isDefault } ?: source.streams.firstOrNull { it.kind == StreamKind.AUDIO }
            val spec = start.quality.spec(start.hevc)
            // Burned in: the server draws it into the picture, fonts and all (only when converting).
            val burned = subtitleIndex.takeIf { spec != null && start.burnInSubtitles && it >= 0 && source.streams.any { s -> s.kind == StreamKind.SUBTITLE && s.index == it } }
            val ext = if (spec == null) extension(source.container) else "mp4"
            val videoFile = "video.$ext"
            val estimate = start.quality.totalBitrate(start.hevc)?.let { DownloadSizing.estimate(it, item.runtimeMs) } ?: source.size
            edit(key) { it.copy(videoFile = videoFile, totalBytes = it.totalBytes ?: estimate) }

            val url = server.downloadUrl(start.itemId, source, spec, audio?.index, sessionId = randomId(), burnInSubtitle = burned)
            // Originals resume where they stopped; a transcode is a new stream each time.
            val resumeFrom = if (spec == null && start.videoFile == videoFile) fileSize(Path(dir, videoFile)) else 0L
            fetch(url, Path(dir, videoFile), resumeFrom, progressOf = key, knownTotal = spec == null)
            // A conversion arrives as a stream with no index: give it one, so it has a length and can seek.
            if (spec != null) indexVideo(Path(dir, videoFile))

            val subtitles = downloadSubtitles(start, source, dir, transcoded = spec != null).filter { it.streamIndex != burned }
            val assFiles = downloadAssScripts(start, source, dir, subtitles, burned)
            val fontFiles = if (assFiles.isEmpty()) emptyList() else downloadFonts(start, source, dir)
            val poster = downloadImage(server.imageUrl(item, ImageKind.PRIMARY, 720), Path(dir, "poster.jpg"))
            val backdrop = downloadImage(server.backdropUrl(item), Path(dir, "backdrop.jpg"))
            val size = fileSize(Path(dir, videoFile))
            edit(key) {
                it.copy(
                    state = DownloadState.COMPLETED,
                    bytesDownloaded = size,
                    totalBytes = size,
                    source = describe(source, spec, audio, subtitles, burned).copy(
                        defaultAudioIndex = audio?.index, defaultSubtitleIndex = burned ?: subtitleIndex, defaultsKnown = true,
                    ),
                    subtitles = subtitles,
                    burnedSubtitle = burned,
                    assFiles = assFiles,
                    fontFiles = fontFiles,
                    posterFile = poster,
                    backdropFile = backdrop,
                    completedAtMs = now(),
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: ResponseException) {
            val message = when (e.response.status) {
                HttpStatusCode.Unauthorized -> "Signed out of the server"
                HttpStatusCode.Forbidden -> "Your account isn't allowed to download from this server"
                HttpStatusCode.NotFound -> "No longer on the server"
                else -> "Server error ${e.response.status.value}"
            }
            edit(key) { it.copy(state = DownloadState.FAILED, error = message) }
            true
        } catch (e: Exception) {
            // Connection trouble: keep it queued and let the scheduler retry with network.
            edit(key) { it.copy(state = DownloadState.QUEUED, error = "Waiting for connection") }
            false
        }
    }

    /** Streams [url] to [file]; with [progressOf], reports bytes on that record. */
    private suspend fun fetch(url: String, file: Path, resumeFrom: Long = 0, progressOf: DownloadRecord.Key? = null, knownTotal: Boolean = false) {
        val key = progressOf
        http.prepareGet(url) {
            header(HttpHeaders.Authorization, server.authorizationHeader())
            if (resumeFrom > 0) header(HttpHeaders.Range, "bytes=$resumeFrom-")
        }.execute { response ->
            val append = resumeFrom > 0 && response.status == HttpStatusCode.PartialContent
            var written = if (append) resumeFrom else 0L
            if (key != null && knownTotal) response.contentLength()?.let { len -> edit(key) { it.copy(totalBytes = written + len) } }
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER)
            var lastSave = now()
            SystemFileSystem.sink(file, append).buffered().use { sink ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    sink.write(buffer, 0, n)
                    written += n
                    if (key == null) continue
                    liveBytes.value = key to written
                    if (now() - lastSave > SAVE_EVERY_MS) {
                        sink.flush()
                        val bytes = written
                        edit(key) { it.copy(bytesDownloaded = bytes) }
                        lastSave = now()
                    }
                }
            }
            if (key != null) edit(key) { it.copy(bytesDownloaded = written) }
        }
    }

    /**
     * Text subtitles as files beside the video. A transcode drops embedded subtitles, so those
     * are saved too; an original keeps its embedded ones and only external files are fetched.
     * Image subtitles (PGS, VobSub) only survive in originals.
     */
    private suspend fun downloadSubtitles(record: DownloadRecord, source: MediaSource, dir: Path, transcoded: Boolean): List<LocalSubtitle> =
        source.streams.filter { it.kind == StreamKind.SUBTITLE && subtitleMimeType(it.codec) != null && (transcoded || it.isExternal) }
            .mapNotNull { s ->
                val format = when (s.codec?.lowercase()) {
                    "ass", "ssa" -> "ass"
                    "vtt", "webvtt" -> "vtt"
                    else -> "srt"
                }
                val file = "sub-${s.index}.$format"
                runCatching { fetch(server.subtitleFileUrl(record.itemId, source, s.index, format), Path(dir, file)) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull() ?: return@mapNotNull null
                LocalSubtitle(s.index, file, format, s.language, s.title, s.isDefault, s.isForced)
            }

    /**
     * Every styled (ASS/SSA) subtitle as a whole script, so libass can draw it offline: the files
     * already saved beside the video, plus ones embedded in an original (which the player could
     * only show plainly).
     */
    private suspend fun downloadAssScripts(record: DownloadRecord, source: MediaSource, dir: Path, saved: List<LocalSubtitle>, burned: Int?): Map<Int, String> =
        source.streams.filter { it.kind == StreamKind.SUBTITLE && dev.nitrate.core.playback.isStyledSubtitle(it.codec) && it.index != burned }
            .mapNotNull { s ->
                saved.firstOrNull { it.streamIndex == s.index && it.codec == "ass" }?.let { return@mapNotNull s.index to it.file }
                val file = "ass-${s.index}.ass"
                runCatching { fetch(server.subtitleFileUrl(record.itemId, source, s.index, "ass"), Path(dir, file)) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull() ?: return@mapNotNull null
                s.index to file
            }.toMap()

    /** The fonts the file carries (for its styled subtitles), in a folder of their own. */
    private suspend fun downloadFonts(record: DownloadRecord, source: MediaSource, dir: Path): List<String> {
        val fonts = source.attachments.filter { it.isFont }
        if (fonts.isEmpty()) return emptyList()
        SystemFileSystem.createDirectories(Path(dir, "fonts"))
        val used = mutableSetOf<String>()
        return fonts.mapNotNull { a ->
            val name = fontFileName(a.fileName, a.index).let { n -> if (used.add(n)) n else "${a.index}-$n".also { used += it } }
            val file = "fonts/$name"
            runCatching { fetch(server.attachmentUrl(record.itemId, source, a.index), Path(dir, file)) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() ?: return@mapNotNull null
            file
        }
    }

    private suspend fun downloadImage(url: String?, file: Path): String? {
        url ?: return null
        return runCatching { fetch(url, file); file.name }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    /** The downloaded file's streams: the original's, or what the transcode produced. */
    private fun describe(source: MediaSource, spec: TranscodeSpec?, audio: MediaStream?, subtitles: List<LocalSubtitle>, burned: Int? = null): MediaSource {
        val subtitleStreams = subtitles.map { s ->
            MediaStream(s.streamIndex, StreamKind.SUBTITLE, codec = s.codec, language = s.language, title = s.title, isDefault = s.isDefault,
                isForced = s.isForced, isExternal = true, delivery = SubtitleDelivery.EXTERNAL)
        } + listOfNotNull(
            // Drawn into the picture: listed (and on), with nothing for the player to show itself.
            source.streams.firstOrNull { it.index == burned }?.copy(delivery = SubtitleDelivery.ENCODE, deliveryUrl = null, isExternal = false),
        )
        if (spec == null) {
            val embedded = source.streams.filterNot { it.kind == StreamKind.SUBTITLE && it.isExternal }
                .map { if (it.kind == StreamKind.SUBTITLE) it.copy(delivery = SubtitleDelivery.EMBED, deliveryUrl = null) else it }
            return source.copy(streams = embedded + subtitleStreams)
        }
        val video = source.streams.firstOrNull { it.kind == StreamKind.VIDEO }
        // Scaled to fit, truncated to even numbers, as the server's scale filter does.
        val scale = if (video?.width != null && video.height != null) minOf(1.0, spec.maxWidth.toDouble() / video.width, spec.maxHeight.toDouble() / video.height) else 1.0
        fun even(v: Int?) = v?.let { (it * scale).toInt() / 2 * 2 }
        val streams = listOfNotNull(
            video?.copy(codec = spec.videoCodec, width = even(video.width), height = even(video.height),
                bitDepth = 8, rangeType = "SDR", profile = null, title = null),
            audio?.copy(codec = "aac", channels = spec.audioChannels, channelLayout = "stereo", title = listOfNotNull(audio.language, "AAC stereo").joinToString(" - ")),
        )
        // The one audio kept is the one to play; the subtitle the server picks still applies.
        return MediaSource(source.id, "mp4", spec.videoBitrate + spec.audioBitrate, streams + subtitleStreams,
            defaultAudioIndex = audio?.index, defaultSubtitleIndex = source.defaultSubtitleIndex, defaultsKnown = source.defaultsKnown)
    }

    // ---- Offline playback ----

    /** Absolute path of the downloaded video, once complete. */
    fun videoPath(record: DownloadRecord): String? =
        record.videoFile?.takeIf { record.state == DownloadState.COMPLETED }?.let { Path(dirFor(record), it).toString() }

    fun imagePath(record: DownloadRecord, poster: Boolean): String? =
        (if (poster) record.posterFile else record.backdropFile)?.let { Path(dirFor(record), it).toString() }

    /**
     * Downloads converted before they were indexed have no length and can't seek; this indexes
     * one (once; quick, a single copy of the file) before it plays. Never stops it playing.
     */
    suspend fun makeSeekable(record: DownloadRecord) {
        if (record.quality == DownloadQuality.ORIGINAL) return
        val path = videoPath(record)?.let { Path(it) } ?: return
        indexVideo(path)
    }

    private suspend fun indexVideo(path: Path) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { Mp4Index.addIndex(path) }.onFailure { if (it is CancellationException) throw it }
        }
    }

    /**
     * Learns the server's audio and subtitle picks for a download saved before they were kept
     * (when the server answers quickly), so it starts like streaming would.
     */
    suspend fun withServerDefaults(record: DownloadRecord): DownloadRecord {
        val source = record.source ?: return record
        if (source.defaultsKnown) return record
        val fresh = withTimeoutOrNull(2_500) { runCatching { server.mediaSource(record.itemId) }.getOrNull() } ?: return record
        val audio = source.streams.filter { it.kind == StreamKind.AUDIO }.let { kept -> fresh.defaultAudioIndex?.takeIf { i -> kept.any { it.index == i } } ?: kept.singleOrNull()?.index }
        val updated = source.copy(defaultAudioIndex = audio, defaultSubtitleIndex = fresh.defaultSubtitleIndex, defaultsKnown = true)
        edit(record.key) { it.copy(source = updated) }
        return record.copy(source = updated)
    }

    /** Plays the local file; external subtitles come from the files saved beside it. */
    fun playbackPlan(record: DownloadRecord): PlaybackPlan? {
        val path = videoPath(record) ?: return null
        val source = record.source ?: return null
        val dir = dirFor(record)
        val subtitles = record.subtitles.mapNotNull { s ->
            val mime = subtitleMimeType(s.codec) ?: return@mapNotNull null
            ExternalSubtitle(s.streamIndex, Path(dir, s.file).toString(), mime, s.language, s.title ?: s.language ?: "Subtitle ${s.streamIndex}")
        }
        val (audioIndex, subtitleIndex) = DownloadTracks.pick(source)
        val assScripts = record.assFiles.mapValues { (_, f) -> Path(dir, f).toString() }
            .filterValues { SystemFileSystem.exists(Path(it)) }
        return PlaybackPlan(
            itemId = record.itemId,
            url = path,
            method = PlayMethod.DIRECT_PLAY,
            playSessionId = null,
            source = source,
            isHls = false,
            audioIndex = audioIndex,
            subtitleIndex = subtitleIndex,
            externalSubtitles = subtitles,
            transcodeReasons = emptyList(),
            assScripts = assScripts,
            fontFiles = record.fontFiles.map { Path(dir, it).toString() },
        )
    }

    /**
     * Where to resume: the server's position when it answers quickly (it may have been watched
     * elsewhere), else this device's.
     */
    suspend fun resumePositionMs(record: DownloadRecord): Long {
        if (record.pendingSync) return record.item.user.positionMs
        val remote = withTimeoutOrNull(3_000) { runCatching { server.userData(record.itemId) }.getOrNull() }
        return remote?.let { if (it.played) 0 else it.positionMs } ?: record.item.user.positionMs
    }

    /**
     * Saves where playback of a download stopped. Past 90% counts as watched (and resets the
     * position), under 5% doesn't count, as on the server. An audiobook keeps its place from
     * [BOOK_MIN_RESUME_MS] on and counts as read only in its last [BOOK_END_MS] (5% of a long book
     * is half an hour). Sent by [syncWatchState].
     */
    suspend fun recordPlayback(itemId: String, positionMs: Long, durationMs: Long, ended: Boolean) {
        val fraction = if (durationMs > 0) positionMs.toDouble() / durationMs else 0.0
        val book = record(itemId)?.item?.kind == dev.nitrate.core.model.ItemKind.AUDIOBOOK
        val played = ended || if (book) durationMs > 0 && durationMs - positionMs < BOOK_END_MS else fraction >= PLAYED_FRACTION
        val position = when {
            played -> 0L
            book -> if (positionMs < BOOK_MIN_RESUME_MS) 0L else positionMs
            fraction < MIN_RESUME_FRACTION -> 0L
            else -> positionMs
        }
        edit(itemId) { r ->
            // Rewatching part of a watched item keeps it watched, as the server does.
            val user = r.item.user.copy(played = played || r.item.user.played, positionMs = position)
            r.copy(item = r.item.copy(user = user), pendingSync = true, watchedAtMs = now())
        }
        scheduler.scheduleSync()
    }

    /**
     * Sends offline watch state to the server. Where the item was played elsewhere more recently,
     * the server's state wins. Returns how many items were synced; stops at the first failure.
     */
    suspend fun syncWatchState(): Int {
        var synced = 0
        for (r in mine(store.data.value).filter { it.pendingSync }) {
            try {
                val remote = server.userData(r.itemId)
                val localAt = r.watchedAtMs ?: 0
                if (remote.lastPlayedMs != null && remote.lastPlayedMs > localAt) {
                    edit(r.key) { it.copy(item = it.item.copy(user = it.item.user.copy(played = remote.played, positionMs = remote.positionMs)), pendingSync = false) }
                } else {
                    server.saveUserData(r.itemId, r.item.user.positionMs, r.item.user.played, localAt)
                    // Finished offline: into the watch diary too (the server only records playback it saw).
                    if (r.item.user.played && r.item.user.positionMs == 0L && localAt > 0) runCatching {
                        if (server.companion()?.features?.contains(dev.nitrate.core.diary.Diary.FEATURE) == true) server.logWatch(r.itemId, localAt)
                    }
                    edit(r.key) { it.copy(pendingSync = false) }
                }
                synced++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                break
            }
        }
        return synced
    }

    /** Space used by this user's downloads. */
    fun bytesUsed(): Long = mine(store.data.value).sumOf { r -> listOfNotNull(r.videoFile, r.posterFile, r.backdropFile).plus(r.subtitles.map { it.file }).plus(r.assFiles.values).plus(r.fontFiles).distinct().sumOf { fileSize(Path(dirFor(r), it)) } }

    // ---- Files ----

    private fun dirFor(r: DownloadRecord) = Path(root, safe(r.userId), safe(r.itemId))

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }

    private fun fileSize(path: Path) = SystemFileSystem.metadataOrNull(path)?.size?.takeIf { it >= 0 } ?: 0L

    private fun deleteDir(dir: Path) {
        if (!SystemFileSystem.exists(dir)) return
        SystemFileSystem.list(dir).forEach {
            if (SystemFileSystem.metadataOrNull(it)?.isDirectory == true) deleteDir(it) else SystemFileSystem.delete(it, mustExist = false)
        }
        SystemFileSystem.delete(dir, mustExist = false)
    }

    private fun extension(container: String?): String {
        val first = container?.split(',')?.firstOrNull()?.trim()?.lowercase()
        return when (first) {
            null, "" -> "mkv"
            "matroska" -> "mkv"
            "mov" -> "mp4"
            else -> first.filter { it.isLetterOrDigit() }.ifEmpty { "mkv" }
        }
    }

    private fun randomId() = Random.nextBytes(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val BUFFER = 64 * 1024
        const val SAVE_EVERY_MS = 3_000L
        const val PLAYED_FRACTION = 0.9
        const val MIN_RESUME_FRACTION = 0.05
        const val BOOK_MIN_RESUME_MS = 30_000L
        const val BOOK_END_MS = 120_000L
    }
}

/** Which audio and subtitle a download starts with. */
object DownloadTracks {
    /**
     * The server's picks for this user when the download kept them (and they're in the file),
     * else the audio marked default and any forced subtitle.
     */
    fun pick(source: MediaSource): Pair<Int?, Int> {
        val audio = source.streams.filter { it.kind == StreamKind.AUDIO }
        val subtitles = source.streams.filter { it.kind == StreamKind.SUBTITLE }
        val audioIndex = source.defaultAudioIndex?.takeIf { i -> audio.any { it.index == i } }
            ?: (audio.firstOrNull { it.isDefault } ?: audio.firstOrNull())?.index
        val subtitleIndex = when {
            source.defaultsKnown && source.defaultSubtitleIndex == -1 -> -1
            source.defaultsKnown && subtitles.any { it.index == source.defaultSubtitleIndex } -> source.defaultSubtitleIndex!!
            else -> subtitles.firstOrNull { it.isForced }?.index ?: -1
        }
        return audioIndex to subtitleIndex
    }
}

/** A safe file name for a font attachment: its own name without any folders or odd characters. */
internal fun fontFileName(name: String?, index: Int): String {
    val base = name?.substringAfterLast('/')?.substringAfterLast('\\')?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.trim('.', ' ')
    return base?.takeIf { it.isNotEmpty() }?.take(100) ?: "font-$index.ttf"
}
