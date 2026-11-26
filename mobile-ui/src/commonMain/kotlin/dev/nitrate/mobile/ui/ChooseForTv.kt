package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * "Choose for <TV>", started from the remote: the phone's own pages are browsed for a TV running
 * Nitrate. While it's on, Play anywhere sends the title to that TV (after the guards, asked here)
 * and returns to its remote, and a details page offers Show on TV (the TV opens that title's page
 * for the room to see).
 */
object ChooseForTv {
    /**
     * The TV being chosen for. With [groupId], what's chosen starts a Group Watch Session of that
     * group ([groupLabel]: "Suzy & Kim") hosted on the TV, instead of just playing there.
     */
    data class Target(val sessionId: String, val deviceName: String, val groupId: String? = null, val groupLabel: String? = null)

    val target = MutableStateFlow<Target?>(null)

    fun start(sessionId: String, deviceName: String, groupId: String? = null, groupLabel: String? = null) {
        target.value = Target(sessionId, deviceName, groupId, groupLabel)
    }

    fun done() { target.value = null }
}

/**
 * Sends [item] to the TV being chosen for, from [startMs] (or the start, or where it was left),
 * with the tracks Play would start with; then the TV's remote. [checked]: the guards, asked here.
 * False when it couldn't be sent.
 */
suspend fun sendToChosenTv(
    container: dev.nitrate.android.AppContainer,
    toRemote: () -> Unit,
    target: ChooseForTv.Target,
    item: MediaItem,
    fromStart: Boolean,
    startMs: Long?,
    checked: suspend (MediaItem) -> MediaItem?,
): Boolean {
    val send = checked(item) ?: return true
    val start = when {
        send.id != item.id -> send.user.positionMs
        startMs != null -> startMs
        fromStart -> 0L
        else -> item.user.positionMs
    }
    val tracks = runCatching { container.trackSetup(send) }.getOrNull()
    return runCatching {
        container.server.playOn(
            target.sessionId, listOf(send.id), start,
            audioIndex = tracks?.audioIndex, subtitleIndex = tracks?.subtitleIndex,
            mediaSourceId = dev.nitrate.android.playback.VersionChoices[send.id],
        )
    }.onSuccess {
        ChooseForTv.done()
        toRemote()
    }.isSuccess
}

/**
 * Over every page while choosing for a TV: who for, Show on TV on a title's page ([itemId]), and
 * Done. Hidden on the remote and the player.
 */
@Composable
fun ChoosingBanner(actions: AppActions, itemId: String?, modifier: Modifier = Modifier) {
    val target by ChooseForTv.target.collectAsState()
    val t = target ?: return
    val scope = rememberCoroutineScope()
    val platform = dev.nitrate.mobile.platform.rememberPlatformActions()
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 6.dp, start = 12.dp, end = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.inverseSurface)
            .padding(start = 16.dp, end = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (t.groupId != null) "Group Watch Session with ${t.groupLabel ?: "your group"} on ${t.deviceName}" else "Choosing for ${t.deviceName}",
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (itemId != null) {
            TextButton(onClick = {
                scope.launch {
                    val ok = runCatching {
                        val item = actions.server.getItem(itemId)
                        actions.server.showOn(t.sessionId, item)
                    }.isSuccess
                    platform.toast(if (ok) "Showing it on ${t.deviceName}" else "Couldn't reach ${t.deviceName}")
                }
            }) { Text("Show on TV", color = MaterialTheme.colorScheme.inversePrimary) }
        }
        TextButton(onClick = { ChooseForTv.done() }) { Text("Done", color = MaterialTheme.colorScheme.inversePrimary) }
    }
}
