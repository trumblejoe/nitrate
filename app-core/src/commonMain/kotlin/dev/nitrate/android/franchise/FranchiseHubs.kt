package dev.nitrate.android.franchise

import dev.nitrate.core.MediaServer
import dev.nitrate.core.effects.PageTheme
import dev.nitrate.core.franchise.FranchiseEntry
import dev.nitrate.core.franchise.FranchiseProgress
import dev.nitrate.core.franchise.Franchises
import dev.nitrate.core.franchise.WatchOrder
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** A franchise hub, ready to show: the playlist, everything in the chosen order and where the user is. */
data class FranchiseHub(
    val playlist: MediaItem,
    val order: WatchOrder,
    val entries: List<FranchiseEntry>,
    val progress: FranchiseProgress,
    val films: Int,
    val shows: Int,
    /** The mood suggested from its titles' tags and genres, used when the user hasn't chosen a theme. */
    val mood: PageTheme,
    /** Whose theme music plays: the playlist's own, else its first titles' with one. */
    val themeMusicFrom: String?,
    /** A picture for the whole franchise: the playlist's backdrop, else its first title's. */
    val backdrop: String?,
    val logo: String?,
    /** Shown from what this device kept last time; the fresh one follows. */
    val fromCache: Boolean = false,
)

/** Builds franchise hubs from the user's video playlists through [PlaylistOrders]. */
object FranchiseHubs {
    /** The user's video playlists, as possible hubs. */
    suspend fun list(server: MediaServer): List<MediaItem> = dev.nitrate.core.diagnostics.withQueryOrigin("Franchises") { Franchises.candidates(server.playlists()) }

    /**
     * The hub: at once from what was kept last time (when there is some), then fresh. A channel flow, since
     * [withQueryOrigin][dev.nitrate.core.diagnostics.withQueryOrigin] changes the coroutine context, which a plain
     * `flow` refuses to emit from ("Flow invariant is violated").
     */
    fun hub(server: MediaServer, orders: PlaylistOrders, playlistId: String, chosen: WatchOrder?): Flow<FranchiseHub> = channelFlow {
        dev.nitrate.core.diagnostics.withQueryOrigin("Franchise hub") {
            val order = chosen ?: WatchOrder.PLAYLIST
            val kept = orders.cached(playlistId, order)
            val keptPlaylist = orders.keptPlaylist(playlistId)
            if (kept != null && kept.isNotEmpty() && keptPlaylist != null) send(build(server, keptPlaylist, order, kept, null, fromCache = true))
            val playlist = server.getItem(playlistId).also { orders.keepPlaylist(it) }
            val entries = orders.entries(playlistId, order, fresherThanMs = 0)
            val titleIds = entries.map { it.item.seriesId ?: it.item.id }.distinct()
            val music = runCatching {
                if (server.themeSongs(playlist.id).isNotEmpty()) playlist.id else titleIds.take(4).firstOrNull { server.themeSongs(it).isNotEmpty() }
            }.getOrNull()
            send(build(server, playlist, order, entries, music, fromCache = false))
        }
    }

    private fun build(server: MediaServer, playlist: MediaItem, order: WatchOrder, entries: List<FranchiseEntry>, music: String?, fromCache: Boolean): FranchiseHub {
        val flat = entries.map { it.item }
        return FranchiseHub(
            playlist = playlist,
            order = order,
            entries = entries,
            progress = Franchises.progress(entries),
            films = flat.count { it.kind == ItemKind.MOVIE },
            shows = flat.mapNotNull { it.seriesId }.distinct().size,
            mood = Franchises.suggestedMood(flat),
            themeMusicFrom = music,
            backdrop = server.backdropUrl(playlist, 1920) ?: flat.firstNotNullOfOrNull { server.backdropUrl(it, 1920) },
            logo = server.logoUrl(playlist),
            fromCache = fromCache,
        )
    }
}
