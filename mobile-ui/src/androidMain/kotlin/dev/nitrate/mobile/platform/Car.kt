package dev.nitrate.mobile.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.nitrate.android.AppContainer
import dev.nitrate.android.playback.AndroidListening
import dev.nitrate.core.MediaServer
import dev.nitrate.core.car.CarLibrary
import dev.nitrate.core.car.CarNode
import dev.nitrate.core.car.CarQueue
import dev.nitrate.core.model.ImageKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The session's player: the listening player (or the video player in the background), with the
 * car's requests turned into the listening player's own. A queue the car (or a voice request) asks
 * for arrives as one stand-in item naming a [CarQueue] that [CarBrowsing] resolved; playing it hands
 * the real songs to [AndroidListening.play], so levelling, reports, downloads and books work as in
 * the app. Next/Previous go through the listening player too (chapters in a book, restart after 3 s).
 */
@OptIn(UnstableApi::class)
class CarPlayer(player: Player, private val listening: () -> AndroidListening?) : ForwardingPlayer(player) {
    private fun own() = listening()?.takeIf { it.player === wrappedPlayer }

    private fun queueOf(items: List<MediaItem>): CarQueue? = items.firstOrNull()?.mediaId?.let(CarBrowsing::take)

    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = setMediaItems(mediaItems, true)
    override fun setMediaItem(mediaItem: MediaItem) = setMediaItems(mutableListOf(mediaItem), true)
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = setMediaItems(mutableListOf(mediaItem), 0, startPositionMs)
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = setMediaItems(mutableListOf(mediaItem), resetPosition)

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        val q = queueOf(mediaItems) ?: return super.setMediaItems(mediaItems, resetPosition)
        start(q)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val q = queueOf(mediaItems) ?: return super.setMediaItems(mediaItems, startIndex, startPositionMs)
        start(q)
    }

    override fun addMediaItems(mediaItems: MutableList<MediaItem>) {
        val q = queueOf(mediaItems) ?: return super.addMediaItems(mediaItems)
        listening()?.addToQueue(q.items)
    }

    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) {
        val q = queueOf(mediaItems) ?: return super.addMediaItems(index, mediaItems)
        listening()?.playNext(q.items)
    }

    private fun start(q: CarQueue) {
        listening()?.play(q.items, q.startIndex, q.shuffle, q.startMs)
    }

    // The listening player prepares and plays what it's given itself.
    override fun prepare() { if (own() == null || wrappedPlayer.playbackState == Player.STATE_IDLE && wrappedPlayer.mediaItemCount > 0) super.prepare() }

    override fun seekToNext() { own()?.next() ?: super.seekToNext() }
    override fun seekToNextMediaItem() { own()?.next() ?: super.seekToNextMediaItem() }
    override fun seekToPrevious() { own()?.previous() ?: super.seekToPrevious() }
    override fun seekToPreviousMediaItem() { own()?.previous() ?: super.seekToPreviousMediaItem() }
}

/** Android Auto's library: [CarLibrary]'s tree as Media3 items, search, and voice requests. */
@OptIn(UnstableApi::class)
class CarBrowsing(private val context: Context, private val scope: CoroutineScope) : MediaLibraryService.MediaLibrarySession.Callback {
    private val container: AppContainer? get() = (context.applicationContext as? AppContainer.Owner)?.container
    private val searches = HashMap<String, List<CarNode>>()

    companion object {
        private const val QUEUE = "nitrate-car-queue:"
        private val queues = HashMap<String, CarQueue>()
        private var next = 0

        /** Keeps [q] for the player and names it with a stand-in item. */
        fun hold(q: CarQueue): MediaItem {
            val token = "${next++}"
            synchronized(queues) { queues[token] = q }
            return MediaItem.Builder().setMediaId(QUEUE + token).setUri("nitrate://car/$token")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(q.items.getOrNull(q.startIndex)?.name).build()).build()
        }

