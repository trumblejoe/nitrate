package dev.nitrate.android.storage

import dev.nitrate.core.rows.MediaRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.IO
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.ByteString.Companion.encodeUtf8

/** A page's rows as last loaded, shown at once next time while fresh ones load. */
@Serializable
data class CachedPage(
    val builtIn: List<MediaRow>,
    val custom: Map<String, MediaRow> = emptyMap(),
    /** The server's change counters when [builtIn] was loaded (`MediaServer.changeCheck`), if it has them. */
    val stamps: String? = null,
    /** Each custom row's change counters and source when it was loaded (`RowChanges.stampOf`), by entry id. */
    val customStamps: Map<String, String> = emptyMap(),
)

/**
 * What screens showed last time (a page's rows, a title's details, the user's playlists...), per
 * server and user, in the app's cache folder so screens open with it at once while fresh data
 * loads. Android may clear the folder; screens then just load as before. Only ever a head start:
 * fresh data always replaces it.
 */
class DataCache(cacheDir: String, private val fs: FileSystem = FileSystem.SYSTEM, private val maxBytes: Long = MAX_BYTES) {
    private val dir: Path = cacheDir.toPath() / "data"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

    /** Each account's files in a folder of its own, so forgetting the account takes them all ([forget]). */
    private fun folder(account: String): Path = dir / name(account).take(24)

    /** Names are SHA-256 of what they hold, so two pages never share a file. */
    private fun file(account: String, key: String): Path = folder(account) / (name(key) + ".json")

    private fun name(s: String) = s.encodeUtf8().sha256().hex()

    private fun readText(f: Path): String? = if (fs.exists(f)) runCatching { fs.read(f) { readUtf8() } }.getOrNull() else null

    private var writes = 0

    private fun writeText(f: Path, text: String) {
        runCatching {
            val parent = f.parent ?: dir
            fs.createDirectories(parent)
            val tmp = parent / (f.name + ".tmp")
            fs.write(tmp) { writeUtf8(text) }
            fs.atomicMove(tmp, f)
        }
        // Kept within [maxBytes]: checked on the first write of a run and every 50th after.
        if (writes++ % PRUNE_EVERY == 0) prune()
    }

    /**
     * Drops the least recently written files until everything fits in [maxBytes] (a page is
     * written again whenever it's shown, so this keeps what's in use), and files from before
     * accounts had folders.
     */
    fun prune() {
        runCatching {
            if (!fs.exists(dir)) return
            val files = mutableListOf<Triple<Path, Long, Long>>()
            fs.list(dir).forEach { p ->
                val meta = fs.metadataOrNull(p) ?: return@forEach
                if (meta.isDirectory) fs.list(p).forEach { f ->
                    fs.metadataOrNull(f)?.takeIf { it.isRegularFile }?.let { files += Triple(f, it.size ?: 0, it.lastModifiedAtMillis ?: 0) }
                } else runCatching { fs.delete(p) }
            }
            var total = files.sumOf { it.second }
            for ((f, size, _) in files.sortedBy { it.third }) {
                if (total <= maxBytes) break
                runCatching { fs.delete(f) }
                total -= size
            }
        }
    }

    /** Lets go of everything kept for [account] (signed out or forgotten). */
    suspend fun forget(account: String) = withContext(Dispatchers.IO) { runCatching { fs.deleteRecursively(folder(account)) }; Unit }

    suspend fun <T> read(account: String, key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        readText(file(account, key))?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
    }

    suspend fun <T> write(account: String, key: String, value: T, serializer: KSerializer<T>) = withContext(Dispatchers.IO) {
        writeText(file(account, key), json.encodeToString(serializer, value))
    }

    /** Plain text under [key] (already per account), for the Jellyfin client's own remembered rows. */
    val text = object : dev.nitrate.core.cache.TextCache {
        override suspend fun get(key: String): String? = withContext(Dispatchers.IO) { readText(file("", key)) }
        override suspend fun put(key: String, value: String) {
            withContext(Dispatchers.IO) { writeText(file("", key), value) }
        }
        override suspend fun remove(key: String) {
            withContext(Dispatchers.IO) { runCatching { fs.delete(file("", key)) } }
        }
    }

    companion object {
        /** Everything kept here, at most. */
        const val MAX_BYTES = 50L * 1024 * 1024
        const val PRUNE_EVERY = 50
    }

    /** A page's rows (see [dev.nitrate.android.viewmodel.RowsViewModel]). */
    suspend fun read(account: String, pageKey: String): CachedPage? = read(account, "rows/$pageKey", CachedPage.serializer())

    suspend fun write(account: String, pageKey: String, page: CachedPage) = write(account, "rows/$pageKey", page, CachedPage.serializer())
}
