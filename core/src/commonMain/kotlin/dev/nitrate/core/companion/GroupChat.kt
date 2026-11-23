package dev.nitrate.core.companion

import dev.nitrate.core.MediaServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The chat of one watch-together group at a time, over the Nitrate Companion plugin: keeps a long
 * poll open while following a group, holds its recent messages, and announces new ones from other
 * people (for the pop-ups over the video). Shared by the player and the phone's chat screen.
 */
class GroupChat(
    private val server: MediaServer,
    private val scope: CoroutineScope,
    /** This user, so their own messages aren't announced back to them. */
    private val selfId: () -> String?,
) {
    private val _groupId = MutableStateFlow<String?>(null)
    /** The group whose chat is followed, if any. */
    val groupId: StateFlow<String?> = _groupId

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _incoming = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 32)
    /** New messages from other people, as they arrive (not the backlog on joining). */
    val incoming: SharedFlow<ChatMessage> = _incoming

    private val _brought = MutableStateFlow(0L)
    /** Counts up when someone brings earlier notes into the session (the server's [ChatPage.brought]). */
    val brought: StateFlow<Long> = _brought

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status

    enum class Status { OFF, CONNECTING, LIVE, RECONNECTING, NOT_ALLOWED }

    private var job: Job? = null

    /** Follows [groupId]'s chat (null: none). Following the same group again changes nothing. */
    fun follow(groupId: String?) {
        if (groupId == _groupId.value && (groupId == null || job?.isActive == true)) return
        job?.cancel()
        _groupId.value = groupId
        _messages.value = emptyList()
        _brought.value = 0
        if (groupId == null) {
            _status.value = Status.OFF
            return
        }
        _status.value = Status.CONNECTING
        job = scope.launch { poll(groupId) }
    }

    private suspend fun poll(groupId: String) {
        var after = -1L
        var failures = 0
        while (scope.isActive && _groupId.value == groupId) {
            try {
                val page = server.chatMessages(groupId, after, if (after < 0) 0 else WAIT_MS)
                val joining = after < 0
                after = page.latest
                _brought.value = page.brought
                failures = 0
                _status.value = Status.LIVE
                if (page.messages.isNotEmpty()) add(page.messages, announce = !joining)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ChatForbidden) {
                // Not (or no longer) in the group: nothing to wait for.
                _status.value = Status.NOT_ALLOWED
                return
            } catch (_: Exception) {
                failures++
                _status.value = Status.RECONNECTING
                delay(backoffMs(failures))
            }
        }
    }

    private fun add(new: List<ChatMessage>, announce: Boolean) {
        val known = _messages.value.lastOrNull()?.seq ?: 0
        val fresh = new.filter { it.seq > known }
        if (fresh.isEmpty()) return
        _messages.value = (_messages.value + fresh).takeLast(KEPT)
        val me = selfId()
        if (announce) fresh.filter { it.userId != me }.forEach { _incoming.tryEmit(it) }
    }

    /** Sends a message to the followed group; the failure says why (too fast, too long...). */
    suspend fun send(text: String, kind: ChatKind = ChatKind.TEXT, positionMs: Long? = null): Result<ChatMessage> {
        val groupId = _groupId.value ?: return Result.failure(IllegalStateException("Not in a watch party"))
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(ChatRejected("The message is empty.", tooFast = false))
        return runCatching { server.sendChat(groupId, kind, trimmed, positionMs) }
            // Shown straight away; the poll brings it again and it's skipped then.
            .onSuccess { sent -> if (_groupId.value == groupId) add(listOf(sent), announce = false) }
    }

    companion object {
        /** How long each request waits on the server for a new message. */
        const val WAIT_MS = 25_000
        const val KEPT = 200

        fun backoffMs(failures: Int): Long = (1_000L shl (failures - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
    }
}
