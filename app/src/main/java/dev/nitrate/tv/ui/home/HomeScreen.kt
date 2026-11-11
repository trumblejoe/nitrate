package dev.nitrate.tv.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.tv.data.HomeRow
import dev.nitrate.tv.data.JellyfinRepository
import dev.nitrate.tv.ui.components.MediaCard
import dev.nitrate.tv.ui.components.displayTitle
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

@Composable
fun HomeScreen(
    repository: JellyfinRepository,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
    onSignOut: () -> Unit,
) {
    val vm = viewModel { HomeViewModel(repository) }
    val state by vm.state.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf<BaseItemDto?>(null) }
    val scope = rememberCoroutineScope()

    // Refresh "Continue Watching" when returning from the player.
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (!firstResume) vm.refresh()
        firstResume = false
        onPauseOrDispose { }
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = focused?.let(repository::backdropUrl), label = "backdrop") { url ->
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(listOf(Color(0xF00B0D12), Color(0x990B0D12), Color(0x330B0D12))))
                        drawRect(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xFF0B0D12)))
                    },
                )
            }
        }

        when (val s = state) {
            HomeState.Loading -> Text("Loading…", Modifier.align(Alignment.Center))
            is HomeState.Error -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::refresh) { Text("Retry") }
                    OutlinedButton(onClick = { scope.launch { repository.logout(); onSignOut() } }) { Text("Sign out") }
                }
            }
            is HomeState.Loaded -> Column(Modifier.fillMaxSize()) {
                FocusedHeader(focused, Modifier.padding(start = 58.dp, top = 36.dp, end = 58.dp).height(200.dp))
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    items(s.rows, key = { it.title }) { row ->
                        MediaRow(row, repository, onFocus = { focused = it }, onClick = onOpen, onPlay = { item ->
                            if (repository.isPlayable(item)) onPlay(item) else onOpen(item)
                        })
                    }
                    item {
                        OutlinedButton(
                            onClick = { scope.launch { repository.logout(); onSignOut() } },
                            modifier = Modifier.padding(start = 58.dp),
                        ) { Text("Sign out") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusedHeader(item: BaseItemDto?, modifier: Modifier) {
    Column(modifier.fillMaxWidth(0.55f), verticalArrangement = Arrangement.Bottom) {
        if (item == null) return@Column
        val title = if (item.type == BaseItemKind.EPISODE) item.seriesName ?: item.name else item.name
        Text(title.orEmpty(), style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = buildList {
            if (item.type == BaseItemKind.EPISODE) {
                add("S${item.parentIndexNumber ?: "?"}:E${item.indexNumber ?: "?"} · ${item.name.orEmpty()}")
            }
            item.productionYear?.let { add(it.toString()) }
            item.officialRating?.let { add(it) }
            item.runTimeTicks?.let { add("${it / 600_000_000} min") }
        }.joinToString("  ·  ")
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        item.overview?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaRow(
    row: HomeRow,
    repository: JellyfinRepository,
    onFocus: (BaseItemDto) -> Unit,
    onClick: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
) {
    // Landscape thumbs for in-progress rows, posters for library rows.
    val landscape = row.title == "Continue Watching" || row.title == "Next Up"
    Column {
        Text(row.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 58.dp, bottom = 12.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 58.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(row.items, key = { it.id }) { item ->
                val image = if (landscape) {
                    repository.imageUrl(item, ImageType.THUMB, 320)
                        ?: repository.backdropUrl(item)
                        ?: repository.imageUrl(item, ImageType.PRIMARY, 320)
                } else {
                    repository.imageUrl(item, ImageType.PRIMARY, 360)
                }
                MediaCard(
                    item = item,
                    imageUrl = image,
                    width = if (landscape) 260.dp else 140.dp,
                    aspectRatio = if (landscape) 16f / 9f else 2f / 3f,
                    title = if (landscape) item.displayTitle() else null,
                    onFocus = { onFocus(item) },
                    onClick = { onClick(item) },
                    onPlay = { onPlay(item) },
                )
            }
        }
    }
}
