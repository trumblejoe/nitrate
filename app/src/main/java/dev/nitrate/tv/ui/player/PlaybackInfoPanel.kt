package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.tv.data.PlaybackPlan
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod

/** What the player is actually doing, and why: method, server reasons, streams and decoders. */
@Composable
fun PlaybackInfoPanel(plan: PlaybackPlan, videoDecoder: String?, audioDecoder: String?, modifier: Modifier = Modifier) {
    val streams = plan.source.mediaStreams.orEmpty()
    val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
    val audio = streams.firstOrNull { it.index == plan.audioIndex }
    val rows = buildList {
        add("Method" to when (plan.playMethod) {
            PlayMethod.DIRECT_PLAY -> "Direct play"
            PlayMethod.DIRECT_STREAM -> "Direct stream (remux)"
            PlayMethod.TRANSCODE -> "Transcode"
        })
        if (plan.transcodeReasons.isNotEmpty()) add("Why" to plan.transcodeReasons.joinToString(", ") { it.toReadableReason() })
        add("Container" to (plan.source.container ?: "?"))
        plan.source.bitrate?.let { add("Bitrate" to "%.1f Mbps".format(it / 1_000_000.0)) }
        video?.let {
            add("Video" to listOfNotNull(it.codec?.uppercase(), it.profile, "${it.width}×${it.height}", it.videoRangeType?.serialName, it.bitDepth?.let { b -> "$b-bit" })
                .joinToString(" · "))
        }
        audio?.let { add("Audio" to listOfNotNull(it.codec?.uppercase(), it.channelLayout, it.language).joinToString(" · ")) }
        videoDecoder?.let { add("Video decoder" to it) }
        audioDecoder?.let { add("Audio decoder" to it) }
    }
    Column(
        modifier.width(560.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Playback info", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 6.dp))
        for ((label, value) in rows) {
            Row {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** "VideoCodecNotSupported" -> "Video codec not supported". */
fun String.toReadableReason(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().replaceFirstChar { it.uppercase() }
