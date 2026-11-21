package dev.nitrate.mobile.widgets

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.nitrate.android.AppContainer
import dev.nitrate.core.widgets.WidgetCard
import dev.nitrate.core.widgets.WidgetCards
import dev.nitrate.core.widgets.WidgetKind
import dev.nitrate.mobile.MainActivity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Home-screen widgets: what's half watched ([WidgetKind.CONTINUE]) and the next episode of shows
 * ([WidgetKind.NEXT_UP]), from Home's own rows. A title opens straight in the player (resuming);
 * the header opens the app. Shown again from the copy kept last time when the server can't be
 * reached; refreshed by the system every half hour and whenever the app goes to the background.
 */
abstract class WatchWidget(private val kind: WidgetKind) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val shown = load(context, kind)
        provideContent { Content(context, kind, shown) }
    }
}

class ContinueWatchingWidget : WatchWidget(WidgetKind.CONTINUE)
class NextUpWidget : WatchWidget(WidgetKind.NEXT_UP)

class ContinueWatchingReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueWatchingWidget()
}

class NextUpReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextUpWidget()
}

object WatchWidgets {
    /** Brings every placed widget up to date (when the app goes to the background, after watching). */
    suspend fun refresh(context: Context) {
        val manager = GlanceAppWidgetManager(context)
        if (manager.getGlanceIds(ContinueWatchingWidget::class.java).isNotEmpty()) ContinueWatchingWidget().updateAll(context)
        if (manager.getGlanceIds(NextUpWidget::class.java).isNotEmpty()) NextUpWidget().updateAll(context)
    }

    /** Opens [itemId] in the player, resuming. */
    const val EXTRA_PLAY_ITEM = "dev.nitrate.mobile.PLAY_ITEM"

    /** A title's tap: a unique address per title, so each line's tap stays its own. */
    fun playIntent(context: Context, itemId: String): Intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .setData(Uri.parse("nitrate://play/$itemId"))
        .putExtra(EXTRA_PLAY_ITEM, itemId)
}

/** What a widget shows: its cards with their pictures, or why there are none. */
internal class Shown(val cards: List<Pair<WidgetCard, Bitmap?>>, val note: String?)

private val json = Json { ignoreUnknownKeys = true }

private suspend fun load(context: Context, kind: WidgetKind): Shown = withContext(Dispatchers.IO) {
    val container = (context.applicationContext as? AppContainer.Owner)?.container ?: return@withContext Shown(emptyList(), "Open Nitrate")
    val dir = File(context.cacheDir, "widgets").apply { mkdirs() }
    val kept = File(dir, "${kind.name}.json")
    val fresh = runCatching {
        // Started by the system with the app closed: load the saved sign-in first.
        if (container.server.userId == null) container.server.restoreSession()
        if (container.server.userId == null) return@withContext Shown(emptyList(), "Open Nitrate to sign in")
        val items = WidgetCards.items(kind, container.server.homeRows())
        items.map { item ->
            val card = WidgetCards.card(item)
            val picture = container.server.backdropUrl(item, 480)?.let { url -> picture(context, url, File(dir, "${item.id}.png")) }
            card to picture
        }
    }.getOrNull()
    if (fresh != null) {
        runCatching { kept.writeText(json.encodeToString(ListSerializer(WidgetCardJson.serializer()), fresh.map { WidgetCardJson.of(it.first) })) }
        return@withContext Shown(fresh, if (fresh.isEmpty()) kind.empty else null)
    }
    // Offline: what it showed last time.
    val old = runCatching { json.decodeFromString(ListSerializer(WidgetCardJson.serializer()), kept.readText()) }.getOrNull().orEmpty()
    Shown(old.map { c -> c.card() to File(dir, "${c.itemId}.png").takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }, if (old.isEmpty()) "Can't reach your server" else null)
}

/** A small copy of the picture, kept beside the cards so the widget still shows it offline. */
private suspend fun picture(context: Context, url: String, file: File): Bitmap? = runCatching {
    val request = ImageRequest.Builder(context).data(url).size(320, 180).allowHardware(false).build()
    val result = coil3.SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return@runCatching null
    result.image.toBitmap().also { b -> runCatching { file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } } }
}.getOrNull()

@kotlinx.serialization.Serializable
private data class WidgetCardJson(val itemId: String, val title: String, val line: String? = null, val progress: Float? = null, val spoken: String = "") {
    fun card() = WidgetCard(itemId, title, line, progress, spoken.ifEmpty { title })

    companion object {
        fun of(c: WidgetCard) = WidgetCardJson(c.itemId, c.title, c.line, c.progress, c.spoken)
    }
}

private val Ink = ColorProvider(Color(0xFFF2F2F5))
private val Quiet = ColorProvider(Color(0xFFA9A9B4))
private val Panel = ColorProvider(Color(0xF0141418))

@Composable
internal fun Content(context: Context, kind: WidgetKind, shown: Shown) {
    val open = actionStartActivity(Intent(context, MainActivity::class.java))
    // Wide enough for the picture beside the words; narrow, words only.
    val pictures = LocalSize.current.width >= 220.dp
    Column(GlanceModifier.fillMaxSize().background(Panel).cornerRadius(20.dp).padding(12.dp)) {
        Text(
            kind.title,
            style = TextStyle(color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 8.dp).clickable(open),
        )
        if (shown.cards.isEmpty()) {
            Box(GlanceModifier.fillMaxSize().clickable(open), contentAlignment = Alignment.Center) {
                Text(shown.note ?: kind.empty, style = TextStyle(color = Quiet, fontSize = 13.sp))
            }
            return@Column
        }
        LazyColumn(GlanceModifier.fillMaxSize()) {
            items(shown.cards, itemId = { it.first.itemId.hashCode().toLong() }) { (card, bitmap) ->
                Card(context, card, if (pictures) bitmap else null)
            }
        }
    }
}

@Composable
private fun Card(context: Context, card: WidgetCard, bitmap: Bitmap?) {
    val play = WatchWidgets.playIntent(context, card.itemId)
    Row(
        GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(actionStartActivity(play)).semantics { contentDescription = "Play ${card.spoken}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(ImageProvider(bitmap), contentDescription = null, contentScale = ContentScale.Crop, modifier = GlanceModifier.size(96.dp, 54.dp).cornerRadius(8.dp))
            Spacer(GlanceModifier.width(10.dp))
        }
        Column(GlanceModifier.defaultWeight()) {
            Text(card.title, maxLines = 1, style = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            card.line?.let { Text(it, maxLines = 1, style = TextStyle(color = Quiet, fontSize = 12.sp)) }
            card.progress?.let { p ->
                Spacer(GlanceModifier.height(4.dp))
                LinearProgressIndicator(p, GlanceModifier.fillMaxWidth().height(3.dp), color = ColorProvider(Color(0xFFE8A33D)), backgroundColor = ColorProvider(Color(0x33FFFFFF)))
            }
        }
    }
}
