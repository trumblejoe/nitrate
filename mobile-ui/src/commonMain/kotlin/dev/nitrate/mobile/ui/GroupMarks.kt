package dev.nitrate.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.GroupMarks
import dev.nitrate.core.partners.WatchGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The group whose own mark [item] shows on this page (entered from that group), or null: only the user's own Watched then. */
@Composable
fun groupMarkFor(actions: AppActions, item: MediaItem): WatchGroup? {
    val info by actions.container.partners.collectAsState()
    val standings by actions.container.groupStandings.collectAsState()
    return GroupMarks.groupFor(actions.groupOf(item), item, info, standings)
}

/** A page's group marks: toggles one, asking "Just this group / All groups where it's next" only when it's next in other groups too. */
class GroupMarker internal constructor(private val actions: AppActions, private val scope: CoroutineScope) {
    internal var asking by mutableStateOf<Triple<WatchGroup, MediaItem, List<WatchGroup>>?>(null)

    fun toggle(group: WatchGroup, item: MediaItem) {
        val now = !GroupMarks.watched(group, item)
        val others = GroupMarks.alsoNext(item, group, now, actions.container.partners.value, actions.container.groupStandings.value)
        if (others.isNotEmpty()) asking = Triple(group, item, others) else mark(listOf(group.id), item, now)
    }

    internal fun mark(groupIds: List<String>, item: MediaItem, watched: Boolean) {
        scope.launch { actions.container.markForGroups(groupIds, listOf(item.id), watched) }
    }
}

/** One per page; [GroupMarkQuestion] shows its question. */
@Composable
fun rememberGroupMarker(actions: AppActions): GroupMarker {
    val scope = rememberCoroutineScope()
    return remember(actions) { GroupMarker(actions, scope) }
}

/** "Also mark it watched on your track with Kim, where it's next too?": Just this group / All groups where it's next. */
@Composable
fun GroupMarkQuestion(marker: GroupMarker) {
    val (group, item, others) = marker.asking ?: return
    fun answer(all: Boolean) {
        marker.asking = null
        marker.mark(listOf(group.id) + if (all) others.map { it.id } else emptyList(), item, true)
    }
    AlertDialog(
        onDismissRequest = { answer(false) },
        title = { Text(GroupMarks.question(others)) },
        text = { Text("Marked watched with ${GroupMarks.caption(group)}. It's the next part for ${dev.nitrate.core.partners.WatchPartners.names(others.map { GroupMarks.caption(it) })} too.") },
        confirmButton = { TextButton(onClick = { answer(true) }) { Text(dev.nitrate.core.partners.CountAsk.ALL) } },
        dismissButton = { TextButton(onClick = { answer(false) }) { Text(dev.nitrate.core.partners.CountAsk.JUST_THIS) } },
    )
}

/** The page's group mark beside the user's own Watched: people icon, "Tommy & Suzy" under it, lit when the group's record has it. */
@Composable
fun GroupMarkAction(actions: AppActions, item: MediaItem, marker: GroupMarker) {
    val group = groupMarkFor(actions, item) ?: return
    RoundAction(NitrateIcons.Group, GroupMarks.caption(group), onClick = { marker.toggle(group, item) }, active = GroupMarks.watched(group, item)) {
        Icon(NitrateIcons.Group, GroupMarks.label(group, item), tint = if (GroupMarks.watched(group, item)) accentColor() else Color.White)
    }
}

/** The same mark, small, in a list row (an episode, a part of a playlist). */
@Composable
fun GroupMarkToggle(actions: AppActions, item: MediaItem, marker: GroupMarker, modifier: Modifier = Modifier) {
    val group = groupMarkFor(actions, item) ?: return
    val on = GroupMarks.watched(group, item)
    Icon(
        NitrateIcons.Group, null,
        tint = if (on) accentColor() else Color.White.copy(alpha = 0.55f),
        modifier = modifier.background(if (on) accentColor().copy(alpha = 0.22f) else Color(0x66000000), CircleShape)
            .pressable({ marker.toggle(group, item) }).semantics { contentDescription = GroupMarks.label(group, item) }
            .padding(6.dp).size(18.dp),
    )
}
