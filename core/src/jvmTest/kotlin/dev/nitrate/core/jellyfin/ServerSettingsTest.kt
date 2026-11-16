package dev.nitrate.core.jellyfin

import dev.nitrate.core.account.SettingReach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerSettingsTest {
    private val me = Json.parseToJsonElement(
        """{"Name":"joe","Configuration":{"AudioLanguagePreference":"jpn","PlayDefaultAudioTrack":false,"SubtitleLanguagePreference":"eng",
        |"SubtitleMode":"Smart","RememberAudioSelections":true,"DisplayMissingEpisodes":true,"MyMediaExcludes":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],
        |"OrderedViews":[],"EnableNextEpisodeAutoPlay":true},
        |"Policy":{"IsAdministrator":false,"EnableVideoPlaybackTranscoding":false,"RemoteClientBitrateLimit":8000000,"EnableContentDownloading":true,
        |"EnableAllFolders":true,"MaxParentalRating":13,"BlockedTags":["gore"]}}""".trimMargin(),
    ).jsonObject

    @Test fun `account settings read plainly and say where they apply`() {
        val settings = JellyfinServerSettings.from(me, null, mapOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" to "Home videos"))
        fun value(label: String) = settings.groups.flatMap { it.settings }.single { it.label == label }
        assertEquals("Japanese (jpn)", value("Preferred audio language").value)
        assertEquals("Smart", value("Subtitle mode").value)
        assertEquals(SettingReach.APPLIES, value("Subtitle mode").reach)
        assertEquals(SettingReach.JELLYFIN_APPS, value("Remember audio selections").reach)
        assertEquals("No", value("Video can be converted").value)
        assertEquals("8 Mbps", value("Bitrate limit away from home").value)
        // Library ids are named.
        assertEquals("Home videos", value("Left out of My Media").value)
        assertEquals("gore", value("Blocked tags").value)
        assertEquals("Rating level 13", value("Highest age rating").value)
    }
}
