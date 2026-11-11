package dev.nitrate.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import dev.nitrate.core.downloads.DownloadOption
import dev.nitrate.core.downloads.DownloadQuality
import dev.nitrate.core.downloads.DownloadRecord
import dev.nitrate.core.downloads.DownloadSettings
import dev.nitrate.core.downloads.DownloadSizing
import dev.nitrate.core.downloads.DownloadState
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.formatClock
import kotlinx.coroutines.launch
import java.io.File

/** Downloaded and downloading items, with download settings. Works without a connection. */
@Composable
fun DownloadsScreen(actions: AppActions, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val downloads = actions.container.downloads
    val records by downloads.downloads.collectAsState(emptyList())
    val settings by downloads.settings.collectAsState(DownloadSettings())
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf<DownloadRecord?>(null) }

    val active = records.filter { it.state != DownloadState.COMPLETED }
    val done = records.filter { it.state == DownloadState.COMPLETED }
    val movies = done.filter { it.item.kind != ItemKind.EPISODE }.sortedBy { it.item.name }
    val shows = done.filter { it.item.kind == ItemKind.EPISODE }
        .groupBy { it.item.seriesName ?: "Other" }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .mapValues { (_, eps) -> eps.sortedWith(compareBy({ it.item.seasonNumber ?: 0 }, { it.item.indexNumber ?: 0 })) }
    // Read once per change: the files aren't large in number.
    val used = remember(records) { downloads.bytesUsed() }
    val free = remember(records) { actions.container.downloadSpaceFree() }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)) }
        item {
            Row(Modifier.padding(start = if (onBack != null) 4.dp else ScreenPadding, end = ScreenPadding, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text("Downloads", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${DownloadSizing.formatBytes(used)} used · ${DownloadSizing.formatBytes(free)} free",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { DownloadSettingsCard(actions, settings) }

        if (records.isEmpty()) {
            item {
                Text(
                    "Nothing downloaded yet. Use the download button on a movie or episode to watch it without a connection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(ScreenPadding),
                )
            }
        }
        if (active.isNotEmpty()) {
            item { SectionTitle("In progress", Modifier.padding(top = 16.dp)) }
            items(active, key = { "a-${it.itemId}" }) { r ->
                ActiveRow(r, actions, onRemove = { removing = r }, onPause = { scope.launch { downloads.pause(r.itemId) } }, onResume = { scope.launch { downloads.resume(r.itemId) } })
            }
        }
        if (movies.isNotEmpty()) {
            item { SectionTitle("Movies", Modifier.padding(top = 16.dp)) }
            items(movies, key = { "m-${it.itemId}" }) { r -> DoneRow(r, actions, onRemove = { removing = r }) }
        }
        shows.forEach { (series, episodes) ->
            item(key = "s-$series") { SectionTitle(series, Modifier.padding(top = 16.dp)) }
            items(episodes, key = { "e-${it.itemId}" }) { r -> DoneRow(r, actions, onRemove = { removing = r }) }
        }
    }

    removing?.let { r ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(if (r.state == DownloadState.COMPLETED) "Delete download?" else "Cancel download?") },
            text = { Text("${r.item.episodeLabel ?: r.item.name} will be removed from this device. It stays on your server.") },
            confirmButton = { TextButton(onClick = { scope.launch { downloads.remove(r.itemId) }; removing = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun DownloadSettingsCard(actions: AppActions, settings: DownloadSettings) {
    val downloads = actions.container.downloads
    val scope = rememberCoroutineScope()
    val hevcCapable = remember { "hevc" in actions.container.capabilities.video }
    Column(
        Modifier.padding(ScreenPadding).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Default quality", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadQuality.entries.forEach { q ->
                FilterChip(selected = settings.quality == q, onClick = { scope.launch { downloads.updateSettings { it.copy(quality = q) } } }, label = { Text(q.label) })
            }
        }
        SwitchRow("Download on Wi-Fi only", "Queued downloads wait for Wi-Fi.", settings.wifiOnly) { on -> scope.launch { downloads.updateSettings { it.copy(wifiOnly = on) } } }
        if (hevcCapable) {
            SwitchRow("Smaller files (HEVC)", "About 40% smaller transcodes. Takes the server longer to make.", settings.hevc) { on ->
                scope.launch { downloads.updateSettings { it.copy(hevc = on) } }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(checked, role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Thumb(r: DownloadRecord, actions: AppActions) {
    val local = actions.container.downloads.imagePath(r, poster = true)
    Box(Modifier.width(56.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        val model: Any? = local?.let(::File) ?: actions.server.cardImage(r.item, CardShape.POSTER, 200)
        if (model != null) AsyncImage(model, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

private fun DownloadRecord.title() = if (item.kind == ItemKind.EPISODE) "S${item.seasonNumber ?: "?"}:E${item.indexNumber ?: "?"} · ${item.name}" else item.name

@Composable
private fun DoneRow(r: DownloadRecord, actions: AppActions, onRemove: () -> Unit) {
    val user = r.item.user
    val status = when {
        user.played -> "Watched"
        user.positionMs > 0 -> "Resume at ${formatClock(user.positionMs)}"
        else -> null
    }
    ListItem(
        modifier = Modifier.clickable { actions.play(r.item, false) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        leadingContent = { Thumb(r, actions) },
        headlineContent = { Text(r.title(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOfNotNull(r.quality.label, r.totalBytes?.let(DownloadSizing::formatBytes), status, "Not synced".takeIf { r.pendingSync }).joinToString(" · "))
        },
        trailingContent = {
            Row {
                IconButton(onClick = { actions.play(r.item, false) }) { Icon(Icons.Default.PlayArrow, "Play") }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Delete") }
            }
        },
    )
}

@Composable
private fun ActiveRow(r: DownloadRecord, actions: AppActions, onRemove: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    val status = when (r.state) {
        DownloadState.DOWNLOADING -> listOfNotNull(
            DownloadSizing.formatBytes(r.bytesDownloaded),
            r.totalBytes?.let { (if (r.quality.isOriginal) "of " else "of about ") + DownloadSizing.formatBytes(it) },
        ).joinToString(" ")
        DownloadState.QUEUED -> r.error ?: "Queued"
        DownloadState.PAUSED -> "Paused"
        DownloadState.FAILED -> r.error ?: "Failed"
        DownloadState.COMPLETED -> ""
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        leadingContent = { Thumb(r, actions) },
        headlineContent = { Text(r.title(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                val progress = r.progress
                if (r.state == DownloadState.DOWNLOADING && progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else if (r.state == DownloadState.DOWNLOADING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("${r.quality.label} · $status", color = if (r.state == DownloadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingContent = {
            Row {
                when (r.state) {
                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> IconButton(onClick = onPause) { Icon(NitrateIcons.Pause, "Pause") }
                    DownloadState.PAUSED -> IconButton(onClick = onResume) { Icon(NitrateIcons.Download, "Resume") }
                    DownloadState.FAILED -> IconButton(onClick = onResume) { Icon(Icons.Default.Refresh, "Retry") }
                    DownloadState.COMPLETED -> Unit
                }
                IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "Cancel") }
            }
        },
    )
}

/**
 * Download button for a movie or episode: shows progress while downloading and a tick when done.
 * Tapping opens the quality picker, or offers to delete a finished download.
 */
@Composable
fun DownloadButton(actions: AppActions, item: MediaItem) {
    val downloads = actions.container.downloads
    val records by downloads.downloads.collectAsState(emptyList())
    val record = records.firstOrNull { it.itemId == item.id }
    var picking by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    FilledTonalIconButton(onClick = { if (record == null) picking = true else confirmRemove = true }) {
        when (record?.state) {
            null -> Icon(NitrateIcons.Download, "Download")
            DownloadState.COMPLETED -> Icon(NitrateIcons.DownloadDone, "Downloaded")
            DownloadState.DOWNLOADING -> {
                val p = record.progress
                if (p != null) CircularProgressIndicator(progress = { p }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            }
            else -> Icon(NitrateIcons.Download, "Download queued", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (picking) DownloadDialog(actions, listOf(item), item.episodeLabel ?: item.name) { picking = false }
    if (confirmRemove && record != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (record.state == DownloadState.COMPLETED) "Delete download?" else "Cancel download?") },
            text = { Text("Removes it from this device. It stays on your server.") },
            confirmButton = { TextButton(onClick = { scope.launch { downloads.remove(item.id) }; confirmRemove = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}

/** Picks a quality for [items] (one item, or a season's episodes) and queues them. */
@Composable
fun DownloadDialog(actions: AppActions, items: List<MediaItem>, title: String, onDismiss: () -> Unit) {
    val downloads = actions.container.downloads
    val settings by downloads.settings.collectAsState(DownloadSettings())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<Result<List<DownloadOption>>?>(null) }
    var chosen by remember { mutableStateOf<DownloadQuality?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(items, settings.hevc) {
        // Sizes from the first item; for a season they're scaled by total runtime.
        options = runCatching {
            val first = downloads.options(items.first(), settings.hevc)
            if (items.size == 1) first else {
                val runtime = items.sumOf { it.runtimeMs ?: 0 }
                first.map { o ->
                    val bitrate = o.quality.totalBitrate(settings.hevc)
                    o.copy(estimatedBytes = if (bitrate != null) DownloadSizing.estimate(bitrate, runtime) else null)
                }
            }
        }
    }
    val list = options?.getOrNull()
    val selected = chosen ?: list?.firstOrNull { it.quality == settings.quality && it.worthwhile }?.quality ?: DownloadQuality.ORIGINAL

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size > 1) "Download ${items.size} episodes" else "Download") },
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                when {
                    options == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
                    list == null -> Text("Couldn't reach the server: ${options?.exceptionOrNull()?.message}", color = MaterialTheme.colorScheme.error)
                    else -> list.forEach { o ->
                        val size = o.estimatedBytes?.let { (if (o.quality.isOriginal) "" else "about ") + DownloadSizing.formatBytes(it) }
                        val detail = when {
                            !o.worthwhile -> "Not smaller than the original"
                            o.quality.isOriginal -> listOfNotNull(size, "full quality").joinToString(" · ")
                            else -> listOfNotNull(size, "converted by your server").joinToString(" · ")
                        }
                        Row(
                            Modifier.fillMaxWidth().selectable(o.quality == selected, enabled = o.worthwhile, role = Role.RadioButton) { chosen = o.quality }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = o.quality == selected, onClick = null, enabled = o.worthwhile)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(o.quality.label, style = MaterialTheme.typography.bodyLarge, color = if (o.worthwhile) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (settings.wifiOnly) {
                    Text("Starts when you're on Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = list != null,
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    // Closed once queued: closing first would cancel this scope.
                    scope.launch {
                        downloads.updateSettings { it.copy(quality = selected) }
                        downloads.enqueue(items, selected, settings.hevc && !selected.isOriginal)
                        onDismiss()
                    }
                },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
