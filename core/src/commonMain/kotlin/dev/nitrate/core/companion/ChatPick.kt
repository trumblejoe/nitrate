package dev.nitrate.core.companion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The chat chosen on a details page for the playback it starts: none, a new one, or an earlier one replayed. */
sealed interface ChatPick {
    data object Off : ChatPick
    data object New : ChatPick
    data class Replay(val chatId: String) : ChatPick

    companion object {
        /** "Off", "New chat", "Replay 2026/11/22 · Tommy, Suzy" (an earlier chat no longer listed: "Replay an earlier chat"). */
        fun label(pick: ChatPick, all: List<NoteChat>, offsetAt: (Long) -> Int): String = when (pick) {
            Off -> "Off"
            New -> NoteChats.NEW_CHAT
            is Replay -> all.firstOrNull { it.id == pick.chatId }?.let { "Replay " + NoteChats.title(it, all, offsetAt) } ?: "Replay an earlier chat"
        }
    }
}

/**
 * Details pages' chat picks, for one playback each: what a page shows ([chosen], Off until picked)
 * and, once its Play is pressed ([arm]), what the player takes as it starts ([take]); taken once, so
 * the next playback (Up Next, a card) goes by the remembered on/off again. Kept until the app closes.
 */
class ChatPicks {
    private val chosenState = MutableStateFlow(emptyMap<String, ChatPick>())
    private val pending = mutableMapOf<String, ChatPick>()

    /** What each title's details page has picked (Off when not in it). */
    val picks: StateFlow<Map<String, ChatPick>> = chosenState

    fun chosen(itemId: String): ChatPick = chosenState.value[itemId] ?: ChatPick.Off

    fun choose(itemId: String, pick: ChatPick) = chosenState.update { it + (itemId to pick) }

    /** Play pressed on a details page: its pick (Off unless chosen) goes to the playback, and the page goes back to Off. */
    fun arm(itemId: String) {
        pending[itemId] = chosen(itemId)
        chosenState.update { it - itemId }
    }

    /** The player starting [itemId]: the details page's pick, once (null: not started from one). */
    fun take(itemId: String): ChatPick? = pending.remove(itemId)
}
