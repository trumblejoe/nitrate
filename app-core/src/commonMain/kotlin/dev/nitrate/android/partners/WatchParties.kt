package dev.nitrate.android.partners

import dev.nitrate.core.MediaServer
import dev.nitrate.core.partners.PartyChoice
import dev.nitrate.core.partners.PartyNews
import dev.nitrate.core.partners.PartyPrompt
import dev.nitrate.core.partners.TogetherChoice
import dev.nitrate.core.partners.WatchGroup
import dev.nitrate.core.partners.WatchParty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What a watch party choice came to. */
sealed interface PartyOutcome {
    /** Done; [message] to show, if any. */
    data class Done(val message: String? = null) : PartyOutcome

    /** The user is first: start the watch party (a live session, the others invited) and play [choice]'s part through the guards. */
    data class Start(val groupId: String, val choice: TogetherChoice.WatchNow) : PartyOutcome

    /** Joined the live session already going: its player opens as it plays. */
    data object Joined : PartyOutcome

    data class Failed(val message: String) : PartyOutcome
}

/**
 * The apps' side of watch parties (Companion feature `party`): what the Companion sends the
 * open apps ([heard], from the server's own live connection; nothing is polled), the prompt to show
 * now ([asking]: each app shows it as a dialog or sheet, simplified in easy mode), and carrying out
 * its choices. Tonight's party can also be opened by hand ([open]) from a title's group choices or
 * Settings → Watch groups, so nothing depends on catching the reminder.
 */
class WatchParties(
    private val server: MediaServer,
    /** The user's groups as last loaded. */
    private val groups: () -> List<WatchGroup>,
    /** A group's party changed (news, or a choice's answer): update what's shown. */
    private val updated: (groupId: String, party: WatchParty?) -> Unit,
    /** Joins a live (SyncPlay) group. */
    private val joinSession: suspend (String) -> Unit,
) {
    private val askingState = MutableStateFlow<PartyNews?>(null)

    /** What to ask about now ([PartyPrompt.of] makes the prompt, with the user's easy mode). */
    val asking: StateFlow<PartyNews?> = askingState

    /** The Companion said something about a group's party: show it, unless it's news to nobody (the user did it, or it changes only what's shown). */
    fun heard(news: PartyNews) {
        updated(news.groupId, news.party)
        if (PartyPrompt.of(news) != null) askingState.value = news
        else if (askingState.value?.groupId == news.groupId) {
            // What's showing for that group is out of date now: show it as it stands.
            askingState.value = askingState.value?.copy(party = news.party)
        }
    }

    /** Opens tonight's (or the next) party of [group] by hand: the same choices as its reminder. */
    fun open(group: WatchGroup) {
        val party = group.party ?: return
        askingState.value = PartyNews(PartyNews.OPEN, group.id, group.seriesName, group.others.map { it.name }, null, false, 0, party)
    }

    fun dismiss() { askingState.value = null }

    /** Forgets what was asked (switching or signing out: it belonged to that account). */
    fun clear() { askingState.value = null }

    /** Carries out [choice] for [news]' group. */
    suspend fun choose(news: PartyNews, choice: PartyChoice): PartyOutcome {
        val groupId = news.groupId
        val result = runCatching {
            when (choice) {
                PartyChoice.Ok -> PartyOutcome.Done()
                PartyChoice.Join -> join(news)
                is PartyChoice.Later -> {
                    val p = server.partyLater(groupId, choice.minutes)
                    updated(groupId, p)
                    PartyOutcome.Done(
                        if (choice.minutes == 15) "Pushed back to ${p?.clock ?: "15 minutes later"}. Everyone's told."
                        else "Asked the group. It moves if most of you agree before it starts.",
                    )
                }
                PartyChoice.NotTonight -> {
                    val p = server.partySkip(groupId)
                    updated(groupId, p)
                    PartyOutcome.Done(if (p?.skipped == true) "Tonight's watch party is off." else "Asked the group. If most don't agree, the others go ahead without you.")
                }
                PartyChoice.CantMake -> {
                    updated(groupId, server.partyCantMake(groupId, true))
                    PartyOutcome.Done("The others will be asked whether to start without you. You'll fall behind.")
                }
                is PartyChoice.Vote -> {
                    updated(groupId, server.partyVote(groupId, choice.requestId, choice.yes))
                    PartyOutcome.Done()
                }
            }
        }
        if (result.isSuccess) dismiss()
        return result.getOrElse { PartyOutcome.Failed(it.message ?: "That didn't work") }
    }

    private suspend fun join(news: PartyNews): PartyOutcome {
        val j = server.partyJoin(news.groupId)
        if (j.start) {
            val partId = j.partId ?: news.party?.partId ?: return PartyOutcome.Failed("Everyone has seen all of ${news.seriesName}.")
            val item = server.getItem(partId)
            val group = groups().firstOrNull { it.id == news.groupId }
            val people = group?.others ?: emptyList()
            return PartyOutcome.Start(news.groupId, TogetherChoice.WatchNow(item, news.groupId, people.map { it.id }, PartyPrompt.names(people.map { it.name })))
        }
        val session = j.sessionId ?: return PartyOutcome.Done(
            "${news.party?.startedBy ?: "Someone"} is starting the watch party. You'll be asked to join in a moment.",
        )
        joinSession(session)
        return PartyOutcome.Joined
    }
}
