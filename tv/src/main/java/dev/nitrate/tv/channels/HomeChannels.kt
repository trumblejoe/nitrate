package dev.nitrate.tv.channels

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.nitrate.android.AppContainer
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.rows.HomeChannelContent
import dev.nitrate.tv.R
import java.util.concurrent.TimeUnit

/**
 * Puts Nitrate on the Android TV home screen: what to carry on with in the system "Play Next"
 * row, and a "Nitrate" channel of new things to start. Selecting either opens the app through a
 * `nitrate://` link ([TvLinks]).
 */
class HomeChannels(private val context: Context, private val server: MediaServer) {
    private val prefs = context.getSharedPreferences("home_channels", Context.MODE_PRIVATE)
    private val helper = PreviewChannelHelper(context)

    /** Rebuilds both from the signed-in user's Home rows; clears them when signed out. */
    suspend fun sync() {
        if (!supported()) return
        if (server.currentAccountKey == null) {
            clear()
            return
        }
        val content = HomeChannelContent.from(server.homeRows())
        syncWatchNext(content.watchNext)
        syncChannel(content.channel)
    }

    /** Removes everything this app put on the home screen (e.g. after signing out). */
    fun clear() {
        if (!supported()) return
        storedIds(WATCH_NEXT).forEach { runCatching { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) } }
        prefs.edit().remove(WATCH_NEXT).apply()
        channelId()?.let { channel -> storedIds(PROGRAMS).forEach { runCatching { helper.deletePreviewProgram(it) } }; prefs.edit().remove(PROGRAMS).apply(); runCatching { helper.deletePreviewChannel(channel) } }
        prefs.edit().remove(CHANNEL).apply()
    }

    private fun supported() = context.packageManager.hasSystemFeature("android.software.leanback")

    @SuppressLint("RestrictedApi")
    private fun syncWatchNext(entries: List<HomeChannelContent.WatchNextEntry>) {
        storedIds(WATCH_NEXT).forEach { runCatching { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) } }
        val now = System.currentTimeMillis()
        val ids = entries.mapIndexedNotNull { i, entry ->
            val item = entry.item
            val program = WatchNextProgram.Builder()
                .setType(if (item.kind == ItemKind.EPISODE) TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE else TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                .setWatchNextType(
                    if (entry.kind == HomeChannelContent.WatchNextEntry.Kind.CONTINUE) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                    else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT,
                )
                // Most recent first: earlier entries count as more recently engaged.
                .setLastEngagementTimeUtcMillis(now - i * 60_000L)
                .apply { describe(this, item) }
                .apply {
                    item.runtimeMs?.let { setDurationMillis(it.toInt()) }
                    if (item.user.positionMs > 0) setLastPlaybackPositionMillis(item.user.positionMs.toInt())
                }
                .setIntentUri(TvLinks.play(item.id))
                .setInternalProviderId(item.id)
                .build()
            runCatching { context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, program.toContentValues()) }.getOrNull()?.let(ContentUris::parseId)
        }
        storeIds(WATCH_NEXT, ids)
    }

    private fun syncChannel(items: List<MediaItem>) {
        val channel = channelId() ?: createChannel() ?: return
        storedIds(PROGRAMS).forEach { runCatching { helper.deletePreviewProgram(it) } }
        val ids = items.mapIndexedNotNull { i, item ->
            val program = PreviewProgram.Builder()
                .setChannelId(channel)
                .setType(
                    when (item.kind) {
                        ItemKind.EPISODE -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
                        ItemKind.SERIES -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                        else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
                    },
                )
                .setWeight(items.size - i)
                .apply { describe(this, item) }
                .setIntentUri(TvLinks.open(item.id))
                .setInternalProviderId(item.id)
                .build()
            runCatching { helper.publishPreviewProgram(program) }.getOrNull()
        }
        storeIds(PROGRAMS, ids)
    }

    /** Title, description and artwork, shared by both kinds of program. */
    private fun <T : androidx.tvprovider.media.tv.BasePreviewProgram.Builder<T>> describe(builder: T, item: MediaItem) {
        val episode = item.kind == ItemKind.EPISODE
        builder.setTitle(if (episode) item.seriesName ?: item.name else item.name)
        if (episode) {
            builder.setEpisodeTitle(item.name)
            item.seasonNumber?.let { builder.setSeasonNumber(it) }
            item.indexNumber?.let { builder.setEpisodeNumber(it) }
        }
        item.overview?.let { builder.setDescription(it.take(240)) }
        item.productionYear?.let { builder.setReleaseDate(it.toString()) }
        val wide = server.imageUrl(item, ImageKind.THUMB, 360) ?: server.backdropUrl(item)
        val poster = server.imageUrl(item, ImageKind.PRIMARY, 480)
        if (episode || poster == null) {
            (wide ?: poster)?.let { builder.setPosterArtUri(Uri.parse(it)) }
            builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        } else {
            builder.setPosterArtUri(Uri.parse(poster))
            builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_MOVIE_POSTER)
        }
    }

    private fun createChannel(): Long? = runCatching {
        val logo = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        val channel = PreviewChannel.Builder()
            .setDisplayName("Nitrate")
            .setAppLinkIntentUri(TvLinks.home())
            .setLogo(logo)
            .build()
        val id = helper.publishDefaultChannel(channel)
        prefs.edit().putLong(CHANNEL, id).apply()
        id
    }.getOrNull()

    private fun channelId(): Long? {
        val id = prefs.getLong(CHANNEL, -1).takeIf { it >= 0 } ?: return null
        // The user may have removed it from the home screen; then it's gone for good.
        return id.takeIf { runCatching { helper.getPreviewChannel(it) }.getOrNull() != null }
    }

    private fun storedIds(key: String): List<Long> = prefs.getString(key, "").orEmpty().split(',').mapNotNull(String::toLongOrNull)
    private fun storeIds(key: String, ids: List<Long>) = prefs.edit().putString(key, ids.joinToString(",")).apply()

    /** Keeps the home screen fresh: every few hours, and soon after something changes. */
    fun schedule() {
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<HomeChannelsWorker>(4, TimeUnit.HOURS).build())
    }

    fun refreshSoon() {
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        work.enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<HomeChannelsWorker>().setInitialDelay(5, TimeUnit.SECONDS).build())
    }

    private companion object {
        const val CHANNEL = "channel"
        const val PROGRAMS = "programs"
        const val WATCH_NEXT = "watch_next"
        const val PERIODIC = "home-channels"
        const val SOON = "home-channels-soon"
    }
}

class HomeChannelsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as? AppContainer.Owner)?.container ?: return Result.failure()
        if (container.server.userId == null) container.server.restoreSession()
        return runCatching { HomeChannels(applicationContext, container.server).sync() }.fold({ Result.success() }, { Result.retry() })
    }
}

/** Links the home screen opens the app with. */
object TvLinks {
    const val SCHEME = "nitrate"
    fun home(): Uri = Uri.parse("$SCHEME://home")
    fun open(itemId: String): Uri = Uri.parse("$SCHEME://item/$itemId")
    fun play(itemId: String): Uri = Uri.parse("$SCHEME://play/$itemId")

    /** What a link asks for: (action, itemId), action being "item", "play", "search" or "home". */
    fun parse(intent: Intent?): Pair<String, String?>? {
        val data = intent?.data ?: return null
        if (data.scheme != SCHEME) return null
        return data.host.orEmpty() to data.pathSegments.firstOrNull()
    }
}
