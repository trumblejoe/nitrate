package dev.nitrate.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.nitrate.core.companion.TimelineChat
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.partners.WatchCompany
import dev.nitrate.core.partners.WatchingWith
import dev.nitrate.core.partners.WithChip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Who this playback is watched with (the player's group button and its panel's top line): made once per player, told as each
 * item starts and where it's got to (what's played to the end counts for whoever's in the room), and
 * it steers [groupChat] to the group it counts for. Null when [enabled] is false (downloads offline,
 * recap clips, test plays).
 */
@Composable
fun rememberWatchCompany(
    actions: AppActions,
    item: MediaItem?,
    enabled: Boolean,
    groupChat: TimelineChat?,
    positionMs: () -> Long,
    durationMs: () -> Long,
    /** The item's segments: its marked credits are where it counts as watched ([dev.nitrate.core.playback.PlaybackMoments.watchedAtMs]). */
    segments: () -> List<dev.nitrate.core.model.MediaSegment> = { emptyList() },
): WatchCompany? {
    if (!enabled) return null
    val scope = rememberCoroutineScope()
    val company = remember { actions.container.watchCompany(scope) }
    val position by rememberUpdatedState(positionMs)
    val duration by rememberUpdatedState(durationMs)
    val marks by rememberUpdatedState(segments)
    // Where the item playing has got to, so that leaving it (the next one, or the player closing) can tell.
    val last = remember { mutableStateOf<dev.nitrate.core.partners.PlaySample?>(null) }
    LaunchedEffect(company, item?.id) {
        val i = item ?: return@LaunchedEffect
        last.value?.takeIf { it.itemId != i.id }?.let { company.left(it.itemId, it.positionMs, it.durationMs, watchedAtMs = it.watchedAtMs) }
        company.start(i, actions.container.groupStandings.value)
        while (true) {
            val sample = dev.nitrate.core.partners.PlaySample.of(i.id, position(), duration(), marks())
            last.value = sample
            // Counted as it reaches the credits (or 90 %), not only as the player closes.
            company.playing(sample.itemId, sample.positionMs, sample.durationMs, sample.watchedAtMs)
            delay(1_000)
        }
    }
    DisposableEffect(company) { onDispose { last.value?.let { company.left(it.itemId, it.positionMs, it.durationMs, watchedAtMs = it.watchedAtMs) } } }
    LaunchedEffect(company, groupChat) { company.chip.collect { groupChat?.focus(chatFocus(it)) } }
    return company
}

/** What the group chat follows for this chip: the group(s) it counts for, none for just the user; everything in a Group Watch Session. */
fun chatFocus(chip: WithChip?): List<String>? = when {
    chip == null || chip.inSession -> null
    else -> chip.context.groups.map { it.id }
}

/**
 * The player's one group button (in place of the old With… chip and chat button): the avatars of
 * who it's with and the group's name ("Will", "Group Watch Session · Tommy"; "Group chat" when only
 * a chat is here). Tapping it opens the group panel ([onOpen]); in easy mode it only says who it
 * counts for. Nothing without a group.
 */
@Composable
fun GroupButton(chip: WithChip?, easy: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val label = (if (easy) chip?.plain else chip?.button) ?: return
    val shape = RoundedCornerShape(50)
    val base = modifier.widthIn(max = 260.dp).background(Color.White.copy(alpha = 0.16f), shape)
    Row(
        (if (easy) base else base.clickable(onClickLabel = "Open the group panel", role = Role.Button, onClick = onOpen))
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!easy) chip?.initials?.forEach { letter ->
            Text(
                letter,
                color = Color.Black,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.size(24.dp).background(accentColor(), androidx.compose.foundation.shape.CircleShape)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (easy || chip?.initials.isNullOrEmpty()) 8.dp else 0.dp, top = 4.dp, bottom = 4.dp))
    }
}

/**
 * When more than one group could count this playback and none was chosen, which one it counts
 * for, shown plainly for about five seconds as it starts ("Counting for: Tommy"), so it can be
 * switched (tap). Once per title.
 */
@Composable
fun CountingBanner(chip: WithChip?, easy: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
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
    AnimatedVisibility(visible && chip != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val c = chip ?: return@AnimatedVisibility
        val words = c.counting + if (easy) "" else " · Change"
        Text(
            words,
            color = Color.Black,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.background(accentColor(), RoundedCornerShape(50))
                .then(if (easy) Modifier else Modifier.clickable(role = Role.Button) { visible = false; onOpen() })
                .semantics { contentDescription = words }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** How long "Counting for: …" stays up as playback starts. */
const val COUNTING_MS = 5_000L


/** A speech bubble: the player's Chat button. */
val ChatBubbleIcon: androidx.compose.ui.graphics.vector.ImageVector =
    androidx.compose.ui.graphics.vector.ImageVector.Builder("ChatBubble", 24.dp, 24.dp, 24f, 24f).addPath(
        androidx.compose.ui.graphics.vector.addPathNodes("M20,2H4C2.9,2 2,2.9 2,4v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM6,9h12v2H6V9zM14,14H6v-2h8v2zM18,8H6V6h12v2z"),
        fill = androidx.compose.ui.graphics.SolidColor(Color.Black),
    ).build()

/**
 * The player's Chat button (where there's a group to chat with; not in easy mode): a speech bubble
 * with a small dot when something popped up since the chat was last open. Opens the chat sheet.
 */
@Composable
fun ChatButton(chat: dev.nitrate.core.companion.TimelineChat, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val unread by chat.unread.collectAsState()
    androidx.compose.material3.IconButton(onClick = onOpen, modifier = modifier) {
        androidx.compose.material3.BadgedBox(badge = { if (unread > 0) androidx.compose.material3.Badge(containerColor = accentColor()) }) {
            androidx.compose.material3.Icon(ChatBubbleIcon, if (unread > 0) "Chat, new messages" else "Chat", tint = Color.White)
        }
    }
}
