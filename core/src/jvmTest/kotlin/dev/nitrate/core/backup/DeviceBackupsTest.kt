package dev.nitrate.core.backup

import dev.nitrate.core.playback.LearnedFailure
import dev.nitrate.core.playback.MaxResolution
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.SubtitleStyle
import dev.nitrate.core.playback.WritingSplit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceBackupsTest {
    private val stick = PlaybackSettings(
        maxBitrate = 20_000_000,
        nightMode = true,
        subtitleStyle = SubtitleStyle(scale = 1.5f, textColor = 0xFFFFE680),
        overrides = mapOf("hevc" to Override.NEVER),
        learnedFailures = mapOf("dts" to LearnedFailure(2, "decoder", 1L)),
        tenBitAllowed = setOf("hevc"),
    )
    private val backup = DeviceSettings(stick, mapOf("writing" to true), mapOf("writing" to """{"backupToServer":false}"""))
    private val phone = DeviceSettings(PlaybackSettings(overrides = mapOf("av1" to Override.ALWAYS)))

    @Test
    fun anotherModelGetsPreferencesButKeepsItsHardwareResults() {
        val next = DeviceBackups.restored(phone, backup, sameModel = false)
        assertEquals(20_000_000, next.playback.maxBitrate)
        assertTrue(next.playback.nightMode)
        assertEquals(1.5f, next.playback.subtitleStyle.scale)
        assertEquals(mapOf("av1" to Override.ALWAYS), next.playback.overrides)
        assertTrue(next.playback.learnedFailures.isEmpty())
        assertTrue(next.playback.tenBitAllowed.isEmpty())
        assertEquals(true, next.pluginsEnabled["writing"])
    }

    @Test
    fun sameModelGetsHardwareResultsToo() {
        val next = DeviceBackups.restored(phone, backup, sameModel = true)
        assertEquals(mapOf("hevc" to Override.NEVER), next.playback.overrides)
        assertEquals(setOf("hevc"), next.playback.tenBitAllowed)
    }

    @Test
    fun differencesListOnlyWhatChangesInWords() {
        val next = DeviceBackups.restored(phone, backup, sameModel = false)
        val diffs = DeviceBackups.differences(phone, next, mapOf("writing" to "Writing pane"))
        val lines = diffs.map { "${it.section} | ${it.setting} | ${it.from} → ${it.to}" }
        assertEquals(
            listOf(
                "Playback | Maximum bitrate | 120 Mbps → 20 Mbps",
                "Playback | Night mode | Off → On",
                "Subtitles | Size | 100% → 150%",
                "Subtitles | Colour | White → Yellow",
                "Plugins | Writing pane | Off → On",
                "Plugins | Writing pane: Backup to server | Default → Off",
            ),
            lines,
        )
    }

    @Test
    fun nothingDifferentIsAnEmptyList() {
        val same = DeviceSettings(PlaybackSettings(maxResolution = MaxResolution.FHD, writingSplit = WritingSplit.STACKED))
        assertTrue(DeviceBackups.differences(same, same).isEmpty())
    }

    @Test
    fun sameModelIgnoresCaseAndNeedsAModel() {
        val s = BackupSummary("a", "Stick", "Amazon AFTKA", "tv", 1L)
        assertTrue(DeviceBackups.sameModel(s, "amazon aftka"))
        assertTrue(!DeviceBackups.sameModel(s.copy(model = ""), ""))
    }

    @Test
    fun prefsIdKeepsOnlySafeCharacters() {
        assertEquals("nitrate-device-abc-123", DeviceBackups.prefsId("abc/-1 2?3"))
    }
}
