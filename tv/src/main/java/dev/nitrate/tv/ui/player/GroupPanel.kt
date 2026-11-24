package dev.nitrate.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.ListItem
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.nitrate.android.AppContainer
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.NoteChat
import dev.nitrate.core.companion.NoteChats
import dev.nitrate.core.companion.QuickPicks
import dev.nitrate.core.companion.QuickReplies
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.WatchCompany
import dev.nitrate.core.partners.WatchingWith
import dev.nitrate.core.partners.WithChip
import dev.nitrate.tv.ui.together.ChatComposer
import dev.nitrate.tv.ui.together.NoteLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player's one group button's words: the group's name ("Tommy & Suzy"), "Group Watch Session ·
 * Tommy", or "Group chat" when only a chat is here (an older app's session). Null: no group here,
 * no button.
 */
internal fun groupButtonLabel(chip: WithChip?, chatAvailable: Boolean): String? = chip?.button ?: if (chatAvailable) "Group chat" else null

/** The group panel wired to the app (see [GroupPanel]). */
@Composable
internal fun PlayerGroupPanel(
    container: AppContainer?,
    company: WatchCompany?,
    chip: WithChip?,
    item: MediaItem?,
    groupChat: TimelineChat?,
    chatAvailable: Boolean,
    chatTitle: String?,
    onGroupChat: (Boolean) -> Unit,
    /** Where this player is now (a session started here starts from there; messages are pinned here). */
    positionMs: () -> Long,
    /** Leaving or ending the session closes the panel. */
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val me = container?.server?.userId
    val pickedBy = groupChat?.broughtBy?.collectAsState()?.value
    // The title's quick replies (the usual ones until its own are worked out: an episode's show is asked for once).
    val picks by produceState(QuickReplies.forTitle(item), item?.id) {
        if (item != null && container != null) value = container.quickPicks(item)
    }
    GroupPanel(
        chip = chip,
        chat = groupChat?.takeIf { chatAvailable },
        chatTitle = chatTitle,
        positionMs = positionMs,
        picks = picks,
        problem = problem,
        pickedBy = pickedBy?.takeIf { it != me }?.let { id -> chip?.groups?.flatMap { it.others }?.firstOrNull { it.id == id }?.name ?: "Someone watching" },
        onCountFor = { company?.countFor(it) },
        // A Group Watch Session of the group it counts for, from where this player is now.
        onStartSession = if (container != null && container.sessionsAvailable() && chip?.group != null) ({
            scope.launch { problem = item?.let { container.startWatchingNow(it, chip.group, positionMs()) } }
        }) else null,
        onLeaveSession = { container?.groupSessions?.leave(); onClose() },
        onEndSession = { container?.groupSessions?.end(); onClose() },
        onSameRoom = { group, ids -> company?.sameRoom(group, ids) },
        onTurnChat = onGroupChat,
        onPlayChat = { id ->
            scope.launch {
                groupChat?.play(id)?.onSuccess { problem = null }?.onFailure { problem = it.message ?: "Couldn't open that chat" }
            }
        },
        modifier = modifier,
    )
}

private enum class GroupPage { MAIN, COUNT, ROOM, CHATS }

