package dev.nitrate.core.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The chat chosen on a details page for the playback it starts: off, on (the group's conversation), or on with an older thread replayed too. */
sealed interface ChatPick {
    data object Off : ChatPick
    data object On : ChatPick
    data class Replay(val threadId: String) : ChatPick

    companion object {
        /** "Off", "On", "Replay 2026/10/02" (an older thread no longer listed: "Replay an earlier thread"). */
        fun label(pick: ChatPick, groups: List<ThreadGroup>, offsetAt: (Long) -> Int): String = when (pick) {
            Off -> "Off"
            On -> "On"
            is Replay -> groups.flatMap { it.threads }.firstOrNull { it.id == pick.threadId }
                ?.let { "Replay " + Threads.date(it.startedMs, offsetAt(it.startedMs)) } ?: "Replay an earlier thread"
        }

        /** What a details page starts on: the user's own setting (Settings, Group chat). */
        fun default(chatOn: Boolean): ChatPick = if (chatOn) On else Off
    }
}

/**
 * Details pages' chat picks, for one playback each: what a page shows ([chosen], the user's setting
 * until picked) and, once its Play is pressed ([arm]), what the player takes as it starts ([take]);
 * taken once, so the next playback (Up Next, a card) goes by the setting again. Kept until the app closes.
 */
class ChatPicks {
    private val chosenState = MutableStateFlow(emptyMap<String, ChatPick>())
    private val pending = mutableMapOf<String, ChatPick>()

    /** What each title's details page has picked (the setting's when not in it). */
    val picks: StateFlow<Map<String, ChatPick>> = chosenState

    /** What [itemId]'s details page shows: what was picked, else [default] (the user's setting). */
    fun chosen(itemId: String, default: ChatPick = ChatPick.On): ChatPick = chosenState.value[itemId] ?: default

    fun choose(itemId: String, pick: ChatPick) = chosenState.update { it + (itemId to pick) }

    /** Play pressed on a details page: its pick goes to the playback (nothing picked: the setting's, as any playback), and the page goes back to the setting. */
    fun arm(itemId: String) {
        chosenState.value[itemId]?.let { pending[itemId] = it } ?: pending.remove(itemId)
        chosenState.update { it - itemId }
    }

    /** The player starting [itemId]: the details page's pick, once (null: not started from one). */
    fun take(itemId: String): ChatPick? = pending.remove(itemId)
}
