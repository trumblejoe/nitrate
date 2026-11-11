package dev.nitrate.core.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json

/** Where download records live. */
interface DownloadStore {
    val data: StateFlow<DownloadsData>
    suspend fun update(transform: (DownloadsData) -> DownloadsData)
}

/** Records as one JSON file, replaced atomically on each change. */
class FileDownloadStore(private val file: Path) : DownloadStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private val state = MutableStateFlow(read())
    override val data: StateFlow<DownloadsData> = state

    private fun read(): DownloadsData = runCatching {
        if (!SystemFileSystem.exists(file)) return DownloadsData()
        json.decodeFromString<DownloadsData>(SystemFileSystem.source(file).buffered().use { it.readString() })
    }.getOrDefault(DownloadsData())

    override suspend fun update(transform: (DownloadsData) -> DownloadsData) = mutex.withLock {
        val updated = transform(state.value)
        if (updated == state.value) return@withLock
        file.parent?.let { SystemFileSystem.createDirectories(it) }
        val tmp = Path("$file.tmp")
        SystemFileSystem.sink(tmp).buffered().use { it.writeString(json.encodeToString(DownloadsData.serializer(), updated)) }
        SystemFileSystem.atomicMove(tmp, file)
        state.value = updated
    }
}

/** For tests and previews. */
class MemoryDownloadStore(initial: DownloadsData = DownloadsData()) : DownloadStore {
    private val mutex = Mutex()
    private val state = MutableStateFlow(initial)
    override val data: StateFlow<DownloadsData> = state
    override suspend fun update(transform: (DownloadsData) -> DownloadsData) = mutex.withLock { state.value = transform(state.value) }
}
