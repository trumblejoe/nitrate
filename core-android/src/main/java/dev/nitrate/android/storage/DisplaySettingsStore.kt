package dev.nitrate.android.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.PageKeys
import dev.nitrate.core.pages.PageLayout
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.displayDataStore by preferencesDataStore(name = "display_settings")

/**
 * Per-user display settings: row overrides (row key to [RowDisplay]) and page view options.
 *
 * Each user's entries are a flat map of name to JSON ("rows", "view:<page>") so the whole set
 * can be exported to, and imported from, the server for [SettingsSync].
 */
class DisplaySettingsStore(private val context: Context) {
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Called after any local change, with the user whose settings changed. */
    var onChanged: (userId: String) -> Unit = {}

    private fun prefix(userId: String) = "u_$userId::"
    private fun prefKey(userId: String, name: String) = stringPreferencesKey(prefix(userId) + name)

    fun overrides(userId: String): Flow<Map<String, RowDisplay>> =
        context.displayDataStore.data.map { prefs -> decodeRows(prefs[prefKey(userId, ROWS)]) }

    suspend fun save(userId: String, row: MediaRow, display: RowDisplay) {
        context.displayDataStore.edit { prefs ->
            val current = decodeRows(prefs[prefKey(userId, ROWS)])
            val updated = if (display == row.defaultDisplay) current - row.key else current + (row.key to display)
            prefs[prefKey(userId, ROWS)] = json.encodeToString(updated)
        }
        onChanged(userId)
    }

    /** Saved view options for a page (e.g. a library), or [default] when nothing is stored. */
    inline fun <reified T> view(userId: String, pageKey: String, default: T): Flow<T> = stored(userId, VIEW + pageKey, default)

    /** A stored entry decoded as [T], or [default] when missing or unreadable. */
    @PublishedApi
    internal inline fun <reified T> stored(userId: String, name: String, default: T): Flow<T> = rawFlow(userId, name).map { raw ->
        raw?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: default
    }

    suspend inline fun <reified T> saveView(userId: String, pageKey: String, value: T) =
        saveRaw(userId, VIEW + pageKey, json.encodeToString(value))

    @PublishedApi
    internal fun rawFlow(userId: String, name: String): Flow<String?> =
        context.displayDataStore.data.map { it[prefKey(userId, name)] }

    @PublishedApi
    internal suspend fun saveRaw(userId: String, name: String, raw: String) {
        context.displayDataStore.edit { it[prefKey(userId, name)] = raw }
        onChanged(userId)
    }

    // ---- Pages ----

    /** A page's row layout (Home, a library, or a custom page). */
    fun layout(userId: String, pageKey: String): Flow<PageLayout> = stored(userId, LAYOUT + pageKey, PageLayout())

    suspend fun saveLayout(userId: String, pageKey: String, layout: PageLayout) =
        if (layout.entries.isEmpty()) removeRaw(userId, LAYOUT + pageKey) else saveRaw(userId, LAYOUT + pageKey, json.encodeToString(layout))

    fun customPages(userId: String): Flow<List<CustomPage>> = stored(userId, PAGES, emptyList())

    suspend fun saveCustomPages(userId: String, pages: List<CustomPage>) = saveRaw(userId, PAGES, json.encodeToString(pages))

    /** Deletes a custom page and its rows. */
    suspend fun deleteCustomPage(userId: String, id: String) {
        val pages = customPages(userId).first().filterNot { it.id == id }
        saveCustomPages(userId, pages)
        removeRaw(userId, LAYOUT + PageKeys.custom(id))
    }

    /** What to show about items (ratings, overviews, badges...). */
    fun prefs(userId: String): Flow<DisplayPrefs> = stored(userId, PREFS, DisplayPrefs())

    suspend fun savePrefs(userId: String, prefs: DisplayPrefs) = saveRaw(userId, PREFS, json.encodeToString(prefs))

    @PublishedApi
    internal suspend fun removeRaw(userId: String, name: String) {
        context.displayDataStore.edit { it.remove(prefKey(userId, name)) }
        onChanged(userId)
    }

    /** Whether this user has local changes the server hasn't received. Kept apart from [export]. */
    suspend fun isDirty(userId: String): Boolean =
        context.displayDataStore.data.first()[stringPreferencesKey("dirty_$userId")] == "1"

    suspend fun setDirty(userId: String, dirty: Boolean) {
        context.displayDataStore.edit { if (dirty) it[stringPreferencesKey("dirty_$userId")] = "1" else it.remove(stringPreferencesKey("dirty_$userId")) }
    }

    /** All of a user's entries, name to JSON. */
    suspend fun export(userId: String): Map<String, String> {
        val prefix = prefix(userId)
        return context.displayDataStore.data.first().asMap()
            .filterKeys { it.name.startsWith(prefix) }
            .mapNotNull { (key, value) -> (value as? String)?.let { key.name.removePrefix(prefix) to it } }
            .toMap()
    }

    /** Replaces a user's entries with [entries]. Does not trigger [onChanged]. */
    suspend fun import(userId: String, entries: Map<String, String>) {
        val prefix = prefix(userId)
        context.displayDataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(prefix) }.forEach { prefs.remove(it) }
            entries.forEach { (name, raw) -> prefs[prefKey(userId, name)] = raw }
        }
    }

    private fun decodeRows(raw: String?): Map<String, RowDisplay> =
        raw?.let { runCatching { json.decodeFromString<Map<String, RowDisplay>>(it) }.getOrNull() }.orEmpty()

    @PublishedApi
    internal companion object {
        const val ROWS = "rows"
        const val VIEW = "view:"
        const val LAYOUT = "layout:"
        const val PAGES = "pages"
        const val PREFS = "prefs"
    }
}
