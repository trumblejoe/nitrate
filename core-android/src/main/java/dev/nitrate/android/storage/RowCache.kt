package dev.nitrate.android.storage

import android.content.Context
import dev.nitrate.core.rows.MediaRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A page's rows as last loaded, shown at once next time while fresh ones load. */
@Serializable
data class CachedPage(val builtIn: List<MediaRow>, val custom: Map<String, MediaRow> = emptyMap())

/**
 * The last rows each page showed, per server and user, in the app's cache folder (Android may
 * clear it; then pages just load as before). Only ever a head start: fresh rows replace them.
 */
class RowCache(context: Context) {
    private val dir = File(context.cacheDir, "rows")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun file(account: String, pageKey: String) =
        File(dir, (account + "|" + pageKey).hashCode().toUInt().toString(16) + ".json")

    suspend fun read(account: String, pageKey: String): CachedPage? = withContext(Dispatchers.IO) {
        val f = file(account, pageKey)
        if (!f.exists()) null else runCatching { json.decodeFromString(CachedPage.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun write(account: String, pageKey: String, page: CachedPage) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val f = file(account, pageKey)
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedPage.serializer(), page))
            tmp.renameTo(f)
        }
    }
}
