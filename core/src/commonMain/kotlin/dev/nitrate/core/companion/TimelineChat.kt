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
 * A chat message or reaction pinned to a point in a film or episode (Companion `notes`), readable
 * by its audience: who was in the conversation when it was written (the writer's watch groups on
 * that title then, or a live session's participants). Older "Mark this moment" bookmarks read as
 * notes too.
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
    /** Came live from this device's Group Watch Session's chat. */
    val live: Boolean = false,
    /** Brought into this live session by someone in it ("Bring our earlier notes"): for this session only. */
    val brought: Boolean = false,
    /** Who brought it in (their user id), for a brought note. */
    val broughtBy: String? = null,
    /** The chat history it was written in (Companion `chats`; older notes: the one they're read as). */
    val chatId: String? = null,
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

    /**
     * A live chat message read as a note at the sender's position (where they were, else
     * [fallbackMs]); one kept as a note (`livenotes`) carries that note's id, so loading the notes
     * again finds the same one.
     */
    fun fromLive(m: ChatMessage, itemId: String, fallbackMs: Long): TimelineNote =
        TimelineNote(m.noteId ?: "live-${m.seq}", m.userId, m.userName, m.itemId ?: itemId, m.positionMs ?: fallbackMs, m.kind, m.text, atMs = m.sentAtMs, live = true)

    /**
     * The same message seen twice: live from the group, then loaded with the notes (the same note,
     * or from older servers the same sender and words within [SAME_MS] of the title).
     */
    fun same(a: TimelineNote, b: TimelineNote): Boolean =
        a.id == b.id || a.userId == b.userId && a.text == b.text && a.kind == b.kind && abs(a.positionMs - b.positionMs) <= SAME_MS

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

    /** A message from the live session (a Group Watch Session): shown at once when on, unless already shown pinned. */
    fun live(note: TimelineNote): TimelineNote? {
        if (notes.any { Notes.same(it, note) && it.id in shown }) return null
        // The live copy of a note already loaded (not reached yet) takes its place: one entry each.
        notes = (notes.filterNot { it.id == note.id } + note).sortedWith(compareBy({ it.positionMs }, { it.atMs }))
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
 * Group chat for one playback. With chat histories (Companion `chats`) a playback starts on a
 * **New chat**: nothing earlier loads or pops up unasked (one request lists the title's chats and
 * the viewer's groups on it, nothing of what was said), the chat is made on the server only with
 * the first message written here, and [play] picks an earlier chat to play along (its notes, and
 * those of the chats it builds on, pop up as playback passes them; a chat written meanwhile links to
 * it, nothing is copied). In a Group Watch Session everyone shares one chat, and a chat picked by
 * anyone there plays for everyone. Without `chats` it is as before: the title's pinned notes
 * (loaded once as it starts, again only every [REFRESH_MS] or when the chat is opened after
 * [REOPEN_MS]) pop up as they're reached, and notes someone in the session brought in (older apps'
 * "Bring our earlier notes") too. Live messages from this device's Group Watch Session show at once.
 */
class TimelineChat(
    private val server: MediaServer,
    private val scope: CoroutineScope,
    private val selfId: () -> String?,
    /** The live chat of the Group Watch Session this device is in, if any (its messages pop up at once). */
    private val live: GroupChat? = null,
    /** Whether the server writes live messages as notes (`livenotes`); older ones get them twice, live and pinned. */
    private val liveNotes: () -> Boolean = { true },
    /** The title turned out to be shared with a watch group (once per playback): the app reloads its groups. */
    private val onShared: () -> Unit = {},
    /** Whether the server keeps chat histories (`chats`). */
    private val chatsOn: () -> Boolean = { false },
    private val nowMs: () -> Long,
) {
    private val clock = NoteClock(selfId)
    private var itemId: String? = null
    private var loadedAt = Long.MIN_VALUE / 2
    private var loadJob: Job? = null
    private var liveJob: Job? = null
    private var broughtJob: Job? = null
    private var pageNotes: List<TimelineNote> = emptyList()
    private var brought: List<TimelineNote> = emptyList()
    private var sharedTold = false
    private var chatsMode = false
    private var writing: String? = null
    private var along: List<TimelineNote> = emptyList()
    private var here: List<TimelineNote> = emptyList()

    /** The live session this device is in, if any. */
    private val sessionId: String? get() = live?.groupId?.value

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

    private val _broughtBy = MutableStateFlow<String?>(null)
    /** Who brought earlier notes into this watch party (user id; null: nobody), for the player's panel. */
    val broughtBy: StateFlow<String?> = _broughtBy

    private val _allChats = MutableStateFlow<List<NoteChat>>(emptyList())
    /** Every chat on the title the viewer may see (for labels: what a chat builds on). */
    val allChats: StateFlow<List<NoteChat>> = _allChats

    private val _chats = MutableStateFlow<List<NoteChat>>(emptyList())
    /** The earlier chats to offer under "Chats" (for the groups this counts for), newest first. */
    val chats: StateFlow<List<NoteChat>> = _chats

    private val _hasChats = MutableStateFlow(false)
    /** The server keeps chat histories: "Chats" can be offered. */
    val hasChats: StateFlow<Boolean> = _hasChats

    private val _playing = MutableStateFlow<String?>(null)
    /** The earlier chat playing along (null: New chat). In a Group Watch Session, the one picked for everyone. */
    val playing: StateFlow<String?> = _playing

    private val _popups = MutableSharedFlow<TimelineNote>(extraBufferCapacity = 32)
    /** Notes to pop up over the video, as they're reached (or arrive live). */
    val popups: SharedFlow<TimelineNote> = _popups

    /** Whether there's anyone to chat with here: a watch group on the title, or a live group. */
    val available: Boolean get() = _groups.value.isNotEmpty() || live?.groupId?.value != null

    private var onFor: (List<String>) -> Boolean = { true }
    private var turned = false

    /** What the on/off choice is remembered under: the title's watch groups (those in [focus]), else the live group. */
    val keys: List<String> get() = _groups.value.map { it.id }.filter { f -> focus?.let { f in it } ?: true }.ifEmpty { listOfNotNull(live?.groupId?.value) }

    private var focus: List<String>? = null

    /**
     * The watch groups this playback counts for (the player's chip, "Counts for"; empty: just the
     * viewer; null: all their groups on the title, as in a watch party). Only those groups' notes pop
     * up and list (the viewer's own, and a party's or brought ones, always do), and new notes go to
     * them only (servers with `notegroups`); with none, nothing is written outside a party.
     */
    fun focus(groupIds: List<String>?) {
        if (focus == groupIds) return
        focus = groupIds
        offer()
        showNotes()
    }

    private fun offer() {
        _chats.value = NoteChats.offered(_allChats.value, focus, writing)
    }

    private fun inFocus(n: TimelineNote): Boolean {
        val f = focus ?: return true
        return n.groupIds.isEmpty() || n.userId == selfId() || n.groupIds.any { it in f }
    }

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
        pageNotes = emptyList()
        brought = emptyList()
        sharedTold = false
        chatsMode = chatsOn()
        _hasChats.value = false
        writing = null
        along = emptyList()
        here = emptyList()
        _playing.value = null
        _allChats.value = emptyList()
        _chats.value = emptyList()
        clock.setNotes(emptyList())
        _groups.value = emptyList()
        publish()
        load()
        liveJob?.cancel()
        broughtJob?.cancel()
        liveJob = live?.let { chat ->
            scope.launch {
                chat.incoming.collect { m ->
                    val id = this@TimelineChat.itemId ?: return@collect
                    clock.live(Notes.fromLive(m, id, clock.reachedMs))?.let { _popups.tryEmit(it) }
                    publish()
                }
            }
        }
        // Someone brought (or stopped bringing) earlier notes into the session, or picked a chat to play along: ask again.
        broughtJob = live?.let { chat ->
            scope.launch {
                var seen: Long? = null
                chat.brought.collect { n ->
                    if (seen != null && n != seen) if (chatsMode) loadSessionChat() else loadBrought()
                    seen = n
                }
            }
        }
    }

    private fun showNotes() {
        if (chatsMode) {
            // Only what was asked for: the chat playing along, and what's written here.
            clock.setNotes(NoteChats.showing(along, here))
            publish()
            return
        }
        _broughtBy.value = brought.firstNotNullOfOrNull { it.broughtBy }
        clock.setNotes(pageNotes.filter(::inFocus) + brought.filter { b -> pageNotes.none { it.id == b.id } })
        publish()
    }

    /** This device's Group Watch Session's chat on the title: what plays along for everyone, and the chat written there. */
    private fun loadSessionChat() {
        val id = itemId ?: return
        val session = sessionId ?: return
        scope.launch {
            val shared = try {
                server.sessionChat(session, id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (itemId != id) return@launch
            applySession(shared)
        }
    }

    private fun applySession(shared: SessionChat) {
        writing = shared.chatId ?: writing
        _playing.value = shared.along
        _broughtBy.value = shared.by
        along = shared.notes
        offer()
        showNotes()
    }

    /**
     * Plays an earlier chat along ([chatId]; null: New chat, nothing earlier): its notes, and those
     * of the chats it builds on, pop up as continuous playback passes them and list up to the
     * furthest point reached. In a Group Watch Session it plays for everyone there. A chat written
     * here from now on links to it. Fails without chat histories, or outside the session.
     */
    suspend fun play(chatId: String?): Result<Unit> {
        val id = itemId ?: return Result.failure(IllegalStateException("Nothing is playing"))
        if (!chatsMode) return Result.failure(UnsupportedOperationException("This server keeps no chat histories"))
        val session = sessionId
        if (session != null) {
            return runCatching { server.playAlong(session, id, chatId) }.onSuccess { loadSessionChat() }
        }
        _playing.value = chatId
        if (chatId == null) {
            along = emptyList()
            showNotes()
            return Result.success(Unit)
        }
        return runCatching { server.chatNotes(id, chatId) }.onSuccess { notes ->
            if (itemId == id && _playing.value == chatId) {
                along = notes
                showNotes()
            }
        }.map { }
    }

    /** The notes brought into this device's live session on this title (none outside one). */
    private suspend fun fetchBrought(id: String): List<TimelineNote> {
        val session = sessionId ?: return emptyList()
        return try {
            server.broughtNotes(session, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadBrought() {
        val id = itemId ?: return
        scope.launch {
            val notes = fetchBrought(id)
            if (itemId != id) return@launch
            brought = notes
            showNotes()
        }
    }

    private fun load() {
        val id = itemId ?: return
        loadedAt = nowMs()
        loadJob?.cancel()
        if (chatsMode) {
            loadJob = scope.launch { loadChats(id) }
            return
        }
        loadJob = scope.launch {
            val page = try {
                server.titleNotes(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            val broughtNow = fetchBrought(id)
            if (itemId != id) return@launch
            _groups.value = page.groups
            if (page.groups.isNotEmpty() && !sharedTold) {
                sharedTold = true
                onShared()
            }
            if (!turned) {
                val on = onFor(keys)
                clock.initially(on)
                _on.value = on
            }
            pageNotes = page.notes
            brought = broughtNow
            showNotes()
        }
    }

    /** The title's chats (one request, nothing of what was said) and, in a session, its shared chat. */
    private suspend fun loadChats(id: String) {
        val page = try {
            server.titleChats(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val session = sessionId
        val shared = if (session == null) null else try {
            server.sessionChat(session, id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (itemId != id) return
        if (page == null) {
            // A server that turned chat histories off (or couldn't answer): as before.
            if (!_hasChats.value && !chatsOn()) {
                chatsMode = false
                load()
            }
            return
        }
        _hasChats.value = true
        _groups.value = page.groups
        _allChats.value = page.chats
        if (page.groups.isNotEmpty() && !sharedTold) {
            sharedTold = true
            onShared()
        }
        if (!turned) {
            val on = onFor(keys)
            clock.initially(on)
            _on.value = on
        }
        if (shared != null) applySession(shared) else offer()
        showNotes()
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
     * Sends a message at [positionMs], written once as a note: in this device's live session with
     * the session (its participants get it at once), else for the viewer's watch groups on the
     * title. Older servers without `livenotes` get it twice, as before: to the live group and pinned.
     */
    suspend fun send(text: String, kind: ChatKind, positionMs: Long): Result<TimelineNote> {
        val id = itemId ?: return Result.failure(IllegalStateException("Nothing is playing"))
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(ChatRejected("The message is empty.", tooFast = false))
        val session = sessionId
        if (chatsMode) return sendToChat(id, trimmed, kind, positionMs, session)
        if (session != null && liveNotes()) {
            return runCatching { server.pinNote(id, positionMs, kind, trimmed, sessionId = session) }.onSuccess {
                clock.own(it)
                publish()
            }
        }
        val to = focus
        if (session == null && to != null && _groups.value.none { it.id in to }) {
            return Result.failure(ChatRejected("You're watching just for you, so nothing is sent. Count it for a watch group to write a note.", tooFast = false))
        }
        val liveSent = live?.takeIf { session != null }?.send(trimmed, kind, positionMs)
        val pinned = when {
            _groups.value.isEmpty() -> null
            to != null -> runCatching { server.pinNote(id, positionMs, kind, trimmed, null, to) }
            else -> runCatching { server.pinNote(id, positionMs, kind, trimmed) }
        }
        val note = pinned?.getOrNull()
            ?: liveSent?.getOrNull()?.let { Notes.fromLive(it, id, positionMs) }
            ?: return Result.failure(pinned?.exceptionOrNull() ?: liveSent?.exceptionOrNull() ?: IllegalStateException("No watch group for this title"))
        clock.own(note)
        publish()
        return Result.success(note)
    }

    /** Written into this playback's chat (made on the server with its first message), linking to the chat playing along. */
    private suspend fun sendToChat(id: String, text: String, kind: ChatKind, positionMs: Long, session: String?): Result<TimelineNote> {
        val to = focus
        if (session == null && (_groups.value.isEmpty() || to != null && _groups.value.none { it.id in to })) {
            return Result.failure(
                ChatRejected(
                    if (_groups.value.isEmpty()) "No watch group for this title." else "You're watching just for you, so nothing is sent. Count it for a watch group to write a note.",
                    tooFast = false,
                ),
            )
        }
        return runCatching {
            server.pinChatNote(id, positionMs, kind, text, session, to.takeIf { session == null }, ChatWrite(writing, _playing.value.takeIf { session == null }))
        }.onSuccess { note ->
            note.chatId?.let { if (writing != it) { writing = it; offer() } }
            here = here + note
            clock.own(note)
            showNotes()
        }
    }

    /** Stops listening to the live group (the player closed). */
    fun close() {
        liveJob?.cancel()
        broughtJob?.cancel()
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
