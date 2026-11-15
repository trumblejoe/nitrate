package dev.nitrate.core.diagnostics

import dev.nitrate.core.model.ItemImages
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.pages.StreamInfo
import dev.nitrate.core.pages.VideoStreamInfo
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.caps
import dev.nitrate.core.playback.video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryHealthTest {
    private val art = ItemImages(primary = "p", backdrops = listOf("b"))
    private fun movie(id: String, name: String, year: Int? = 2001, images: ItemImages = art) =
        MediaItem(id, name, ItemKind.MOVIE, productionYear = year, images = images)
    private fun streams(codec: String = "h264", bits: Int = 8, ranges: Set<String> = setOf("sdr"), audio: Set<String> = setOf("aac")) =
        StreamInfo(setOf(VideoStreamInfo(codec, bits, ranges)), audio)
    private fun title(item: MediaItem, matched: Boolean = true, overview: Boolean = true, files: List<Pair<MediaItem, StreamInfo>> = listOf(item to streams())) =
        HealthTitle(item, overview, matched, files)

    private val device = caps() // h264 + hevc (10-bit), aac + ac3, HDR10 + HLG

    @Test fun aHealthyLibraryHasNothingToSay() {
        val found = LibraryHealth.findings(HealthScan(listOf(title(movie("m1", "Night Shift"))), emptyList()), device, PlaybackSettings())
        assertTrue(found.toString(), found.isEmpty())
    }

    @Test fun findsMissingArtworkDescriptionsAndMatches() {
        val found = LibraryHealth.findings(HealthScan(listOf(
            title(movie("m1", "No Art", images = ItemImages(backdrops = listOf("b")))),
            title(movie("m2", "Unknown"), matched = false),
            title(movie("m3", "Quiet"), overview = false),
        ), emptyList()), device, PlaybackSettings())
        assertEquals("No poster", found.getValue(HealthCheck.NO_ARTWORK).single().detail)
        assertEquals(listOf(HealthFix.FIND_IMAGES), found.getValue(HealthCheck.NO_ARTWORK).single().fixes)
        assertEquals("m2", found.getValue(HealthCheck.UNMATCHED).single().item.id)
        assertEquals("m3", found.getValue(HealthCheck.NO_DESCRIPTION).single().item.id)
    }

    @Test fun findsCopiesOfOneMovieToMerge() {
        val found = LibraryHealth.findings(HealthScan(listOf(
            title(movie("a", "Night Shift", 2001)), title(movie("b", "Night shift", 2001)), title(movie("c", "Night Shift", 1985)),
        ), emptyList()), device, PlaybackSettings())
        val dup = found.getValue(HealthCheck.DUPLICATES).single()
        assertEquals("2 copies (2001)", dup.detail)
        assertEquals(listOf("b"), dup.related.map { it.id })
        assertEquals(listOf(HealthFix.MERGE), dup.fixes)
    }

    @Test fun findsWhatThisDeviceMustAlwaysConvert() {
        assertNull(LibraryHealth.whyConverts(streams(), device, PlaybackSettings()))
        assertEquals("AV1 video", LibraryHealth.whyConverts(streams(codec = "av1"), device, PlaybackSettings()))
        assertEquals("Dolby Vision on this display", LibraryHealth.whyConverts(streams(codec = "hevc", ranges = setOf("dovi")), device, PlaybackSettings()))
        // Dolby Vision with an HDR10 base layer plays as HDR10.
        assertNull(LibraryHealth.whyConverts(streams(codec = "hevc", ranges = setOf("dovi", "hdr10")), device, PlaybackSettings()))
        assertEquals("TrueHD/DTS sound", LibraryHealth.whyConverts(streams(audio = setOf("truehd", "dts")), device, PlaybackSettings()))
        assertNull(LibraryHealth.whyConverts(streams(audio = setOf("truehd", "ac3")), device, PlaybackSettings()))
        val eightBit = caps(video = listOf(video("h264"), video("hevc", tenBit = false)))
        assertEquals("10-bit HEVC", LibraryHealth.whyConverts(streams(codec = "hevc", bits = 10), eightBit, PlaybackSettings()))
        // Turned off by the user counts too.
        assertEquals("HEVC video", LibraryHealth.whyConverts(streams(codec = "hevc"), device, PlaybackSettings(overrides = mapOf("hevc" to Override.NEVER))))

        val show = MediaItem("s1", "Long Show", ItemKind.SERIES, images = art)
        val eps = (1..4).map { MediaItem("e$it", "Ep $it", ItemKind.EPISODE) }
        val found = LibraryHealth.findings(HealthScan(listOf(
            HealthTitle(show, true, true, eps.mapIndexed { i, e -> e to if (i < 3) streams(codec = "av1") else streams() }),
        ), emptyList()), device, PlaybackSettings())
        assertEquals("3 of 4 episodes: AV1 video", found.getValue(HealthCheck.ALWAYS_CONVERTS).single().detail)
    }

    @Test fun aDeviceWithNoDecodersReadIsNotBlamedForEverything() {
        val unread = caps(video = emptyList())
        val found = LibraryHealth.findings(HealthScan(listOf(title(movie("m1", "Night Shift"), files = listOf(movie("m1", "Night Shift") to streams(codec = "av1")))), emptyList()), unread, PlaybackSettings())
        assertTrue(found.toString(), HealthCheck.ALWAYS_CONVERTS !in found)
    }

    @Test fun groupsMissingEpisodesByShow() {
        val missing = listOf(3, 1, 7).map { n -> MediaItem("e$n", "Ep", ItemKind.EPISODE, seriesId = "s1", seriesName = "Long Show", seasonNumber = 2, indexNumber = n) }
        val found = LibraryHealth.findings(HealthScan(emptyList(), missing), device, PlaybackSettings())
        val f = found.getValue(HealthCheck.MISSING_EPISODES).single()
        assertEquals("s1", f.item.id)
        assertEquals("3 missing: S2 E1, S2 E3, S2 E7", f.detail)
    }
}
