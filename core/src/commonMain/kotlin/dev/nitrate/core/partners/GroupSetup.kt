package dev.nitrate.core.partners

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem

/**
 * The guided setup of a new watch group ("Watch something with someone", both apps): 1) What (one
 * or more titles), 2) Who (someone listed, or an exact user name: nothing tells whether it exists),
 * 3) Release schedule (the pace), 4) Group Watch Session (optional days and time), 5) Together on
 * or off. Then the invite is sent ([send]); accepting agrees to the schedule. Back steps back one
 * page ([back]; null on the first: the setup closes).
 */
data class GroupSetup(
    val step: Step = Step.WHAT,
    val titles: List<GroupTitle> = emptyList(),
    /** Someone listed, by id. */
    val personId: String? = null,
    /** Or anyone by exact user name (when no one listed is picked). */
    val typed: String = "",
    val schedule: GroupSchedule = GroupSchedule(),
) {
    enum class Step(val title: String) {
        WHAT("What to watch"),
        WHO("Who with"),
        PACE("Release schedule"),
        SESSION("Group Watch Session"),
        TOGETHER("Together"),
    }

    /** "Step 2 of 5 · Who with". */
    val heading: String get() = "Step ${step.ordinal + 1} of ${Step.entries.size} · ${step.title}"

    /** Whether this page has what it needs to go on. */
    val canGoOn: Boolean get() = when (step) {
        Step.WHAT -> titles.isNotEmpty()
        Step.WHO -> personId != null || typed.isNotBlank()
        else -> true
    }

    /** The last page: its button sends the invite. */
    val last: Boolean get() = step == Step.TOGETHER

    /** The next page (this one when it can't go on yet, or it's the last). */
    fun next(): GroupSetup = if (!canGoOn || last) this else copy(step = Step.entries[step.ordinal + 1])

    /** The page before; null on the first (Back closes the setup). */
    fun back(): GroupSetup? = if (step == Step.WHAT) null else copy(step = Step.entries[step.ordinal - 1])

    /** Picks [item] as one of the titles, or drops it when it was picked. */
    fun toggle(item: MediaItem): GroupSetup =
        if (picked(item.id)) copy(titles = titles.filter { it.id != item.id }) else copy(titles = titles + GroupTitle(item.id, item.name))

    fun picked(id: String): Boolean = titles.any { it.id == id }

    /** Picks someone listed ([id]; again: nobody), clearing a typed name. */
    fun pick(id: String): GroupSetup = copy(personId = id.takeIf { it != personId }, typed = "")

    /** Types a user name, clearing whoever was picked. */
    fun type(name: String): GroupSetup = copy(typed = name, personId = if (name.isNotBlank()) null else personId)

    /** What's chosen so far, for the last page: "Severance and The Bear · Thursdays 7pm, together". */
    val summary: String get() = WatchPartners.names(titles.map { it.name }) + " · " + schedule.label

    /** Sends the invite for a new group ([MediaServer.askPartner] with the titles and the schedule). Always "sent". */
    suspend fun send(server: MediaServer): OutgoingRequest {
        val first = titles.first()
        return server.askPartner(personId, typed.trim().takeIf { personId == null }, first.id, first.name, null, schedule, titles)
    }

    companion object {
        /** What the invite says once sent (whether the account exists is never told). */
        const val SENT = "Sent. If that's someone on this server, they'll see it next time they open Nitrate."
    }
}
