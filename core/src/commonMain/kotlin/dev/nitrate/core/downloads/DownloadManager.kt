package dev.nitrate.core.downloads

import dev.nitrate.core.MediaServer
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

    /** Queues [items] (movies or episodes) not already downloaded. Returns how many were added. */
    suspend fun enqueue(items: List<MediaItem>, quality: DownloadQuality, hevc: Boolean): Int {
        val user = server.userId ?: return 0
        val url = server.serverUrl ?: return 0
        val existing = mine(store.data.value).map { it.itemId }.toSet()
        val fresh = items.filter { it.isPlayable && it.id !in existing }.distinctBy { it.id }
        if (fresh.isEmpty()) return 0
        val created = now()
        store.update { data ->
            data.copy(records = data.records + fresh.mapIndexed { i, item ->
                DownloadRecord(item.id, url, user, item, quality, hevc, createdAtMs = created + i)
            })
        }
        scheduler.schedule(store.data.value.settings.wifiOnly)
        return fresh.size
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
            val audio = source.streams.firstOrNull { it.kind == StreamKind.AUDIO && it.isDefault } ?: source.streams.firstOrNull { it.kind == StreamKind.AUDIO }
            val spec = start.quality.spec(start.hevc)
            val ext = if (spec == null) extension(source.container) else "mp4"
            val videoFile = "video.$ext"
            val estimate = start.quality.totalBitrate(start.hevc)?.let { DownloadSizing.estimate(it, item.runtimeMs) } ?: source.size
            edit(key) { it.copy(videoFile = videoFile, totalBytes = it.totalBytes ?: estimate) }

            val url = server.downloadUrl(start.itemId, source, spec, audio?.index, sessionId = randomId())
            // Originals resume where they stopped; a transcode is a new stream each time.
            val resumeFrom = if (spec == null && start.videoFile == videoFile) fileSize(Path(dir, videoFile)) else 0L
            fetch(url, Path(dir, videoFile), resumeFrom, progressOf = key, knownTotal = spec == null)

            val subtitles = downloadSubtitles(start, source, dir, transcoded = spec != null)
            val poster = downloadImage(server.imageUrl(item, ImageKind.PRIMARY, 720), Path(dir, "poster.jpg"))
            val backdrop = downloadImage(server.backdropUrl(item), Path(dir, "backdrop.jpg"))
            val size = fileSize(Path(dir, videoFile))
            edit(key) {
                it.copy(
                    state = DownloadState.COMPLETED,
                    bytesDownloaded = size,
                    totalBytes = size,
                    source = describe(source, spec, audio, subtitles),
                    subtitles = subtitles,
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

    private suspend fun downloadImage(url: String?, file: Path): String? {
        url ?: return null
        return runCatching { fetch(url, file); file.name }.onFailure { if (it is CancellationException) throw it }.getOrNull()
    }

    /** The downloaded file's streams: the original's, or what the transcode produced. */
    private fun describe(source: MediaSource, spec: TranscodeSpec?, audio: MediaStream?, subtitles: List<LocalSubtitle>): MediaSource {
        val subtitleStreams = subtitles.map { s ->
            MediaStream(s.streamIndex, StreamKind.SUBTITLE, codec = s.codec, language = s.language, title = s.title, isDefault = s.isDefault,
                isForced = s.isForced, isExternal = true, delivery = SubtitleDelivery.EXTERNAL)
        }
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
        return MediaSource(source.id, "mp4", spec.videoBitrate + spec.audioBitrate, streams + subtitleStreams)
    }

    // ---- Offline playback ----

    /** Absolute path of the downloaded video, once complete. */
    fun videoPath(record: DownloadRecord): String? =
        record.videoFile?.takeIf { record.state == DownloadState.COMPLETED }?.let { Path(dirFor(record), it).toString() }

    fun imagePath(record: DownloadRecord, poster: Boolean): String? =
        (if (poster) record.posterFile else record.backdropFile)?.let { Path(dirFor(record), it).toString() }

    /** Plays the local file; external subtitles come from the files saved beside it. */
    fun playbackPlan(record: DownloadRecord): PlaybackPlan? {
        val path = videoPath(record) ?: return null
        val source = record.source ?: return null
        val dir = dirFor(record)
        val subtitles = record.subtitles.mapNotNull { s ->
            val mime = subtitleMimeType(s.codec) ?: return@mapNotNull null
            ExternalSubtitle(s.streamIndex, Path(dir, s.file).toString(), mime, s.language, s.title ?: s.language ?: "Subtitle ${s.streamIndex}")
        }
        val audio = source.streams.firstOrNull { it.kind == StreamKind.AUDIO && it.isDefault } ?: source.streams.firstOrNull { it.kind == StreamKind.AUDIO }
        val subtitle = source.streams.firstOrNull { it.kind == StreamKind.SUBTITLE && it.isForced }
        return PlaybackPlan(
            itemId = record.itemId,
            url = path,
            method = PlayMethod.DIRECT_PLAY,
            playSessionId = null,
            source = source,
            isHls = false,
            audioIndex = audio?.index,
            subtitleIndex = subtitle?.index ?: -1,
            externalSubtitles = subtitles,
            transcodeReasons = emptyList(),
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
     * position), under 5% doesn't count, as on the server. Sent by [syncWatchState].
     */
    suspend fun recordPlayback(itemId: String, positionMs: Long, durationMs: Long, ended: Boolean) {
        val fraction = if (durationMs > 0) positionMs.toDouble() / durationMs else 0.0
        val played = ended || fraction >= PLAYED_FRACTION
        val position = if (played || fraction < MIN_RESUME_FRACTION) 0L else positionMs
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
    fun bytesUsed(): Long = mine(store.data.value).sumOf { r -> listOfNotNull(r.videoFile, r.posterFile, r.backdropFile).plus(r.subtitles.map { it.file }).sumOf { fileSize(Path(dirFor(r), it)) } }

    // ---- Files ----

    private fun dirFor(r: DownloadRecord) = Path(root, safe(r.userId), safe(r.itemId))

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' }

    private fun fileSize(path: Path) = SystemFileSystem.metadataOrNull(path)?.size?.takeIf { it >= 0 } ?: 0L

    private fun deleteDir(dir: Path) {
        if (!SystemFileSystem.exists(dir)) return
        SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it, mustExist = false) }
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
    }
}
