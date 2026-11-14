package dev.nitrate.android.storage

import android.content.Context
import dev.nitrate.core.rows.MediaRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A page's rows as last loaded, shown at once next time while fresh ones load. */
@Serializable
data class CachedPage(val builtIn: List<MediaRow>, val custom: Map<String, MediaRow> = emptyMap())

/**
 * What screens showed last time (a page's rows, a title's details, the user's playlists...), per
 * server and user, in the app's cache folder so screens open with it at once while fresh data
 * loads. Android may clear the folder; screens then just load as before. Only ever a head start:
 * fresh data always replaces it.
 */
class DataCache(context: Context) {
    private val dir = File(context.cacheDir, "data")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true }

    private fun file(account: String, key: String) =
        File(dir, (account + "|" + key).hashCode().toUInt().toString(16) + ".json")

    suspend fun <T> read(account: String, key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        val f = file(account, key)
        if (!f.exists()) null else runCatching { json.decodeFromString(serializer, f.readText()) }.getOrNull()
    }

    suspend fun <T> write(account: String, key: String, value: T, serializer: KSerializer<T>) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val f = file(account, key)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            tmp.renameTo(f)
        }
    }

    /** A page's rows (see [dev.nitrate.android.viewmodel.RowsViewModel]). */
    suspend fun read(account: String, pageKey: String): CachedPage? = read(account, "rows/$pageKey", CachedPage.serializer())

    suspend fun write(account: String, pageKey: String, page: CachedPage) = write(account, "rows/$pageKey", page, CachedPage.serializer())
}