        fun take(mediaId: String): CarQueue? =
            if (!mediaId.startsWith(QUEUE)) null else synchronized(queues) { queues.remove(mediaId.removePrefix(QUEUE)) }
    }

    /** The server, signed in again if the app hasn't been opened since the phone started. */
    private suspend fun server(): MediaServer? {
        val c = container ?: return null
        if (c.server.userId == null) runCatching { c.server.restoreSession() }
        return c.server.takeIf { it.userId != null }
    }

    private fun <T : Any> later(work: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
        val f = SettableFuture.create<LibraryResult<T>>()
        scope.launch {
            f.set(runCatching { work() }.getOrElse { LibraryResult.ofError<T>(SessionError(SessionError.ERROR_IO, "Couldn't reach your server")) })
        }
        return f
    }

    private val signIn = SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, "Open Nitrate on your phone to sign in")

    override fun onGetLibraryRoot(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
        val extras = Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
        }
        val root = MediaItem.Builder().setMediaId(CarLibrary.ROOT)
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Nitrate").setIsBrowsable(true).setIsPlayable(false).build()).build()
        return SettableFuture.create<LibraryResult<MediaItem>>().apply { set(LibraryResult.ofItem(root, MediaLibraryService.LibraryParams.Builder().setExtras(extras).build())) }
    }

    override fun onGetChildren(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?) = later<ImmutableList<MediaItem>> {
        val server = server() ?: return@later LibraryResult.ofError(signIn)
        LibraryResult.ofItemList(CarLibrary.children(server, parentId, page, pageSize.coerceIn(1, CarLibrary.PAGE)).map { item(server, it) }, params)
    }

    override fun onGetItem(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) = later<MediaItem> {
        val server = server() ?: return@later LibraryResult.ofError(signIn)
        val tab = CarLibrary.tabs.firstOrNull { it.id == mediaId }
        LibraryResult.ofItem(item(server, tab ?: CarNode(mediaId, "", playable = mediaId.startsWith("play:"))), null)
    }

    override fun onSearch(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: MediaLibraryService.LibraryParams?) = later<Void> {
        val server = server() ?: return@later LibraryResult.ofError(signIn)
        val found = CarLibrary.search(server, query)
        synchronized(searches) { searches[query] = found }
        session.notifySearchResultChanged(browser, query, found.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?) = later<ImmutableList<MediaItem>> {
        val server = server() ?: return@later LibraryResult.ofError(signIn)
        val found = synchronized(searches) { searches[query] } ?: CarLibrary.search(server, query)
        LibraryResult.ofItemList(found.drop(page * pageSize).take(pageSize).map { item(server, it) }, params)
    }

    /** Something to play, by id from the library or by name from a voice request: resolved to a held queue. */
    override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
        val f = SettableFuture.create<MutableList<MediaItem>>()
        scope.launch {
            val server = server()
            val resolved = mediaItems.mapNotNull { requested ->
                if (server == null) return@mapNotNull null
                val query = requested.requestMetadata.searchQuery
                val q = runCatching {
                    when {
                        requested.mediaId.isNotEmpty() && requested.localConfiguration == null -> CarLibrary.queue(server, requested.mediaId)
                        query != null -> CarLibrary.forVoice(server, query)
                        else -> null
                    }
                }.getOrNull()
                q?.let(::hold) ?: requested.takeIf { it.localConfiguration != null }
            }
            if (resolved.isEmpty()) f.setException(IllegalStateException("Nothing found to play")) else f.set(resolved.toMutableList())
        }
        return f
    }

    private fun item(server: MediaServer, node: CarNode): MediaItem {
        val art = node.art?.let { server.imageUrl(it, ImageKind.PRIMARY, 300) }?.let { CarArt.uri(context, it) }
        val extras = Bundle().apply {
            if (node.grid) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            if (node.grid) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        }
        return MediaItem.Builder().setMediaId(node.id).setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(node.title)
                .setSubtitle(node.subtitle)
                .setArtist(node.subtitle)
                .setArtworkUri(art)
                .setIsBrowsable(!node.playable)
                .setIsPlayable(node.playable)
                .setMediaType(if (node.playable) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(extras)
                .build(),
        ).build()
    }
}

/**
 * Artwork for the car: Android Auto only loads pictures from content addresses, so the server's
 * address is wrapped in one of this provider's, which fetches the picture once into the cache.
 */
class CarArt : ContentProvider() {
    companion object {
        fun uri(context: Context, url: String): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.carart").appendPath("art").appendQueryParameter("u", url).build()
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val url = uri.getQueryParameter("u") ?: return null
        val ctx = context ?: return null
        val base = (ctx.applicationContext as? AppContainer.Owner)?.container?.server?.serverUrl ?: return null
        // Only pictures from the signed-in server: this provider is open to other apps.
        if (!url.startsWith(base.trimEnd('/') + "/")) return null
        val dir = File(ctx.cacheDir, "car-art").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$name.img")
        if (!file.exists()) {
            val part = File(dir, "$name.part")
            runCatching {
                (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8_000; readTimeout = 15_000
                    inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
                }
                part.renameTo(file)
            }.onFailure { part.delete(); return null }
            // Keeps the folder small: the oldest pictures go past 300.
            dir.listFiles()?.filter { it.name.endsWith(".img") }?.sortedByDescending { it.lastModified() }?.drop(300)?.forEach { it.delete() }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
