package dev.nitrate.core.pages

import kotlinx.serialization.Serializable

/** A named set of display settings a device uses. Built-in ones are never changed. */
@Serializable
data class DisplayProfile(val id: String, val name: String)

/** Which built-in profile a device starts on. */
enum class DeviceKind(val builtInId: String) { TV("tv"), PHONE("phone"), TABLET("tablet") }

/**
 * Display profiles: each device uses one profile (its choice is kept on the device), so the TV,
 * a phone and a tablet can each look their own way. A user's display settings are a flat map of
 * names to JSON (synced to the server); profile settings are stored under "p:<profile>:<name>",
 * and a few things stay the same on every device ([isShared]).
 *
 * The built-in profiles (TV, Phone, Tablet) hold Nitrate's defaults and nothing is ever saved to
 * them: choosing one starts a new named profile from it, and a change made while a device is still
 * on one saves into a new profile instead.
 */
object DisplayProfiles {
    val BUILT_IN = listOf(DisplayProfile("tv", "TV"), DisplayProfile("phone", "Phone"), DisplayProfile("tablet", "Tablet"))

    /** The list of the user's own profiles. */
    const val PROFILES = "profiles"
    /** Settings that follow the user everywhere (spoiler hiding). */
    const val SHARED = "shared"
    /** The profile made from settings saved before profiles existed. */
    const val MIGRATED_ID = "migrated"
    const val MIGRATED_NAME = "My settings"

    private const val ROWS = "rows"
    private const val PREFS = "prefs"
    private const val VIEW = "view:"
    private const val LAYOUT = "layout:"
    private const val PAGES = "pages"
    private const val TRACKS = "tracks"

    fun isBuiltIn(id: String) = BUILT_IN.any { it.id == id }

    /**
     * Stays the same on every device: custom pages (and their rows), per-series languages, the
     * profile list and shared settings. Everything else belongs to a profile.
     */
    fun isShared(name: String) = name == PAGES || name == TRACKS || name == PROFILES || name == SHARED ||
        name.startsWith(LAYOUT + PageKeys.custom(""))

    /** Where [name] is stored for [profileId]. */
    fun scoped(profileId: String, name: String) = if (isShared(name)) name else "p:$profileId:$name"

    /** The prefix of every entry in [profileId]. */
    fun prefix(profileId: String) = "p:$profileId:"

    /** True when [entries] are from before profiles (profile settings stored without a prefix). */
    fun isLegacy(entries: Map<String, String>) = PROFILES !in entries && entries.keys.any { !it.startsWith("p:") && !isShared(it) }

    /**
     * Turns settings saved before profiles into the "My settings" profile: profile settings move
     * under its prefix, shared ones stay; [hideSpoilers] (read from the old prefs) becomes shared.
     */
    fun migrate(entries: Map<String, String>, sharedJson: (Map<String, String>) -> String?): Map<String, String> {
        if (!isLegacy(entries)) return entries
        val out = mutableMapOf<String, String>()
        entries.forEach { (name, raw) -> if (isShared(name) || name.startsWith("p:")) out[name] = raw else out[prefix(MIGRATED_ID) + name] = raw }
        sharedJson(entries)?.let { out[SHARED] = it }
        out[PROFILES] = profilesJson(listOf(DisplayProfile(MIGRATED_ID, MIGRATED_NAME)))
        return out
    }

    /** A copy of every entry of [from] under [to] (nothing, for a built-in). */
    fun copy(entries: Map<String, String>, from: String, to: String): Map<String, String> =
        entries.filterKeys { it.startsWith(prefix(from)) }.mapKeys { (k, _) -> prefix(to) + k.removePrefix(prefix(from)) }

    /** The entries without any of [profileId]'s. */
    fun without(entries: Map<String, String>, profileId: String): Map<String, String> = entries.filterKeys { !it.startsWith(prefix(profileId)) }

    fun profilesJson(list: List<DisplayProfile>): String =
        kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DisplayProfile.serializer()), list)

    fun profilesOf(entries: Map<String, String>): List<DisplayProfile> = entries[PROFILES]
        ?.let { runCatching { kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DisplayProfile.serializer()), it) }.getOrNull() }
        .orEmpty()

    /**
     * Three-way merge of a user's settings, entry by entry: what changed here since the last sync
     * ([base]) wins, everything else comes from the server. So two devices changing different
     * settings never undo each other. A null value is "removed".
     */
    fun merge(base: Map<String, String>, local: Map<String, String>, remote: Map<String, String>): Map<String, String> {
        val out = remote.toMutableMap()
        for (key in base.keys + local.keys) {
            val mine = local[key]
            if (mine == base[key]) continue
            if (mine == null) out.remove(key) else out[key] = mine
        }
        // The profile list merges profile by profile too: added on either side, removed on either
        // side, renamed here or there.
        val b = profilesOf(base).associateBy { it.id }
        val l = profilesOf(local).associateBy { it.id }
        val r = profilesOf(remote).associateBy { it.id }
        val gone = (b.keys - l.keys) + (b.keys - r.keys)
        val merged = (r.keys + l.keys).filter { it !in gone }.map { id -> l[id]?.takeIf { it != b[id] } ?: r[id] ?: l.getValue(id) }
        if (merged.isNotEmpty()) out[PROFILES] = profilesJson(merged) else out.remove(PROFILES)
        return out
    }
}
