package dev.nitrate.tv.ui.partners

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.GroupMarks
import dev.nitrate.core.partners.WatchGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** People (Material "Group", Apache 2.0; the core icon set has none): a watch group's own mark. */
val GroupIcon: ImageVector by lazy {
    ImageVector.Builder("Group", 24.dp, 24.dp, 24f, 24f).addPath(
        androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z",
        ).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(Color.White),
    ).build()
}

/**
 * A page entered from watch group [groupId] (its Watching together card, or Watch groups): the
 * group's own marks on the title's parts ([GroupMarks]), apart from the user's own Watched.
 */
class TvGroupMarks internal constructor(val container: AppContainer, val groupId: String, private val scope: CoroutineScope) {
    internal var asking by mutableStateOf<Triple<WatchGroup, MediaItem, List<WatchGroup>>?>(null)

    /** Toggles [item] in [group]'s record, asking "Just this group / All groups where it's next" only when it's next in other groups too. */
    fun toggle(group: WatchGroup, item: MediaItem) {
        val now = !GroupMarks.watched(group, item)
        val others = GroupMarks.alsoNext(item, group, now, container.partners.value, container.groupStandings.value)
        if (others.isNotEmpty()) asking = Triple(group, item, others) else mark(listOf(group.id), item, now)
    }

    internal fun mark(groupIds: List<String>, item: MediaItem, watched: Boolean) {
        scope.launch { container.markForGroups(groupIds, listOf(item.id), watched) }
    }
}

/** The page's group marks (null: not entered from a group). */
val LocalGroupMarks = staticCompositionLocalOf<TvGroupMarks?> { null }

/** Gives [content] (a details or playlist page) the group it was entered from, and shows the marks' one question. */
@Composable
fun ProvideGroupMarks(container: AppContainer, groupId: String?, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val marks = remember(groupId) { groupId?.let { TvGroupMarks(container, it, scope) } }
    CompositionLocalProvider(LocalGroupMarks provides marks) { content() }
    marks?.let { GroupMarkQuestion(it) }
}

/** The group whose own mark [item] shows here, or null (not entered from a group, or not one of its titles' parts). */
@Composable
fun groupMarkFor(item: MediaItem): WatchGroup? {
    val marks = LocalGroupMarks.current ?: return null
    val info by marks.container.partners.collectAsState()
    val standings by marks.container.groupStandings.collectAsState()
    return GroupMarks.groupFor(marks.groupId, item, info, standings)
}

/**
 * [item] as drawn on this page: entered from a group, a part of its titles shows the group's tick
 * and progress (`GroupMarks.asGroupSees`), never the user's own; elsewhere, as it is.
 */
@Composable
fun asShownHere(item: MediaItem): MediaItem = groupMarkFor(item)?.let { GroupMarks.asGroupSees(it, item) } ?: item

/** "Also mark it watched on your track with Kim, where it's next too?": Just this group (focused; Back is the same) / All groups where it's next. */
@Composable
private fun GroupMarkQuestion(marks: TvGroupMarks) {
    val (group, item, others) = marks.asking ?: return
    val focus = remember(item.id) { FocusRequester() }
    fun answer(all: Boolean) {
        marks.asking = null
        marks.mark(listOf(group.id) + if (all) others.map { it.id } else emptyList(), item, true)
    }
    dev.nitrate.tv.ui.lists.TvDialog({ answer(false) }, width = 640) {
        Text(GroupMarks.question(others), style = MaterialTheme.typography.headlineSmall)
        Text("Marked watched with ${GroupMarks.caption(group)}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { answer(false) }, modifier = Modifier.focusRequester(focus)) { Text(dev.nitrate.core.partners.CountAsk.JUST_THIS) }
        OutlinedButton(onClick = { answer(true) }) { Text(dev.nitrate.core.partners.CountAsk.ALL) }
    }
    LaunchedEffect(item.id) { delay(60); runCatching { focus.requestFocus() } }
}
