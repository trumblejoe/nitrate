package dev.nitrate.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleasesTest {
    private fun release(tag: String, pre: Boolean, body: String = "", apps: List<String> = listOf("mobile", "tv")) = """
        {"tag_name":"$tag","name":"Nitrate $tag","prerelease":$pre,"draft":false,"body":${"\"" + body.replace("\n", "\\n") + "\""},
         "assets":[${(apps.map { """{"name":"nitrate-$it-$tag.apk","browser_download_url":"https://example/$tag/nitrate-$it-$tag.apk"}""" } + """{"name":"SHA256SUMS","browser_download_url":"https://example/$tag/SHA256SUMS"}""").joinToString(",")}]}
    """.trimIndent()

    private val list = "[" + listOf(
        release("dev-12", true, "Phone: ...\n\n## What's new\n- Cressida ink colours\n- Auto-update\n<!-- nitrate-version-code: 1012 -->"),
        release("dev-11", true, apps = listOf("tv")),
        release("v0.3.0", false, "<!-- nitrate-version-code: 1010 -->"),
        release("dev-9", true),
    ).joinToString(",") + "]"

    @Test
    fun `picks the newest build for this app and channel`() {
        val phone = Releases.parse(list, "mobile")
        assertEquals(listOf("dev-12", "v0.3.0", "dev-9"), phone.map { it.tag })
        assertEquals(listOf("Cressida ink colours", "Auto-update"), phone.first().notes)
        assertEquals("dev-12", Releases.newest(phone, 1009, UpdateChannel.DEV)?.tag)
        assertNull(Releases.newest(phone, 1012, UpdateChannel.DEV))
        assertEquals("v0.3.0", Releases.newest(phone, 1003, UpdateChannel.STABLE)?.tag)
        assertNull(Releases.newest(phone, 1, UpdateChannel.NONE))
        // The TV app sees dev-11, which has no phone build.
        assertEquals(1011, Releases.parse(list, "tv").first { it.tag == "dev-11" }.versionCode)
    }

    @Test
    fun `channels follow the app id and checksums are read from SHA256SUMS`() {
        assertEquals(UpdateChannel.DEV, UpdateChannel.forAppId("dev.nitrate.app.dev"))
        assertEquals(UpdateChannel.NONE, UpdateChannel.forAppId("dev.nitrate.tv.debug"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.forAppId("dev.nitrate.app"))
        val sums = "abc123  nitrate-mobile-0.0.0-dev.12.apk\nDEF456 *nitrate-tv-0.0.0-dev.12.apk\n"
        assertEquals("abc123", Releases.checksum(sums, "nitrate-mobile-0.0.0-dev.12.apk"))
        assertEquals("def456", Releases.checksum(sums, "nitrate-tv-0.0.0-dev.12.apk"))
        assertNull(Releases.checksum(sums, "other.apk"))
    }
}
