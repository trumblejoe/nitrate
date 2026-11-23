package dev.nitrate.core.companion

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A chat message or reaction pinned to a point in a film or episode, for the writer's watch groups
 * on that title (Companion `notes`). Older "Mark this moment" bookmarks read as notes too.
 */
data class TimelineNote(
    val id: String,
    val userId: String,
    val userName: String,
    val itemId: String,
    val positionMs: Long,
    val kind: ChatKind,
    val text: String,
    /** The viewer's groups it was shared in. */
    val groupIds: List<String> = emptyList(),
    val atMs: Long = 0,
    /** An older moment (kept before notes; may have no words). */
    val moment: Boolean = false,
    /** Came live from this device's SyncPlay group's chat. */
    val live: Boolean = false,
)

/** One of the viewer's watch groups on a title. */
data class NoteGroup(val id: String, val title: String)

/** A title's notes the viewer may read, and their watch groups on it (none: nobody to write to). */
data class TitleNotes(val groups: List<NoteGroup>, val notes: List<TimelineNote>)

object Notes {
    /** What a note says: an older moment without words is "Marked this moment". */
    fun text(n: TimelineNote): String = n.text.ifBlank { if (n.moment) "Marked this moment" else "" }

    /** "1:02:03 · what a twist · Sam" (the name only for someone else's). */
    fun line(n: TimelineNote, me: String?): String =
        listOfNotNull(Moments.clock(n.positionMs), text(n).ifBlank { null }, n.userName.takeIf { n.userId != me }).joinToString(" · ")

    /** The notes at or before [upToMs], in the title's order: never anything further on (spoilers). */
    fun reached(notes: List<TimelineNote>, upToMs: Long): List<TimelineNote> =
        notes.filter { it.positionMs <= upToMs }.sortedWith(compareBy({ it.positionMs }, { it.atMs }))

    /**
     * How far a details page may list a title's notes: all of them once it's watched, else up to
     * where the viewer has got (its resume point; nothing when not started).
     */
    fun listableUpTo(item: MediaItem): Long = if (item.user.played) Long.MAX_VALUE else item.user.positionMs

    /** A live chat message read as a note at the sender's position (where they were, else [fallbackMs]). */
    fun fromLive(m: ChatMessage, itemId: String, fallbackMs: Long): TimelineNote =
        TimelineNote("live-${m.seq}", m.userId, m.userName, itemId, m.positionMs ?: fallbackMs, m.kind, m.text, atMs = m.sentAtMs, live = true)

    /** The same message seen twice: live from the group, then pinned (same sender and words, within [SAME_MS] of the title). */
    fun same(a: TimelineNote, b: TimelineNote): Boolean =
        a.userId == b.userId && a.text == b.text && a.kind == b.kind && abs(a.positionMs - b.positionMs) <= SAME_MS

    const val SAME_MS = 5_000L
}

/**
 * Which pinned notes pop up as one playback goes (no I/O; [TimelineChat] drives it). A note pops up
 * once, when continuous playback passes its point, never ahead of where the viewer is; a jump
 * (seek) skips the notes it jumps over, and the viewer's own never pop up. With group chat off,
 * nothing pops up; turning it on shows what follows, and [missed] lists what was passed before.
 */
class NoteClock(private val selfId: () -> String?) {
    private var notes: List<TimelineNote> = emptyList()
    private val shown = mutableSetOf<String>()
    private var lastMs: Long? = null

    /** The furthest point reached in this playback (its start, at first). */
    var reachedMs: Long = 0
        private set

    var on: Boolean = true
        private set

    /** Where group chat was turned on in this playback (earlier notes wait for "Show what I missed"); null: on from the start. */
    var onSinceMs: Long? = null
        private set

    /** Playback starts (or restarts) at [atMs]. */
    fun start(atMs: Long, on: Boolean) {
        lastMs = atMs
        reachedMs = atMs
        this.on = on
        onSinceMs = null
    }

    /** The title's notes as loaded (again): live ones already shown keep their place; pinned copies of them don't pop up again. */
    fun setNotes(loaded: List<TimelineNote>) {
        val live = notes.filter { it.live && loaded.none { l -> Notes.same(l, it) } }
        loaded.forEach { l -> if (notes.any { it.id in shown && Notes.same(it, l) }) shown += l.id }
        notes = (loaded + live).sortedWith(compareBy({ it.positionMs }, { it.atMs }))
    }

