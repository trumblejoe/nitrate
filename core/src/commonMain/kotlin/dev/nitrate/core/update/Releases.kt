package dev.nitrate.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A published build of one app (phone or TV) that can be installed over this one. */
data class AppRelease(
    val tag: String,
    val title: String,
    val versionCode: Int,
    val prerelease: Boolean,
    val apkName: String,
    val apkUrl: String,
    /** The release's SHA256SUMS file, if it has one. */
    val sumsUrl: String?,
    /** What's new, as plain lines. */
    val notes: List<String>,
)

/** Which builds this install should update to. */
enum class UpdateChannel {
    /** Dev-key builds (app id ending .dev): dev pre-releases. */
    DEV,

    /** Release-key builds: full releases only. */
    STABLE,

    /** Local debug builds: never updated from releases. */
    NONE;

    companion object {
        fun forAppId(appId: String) = when {
            appId.endsWith(".debug") -> NONE
            appId.endsWith(".dev") -> DEV
            else -> STABLE
        }
    }
}

/**
 * Reads GitHub's release list. Releases name each app's APK "nitrate-<app>-<version>.apk" and
 * carry their version code in a hidden note ("nitrate-version-code: 1012"); dev releases are
 * tagged dev-<run>, which also gives the code (1000 + run) for older ones.
 */
object Releases {
    const val REPO = "trumblejoe/nitrate"
    /** Releases a page holds; GitHub lists them by tag name as text (dev-9 before dev-14), so read every page. */
    const val PAGE_SIZE = 100

    fun listUrl(page: Int) = "https://api.github.com/repos/$REPO/releases?per_page=$PAGE_SIZE&page=$page"

    /** How many releases a page of the list held, drafts and other apps included. */
    fun pageCount(body: String): Int = runCatching { (json.parseToJsonElement(body) as? JsonArray)?.size }.getOrNull() ?: 0

    private val json = Json { ignoreUnknownKeys = true }
    private val codeNote = Regex("""nitrate-version-code:\s*(\d+)""")

    /** Releases with an APK for [app] ("mobile" or "tv"), newest first. */
    fun parse(body: String, app: String): List<AppRelease> {
        val list = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return emptyList()
        return list.mapNotNull { it as? JsonObject }.mapNotNull { r ->
            if (r.bool("draft")) return@mapNotNull null
            val tag = r.str("tag_name") ?: return@mapNotNull null
            val notesText = r.str("body").orEmpty()
            val code = versionCode(tag, notesText) ?: return@mapNotNull null
            val assets = (r["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val apk = assets.firstOrNull { a -> a.str("name")?.let { it.startsWith("nitrate-$app-") && it.endsWith(".apk") } == true } ?: return@mapNotNull null
            AppRelease(
                tag = tag,
                title = r.str("name") ?: tag,
                versionCode = code,
                prerelease = r.bool("prerelease"),
                apkName = apk.str("name")!!,
                apkUrl = apk.str("browser_download_url") ?: return@mapNotNull null,
                sumsUrl = assets.firstOrNull { it.str("name") == "SHA256SUMS" }?.str("browser_download_url"),
                notes = notes(notesText),
            )
        }.sortedByDescending { it.versionCode }
    }

    /** The release to update to, if any is newer than [currentCode] on [channel]. */
    fun newest(releases: List<AppRelease>, currentCode: Int, channel: UpdateChannel): AppRelease? = when (channel) {
        UpdateChannel.NONE -> null
        UpdateChannel.DEV -> releases.filter { it.prerelease }.maxByOrNull { it.versionCode }
        UpdateChannel.STABLE -> releases.filter { !it.prerelease }.maxByOrNull { it.versionCode }
    }?.takeIf { it.versionCode > currentCode }

    fun versionCode(tag: String, notes: String): Int? =
        codeNote.find(notes)?.groupValues?.get(1)?.toIntOrNull()
            ?: tag.removePrefix("dev-").takeIf { tag.startsWith("dev-") }?.toIntOrNull()?.let { 1000 + it }

    /** The expected SHA-256 (hex) of [fileName] in a SHA256SUMS file. */
    fun checksum(sums: String, fileName: String): String? = sums.lineSequence()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].trimStart('*') == fileName }
        ?.get(0)?.lowercase()

    /** The "What's new" lines of release notes (list items), without the hidden markers. */
    private fun notes(body: String): List<String> = body.lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { it.drop(2).trim() }
        .filter { it.isNotEmpty() }
        .take(12)

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonArray?.orEmpty() = this ?: emptyList()
}
