package dev.nitrate.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPathTest {
    @Test fun cadence() {
        assertTrue(VideoPath.cadenceFits(23.976f, 23.976f))
        assertTrue(VideoPath.cadenceFits(24f, 120f))
        assertTrue(VideoPath.cadenceFits(25f, 50f))
        assertTrue(VideoPath.cadenceFits(29.97f, 59.94f))
        assertFalse(VideoPath.cadenceFits(23.976f, 60f))
        assertFalse(VideoPath.cadenceFits(23.976f, 59.94f))
        assertFalse(VideoPath.cadenceFits(25f, 60f))
        assertFalse(VideoPath.cadenceFits(24f, 23.976f))
    }

    @Test fun judderNote() {
        val p = VideoPath(inFps = 23.976f, displayHz = 60f)
        assertTrue(p.notes().single().startsWith("23.976 fps on a 60 Hz display"))
        assertTrue(VideoPath(inFps = 23.976f, displayHz = 23.976f).notes().isEmpty())
    }

    @Test fun hdrPaths() {
        assertEquals(
            "Dolby Vision (profile 7) → Dolby Vision (converted to profile 8.1 here), shown as Dolby Vision",
            VideoPath(inRange = "Dolby Vision", fileRange = "Dolby Vision (profile 7)", profile7 = Profile7Playback.AS_PROFILE_8, displayHdr = setOf("Dolby Vision", "HDR10")).hdrPath(),
        )
        assertEquals(
            "Dolby Vision (profile 7) → HDR10 (its base layer), shown as HDR10",
            VideoPath(inRange = "HDR10", fileRange = "Dolby Vision (profile 7)", profile7 = Profile7Playback.BASE_LAYER, displayHdr = setOf("HDR10")).hdrPath(),
        )
        assertEquals("HDR10, sent as HDR. Android reports no HDR on this display (if the picture looks dim, try setting this device to always send HDR).", VideoPath(inRange = "HDR10", displayHdr = emptySet()).hdrPath())
        assertEquals("HDR10, sent as HDR. Android reports no HDR, but your TV showed it in the TV check (if the picture looks dim, try setting Fire TV to Always HDR).",
            VideoPath(inRange = "HDR10", displayHdr = emptySet(), tvShowedHdr = true, fireTv = true).hdrPath())
        assertNull(VideoPath(inRange = "SDR").hdrPath())
    }

    @Test fun rows() {
        val r = VideoPath(
            inCodec = "hevc", inWidth = 3840, inHeight = 2160, inFps = 23.976f, inRange = "HDR10",
            displayWidth = 3840, displayHeight = 2160, displayHz = 23.976f, displayHdr = setOf("HDR10", "HLG"),
            droppedFrames = 2, bufferedMs = 24_500, networkBps = 80_000_000, fileBps = 45_000_000,
            subtitles = "Drawn by libass (as the file styles them)", secondSubtitle = "English",
        ).rows().toMap()
        assertEquals("HEVC · 3840×2160 · 23.976 fps · HDR10", r["Picture"])
        assertEquals("3840×2160 @ 23.976 Hz · HDR10, HLG", r["Display"])
        assertEquals("2 dropped frames · 24.5 s ready · network 80.0 Mbps (video needs 45.0 Mbps)", r["Smoothness"])
        assertEquals("Drawn by libass (as the file styles them) · second: English", r["Subtitles"])
        assertNull(r["Note"])
    }

    @Test fun slowNetworkAndDrops() {
        val notes = VideoPath(droppedFrames = 120, bufferedMs = 1_000, networkBps = 10_000_000, fileBps = 40_000_000).notes()
        assertEquals(2, notes.size)
    }

    @Test fun convertingTo() {
        assertEquals(
            "H.264 · up to 1080p · 8.0 Mbps",
            VideoPath.convertingTo("http://s/videos/x/master.m3u8?VideoCodec=h264,hevc&AudioCodec=aac&VideoBitrate=8000000&MaxHeight=1080&api=1"),
        )
        assertNull(VideoPath.convertingTo("http://s/Videos/x/stream.mkv?static=true&MediaSourceId=1"))
        assertNull(VideoPath.convertingTo("http://s/Videos/x/stream.mkv"))
    }
}
