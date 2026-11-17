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

/** A page's rows as last loaded, shown at once next time while fresh ones load. */
@Serializable
data class CachedPage(val builtIn: List<MediaRow>, val custom: Map<String, MediaRow> = emptyMap())

/**
 * What screens showed last time (a page's rows, a title's details, the user's playlists...), per
 * server and user, in the app's cache folder so screens open with it at once while fresh data
 * loads. Android may clear the folder; screens then just load as before. Only ever a head start:
 * fresh data always replaces it.
 */
class DataCache(cacheDir: String, private val fs: FileSystem = FileSystem.SYSTEM) {
    private val dir: Path = cacheDir.toPath() / "data"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

    private fun file(account: String, key: String): Path =
        dir / ((account + "|" + key).hashCode().toUInt().toString(16) + ".json")

    private fun readText(f: Path): String? = if (fs.exists(f)) runCatching { fs.read(f) { readUtf8() } }.getOrNull() else null

    private fun writeText(f: Path, text: String) {
        runCatching {
            fs.createDirectories(dir)
            val tmp = dir / (f.name + ".tmp")
            fs.write(tmp) { writeUtf8(text) }
            fs.atomicMove(tmp, f)
        }
    }

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
    }

    /** A page's rows (see [dev.nitrate.android.viewmodel.RowsViewModel]). */
    suspend fun read(account: String, pageKey: String): CachedPage? = read(account, "rows/$pageKey", CachedPage.serializer())

    suspend fun write(account: String, pageKey: String, page: CachedPage) = write(account, "rows/$pageKey", page, CachedPage.serializer())
}
