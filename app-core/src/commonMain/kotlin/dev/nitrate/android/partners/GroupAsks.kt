package dev.nitrate.android.partners

import dev.nitrate.core.MediaServer
import dev.nitrate.core.partners.AskNews
import dev.nitrate.core.partners.AskPrompt
import dev.nitrate.core.partners.WatchAsk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Asks to watch together on this device (Companion feature [WatchAsk.FEATURE]): the one to answer
 * now ([asking]: someone in a group would like to watch together, "Start it" / Not now), a later
 * one this user took whose time came ([due]), what the user's own asks came to ([toAsker]), and
 * what to start here ([starts]: the app starts the Group Watch Session and plays it, as host).
 * Told by the Companion's news as it happens, and read again when the app comes back.
 */
class GroupAsks(private val server: MediaServer, private val scope: CoroutineScope) {
    private val askingState = MutableStateFlow<WatchAsk?>(null)

    /** Someone's ask to answer here (one at a time). */
    val asking: StateFlow<WatchAsk?> = askingState

    private val dueState = MutableStateFlow<WatchAsk?>(null)

    /** A later ask this user took whose time came: start it now? */
    val due: StateFlow<WatchAsk?> = dueState

    private val told = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** What the user's own asks came to ("Suzy can't right now"), to say in passing. */
    val toAsker: SharedFlow<String> = told

    private val startState = MutableSharedFlow<WatchAsk>(extraBufferCapacity = 2)

    /** Asks to start here, as host: the app starts the session for the ask's group and plays its part. */
    val starts: SharedFlow<WatchAsk> = startState

    /** Answered or put aside here (not asked again this run). */
    private val seen = mutableSetOf<String>()

    fun heard(news: AskNews) {
        val a = news.ask
        when (news.event) {
            AskNews.ASKED -> if (!a.fromMe && a.open && a.id !in seen) askingState.value = a
            AskNews.TAKEN, AskNews.CANCELLED, AskNews.EXPIRED -> {
                if (askingState.value?.id == a.id) askingState.value = null
                if (dueState.value?.id == a.id && news.event == AskNews.CANCELLED) dueState.value = null
            }
            AskNews.DUE -> if (a.takenByMe) dueState.value = a
        }
        if (a.fromMe) AskPrompt.toAsker(news)?.let { told.tryEmit(it) }
    }

    /** Reads the open asks again (the app came back): the oldest one not answered here is asked. */
    fun refresh() {
        scope.launch {
            val asks = runCatching { server.watchAsks() }.getOrNull() ?: return@launch
            if (askingState.value == null) askingState.value = asks.filter { it.open && !it.fromMe && it.id !in seen }.minByOrNull { it.atMs }
        }
    }

    /** Asks [groupId] to watch [itemId] together, now (0) or 15, 30 or 60 minutes later. An error, if any. */
    suspend fun ask(groupId: String, itemId: String, laterMinutes: Int): String? =
        runCatching { server.askToWatch(groupId, itemId, laterMinutes) }.exceptionOrNull()?.let { it.message ?: "Couldn't ask the group" }

    /** "Start it": this user hosts it (now: [starts] at once; later: [due] at its time). An error, if any. */
    suspend fun take(a: WatchAsk): String? {
        seen += a.id
        askingState.value = null
        return runCatching { server.answerAsk(a.id, take = true) }
            .onSuccess { if (!it.later) startState.tryEmit(it) }
            .exceptionOrNull()?.let { it.message ?: "Couldn't take it" }
    }

    /** Not now (the asker is told). */
    fun decline(a: WatchAsk) {
        seen += a.id
        askingState.value = null
        scope.launch { runCatching { server.answerAsk(a.id, take = false) } }
    }

    /** The reminder's "Start it now" (or Not now, which calls nothing off: the asker can ask again). */
    fun startDue(start: Boolean) {
        val a = dueState.value ?: return
        dueState.value = null
        if (start) startState.tryEmit(a)
    }

    /** Forgets everything (switching or signing out: it belonged to that account). */
    fun clear() {
        askingState.value = null
        dueState.value = null
        seen.clear()
    }
}
