package dev.nitrate.core.writing

import dev.nitrate.core.MediaServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import dev.nitrate.core.writing.story.RecordChange
import dev.nitrate.core.writing.story.Story
import dev.nitrate.core.writing.story.StoryRecord

/**
 * What a device keeps about a shared script between runs: the last text agreed with the server
 * (and its version), the text shown here (with changes not yet sent), and a change that was sent
 * but not yet confirmed, so it's never sent twice as two different changes.
 */
@Serializable
data class SharedScriptState(
    val version: Long,
    val serverText: String,
    val text: String,
    val sentId: String? = null,
    /** The sent change, in its wire form as JSON. */
    val sent: String? = null,
    /** The story data as the server last had it. */
    val records: Map<String, StoryRecord> = emptyMap(),
    /** Story changes made here that the server hasn't confirmed yet, oldest first. */
    val pendingRecords: List<PendingRecords> = emptyList(),
)

/** Story changes waiting to be confirmed, with the id they're sent under (never applied twice). */
@Serializable
data class PendingRecords(val editId: String, val changes: List<RecordChange>)

/** Someone else's cursor, as an offset into the text shown here. */
data class OtherCursor(val userId: String, val userName: String, val cursor: Int)

/** A change from someone else, as applied to the text shown here (for moving the selection). */
data class RemoteChange(val op: TextOperation, val userName: String)

/**
 * Writing in a shared script together. The server takes a change only when it was made on its
 * latest version; this keeps the change waiting for an answer ([sent]) and everything typed since
 * ([buffer]), rewrites both over everyone else's changes as they arrive ([TextOperation.transform]),
 * and sends again after a refusal. Typing never waits on the network, and changes made offline or
 * before a restart (see [SharedScriptState]) are merged in when the server is back.
 *
 * Not thread-safe: [scope] must run on one thread (the UI's), and [edit]/[moveCursor] be called on it.
 */
