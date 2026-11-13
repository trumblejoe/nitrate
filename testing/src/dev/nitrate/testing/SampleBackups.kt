package dev.nitrate.testing

import dev.nitrate.android.backup.RestorePreview
import dev.nitrate.core.backup.BackupSummary
import dev.nitrate.core.backup.DeviceBackups
import dev.nitrate.core.backup.DeviceSettings
import dev.nitrate.core.playback.LearnedFailure
import dev.nitrate.core.playback.Override
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.SubtitleStyle
import dev.nitrate.core.playback.WritingSplit

/** Backups and a restore preview for screenshot tests. */
object SampleBackups {
    private val NOW = System.currentTimeMillis()

    val list = listOf(
        BackupSummary("stick-1", "Living room Fire Stick", "Amazon AFTKA", "tv", NOW - 3 * 3_600_000L),
        BackupSummary("pixel-1", "Pixel 9", "Google Pixel 9", "mobile", NOW - 2 * 86_400_000L),
    )

    val preview: RestorePreview by lazy {
        val current = DeviceSettings(PlaybackSettings(overrides = mapOf("av1" to Override.ALWAYS)))
        val backup = DeviceSettings(
            PlaybackSettings(
                maxBitrate = 20_000_000,
                nightMode = true,
                writingSplit = WritingSplit.STACKED,
                subtitleStyle = SubtitleStyle(scale = 1.5f, textColor = 0xFFFFE680),
                learnedFailures = mapOf("dts" to LearnedFailure(2, "decoder", 1L)),
            ),
            pluginsEnabled = mapOf("writing" to true),
            pluginSettings = mapOf("writing" to """{"backupToServer":false}"""),
        )
        val next = DeviceBackups.restored(current, backup, sameModel = false)
        RestorePreview(list[0], current, next, DeviceBackups.differences(current, next, mapOf("writing" to "Writing pane")), sameModel = false)
    }
}
