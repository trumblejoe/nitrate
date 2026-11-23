package dev.nitrate.tv.ui.details

import androidx.tv.material3.ListItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import kotlinx.coroutines.launch
import dev.nitrate.core.playback.audioChoices
import dev.nitrate.core.playback.subtitleChoices
import androidx.compose.runtime.rememberCoroutineScope
import dev.nitrate.tv.ui.components.LocalDisplayPrefs
import dev.nitrate.core.pages.hidesDescription
import dev.nitrate.core.pages.isSpoiler
import dev.nitrate.android.viewmodel.DetailsState
import dev.nitrate.android.viewmodel.DetailsViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.focusable
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.tv.ui.player.PlaybackInfoPanel
import dev.nitrate.core.model.MediaKind
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import coil3.compose.AsyncImage
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.nitrate.core.MediaServer
import dev.nitrate.tv.ui.components.Backdrop
import dev.nitrate.tv.ui.components.GenreLine
import dev.nitrate.tv.ui.components.ItemMetaLine
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.components.TitleOrLogo
import dev.nitrate.tv.ui.components.RoundAction
import dev.nitrate.tv.ui.components.Crumb
import dev.nitrate.core.model.formatClock
import dev.nitrate.core.model.formatRuntime
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.ImageKind

/** Everything the action row leaves out: how it plays, moments, chat, lists, more like it, managing. */
@Composable
internal fun MorePanel(
    item: MediaItem,
    repository: MediaServer,
    playTarget: MediaItem?,
    canCollect: Boolean,
    onManage: ((String) -> Unit)?,
    onBrowse: (String, String?, Int?) -> Unit,
    checkPlayback: (suspend (MediaItem) -> dev.nitrate.core.playback.PlaybackDiagnosis)?,
    applyPlaybackFix: suspend (MediaItem, dev.nitrate.core.playback.PlaybackFix) -> Unit,
    onPlayFrom: (MediaItem, Long) -> Unit,
    onPlay: (MediaItem) -> Unit = {},
    onOpenShow: (String) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var open by remember { mutableStateOf<String?>(null) }
    val features = rememberCompanionFeatures(repository)
    // In one of the user's watch groups: the same choices as holding OK on its Watching together card.
    val container = dev.nitrate.tv.ui.pages.LocalTvContainer.current
    val standings = container?.groupStandings?.collectAsState()?.value.orEmpty()
    val together = container != null && dev.nitrate.core.partners.TogetherRow.titleOf(item, standings) in standings
    val video = item.isPlayable && item.mediaKind != MediaKind.AUDIO
    val studio = item.studios.firstOrNull()
    val year = item.productionYear
    if (open == null) {
        val first = remember { FocusRequester() }
        dev.nitrate.tv.ui.lists.TvDialog(onDismiss) {
            Text(if (item.kind == ItemKind.EPISODE) item.name.orEmpty() else item.displayTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val entries = buildList<Triple<String, String?, () -> Unit>> {
                if (item.isPlayable) add(Triple("How it plays", "Whether it plays as it is here, and why", { open = "plays" }))
                if (video && "moments" in features) add(Triple("Moments", "Marked places, yours and your groups'", { open = "moments" }))
                if (video && "chathistory" in features) add(Triple("Earlier chat", "What was said watching it together", { open = "chat" }))
                if (together) add(Triple("Watching together…", "Catch up, watched it together, watch now, invite, leave", { open = "together" }))
                if (item.isPlayable || item.kind == ItemKind.SERIES) add(Triple("Add to a playlist or collection", null, { open = "add" }))
                studio?.let { add(Triple("More from $it", null, { onDismiss(); onBrowse(it, it, null) })) }
                year?.let { add(Triple("More from $it", null, { onDismiss(); onBrowse("From $it", null, it) })) }
                if (onManage != null) add(Triple("Manage", "Details, images, identify", { onDismiss(); onManage(item.id) }))
                if (onManage != null && item.kind == ItemKind.BOOK) add(Triple("Comic details", null, { open = "comic" }))
            }
            entries.forEachIndexed { i, (label, note, action) ->
                ListItem(
                    selected = false,
                    onClick = action,
                    headlineContent = { Text(label) },
                    supportingContent = note?.let { { Text(it) } },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    }
    when (open) {
        "plays" -> if (checkPlayback != null) CheckPlaybackDialog(item, checkPlayback, applyPlaybackFix, onDismiss) else PlaybackDecisionDialog(repository, item, onDismiss)
        "moments" -> MomentsDialog(repository, item.id, { at -> onPlayFrom(item, at) }, onDismiss)
        "chat" -> EarlierChatDialog(repository, item.id, onDismiss)
        "add" -> dev.nitrate.tv.ui.lists.AddToListDialog(repository, item, canCollect, onDismiss)
        "comic" -> dev.nitrate.tv.ui.comics.ComicInfoDialog(repository, item, onDismiss)
        "together" -> if (container != null) {
            val info by container.partners.collectAsState()
            dev.nitrate.tv.ui.partners.TogetherChoicesDialog(container, item, info, onPlay = onPlay, onOpenShow = onOpenShow, onStart = {}, onCustomize = null, onDismiss = onDismiss)
        }
    }
}

/** What the server would do if this item were played now, and why, without starting playback. */
@Composable
internal fun PlaybackDecisionDialog(repository: MediaServer, item: MediaItem, onDismiss: () -> Unit) {
    val plan by produceState<Result<PlaybackPlan>?>(null, item.id) {
        value = runCatching { repository.resolvePlayback(item.id, item.mediaKind == MediaKind.AUDIO, startMs = 0) }
    }
    Dialog(onDismissRequest = onDismiss) {
        val p = plan
        when {
            p == null -> Text("Asking the server…")
            p.isFailure -> Text(p.exceptionOrNull()?.message ?: "Failed", color = MaterialTheme.colorScheme.error)
            else -> PlaybackInfoPanel(p.getOrThrow(), videoDecoder = null, audioDecoder = null, modifier = Modifier.focusable())
        }
    }
}

/** How the item would play on this TV, why, and fixes that can be made before playing. */
@Composable
internal fun CheckPlaybackDialog(
    item: MediaItem,
    check: suspend (MediaItem) -> dev.nitrate.core.playback.PlaybackDiagnosis,
    apply: suspend (MediaItem, dev.nitrate.core.playback.PlaybackFix) -> Unit,
    onDismiss: () -> Unit,
) {
    // Asked again after each fix, so the dialog shows what the fix changed.
    var round by remember { mutableStateOf(0) }
    val result by produceState<Result<dev.nitrate.core.playback.PlaybackDiagnosis>?>(null, item.id, round) {
        value = null
        value = runCatching { check(item) }
    }
    val scope = rememberCoroutineScope()
    dev.nitrate.tv.ui.player.PlaybackFixDialog(
        diagnosis = result?.getOrNull(),
        error = result?.exceptionOrNull()?.let { it.message ?: it::class.simpleName },
        onFix = { fix -> scope.launch { apply(item, fix); round++ } },
        onDismiss = onDismiss,
        canApply = { it is dev.nitrate.core.playback.PlaybackFix.Change || it is dev.nitrate.core.playback.PlaybackFix.UseVersion },
    )
}
