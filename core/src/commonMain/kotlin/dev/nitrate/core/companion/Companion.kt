package dev.nitrate.core.companion

import kotlinx.serialization.Serializable

/** The Nitrate Companion server plugin, when the server has it: its version and features. */
data class CompanionInfo(val version: String, val features: Set<String>) {
    val chat get() = CHAT in features

    companion object {
        const val CHAT = "chat"
    }
}

@Serializable
enum class ChatKind { TEXT, REACTION }

/** One message in a watch-together group's chat. */
data class ChatMessage(
    /** Its place in the chat, counting up from 1. */
    val seq: Long,
    val userId: String,
    val userName: String,
    val kind: ChatKind,
    val text: String,
    /** Where the sender was in what's playing, if anything. */
    val positionMs: Long?,
    val sentAtMs: Long,
    /** The note it is kept as (Companion `livenotes`: a message in a live session is one note, delivered at once). */
    val noteId: String? = null,
    /** The film or episode that note is pinned to. */
    val itemId: String? = null,
)

/**
 * Messages after a point, and the chat's newest number (ask for messages after it next).
 * [brought] counts up whenever someone brings earlier notes into the session (ask for them again).
 */
data class ChatPage(val messages: List<ChatMessage>, val latest: Long, val brought: Long = 0)

/** The server turned a message down: [message] says why, for the sender. */
class ChatRejected(message: String, val tooFast: Boolean) : Exception(message)

/** Not allowed in that chat (not watching in the group, or the group is gone). */
class ChatForbidden : Exception("You're not in this watch party")

/** Quick reactions, sendable with a remote. */
val QUICK_REACTIONS = listOf("😂", "😮", "😍", "👏", "🔥", "😢", "😱", "🍿")