class SharedScriptSession(
    private val server: MediaServer,
    private val scope: CoroutineScope,
    val scriptId: String,
    start: SharedScriptState,
    /** Called after every change with what to keep for next time (the caller decides when to write it). */
    private val save: (SharedScriptState) -> Unit,
    /** Called when this device's unsent changes can't be merged (it fell too far behind): keep them as a copy. */
    private val onConflict: (String) -> Unit = {},
    private val newEditId: () -> String,
    /** This user, for story changes shown here before the server confirms them. */
    private val selfId: String = "",
    private val selfName: String = "",
    private val now: () -> Long = { 0L },
) {
    enum class Status { CONNECTING, LIVE, OFFLINE, UNAVAILABLE }

    private class Sent(val editId: String, val op: TextOperation)

    private var version = start.version
    private var serverText = start.serverText
    private var sent: Sent? = start.sentId?.let { id -> start.sent?.let { Sent(id, decode(it)) } }
    private var buffer: TextOperation? = null
    private var cursor = 0
    private var stamp: Long? = null
    private var serverStory = Story(start.records)
    private val pendingRecords = start.pendingRecords.toMutableList()
    /** Story changes sent and not yet seen back (resent only after a restart). */
    private val recordsSent = mutableSetOf<String>()
    private var presence: List<ScriptPresence> = emptyList()
    /** Recent server changes, to bring others' cursors up to date. */
    private val recent = ArrayDeque<Pair<Long, TextOperation>>()

    private val _text = MutableStateFlow(start.text)
    /** The script as shown here: the server's text with this device's unconfirmed changes. */
    val text: StateFlow<String> = _text

    private val _remote = MutableSharedFlow<RemoteChange>(extraBufferCapacity = 64)
    /** Others' changes as they're applied to [text]. */
    val remote: SharedFlow<RemoteChange> = _remote

    private val _others = MutableStateFlow<List<OtherCursor>>(emptyList())
    val others: StateFlow<List<OtherCursor>> = _others

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status

    private val _story = MutableStateFlow(Story())
    /** The story data as shown here: the server's, with this device's unconfirmed changes. */
    val story: StateFlow<Story> = _story

    private val _pending = MutableStateFlow(false)
    /** True while some of this device's changes haven't reached the server. */
    val pending: StateFlow<Boolean> = _pending

    private val kick = Channel<Unit>(Channel.CONFLATED)
    private var presenceJob: Job? = null
    private val jobs = mutableListOf<Job>()

    init {
        val confirmedOrSent = sent?.op?.let { runCatching { it.apply(serverText) }.getOrNull() } ?: run { sent = null; serverText }
        TextOperation.diff(confirmedOrSent, start.text).takeIf { !it.isNoop }?.let { buffer = it }
        refreshStory()
        updatePending()
    }

    /** Starts talking to the server. */
    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch { pollLoop() }
        jobs += scope.launch { sendLoop() }
        if (sent != null || buffer != null || pendingRecords.isNotEmpty()) kick.trySend(Unit)
    }

    fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        presenceJob?.cancel()
    }

    /** The editor's text changed to [newText] with the cursor at [newCursor]. */
    fun edit(newText: String, newCursor: Int) {
        val op = TextOperation.diff(_text.value, newText)
        cursor = newCursor
        if (op.isNoop) {
            moveCursor(newCursor)
            return
        }
        buffer = buffer?.compose(op) ?: op
        _text.value = newText
        updatePending()
        refreshOthers()
        persist()
        kick.trySend(Unit)
    }

    /** Changes story data (lists, entries, links, comments, scene details): shown at once, sent soon. */
    fun changeStory(changes: List<RecordChange>) {
        if (changes.isEmpty()) return
        pendingRecords += PendingRecords(newEditId(), changes)
        refreshStory()
        updatePending()
        persist()
        kick.trySend(Unit)
    }

    private fun refreshStory() {
        var s = serverStory
        for (p in pendingRecords) for (c in p.changes) s = s.apply(c, selfId, selfName, now())
        _story.value = s
    }

    /** The cursor moved without typing: tells the others after a moment. */
    fun moveCursor(newCursor: Int) {
        cursor = newCursor
        presenceJob?.cancel()
        presenceJob = scope.launch {
            delay(PRESENCE_DELAY_MS)
            // Only when everything here is on the server, so the offset means the same text there.
            if (sent == null && buffer == null) runCatching { server.sendScriptPresence(scriptId, cursor, version) }
        }
    }

    // ---- Sending ----

    private suspend fun sendLoop() {
        var failures = 0
        for (k in kick) {
            delay(SEND_DELAY_MS) // a moment's typing goes as one change
            while (scope.isActive) {
                val p = sent ?: buffer?.let { b -> Sent(newEditId(), b).also { sent = it; buffer = null; persist() } } ?: break
                val base = version
                val result = try {
                    server.sendScriptEdit(scriptId, p.editId, base, p.op, cursor.takeIf { buffer == null })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ScriptUnavailable) {
                    unavailable()
                    return
                } catch (_: Exception) {
                    // Offline or the server is away: try again later; nothing is lost.
                    _status.value = Status.OFFLINE
                    delay(backoffMs(++failures))
                    continue
                }
                failures = 0
                if (sent !== p) continue // confirmed or rewritten meanwhile (by the poll)
                if (result.accepted) {
                    if (result.version == version + 1) confirm(p, result.version)
                } else {
                    // Others got there first: fetch their changes (which rewrites ours) and send again.
                    catchUp()
                }
            }
            // Story changes never wait on the version; the poll brings them back in order.
            for (p in pendingRecords.toList()) {
                if (p.editId in recordsSent) continue
                try {
                    server.sendScriptRecords(scriptId, p.editId, version, p.changes)
                    recordsSent += p.editId
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (_: ScriptUnavailable) {
                    unavailable()
                    return
                } catch (_: IllegalArgumentException) {
                    // The server refused it (too big...): drop it rather than retry forever.
                    pendingRecords.removeAll { it.editId == p.editId }
                    refreshStory()
                    persist()
                } catch (_: Exception) {
                    _status.value = Status.OFFLINE
                    delay(backoffMs(++failures))
                    kick.trySend(Unit)
                    break
                }
            }
            updatePending()
        }
    }

    private fun confirm(p: Sent, newVersion: Long) {
        serverText = p.op.apply(serverText)
        version = newVersion
        remember(newVersion, p.op)
        sent = null
        updatePending()
        persist()
    }

    // ---- Receiving ----

    private suspend fun pollLoop() {
        var failures = 0
        var first = true
        while (scope.isActive) {
            try {
                val page = server.scriptEdits(scriptId, version, stamp, if (first) 0 else WAIT_MS)
                first = false
                failures = 0
                receive(page)
                if (_status.value != Status.UNAVAILABLE) _status.value = Status.LIVE
            } catch (e: CancellationException) {
                throw e
            } catch (_: ScriptUnavailable) {
                unavailable()
                return
            } catch (_: ScriptTooFarBehind) {
                reload()
            } catch (_: Exception) {
                _status.value = Status.OFFLINE
                delay(backoffMs(++failures))
            }
        }
    }

    private suspend fun catchUp() {
        try {
            receive(server.scriptEdits(scriptId, version, null, 0))
        } catch (_: ScriptTooFarBehind) {
            reload()
        }
    }

    private fun receive(page: ScriptEditsPage) {
        for (e in page.edits.sortedBy { it.version }) {
            if (e.version <= version) continue
            if (e.version != version + 1) break // a gap: the next poll asks again from here
            apply(e)
        }
        stamp = page.stamp
        presence = page.presence
        refreshOthers()
    }

    private fun apply(e: ScriptEdit) {
        if (e.records.isNotEmpty()) {
            for (c in e.records) serverStory = serverStory.apply(c, e.userId, e.userName, e.atMs)
            pendingRecords.removeAll { it.editId == e.editId }
            recordsSent -= e.editId
            refreshStory()
        }
        val edited = e.op
        if (edited == null) {
            version = e.version
            updatePending()
            persist()
            return
        }
        val mine = sent
        if (mine != null && mine.editId == e.editId) {
            confirm(mine, e.version)
            return
        }
        var op: TextOperation = edited
        if (mine != null) {
            val (m2, op2) = TextOperation.transform(mine.op, op)
            sent = Sent(mine.editId, m2)
            op = op2
        }
        buffer?.let { b ->
            val (b2, op3) = TextOperation.transform(b, op)
            buffer = b2
            op = op3
        }
        serverText = edited.apply(serverText)
        version = e.version
        remember(e.version, edited)
        cursor = op.transformIndex(cursor)
        _text.value = op.apply(_text.value)
        _remote.tryEmit(RemoteChange(op, e.userName))
        persist()
    }

    /** Fell too far behind to merge: start again from the server's text, keeping unsent changes as a copy. */
    private suspend fun reload() {
        val doc = server.sharedScript(scriptId)
        val old = _text.value
        serverStory = Story(doc.records.associateBy { it.id })
        recordsSent.clear()
        refreshStory()
        if (sent != null || buffer != null) onConflict(old)
        version = doc.version
        serverText = doc.text
        sent = null
        buffer = null
        recent.clear()
        stamp = null
        val op = TextOperation.diff(old, doc.text)
        cursor = op.transformIndex(cursor)
        _text.value = doc.text
        if (!op.isNoop) _remote.tryEmit(RemoteChange(op, "the server"))
        updatePending()
        persist()
    }

    private fun unavailable() {
        _status.value = Status.UNAVAILABLE
        _others.value = emptyList()
    }

    // ---- Others' cursors ----

    private fun remember(v: Long, op: TextOperation) {
        recent.addLast(v to op)
        while (recent.size > KEPT_OPS) recent.removeFirst()
    }

    private fun refreshOthers() {
        _others.value = presence.mapNotNull { p ->
            var at = p.cursor
            if (p.version < version) {
                val needed = recent.filter { it.first > p.version }
                // Too old to place: leave them out until they move again.
                if (needed.size.toLong() != version - p.version) return@mapNotNull null
                needed.forEach { at = it.second.transformIndex(at) }
            } else if (p.version > version) return@mapNotNull null
            sent?.let { at = it.op.transformIndex(at) }
            buffer?.let { at = it.transformIndex(at) }
            OtherCursor(p.userId, p.userName, at.coerceIn(0, _text.value.length))
        }
    }

    // ---- Keeping ----

    private fun persist() = save(
        SharedScriptState(version, serverText, _text.value, sent?.editId, sent?.op?.let(::encode), serverStory.records, pendingRecords.toList()),
    )

    private fun updatePending() {
        _pending.value = sent != null || buffer != null || pendingRecords.isNotEmpty()
    }

    companion object {
        const val WAIT_MS = 25_000
        const val SEND_DELAY_MS = 300L
        const val PRESENCE_DELAY_MS = 1_500L
        const val KEPT_OPS = 200

        fun backoffMs(failures: Int): Long = (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

        fun encode(op: TextOperation): String = Json.encodeToString(
            JsonArray.serializer(),
            JsonArray(TextOperation.toWire(op).map { if (it is String) JsonPrimitive(it) else JsonPrimitive(it as Int) }),
        )

        fun decode(json: String): TextOperation = TextOperation.fromWire(
            Json.parseToJsonElement(json).let { it as JsonArray }.map { e ->
                val p = e as JsonPrimitive
                if (p.isString) p.content else p.content.toInt()
            },
        )

        /** Where a script just shared or opened from the server starts. */
        fun fresh(doc: SharedScriptDoc) = SharedScriptState(doc.version, doc.text, doc.text, records = doc.records.associateBy { it.id })
    }
}
