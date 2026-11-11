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
        if (backup) editBackup { it - id }
    }

    /** Sends one document to the server copy. */
    suspend fun backUp(id: String) {
        val f = file(id)
        if (!f.exists()) return
        val entry = BackupEntry(withContext(Dispatchers.IO) { f.readText() }, f.lastModified())
        editBackup { it + (id to json.encodeToString(entry)) }
    }

    /** Brings in server copies that are newer than, or missing from, this TV. Returns how many. */
    suspend fun restore(): Int {
        val remote = runCatching { repository.loadCustomPrefs(PREFS_ID) }.getOrNull() ?: return 0
        var restored = 0
        withContext(Dispatchers.IO) {
            for ((id, raw) in remote) {
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

    private suspend fun editBackup(change: (Map<String, String>) -> Map<String, String>) = backupLock.withLock {
        runCatching {
            val current = repository.loadCustomPrefs(PREFS_ID)
            repository.saveCustomPrefs(change(current), PREFS_ID)
        }
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
