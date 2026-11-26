package dev.nitrate.core.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A question the TV is asking on screen ("Still watching?", Up Next, a scene in the credits), sent
 * to the user's phones so it can be answered from the remote too. Carried as Jellyfin messages
 * between the user's own sessions (headers [PROMPT], [PROMPT_GONE], [ANSWER]), so no plugin is
 * needed; apps never show these as messages.
 */
@Serializable
data class RemotePrompt(
    /** Unique per question, so a late answer to an old one is ignored. */
    val id: String,
    val kind: Kind,
    /** The asking device's id (the phone matches it to the session it's controlling). */
    val deviceId: String,
    val title: String,
    val detail: String? = null,
    val choices: List<Choice>,
) {
    enum class Kind { STILL_WATCHING, UP_NEXT, CREDIT_SCENE }

    @Serializable data class Choice(val id: String, val label: String)

    companion object {
        const val PROMPT = "Nitrate.Prompt"
        const val PROMPT_GONE = "Nitrate.PromptGone"
        const val ANSWER = "Nitrate.Answer"

        /** The phone app's client name: prompts go only to phones running Nitrate. */
        const val PHONE_CLIENT = "Nitrate Mobile"

        /** The TV sends a question again this often while it's showing, for phones opening the remote meanwhile. */
        const val REPEAT_MS = 12_000L
        /** A phone forgets a question not heard again in this long (the TV went away). */
        const val FORGET_MS = 30_000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun isOurs(header: String?) = header == PROMPT || header == PROMPT_GONE || header == ANSWER || header == RemoteSessionStart.HEADER

        fun encode(p: RemotePrompt) = json.encodeToString(serializer(), p)
        fun decode(text: String): RemotePrompt? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun answer(promptId: String, choiceId: String) = "$promptId|$choiceId"
        /** (prompt id, choice id) from an answer's text. */
        fun readAnswer(text: String): Pair<String, String>? = text.split('|', limit = 2).takeIf { it.size == 2 && it.all(String::isNotEmpty) }?.let { it[0] to it[1] }
    }
}

/**
 * A phone's remote asking the user's TV to start a Group Watch Session of [groupId] on [itemId],
 * hosted there (it plays there, gathering for [dev.nitrate.core.partners.GroupWatchSession.REMOTE_GATHER_MINUTES]).
 * Sent like [RemotePrompt]s, between the user's own sessions.
 */
@Serializable
data class RemoteSessionStart(val groupId: String, val itemId: String) {
    companion object {
        const val HEADER = "Nitrate.StartSession"
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(s: RemoteSessionStart) = json.encodeToString(serializer(), s)
        fun decode(text: String): RemoteSessionStart? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
