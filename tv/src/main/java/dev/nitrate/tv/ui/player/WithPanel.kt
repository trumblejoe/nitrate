package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.WatchCompany
import dev.nitrate.core.partners.WithChip
import kotlinx.coroutines.delay

/**
 * Who this playback is watched with (the group button and its panel's top line): one per player, told as each item starts and
 * where it's got to (what's played to the end counts for whoever's in the room), steering
 * [groupChat] to the group it counts for. Null without [container] or when not [enabled].
 */
@Composable
internal fun rememberWatchCompany(
    container: AppContainer?,
    item: MediaItem?,
    enabled: Boolean,
    groupChat: TimelineChat?,
    positionMs: () -> Long,
    durationMs: () -> Long,
    /** The item's segments: its marked credits are where it counts as watched ([dev.nitrate.core.playback.PlaybackMoments.watchedAtMs]). */
    segments: () -> List<dev.nitrate.core.model.MediaSegment> = { emptyList() },
): WatchCompany? {
    if (container == null || !enabled) return null
    val scope = rememberCoroutineScope()
    val company = remember { container.watchCompany(scope) }
    val position by rememberUpdatedState(positionMs)
    val duration by rememberUpdatedState(durationMs)
    val marks by rememberUpdatedState(segments)
    val last = remember { mutableStateOf<dev.nitrate.core.partners.PlaySample?>(null) }
    LaunchedEffect(company, item?.id) {
        val i = item ?: return@LaunchedEffect
        last.value?.takeIf { it.itemId != i.id }?.let { company.left(it.itemId, it.positionMs, it.durationMs, watchedAtMs = it.watchedAtMs) }
        company.start(i, container.groupStandings.value)
        while (true) {
            val sample = dev.nitrate.core.partners.PlaySample.of(i.id, position(), duration(), marks())
            last.value = sample
            // Counted as it reaches the credits (or 90 %), not only as the player closes.
            company.playing(sample.itemId, sample.positionMs, sample.durationMs, sample.watchedAtMs)
            delay(1_000)
        }
    }
    DisposableEffect(company) { onDispose { last.value?.let { company.left(it.itemId, it.positionMs, it.durationMs, watchedAtMs = it.watchedAtMs) } } }
    LaunchedEffect(company, groupChat) {
        company.chip.collect { c -> groupChat?.focus(if (c == null || c.inSession) null else c.context.groups.map { it.id }) }
    }
    return company
}

/**
 * More than one group would count this playback and none was chosen: which one, plainly, for about
 * five seconds as it starts ("Counting for: Will"). It never takes focus: the group button in the
 * controls opens the panel that changes it.
 */
@Composable
internal fun CountingBanner(chip: WithChip?, modifier: Modifier = Modifier) {
    val title = chip?.context?.titleId
    var shownFor by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(title, chip?.ambiguous) {
        if (chip == null || !chip.ambiguous || title == null || shownFor == title) return@LaunchedEffect
        shownFor = title
        visible = true
        delay(COUNTING_MS)
        visible = false
    }
    if (visible && chip != null) {
        Text(
            chip.counting,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            modifier = modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 12.dp),
        )
    }
}

internal const val COUNTING_MS = 5_000L