/**
 * The group panel, beside the video (the player's one group button opens it): the top line says who
 * it counts for ("Counting for you, Tommy & Suzy · Change", which opens the Change choices and "We're in
 * the same room") or "In a Group Watch Session with Tommy"; then the chat: which chat this is ("New
 * chat", or the earlier one playing along; OK opens **Chats**: Group chat on or off, New chat and
 * the earlier chats, newest first, each with a heart to keep it), what was said up to here, the
 * title's quick reactions and lines (one press each) and a text field; then
 * "Start a Group Watch Session" (in one: leave it, or end it for everyone when the user started
 * it). Focus stays inside; Back goes back a page, then (left to the player) closes it, focus back on
 * the group button.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GroupPanel(
    chip: WithChip?,
    chat: TimelineChat?,
    chatTitle: String? = null,
    positionMs: () -> Long = { 0L },
    problem: String? = null,
    /** Who else picked the chat playing along for everyone in the session (null: the user, or nobody). */
    pickedBy: String? = null,
    onCountFor: (WatchingWith) -> Unit = {},
    /** Starts a Group Watch Session (null: the server has none, or no group to start it for). */
    onStartSession: (() -> Unit)? = {},
    onLeaveSession: () -> Unit = {},
    onEndSession: () -> Unit = {},
    onSameRoom: (groupId: String, present: List<String>) -> Unit = { _, _ -> },
    onTurnChat: (Boolean) -> Unit = {},
    onPlayChat: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
    /** The chat's one-press reactions and lines for this title. */
    picks: QuickPicks = QuickPicks.PLAIN,
    /** The device's offset from UTC (minutes) at a moment, for the chats' dates. */
    offsetAt: (Long) -> Int = { java.util.TimeZone.getDefault().getOffset(it) / 60_000 },
) {
    var page by remember { mutableStateOf(GroupPage.MAIN) }
    // Where focus goes on each page: the first choice, or back on the line that opened a page.
    val first = remember { FocusRequester() }
    val topLine = remember { FocusRequester() }
    val chatLine = remember { FocusRequester() }
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    fun open(p: GroupPage, from: FocusRequester?) { returnTo = from; page = p }
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
            GroupPage.MAIN -> MainPage(
                chip, chat, chatTitle, positionMs, problem, topLine, chatLine, first, offsetAt, pickedBy, picks,
                onChange = { open(GroupPage.COUNT, topLine) },
                onChats = { open(GroupPage.CHATS, chatLine) },
                onStartSession, onLeaveSession, onEndSession,
            )
            GroupPage.COUNT -> chip?.let { c ->
                Text("Counting for", style = MaterialTheme.typography.headlineSmall)
                Text("Who what you play to the end counts for.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val chosen = c.group?.id
                c.groups.forEachIndexed { i, g ->
                    Choice("With " + WithChip.nameOf(g), chosen == g.id, if (i == 0) Modifier.focusRequester(first) else Modifier) { onCountFor(WatchingWith.Group(g.id)); back() }
                }
                Choice("No group", chosen == null, if (c.groups.isEmpty()) Modifier.focusRequester(first) else Modifier) { onCountFor(WatchingWith.JustMe); back() }
                if (c.group != null) {
                    Action(
                        "We're in the same room",
                        if (c.roomNames.isEmpty()) "Pick who's watching here with you: it counts for them too" else "Here: " + WithChip.and(c.roomNames) + " · change",
                        Modifier,
                    ) { page = GroupPage.ROOM }
                }
            }
            GroupPage.ROOM -> chip?.group?.let { g ->
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
            GroupPage.CHATS -> chat?.let { ChatsPage(it, chip?.inSession == true, pickedBy, first, offsetAt, onTurnChat) { id -> onPlayChat(id); back() } }
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
    chip: WithChip?,
    chat: TimelineChat?,
    chatTitle: String?,
    positionMs: () -> Long,
    problem: String?,
    topLine: FocusRequester,
    chatLine: FocusRequester,
    first: FocusRequester,
    offsetAt: (Long) -> Int,
    pickedBy: String?,
    picks: QuickPicks,
    onChange: () -> Unit,
    onChats: () -> Unit,
    onStartSession: (() -> Unit)?,
    onLeaveSession: () -> Unit,
    onEndSession: () -> Unit,
) {
    var firstUsed = false
    fun firstOne(own: FocusRequester): Modifier = (if (firstUsed) Modifier else Modifier.focusRequester(first).also { firstUsed = true }).focusRequester(own)
    // Who it counts for: OK changes it (outside a Group Watch Session).
    if (chip != null && !chip.inSession) {
        ListItem(
            selected = false,
            onClick = onChange,
            headlineContent = { Text(chip.label) },
            supportingContent = { Text(chip.who) },
            modifier = firstOne(topLine),
        )
    } else if (chip != null) {
        Text(chip.label, style = MaterialTheme.typography.titleLarge)
        Text(chip.who, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    } else {
        Text("Group chat" + (chatTitle?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleLarge)
    }
    if (chat != null) ChatSection(chat, positionMs, chatLine, ::firstOne, offsetAt, pickedBy, picks, onChats, Modifier.weight(1f))
    if (chip?.inSession == true) {
        Action("Leave the Group Watch Session", "Carry on by yourself from here", firstOne(remember { FocusRequester() }), onLeaveSession)
        if (chip.startedByMe) Action("End it for everyone", "Everyone carries on by themselves", Modifier, onEndSession)
    } else if (onStartSession != null && chip?.group != null) {
        Action(
            "Start a Group Watch Session",
            "With " + WithChip.nameOf(chip.group!!) + ": they're asked to join, and everyone starts together on their own screen",
            firstOne(remember { FocusRequester() }), onStartSession,
        )
    }
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun ChatSection(
    chat: TimelineChat,
    positionMs: () -> Long,
    chatLine: FocusRequester,
    firstOne: (FocusRequester) -> Modifier,
    offsetAt: (Long) -> Int,
    pickedBy: String?,
    picks: QuickPicks,
    onChats: () -> Unit,
    modifier: Modifier,
) {
    val on by chat.on.collectAsState()
    val history by chat.history.collectAsState()
    val missed by chat.missed.collectAsState()
    val hasChats by chat.hasChats.collectAsState()
    val playing by chat.playing.collectAsState()
    val all by chat.allChats.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val firstReaction = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { chat.opened() }
    LaunchedEffect(history.size) { if (history.isNotEmpty()) list.scrollToItem(history.size - 1) }
    fun send(text: String, kind: ChatKind) {
        scope.launch {
            chat.send(text, kind, positionMs()).onSuccess { if (kind == ChatKind.TEXT) draft = ""; problem = null }
                .onFailure { problem = it.message ?: "Couldn't send" }
        }
    }
    // Which chat this is; OK picks another (and turns group chat on or off).
    ListItem(
        selected = false,
        onClick = onChats,
        headlineContent = {
            Text(
                when {
                    !on -> "Group chat: Off"
                    hasChats -> "Chat: " + NoteChats.playingLabel(playing, all, offsetAt)
                    else -> "Group chat: On"
                },
            )
        },
        supportingContent = {
            Text(
                when {
                    pickedBy != null && playing != null -> "$pickedBy picked it for everyone · Chats ›"
                    hasChats -> "Chats: play an earlier chat along, or turn it off ›"
                    else -> "Turn it on or off ›"
                },
            )
        },
        modifier = firstOne(chatLine),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!on) {
            Text(
                "Turn it on to see what's said at each point as you reach it, and to leave your own reactions and notes here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        if (missed.isNotEmpty()) OutlinedButton(onClick = { chat.showMissed() }) { Text("Show what I missed (${missed.size})") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (history.isEmpty()) item {
                Text(
                    if (playing == null) "A new chat: nothing earlier pops up. A reaction or a note stays at this point for your group."
                    else "Nothing yet up to here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(history, key = { it.id }) { NoteLine(it) }
        }
        ChatComposer(firstReaction, ::send, draft, { draft = it.take(500) }, picks)
        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Chats: Group chat on or off, then New chat and the earlier chats for this group and part, newest first. */
@Composable
private fun ChatsPage(
    chat: TimelineChat,
    inSession: Boolean,
    pickedBy: String?,
    first: FocusRequester,
    offsetAt: (Long) -> Int,
    onTurn: (Boolean) -> Unit,
    onPick: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val on by chat.on.collectAsState()
    val hasChats by chat.hasChats.collectAsState()
    val offered by chat.chats.collectAsState()
    val all by chat.allChats.collectAsState()
    val playing by chat.playing.collectAsState()
    Text("Chats", style = MaterialTheme.typography.headlineSmall)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ListItem(
                selected = false,
                onClick = { onTurn(!on) },
                headlineContent = { Text("Group chat: " + if (on) "On" else "Off") },
                supportingContent = { Text(if (on) "What's said pops up as you reach it" else "No pop-ups, nothing sent") },
                trailingContent = { Switch(checked = on, onCheckedChange = null) },
                modifier = Modifier.focusRequester(first),
            )
        }
        if (hasChats && on) {
            item {
                Text(
                    if (inSession) "What you pick plays along for everyone in the Group Watch Session." + (pickedBy?.let { " $it picked the one now." } ?: "")
                    else "Pick an earlier chat to play along: its messages pop up as you reach them. Anything you write starts a new chat that builds on it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item { Choice(NoteChats.NEW_CHAT, playing == null, Modifier, "Nothing earlier pops up") { onPick(null) } }
            items(offered, key = { it.id }) { c: NoteChat ->
                // ▶ from the chat's line reaches its heart: a favourite is never deleted by the server's time rules.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Choice(NoteChats.title(c, all, offsetAt), playing == c.id, Modifier.weight(1f), detail = NoteChats.detail(c, all, offsetAt)) { onPick(c.id) }
                    if (chat.canFavourite) Heart(c.favourite) { scope.launch { chat.favourite(c.id, !c.favourite) } }
                }
            }
            if (offered.isEmpty()) item {
                Text("No earlier chats on this yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier, detail: String? = null, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(text) },
        supportingContent = detail?.let { { Text(it) } },
        trailingContent = if (selected) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null,
        modifier = modifier,
    )
}

/** A chat's heart: filled for a favourite; says "Favourite" or "Not a favourite". */
@Composable
private fun Heart(favourite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            if (favourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (favourite) NoteChats.FAVOURITE else NoteChats.NOT_FAVOURITE,
            tint = if (favourite) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
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