    /** One of the viewer's own, just sent: kept (for the history), never popped. */
    fun own(note: TimelineNote) {
        if (notes.any { Notes.same(it, note) && !it.live }) return
        notes = (notes.filterNot { it.live && Notes.same(it, note) } + note).sortedWith(compareBy({ it.positionMs }, { it.atMs }))
        shown += note.id
    }

    /** A message from the live group (SyncPlay): shown at once when on, unless already shown pinned. */
    fun live(note: TimelineNote): TimelineNote? {
        if (notes.any { Notes.same(it, note) && it.id in shown }) return null
        notes = (notes + note).sortedWith(compareBy({ it.positionMs }, { it.atMs }))
        if (!on || note.userId == selfId()) return null
        shown += note.id
        return note
    }

    /** Playback is at [positionMs]: the notes passed since the last time, to pop up (in order). */
    fun at(positionMs: Long): List<TimelineNote> {
        val from = lastMs ?: positionMs
        lastMs = positionMs
        reachedMs = maxOf(reachedMs, positionMs)
        // Backwards, or a jump forward: nothing pops up for what was jumped over.
        if (positionMs < from || positionMs - from > MAX_STEP_MS || !on) return emptyList()
        val me = selfId()
        return notes.filter { it.positionMs > from && it.positionMs <= positionMs && it.userId != me && it.id !in shown && !it.live }
            .onEach { shown += it.id }
    }

    /** On or off as the playback's starting choice (once the title's groups are known), not a switch mid-play. */
    fun initially(on: Boolean) {
        this.on = on
        onSinceMs = null
    }

    /** Group chat on or off at [positionMs]; turned on mid-play, what came before waits in [missed]. */
    fun turn(on: Boolean, positionMs: Long) {
        if (on && !this.on) onSinceMs = positionMs
        this.on = on
        lastMs = positionMs
        reachedMs = maxOf(reachedMs, positionMs)
    }

    /** The history so far: every note up to where the viewer has got (live ones too), from [onSinceMs] on unless [all]. */
    fun history(all: Boolean = false): List<TimelineNote> {
        val since = onSinceMs.takeUnless { all }
        return notes.filter { (it.live || it.positionMs <= reachedMs) && (since == null || it.positionMs >= since || it.live || it.userId == selfId()) }
    }

    /** Others' notes passed before group chat was turned on in this playback ("Show what I missed"). */
    fun missed(): List<TimelineNote> {
        val since = onSinceMs ?: return emptyList()
        val me = selfId()
        return notes.filter { !it.live && it.positionMs < since && it.userId != me }
    }

    companion object {
        /** Steps longer than this between two readings are jumps, not playing. */
        const val MAX_STEP_MS = 10_000L
    }
}

/**
 * Group chat for one playback: the title's pinned notes (loaded once as it starts, again only
 * every [REFRESH_MS] or when the chat is opened after [REOPEN_MS]), pop-ups as the viewer reaches
 * them, live messages from this device's SyncPlay group at once, and sending (pinned for the
 * viewer's watch groups on the title, and to the live group when there is one).
 */
