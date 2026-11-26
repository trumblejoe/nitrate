package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.WatchCompany
import dev.nitrate.core.partners.WatchingWith
import dev.nitrate.core.partners.WithChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player's group button's words: the group's name ("Tommy & Suzy") or "Group Watch Session ·
 * Tommy". Null: no group here, no button (the chat has its own Chat button).
 */
internal fun groupButtonLabel(chip: WithChip?): String? = chip?.button

/** The group panel wired to the app (see [GroupPanel]). */
@Composable
internal fun PlayerGroupPanel(
    container: AppContainer?,
    company: WatchCompany?,
    chip: WithChip,
    item: MediaItem?,
    /** Where this player is now (a session started here starts from there). */
    positionMs: () -> Long,
    /** Leaving or ending the session closes the panel. */
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    GroupPanel(
        chip = chip,
        problem = problem,
        onCountFor = { company?.countFor(it) },
        // A Group Watch Session of the group it counts for, from where this player is now.
        onStartSession = if (container != null && container.sessionsAvailable() && chip.group != null) ({
            scope.launch { problem = item?.let { container.startWatchingNow(it, chip.group, positionMs()) } }
        }) else null,
        onLeaveSession = { container?.groupSessions?.leave(); onClose() },
        onEndSession = { container?.groupSessions?.end(); onClose() },
        onSameRoom = { group, ids -> company?.sameRoom(group, ids) },
        modifier = modifier,
    )
}

private enum class GroupPage { MAIN, COUNT, ROOM }

/**
 * The group panel, beside the video (the player's group button opens it): the top line says who it
 * counts for ("Counting for you, Tommy & Suzy · Change", which opens the Change choices and "We're in
 * the same room") or "In a Group Watch Session with Tommy"; then "Start a Group Watch Session" (in
 * one: leave it, or end it for everyone when the user started it). The chat is the Chat button's
 * ([ChatStrip]). Focus stays inside; Back goes back a page, then (left to the player) closes it,
 * focus back on the group button.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GroupPanel(
    chip: WithChip,
    problem: String? = null,
    onCountFor: (WatchingWith) -> Unit = {},
    /** Starts a Group Watch Session (null: the server has none, or no group to start it for). */
    onStartSession: (() -> Unit)? = {},
    onLeaveSession: () -> Unit = {},
    onEndSession: () -> Unit = {},
    onSameRoom: (groupId: String, present: List<String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(GroupPage.MAIN) }
    // Where focus goes on each page: the first choice, or back on the line that opened a page.
    val first = remember { FocusRequester() }
    val topLine = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    val back = { page = if (page == GroupPage.ROOM) GroupPage.COUNT else GroupPage.MAIN }
    Column(
        modifier.width(480.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)).padding(24.dp)
            // A page goes back one step on Back; on the first page Back is left to the player, which closes the panel.
            .onKeyEvent { e ->
                if (page == GroupPage.MAIN || (e.key != Key.Back && e.key != Key.Escape)) return@onKeyEvent false
                if (e.type == KeyEventType.KeyUp) back()
                true
            }
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (page) {
            GroupPage.MAIN -> MainPage(chip, problem, topLine, first, onChange = { returnTo = topLine; page = GroupPage.COUNT }, onStartSession, onLeaveSession, onEndSession)
            GroupPage.COUNT -> {
                Text("Counting for", style = MaterialTheme.typography.headlineSmall)
                Text("Who what you play to the end counts for.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val chosen = chip.group?.id
                chip.groups.forEachIndexed { i, g ->
                    Choice("With " + WithChip.nameOf(g), chosen == g.id, if (i == 0) Modifier.focusRequester(first) else Modifier) { onCountFor(WatchingWith.Group(g.id)); back() }
                }
                Choice("No group", chosen == null, if (chip.groups.isEmpty()) Modifier.focusRequester(first) else Modifier) { onCountFor(WatchingWith.JustMe); back() }
                if (chip.group != null) {
                    Action(
                        "We're in the same room",
                        if (chip.roomNames.isEmpty()) "Pick who's watching here with you: it counts for them too" else "Here: " + WithChip.and(chip.roomNames) + " · change",
                        Modifier,
                    ) { page = GroupPage.ROOM }
                }
            }
            GroupPage.ROOM -> chip.group?.let { g ->
                var here by remember { mutableStateOf(chip.room?.takeIf { it.groupId == g.id }?.present?.toSet() ?: g.others.map { it.id }.toSet()) }
                Text("We're in the same room", style = MaterialTheme.typography.headlineSmall)
                Text("Who's watching here with you? What you play to the end counts for them too.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                g.others.forEachIndexed { i, p ->
                    Toggle(p.name, p.id in here, if (i == 0) Modifier.focusRequester(first) else Modifier) { here = if (p.id in here) here - p.id else here + p.id }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.tv.material3.Button(onClick = { onSameRoom(g.id, g.others.map { it.id }.filter { it in here }); page = GroupPage.MAIN }) {
                        Text(if (here.isEmpty()) "Nobody else here" else "Done")
                    }
                    OutlinedButton(onClick = { page = GroupPage.COUNT }) { Text("Back") }
                }
            }
        }
    }
    // Each page opens with focus on its first choice; back on the first page, on the line that opened the page.
    // Once more a moment later: as the panel first opens, focus may still be on its way from the button.
    LaunchedEffect(page) {
        val target = if (page == GroupPage.MAIN) returnTo ?: first else first
        delay(50)
        runCatching { target.requestFocus() }
        delay(300)
        if (page == GroupPage.MAIN && returnTo == null) runCatching { first.requestFocus() }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.MainPage(
    chip: WithChip,
    problem: String?,
    topLine: FocusRequester,
    first: FocusRequester,
    onChange: () -> Unit,
    onStartSession: (() -> Unit)?,
    onLeaveSession: () -> Unit,
    onEndSession: () -> Unit,
) {
    var firstUsed = false
    fun firstOne(own: FocusRequester): Modifier = (if (firstUsed) Modifier else Modifier.focusRequester(first).also { firstUsed = true }).focusRequester(own)
    // Who it counts for: OK changes it (outside a Group Watch Session).
    if (!chip.inSession) {
        ListItem(
            selected = false,
            onClick = onChange,
            headlineContent = { Text(chip.label) },
            supportingContent = { Text(chip.who) },
            modifier = firstOne(topLine),
        )
    } else {
        Text(chip.label, style = MaterialTheme.typography.titleLarge)
        Text(chip.who, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (chip.inSession) {
        Action("Leave the Group Watch Session", "Carry on by yourself from here", firstOne(remember { FocusRequester() }), onLeaveSession)
        if (chip.startedByMe) Action("End it for everyone", "Everyone carries on by themselves", Modifier, onEndSession)
    } else if (onStartSession != null && chip.group != null) {
        Action(
            "Start a Group Watch Session",
            "With " + WithChip.nameOf(chip.group!!) + ": they're asked to join, and everyone starts together on their own screen",
            firstOne(remember { FocusRequester() }), onStartSession,
        )
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
internal fun Choice(text: String, selected: Boolean, modifier: Modifier, detail: String? = null, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(text) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = if (selected) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null,
        modifier = modifier,
    )
}

@Composable
private fun Toggle(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = on,
        onClick = onClick,
        headlineContent = { Text(text) },
        trailingContent = { Switch(checked = on, onCheckedChange = null) },
        modifier = modifier,
    )
}

@Composable
private fun Action(title: String, detail: String, modifier: Modifier, onClick: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        modifier = modifier,
    )
}
