package dev.nitrate.core.music

import dev.nitrate.core.model.MediaItem
import kotlin.random.Random

/**
 * The listening player's queue where the platform's player has none of its own (iOS): the songs,
 * the order they play in (shuffled or not), which one is on, and what comes next under [Repeat].
 * Positions are in playing order, as [ListenState.queue] shows them. Android's ExoPlayer keeps all
 * this itself.
 */
class ListenQueue(private val random: Random = Random.Default) {
    private val items = ArrayList<MediaItem>()
    /** Playing order: indices into [items]. */
    private var order: List<Int> = emptyList()

    /** The position (in playing order) of the song on; -1 when empty. */
    var position: Int = -1
        private set

    var shuffle: Boolean = false
        private set

    val size: Int get() = order.size
    val current: MediaItem? get() = order.getOrNull(position)?.let(items::get)

    /** The songs in playing order. */
    fun playing(): List<MediaItem> = order.map(items::get)

    fun at(position: Int): MediaItem? = order.getOrNull(position)?.let(items::get)

    /** Replaces the queue with [songs], [start] (an index into [songs]) on first; shuffled, it's first and the rest follow at random. */
    fun set(songs: List<MediaItem>, start: Int, shuffle: Boolean) {
        items.clear()
        items += songs
        this.shuffle = shuffle
        val first = start.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        order = if (shuffle) listOf(first) + (songs.indices - first).shuffled(random) else songs.indices.toList()
        position = if (songs.isEmpty()) -1 else order.indexOf(first)
    }

    /** Shuffling on keeps the song on first and mixes the rest; off goes back to the songs' own order. */
    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        shuffle = on
        val now = order.getOrNull(position)
        order = if (on && now != null) listOf(now) + (items.indices - now).shuffled(random) else items.indices.toList()
        position = now?.let { order.indexOf(it) } ?: -1
    }

    /** Where playing goes after the song on ends (or Next is pressed with [manual]); null: the end. */
    fun nextPosition(repeat: Repeat, manual: Boolean = false): Int? = when {
        order.isEmpty() -> null
        repeat == Repeat.ONE && !manual -> position
        position + 1 < order.size -> position + 1
        repeat != Repeat.OFF -> 0
        else -> null
    }

    /** The one before, if any (Previous within [ListenRules.RESTART_AFTER_MS] of a song's start). */
    fun previousPosition(repeat: Repeat): Int? = when {
        position > 0 -> position - 1
        repeat == Repeat.ALL && order.isNotEmpty() -> order.lastIndex
        else -> null
    }

    /** The same title with more details (a book's chapters): order and shuffle untouched. */
    fun replace(item: MediaItem) {
        for (i in items.indices) if (items[i].id == item.id) items[i] = item
    }

    fun moveTo(position: Int) {
        if (position in order.indices) this.position = position
    }

    /** [songs] right after the one on. */
    fun insertNext(songs: List<MediaItem>) {
        val added = songs.indices.map { it + items.size }
        items += songs
        val at = (position + 1).coerceIn(0, order.size)
        order = order.take(at) + added + order.drop(at)
        if (position < 0 && order.isNotEmpty()) position = 0
    }

    /** [songs] at the end. */
    fun append(songs: List<MediaItem>) {
        val added = songs.indices.map { it + items.size }
        items += songs
        order = order + added
        if (position < 0 && order.isNotEmpty()) position = 0
    }

    /** Takes out the song at [at]; removing the one on leaves the next one on (or the one before at the end). */
    fun remove(at: Int) {
        if (at !in order.indices) return
        order = order.filterIndexed { i, _ -> i != at }
        position = when {
            order.isEmpty() -> -1
            at < position -> position - 1
            at == position -> position.coerceAtMost(order.lastIndex)
            else -> position
        }
    }

    /** Moves the song at [from] to [to], the one on staying on. */
    fun move(from: Int, to: Int) {
        if (from !in order.indices || to !in order.indices || from == to) return
        val on = order.getOrNull(position)
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        order = list
        position = on?.let { order.indexOf(it) } ?: position
    }

    fun clear() {
        items.clear()
        order = emptyList()
        position = -1
        shuffle = false
    }
}
