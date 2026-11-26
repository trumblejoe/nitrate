package dev.nitrate.core.remote

import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.Moments
import dev.nitrate.core.companion.NoteChat
import dev.nitrate.core.companion.NoteChats
import dev.nitrate.core.companion.Notes
import dev.nitrate.core.companion.TimelineNote
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The chat of what the user's TV is playing, as it shows it, told to the user's phones so their
 * remote can chat through the TV ([RemoteChatDo]): the phone sends nothing to the server itself;
 * the TV posts what it's handed at its own position, in its chat, for its group. Sent like
 * [RemotePrompt]s, between the user's own sessions: on each change, and again every
 * [RemotePrompt.REPEAT_MS] while the TV plays it; [GONE] when it stops.
 */
@Serializable
data class RemoteChatState(
    /** The TV's device id (the phone matches it to the session it's controlling). */
    val deviceId: String,
    val itemId: String,
    val on: Boolean,
    /** Which chat and who with: "New chat · Will", "Playing along: 2026/11/22 · Tommy · Will", "Group chat · The Bear". */
    val line: String,
    /** The earlier chat playing along (null: a new chat). */
    val playing: String? = null,
    /** The server keeps chat histories: earlier chats can be picked. */
    val hasChats: Boolean = false,
    /** The earlier chats to pick from, newest first. */
    val chats: List<Option> = emptyList(),
    /** The last few messages up to where the TV is (never further on). */
    val recent: List<Line> = emptyList(),
    /** The title's own emojis and the usual ones, first in the emoji list. */
    val emojis: List<String> = emptyList(),
    /** Its characters' names and short lines. */
    val lines: List<String> = emptyList(),
) {
    @Serializable data class Option(val id: String, val title: String, val detail: String)

    @Serializable data class Line(val name: String, val at: String, val text: String, val reaction: Boolean = false)

    companion object {
        const val STATE = "Nitrate.ChatState"
        const val GONE = "Nitrate.ChatGone"
        /** How many recent messages and earlier chats go to the phone. */
        const val RECENT = 6
        const val CHATS = 8

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(s: RemoteChatState) = json.encodeToString(serializer(), s)
        fun decode(text: String): RemoteChatState? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        /** The state of a TV's chat, from what its player shows ([offsetAt]: the TV's offset from UTC in minutes, for the chats' dates). */
        fun of(
            deviceId: String,
            itemId: String,
            on: Boolean,
            who: String?,
            hasChats: Boolean,
            playing: String?,
            offered: List<NoteChat>,
            all: List<NoteChat>,
            history: List<TimelineNote>,
            emojis: List<String>,
            lines: List<String>,
            offsetAt: (Long) -> Int,
        ) = RemoteChatState(
            deviceId = deviceId,
            itemId = itemId,
            on = on,
            line = (if (hasChats && on) NoteChats.playingLabel(playing, all, offsetAt) else "Group chat") + (who?.let { " · $it" } ?: ""),
            playing = playing,
            hasChats = hasChats,
            chats = offered.take(CHATS).map { Option(it.id, NoteChats.title(it, all, offsetAt), NoteChats.detail(it, all, offsetAt)) },
            recent = if (on) history.takeLast(RECENT).map { Line(it.userName, Moments.clock(it.positionMs), Notes.text(it), it.kind == ChatKind.REACTION) } else emptyList(),
            emojis = emojis,
            lines = lines,
        )
    }
}

/** What a phone's remote asks the TV's chat to do: send a message or reaction, turn it on or off, or play an earlier chat (null: a new one). */
@Serializable
data class RemoteChatDo(val kind: Kind, val text: String? = null, val on: Boolean? = null, val chatId: String? = null) {
    enum class Kind { TEXT, REACTION, TURN, PLAY }

    companion object {
        const val HEADER = "Nitrate.ChatDo"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun encode(d: RemoteChatDo) = json.encodeToString(serializer(), d)
        fun decode(text: String): RemoteChatDo? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