class TimelineChat(
    private val server: MediaServer,
    private val scope: CoroutineScope,
    private val selfId: () -> String?,
    /** The SyncPlay group chat this device follows, if any (its messages pop up at once). */
    private val live: GroupChat? = null,
    private val nowMs: () -> Long,
) {
    private val clock = NoteClock(selfId)
    private var itemId: String? = null
    private var loadedAt = Long.MIN_VALUE / 2
    private var loadJob: Job? = null
    private var liveJob: Job? = null

    private val _groups = MutableStateFlow<List<NoteGroup>>(emptyList())
    /** The viewer's watch groups on this title (empty: notes can't be written here). */
    val groups: StateFlow<List<NoteGroup>> = _groups

    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on

    private val _history = MutableStateFlow<List<TimelineNote>>(emptyList())
    /** Notes up to where the viewer has got (see [NoteClock.history]). */
    val history: StateFlow<List<TimelineNote>> = _history

    private val _missed = MutableStateFlow<List<TimelineNote>>(emptyList())
    /** Others' notes passed before group chat was turned on in this playback. */
    val missed: StateFlow<List<TimelineNote>> = _missed

    private val _showAll = MutableStateFlow(false)
    /** "Show what I missed" pressed: the history holds what came before too. */
    val showAll: StateFlow<Boolean> = _showAll

    private val _popups = MutableSharedFlow<TimelineNote>(extraBufferCapacity = 32)
    /** Notes to pop up over the video, as they're reached (or arrive live). */
    val popups: SharedFlow<TimelineNote> = _popups

    /** Whether there's anyone to chat with here: a watch group on the title, or a live group. */
    val available: Boolean get() = _groups.value.isNotEmpty() || live?.groupId?.value != null

    private var onFor: (List<String>) -> Boolean = { true }
    private var turned = false

    /** What the on/off choice is remembered under: the title's watch groups, else the live group. */
    val keys: List<String> get() = _groups.value.map { it.id }.ifEmpty { listOfNotNull(live?.groupId?.value) }

    /**
     * A playback of [itemId] starts at [startMs]: one request for its notes. Group chat starts as
     * [onFor] says for the groups (asked again once they're known, unless switched meanwhile).
     */
    fun start(itemId: String, startMs: Long, onFor: (List<String>) -> Boolean) {
        this.itemId = itemId
        this.onFor = onFor
        turned = false
        val on = onFor(keys)
        clock.start(startMs, on)
        _on.value = on
        _showAll.value = false
        clock.setNotes(emptyList())
        _groups.value = emptyList()
        publish()
        load()
        liveJob?.cancel()
        liveJob = live?.let { chat ->
            scope.launch {
                chat.incoming.collect { m ->
                    val id = this@TimelineChat.itemId ?: return@collect
                    clock.live(Notes.fromLive(m, id, clock.reachedMs))?.let { _popups.tryEmit(it) }
                    publish()
                }
            }
        }
    }

    private fun load() {
        val id = itemId ?: return
        loadedAt = nowMs()
        loadJob?.cancel()
        loadJob = scope.launch {
            val page = try {
                server.titleNotes(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (itemId != id) return@launch
            _groups.value = page.groups
            if (!turned) {
                val on = onFor(keys)
                clock.initially(on)
                _on.value = on
            }
            clock.setNotes(page.notes)
            publish()
        }
    }

    /** Playback is at [positionMs] (call about once a second): pops up what's been reached; reloads rarely. */
    fun at(positionMs: Long) {
        clock.at(positionMs).forEach { _popups.tryEmit(it) }
        publish()
        if (nowMs() - loadedAt >= REFRESH_MS) load()
    }

    /** The chat was opened: notes written since they were loaded, if it's been a while. */
    fun opened() {
        if (nowMs() - loadedAt >= REOPEN_MS) load()
    }

    /** Group chat on or off (the app remembers it per watch group). */
    fun turn(on: Boolean, positionMs: Long) {
        turned = true
        clock.turn(on, positionMs)
        _on.value = on
        _showAll.value = false
        publish()
    }

    /** "Show what I missed": the history includes what came before turning it on. */
    fun showMissed() {
        _showAll.value = true
        publish()
    }

    private fun publish() {
        _history.value = clock.history(_showAll.value)
        _missed.value = if (_showAll.value) emptyList() else clock.missed()
    }

    /**
     * Sends a message at [positionMs]: pinned for the viewer's watch groups on the title, and to the
     * live group when this device is in one. Fails only when neither took it.
     */
    suspend fun send(text: String, kind: ChatKind, positionMs: Long): Result<TimelineNote> {
        val id = itemId ?: return Result.failure(IllegalStateException("Nothing is playing"))
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(ChatRejected("The message is empty.", tooFast = false))
        val liveSent = live?.takeIf { it.groupId.value != null }?.send(trimmed, kind, positionMs)
        val pinned = if (_groups.value.isEmpty()) null else runCatching { server.pinNote(id, positionMs, kind, trimmed) }
        val note = pinned?.getOrNull()
            ?: liveSent?.getOrNull()?.let { Notes.fromLive(it, id, positionMs) }
            ?: return Result.failure(pinned?.exceptionOrNull() ?: liveSent?.exceptionOrNull() ?: IllegalStateException("No watch group for this title"))
        clock.own(note)
        publish()
        return Result.success(note)
    }

    /** Stops listening to the live group (the player closed). */
    fun close() {
        liveJob?.cancel()
        loadJob?.cancel()
        itemId = null
    }

    companion object {
        /** How often notes are loaded again while playing (others may have written meanwhile). */
        const val REFRESH_MS = 10 * 60_000L

        /** Opening the chat loads them again only when they're older than this. */
        const val REOPEN_MS = 60_000L
    }
}
