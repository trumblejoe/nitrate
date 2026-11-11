package dev.nitrate.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.nitrate.core.model.MediaItem

/** Remote "Play" keys, so any card can start playback without opening details first. */
fun Modifier.onPlayKey(onPlay: (() -> Unit)?): Modifier =
    if (onPlay == null) this else onPreviewKeyEvent { event ->
        val isPlay = event.key == Key.MediaPlay || event.key == Key.MediaPlayPause
        if (isPlay && event.type == KeyEventType.KeyUp) onPlay()
        isPlay
    }

@Composable
fun MediaCard(
    item: MediaItem,
    imageUrl: String?,
    /** Fixed width, or null to fill the parent (grid cells). */
    width: Dp?,
    aspectRatio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    onFocus: () -> Unit = {},
    onPlay: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    showProgress: Boolean = true,
) {
    val progress = item.user.playedPercentage?.toFloat()?.div(100f)
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .onFocusChanged { if (it.isFocused) onFocus() }
                .onPlayKey(onPlay),
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (imageUrl != null) {
                    AsyncImage(imageUrl, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        item.name.orEmpty(),
                        Modifier.align(Alignment.Center).padding(8.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (showProgress && progress != null && progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x99000000))) {
                        Box(Modifier.fillMaxWidth(progress).height(4.dp).background(MaterialTheme.colorScheme.primary))
                    }
                }
                if (item.user.played) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                            .padding(horizontal = 6.dp),
                    )
                }
            }
        }
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
