package dev.nitrate.tv.plugins.writing

import android.content.Context
import dev.nitrate.tv.data.JellyfinRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class WritingDoc(val id: String, val title: String, val modifiedMs: Long)

@Serializable
private data class BackupEntry(val text: String, val modifiedMs: Long)

/**
 * Screenplays saved as Fountain files in the app's private storage, one file per document.
 * When backup is on, each document is also kept in the user's Jellyfin display preferences
 * (a slot of its own), so a reset TV or another TV can restore it. Newest copy wins.
 */
class WritingStore(context: Context, private val repository: JellyfinRepository) {
    private val dir = File(context.filesDir, "writing").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val backupLock = Mutex()
    private val _docs = MutableStateFlow(scan())
    val docs: StateFlow<List<WritingDoc>> = _docs

    private fun file(id: String) = File(dir, "$id.fountain")

    // Backups that haven't reached the server yet (offline, server down). Survive restarts.
    private val pendingFile = File(dir, "pending-backup.json")

    @Serializable
    private data class Pending(val upload: Set<String> = emptySet(), val delete: Set<String> = emptySet())

    private fun pending(): Pending = runCatching { json.decodeFromString<Pending>(pendingFile.readText()) }.getOrDefault(Pending())

    private fun setPending(p: Pending) = pendingFile.writeText(json.encodeToString(p))

    /** Number of scripts waiting to be backed up. */
    val pendingCount: Int get() = pending().let { it.upload.size + it.delete.size }

    private fun scan(): List<WritingDoc> = dir.listFiles { f -> f.extension == "fountain" }.orEmpty()
        .map { WritingDoc(it.nameWithoutExtension, titleOf(it.readText()), it.lastModified()) }
        .sortedByDescending { it.modifiedMs }

    suspend fun read(id: String): String = withContext(Dispatchers.IO) { file(id).takeIf { it.exists() }?.readText().orEmpty() }

    suspend fun create(text: String = ""): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        file(id).writeText(text)
        _docs.value = scan()
        id
    }

    suspend fun save(id: String, text: String) = withContext(Dispatchers.IO) {
        file(id).writeText(text)
        _docs.value = scan()
    }

    suspend fun delete(id: String, backup: Boolean) {
        withContext(Dispatchers.IO) { file(id).delete() }
        _docs.value = scan()
        if (backup) {
            backupLock.withLock { withContext(Dispatchers.IO) { pending().let { setPending(it.copy(upload = it.upload - id, delete = it.delete + id)) } } }
            flushPending()
        }
    }

    /** Queues one document for the server copy and tries to send everything queued. */
    suspend fun backUp(id: String): Boolean {
        backupLock.withLock { withContext(Dispatchers.IO) { pending().let { setPending(it.copy(upload = it.upload + id)) } } }
        return flushPending()
    }

    /**
     * Sends queued uploads and deletions in one update. Returns true when nothing is left
     * waiting; on failure (e.g. offline) everything stays queued for the next attempt.
     */
    suspend fun flushPending(): Boolean = backupLock.withLock {
        val queued = withContext(Dispatchers.IO) { pending() }
        if (queued.upload.isEmpty() && queued.delete.isEmpty()) return@withLock true
        val ok = runCatching {
            val current = repository.loadCustomPrefs(PREFS_ID)
            val uploads = withContext(Dispatchers.IO) {
                queued.upload.mapNotNull { id ->
                    file(id).takeIf { it.exists() }?.let { f -> id to json.encodeToString(BackupEntry(f.readText(), f.lastModified())) }
                }.toMap()
            }
            repository.saveCustomPrefs(current - queued.delete + uploads, PREFS_ID)
        }.isSuccess
        if (ok) {
            // Only clear what was sent; edits made meanwhile stay queued.
            withContext(Dispatchers.IO) { pending().let { setPending(Pending(it.upload - queued.upload, it.delete - queued.delete)) } }
        }
        ok && withContext(Dispatchers.IO) { pending().let { it.upload.isEmpty() && it.delete.isEmpty() } }
    }

    /** Brings in server copies that are newer than, or missing from, this TV. Returns how many. */
    suspend fun restore(): Int {
        val remote = runCatching { repository.loadCustomPrefs(PREFS_ID) }.getOrNull() ?: return 0
        var restored = 0
        val deletedHere = withContext(Dispatchers.IO) { pending().delete }
        withContext(Dispatchers.IO) {
            for ((id, raw) in remote) {
                if (id in deletedHere) continue
                val entry = runCatching { json.decodeFromString<BackupEntry>(raw) }.getOrNull() ?: continue
                val local = file(id)
                if (!local.exists() || local.lastModified() < entry.modifiedMs) {
                    local.writeText(entry.text)
                    local.setLastModified(entry.modifiedMs)
                    restored++
                }
            }
        }
        _docs.value = scan()
        return restored
    }

    companion object {
        const val PREFS_ID = "nitrate-writing"

        /** The Title: from the title page, else the first line with text. */
        fun titleOf(text: String): String {
            val lines = text.lineSequence().map { it.trim() }
            return lines.firstOrNull { it.startsWith("Title:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
                ?: lines.firstOrNull { it.isNotEmpty() }?.take(60)
                ?: "Untitled"
        }
    }
}
