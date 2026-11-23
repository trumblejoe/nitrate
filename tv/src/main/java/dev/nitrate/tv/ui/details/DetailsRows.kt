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

@Composable
internal fun SeasonSelector(seasons: List<MediaItem>, selected: Int, onSelect: (Int) -> Unit) {
    val selectedChip = remember { FocusRequester() }
    var rowEntered by remember { mutableStateOf(false) }
    LazyRow(
        // Coming in from above lands on the season shown (then the last one focused).
        modifier = Modifier.focusRestorer(selectedChip).onFocusChanged { rowEntered = it.hasFocus },
        contentPadding = PaddingValues(horizontal = PagePadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(seasons, key = { _, s -> s.id }) { index, season ->
            // Moving along the chips switches season, so the D-pad alone browses them; arriving
            // from above lands on the season already shown, so it doesn't switch on the way in.
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                modifier = Modifier
                    .then(if (index == selected) Modifier.focusRequester(selectedChip) else Modifier)
                    .onFocusChanged { if (it.isFocused && rowEntered) onSelect(index) },
            ) {
                Text(season.name.ifBlank { "Season ${season.indexNumber}" })
            }
        }
    }
}

@Composable
internal fun PersonCard(person: Person, imageUrl: String?) {
    Column {
        Card(
            onClick = {},
            modifier = Modifier.padding(bottom = 6.dp).height(150.dp).aspectRatio(2f / 3f),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    imageUrl,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(person.name.orEmpty().split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Text(person.name.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(100.dp))
        person.role?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(100.dp))
        }
    }
}

@Composable
internal fun RowTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = PagePadding, bottom = 12.dp))
}
