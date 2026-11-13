package dev.nitrate.android.storage

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nitrate.core.pages.CustomPage
import dev.nitrate.core.playback.LanguageChoice
import dev.nitrate.core.playback.TrackMemoryStore
import dev.nitrate.core.pages.DeviceKind
import dev.nitrate.core.pages.DisplayPrefs
import dev.nitrate.core.pages.DisplayProfile
import dev.nitrate.core.pages.DisplayProfiles
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
 * Per-user display settings: row overrides (row key to [RowDisplay]), page layouts and view
 * options, what's shown about items, custom pages and per-series languages.
 *
 * Each user's entries are a flat map of name to JSON so the whole set can be synced with the
 * server ([SettingsSync]). Most settings belong to a display profile ([DisplayProfiles]): this
 * device's choice of profile is kept here only ("active_<user>", never synced), and names are
 * stored under that profile. A built-in profile is never written to: the first change made on one
 * saves into a new profile named after this device.
 */
class DisplaySettingsStore(
    private val context: Context,
    /** Which built-in profile this device starts on. */
    val deviceKind: DeviceKind = DeviceKind.PHONE,
    /** Name for a profile started by a change made on a built-in one. */
    private val deviceName: String = "This device",
) {
    @PublishedApi
    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Called after any local change, with the user whose settings changed. */
    var onChanged: (userId: String) -> Unit = {}

    private fun prefix(userId: String) = "u_$userId::"
    private fun prefKey(userId: String, name: String) = stringPreferencesKey(prefix(userId) + name)
    private fun activeKey(userId: String) = stringPreferencesKey("active_$userId")

    // ---- Profiles ----

    private fun customProfiles(prefs: Preferences, userId: String): List<DisplayProfile> =
        prefs[prefKey(userId, DisplayProfiles.PROFILES)]?.let { DisplayProfiles.profilesOf(mapOf(DisplayProfiles.PROFILES to it)) }.orEmpty()

    /** The profile this device uses for [userId]: its choice, else "My settings" if there is one, else the built-in for this kind of device. */
    @PublishedApi
    internal fun activeId(prefs: Preferences, userId: String): String {
        val custom = customProfiles(prefs, userId)
        val chosen = prefs[activeKey(userId)]
        return when {
            chosen != null && (DisplayProfiles.isBuiltIn(chosen) || custom.any { it.id == chosen }) -> chosen
            custom.any { it.id == DisplayProfiles.MIGRATED_ID } -> DisplayProfiles.MIGRATED_ID
            else -> deviceKind.builtInId
        }
    }

    @PublishedApi
    internal fun resolve(prefs: Preferences, userId: String, name: String) =
        prefKey(userId, DisplayProfiles.scoped(activeId(prefs, userId), name))

    /** The user's own profiles (built-in ones are [DisplayProfiles.BUILT_IN]). */
    fun profiles(userId: String): Flow<List<DisplayProfile>> = context.displayDataStore.data.map { customProfiles(it, userId) }

    /** The id of the profile this device uses. */
    fun activeProfile(userId: String): Flow<String> = context.displayDataStore.data.map { activeId(it, userId) }

    /** The profile this device uses for whoever [userId] says is signed in (re-read on every change). */
    fun activeProfileOf(userId: () -> String?): Flow<String?> = context.displayDataStore.data.map { prefs -> userId()?.let { activeId(prefs, it) } }

    /** This device uses [profileId] from now on (only here). */
    suspend fun useProfile(userId: String, profileId: String) {
        context.displayDataStore.edit { it[activeKey(userId)] = profileId }
    }

    /** Starts a profile called [name] as a copy of [from] (a built-in one starts from the defaults), and uses it here. */
    suspend fun createProfile(userId: String, name: String, from: String): String {
        val id = newId()
        context.displayDataStore.edit { prefs ->
            val copies = DisplayProfiles.copy(entries(prefs, userId), from, id)
            copies.forEach { (n, raw) -> prefs[prefKey(userId, n)] = raw }
            prefs[prefKey(userId, DisplayProfiles.PROFILES)] = DisplayProfiles.profilesJson(customProfiles(prefs, userId) + DisplayProfile(id, name.trim()))
            prefs[activeKey(userId)] = id
        }
        onChanged(userId)
        return id
    }

    suspend fun renameProfile(userId: String, profileId: String, name: String) {
        context.displayDataStore.edit { prefs ->
            prefs[prefKey(userId, DisplayProfiles.PROFILES)] =
                DisplayProfiles.profilesJson(customProfiles(prefs, userId).map { if (it.id == profileId) it.copy(name = name.trim()) else it })
        }
        onChanged(userId)
    }

    /** Deletes one of the user's profiles everywhere; devices using it go back to their built-in one. */
    suspend fun deleteProfile(userId: String, profileId: String) {
        if (DisplayProfiles.isBuiltIn(profileId)) return
        context.displayDataStore.edit { prefs ->
            entries(prefs, userId).keys.filter { it.startsWith(DisplayProfiles.prefix(profileId)) }.forEach { prefs.remove(prefKey(userId, it)) }
            val left = customProfiles(prefs, userId).filterNot { it.id == profileId }
            if (left.isEmpty()) prefs.remove(prefKey(userId, DisplayProfiles.PROFILES)) else prefs[prefKey(userId, DisplayProfiles.PROFILES)] = DisplayProfiles.profilesJson(left)
            if (prefs[activeKey(userId)] == profileId) prefs.remove(activeKey(userId))
        }
        onChanged(userId)
    }

    /** What's shown about items in another profile (for importing its radial menu). */
    fun prefsOf(userId: String, profileId: String): Flow<DisplayPrefs> = context.displayDataStore.data.map { prefs ->
        prefs[prefKey(userId, DisplayProfiles.scoped(profileId, PREFS))]?.let { runCatching { json.decodeFromString<DisplayPrefs>(it) }.getOrNull() } ?: DisplayPrefs()
    }

    /**
     * The profile to write [name] to: the active one, or for a profile setting on a built-in
     * profile, a new profile named after this device (built-in profiles never change).
     */
    private fun writableProfile(prefs: MutablePreferences, userId: String, name: String): String {
        val active = activeId(prefs, userId)
        if (DisplayProfiles.isShared(name) || !DisplayProfiles.isBuiltIn(active)) return active
        val id = newId()
        val taken = customProfiles(prefs, userId).map { it.name }.toSet()
        val label = generateSequence(1) { it + 1 }.map { if (it == 1) deviceName else "$deviceName $it" }.first { it !in taken }
        prefs[prefKey(userId, DisplayProfiles.PROFILES)] = DisplayProfiles.profilesJson(customProfiles(prefs, userId) + DisplayProfile(id, label))
        prefs[activeKey(userId)] = id
        return id
    }

    private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

    private fun entries(prefs: Preferences, userId: String): Map<String, String> {
        val prefix = prefix(userId)
        return prefs.asMap().mapNotNull { (key, value) -> if (key.name.startsWith(prefix) && value is String) key.name.removePrefix(prefix) to value else null }.toMap()
    }

    /** Moves settings saved before profiles into "My settings" (once; nothing happens after that). */
    suspend fun migrateLocal(userId: String): Boolean {
        var migrated = false
        context.displayDataStore.edit { prefs ->
            val current = entries(prefs, userId)
            if (!DisplayProfiles.isLegacy(current)) return@edit
            val next = DisplayProfiles.migrate(current) { old -> sharedFromLegacy(old[PREFS]) }
            current.keys.forEach { prefs.remove(prefKey(userId, it)) }
            next.forEach { (n, raw) -> prefs[prefKey(userId, n)] = raw }
            migrated = true
        }
        return migrated
    }

    private fun sharedFromLegacy(rawPrefs: String?): String? {
        val old = rawPrefs?.let { runCatching { json.decodeFromString<DisplayPrefs>(it) }.getOrNull() } ?: return null
        return json.encodeToString(SharedPrefs(hideSpoilers = old.hideSpoilers))
    }

    // ---- Rows ----

    fun overrides(userId: String): Flow<Map<String, RowDisplay>> =
        context.displayDataStore.data.map { prefs -> decodeRows(prefs[resolve(prefs, userId, ROWS)]) }

    suspend fun save(userId: String, row: MediaRow, display: RowDisplay) {
        context.displayDataStore.edit { prefs ->
            val current = decodeRows(prefs[resolve(prefs, userId, ROWS)])
            val updated = if (display == row.defaultDisplay) current - row.key else current + (row.key to display)
            if (updated == current) return@edit
            prefs[prefKey(userId, DisplayProfiles.scoped(writableProfile(prefs, userId, ROWS), ROWS))] = json.encodeToString(updated)
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
        context.displayDataStore.data.map { it[resolve(it, userId, name)] }

    @PublishedApi
    internal suspend fun saveRaw(userId: String, name: String, raw: String) {
        var changed = false
        context.displayDataStore.edit { prefs ->
            if (prefs[resolve(prefs, userId, name)] == raw) return@edit
            prefs[prefKey(userId, DisplayProfiles.scoped(writableProfile(prefs, userId, name), name))] = raw
            changed = true
        }
        if (changed) onChanged(userId)
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

    /** What to show about items (ratings, overviews, badges...); spoiler hiding is the same on every device. */
    fun prefs(userId: String): Flow<DisplayPrefs> = context.displayDataStore.data.map { prefs ->
        val own = prefs[resolve(prefs, userId, PREFS)]?.let { runCatching { json.decodeFromString<DisplayPrefs>(it) }.getOrNull() } ?: DisplayPrefs()
        val shared = prefs[prefKey(userId, DisplayProfiles.SHARED)]?.let { runCatching { json.decodeFromString<SharedPrefs>(it) }.getOrNull() }
        if (shared != null) own.copy(hideSpoilers = shared.hideSpoilers) else own
    }

    suspend fun savePrefs(userId: String, prefs: DisplayPrefs) {
        val before = prefs(userId).first()
        if (before.hideSpoilers != prefs.hideSpoilers) saveRaw(userId, DisplayProfiles.SHARED, json.encodeToString(SharedPrefs(hideSpoilers = prefs.hideSpoilers)))
        // Only a change to the profile's own settings is saved to it (so turning spoilers on or
        // off doesn't start a profile from a built-in one).
        if (before.copy(hideSpoilers = false) != prefs.copy(hideSpoilers = false)) saveRaw(userId, PREFS, json.encodeToString(prefs))
    }

    /** Audio and subtitle languages remembered per show, as a [TrackMemoryStore] for [userId]. */
    fun trackMemory(userId: () -> String?): TrackMemoryStore = object : TrackMemoryStore {
        override suspend fun get(seriesId: String): LanguageChoice? =
            userId()?.let { stored<Map<String, LanguageChoice>>(it, TRACKS, emptyMap()).first()[seriesId] }

        override suspend fun set(seriesId: String, choice: LanguageChoice) {
            val user = userId() ?: return
            val all = stored<Map<String, LanguageChoice>>(user, TRACKS, emptyMap()).first()
            if (all[seriesId] != choice) saveRaw(user, TRACKS, json.encodeToString(all + (seriesId to choice)))
        }
    }

    @PublishedApi
    internal suspend fun removeRaw(userId: String, name: String) {
        var changed = false
        context.displayDataStore.edit { prefs ->
            // Nothing stored here (e.g. on a built-in profile): nothing to remove.
            if (prefs[resolve(prefs, userId, name)] == null) return@edit
            prefs.remove(resolve(prefs, userId, name))
            changed = true
        }
        if (changed) onChanged(userId)
    }

    /** The last copy agreed with the server, for merging setting by setting ([SettingsSync]). */
    suspend fun syncBase(userId: String): Map<String, String>? =
        context.displayDataStore.data.first()[stringPreferencesKey("base_$userId")]?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }

    suspend fun setSyncBase(userId: String, base: Map<String, String>) {
        context.displayDataStore.edit { it[stringPreferencesKey("base_$userId")] = json.encodeToString(base) }
    }

    /** All of a user's entries, name to JSON. */
    suspend fun export(userId: String): Map<String, String> = entries(context.displayDataStore.data.first(), userId)

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
        const val TRACKS = "tracks"
    }
}

/** Display settings that are the same on every device, whatever its profile. */
@kotlinx.serialization.Serializable
internal data class SharedPrefs(val hideSpoilers: Boolean = false)
