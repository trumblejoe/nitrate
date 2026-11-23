package dev.nitrate.android.partners

import dev.nitrate.core.partners.ScheduleNews
import dev.nitrate.core.partners.SchedulePrompt
import dev.nitrate.core.partners.TogetherChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The apps' side of schedule changes as the Companion tells them (sent over the server's live
 * connection with the watch parties' news; nothing is polled): a change someone in a group asked
 * for, to answer Yes or No, and a note when one passes, fails or lapses ([SchedulePrompt.of]).
 * One shows at a time ([showing]), the rest wait; news about a change replaces what was waiting
 * about it. The same vote stays on the card's choices and in Settings → Watch groups.
 */
class ScheduleAsks(
    /** Votes (the app's `runTogether`, which reloads the groups). Returns an error, if any. */
    private val vote: suspend (TogetherChoice.Vote) -> String?,
    /** The groups may have changed (a change asked for or settled): reload them. */
    private val changed: () -> Unit,
) {
    private val queue = MutableStateFlow<List<ScheduleNews>>(emptyList())

    private val showingState = MutableStateFlow<ScheduleNews?>(null)

    /** What to show now, if anything. */
    val showing: StateFlow<ScheduleNews?> = showingState

    /** The Companion told this app about a schedule change. */
    fun heard(news: ScheduleNews) {
        changed()
        val rest = queue.value.filter { it.changeId != news.changeId }
        set(if (SchedulePrompt.of(news) != null) rest + news else rest)
    }

    /** Shows [group]'s waiting change, if the user hasn't answered it (a tapped notification). */
    fun open(group: dev.nitrate.core.partners.WatchGroup) {
        val news = ScheduleNews.askOf(group) ?: return
        set(listOf(news) + queue.value.filter { it.changeId != news.changeId })
    }

    /** Answers [news]' change; returns an error, if any (the question stays then). */
    suspend fun answer(news: ScheduleNews, yes: Boolean): String? {
        val error = vote(TogetherChoice.Vote(news.groupId, news.changeId, yes, news.proposed, news.fromName))
        if (error == null) dismiss(news)
        return error
    }

    /** Puts [news] away (Later, OK, Back): the question stays on the card's choices and in Settings. */
    fun dismiss(news: ScheduleNews) = set(queue.value - news)

    /** Forgets everything (switching or signing out: it belonged to that account). */
    fun clear() = set(emptyList())

    private fun set(list: List<ScheduleNews>) {
        queue.value = list
        showingState.value = list.firstOrNull()
    }
}
