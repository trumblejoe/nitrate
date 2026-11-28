package dev.nitrate.core.partners

import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.MediaItem

/**
 * The guided setup of a new watch group ("Watch something with someone", both apps): 1) What (one
 * or more titles), 2) Who (one or more people listed, and/or exact user names separated by commas:
 * nothing tells whether they exist; all invited together into one group, Companion feature
 * `invitebatch`, else one person as before),
 * 3) Release schedule (the pace), 4) Group Watch Session (optional days and time), 5) Together on
 * or off. Then the invite is sent ([send]); accepting agrees to the schedule. Back steps back one
 * page ([back]; null on the first: the setup closes).
 */
data class GroupSetup(
    val step: Step = Step.WHAT,
    val titles: List<GroupTitle> = emptyList(),
    /** People listed, by id. */
    val people: List<String> = emptyList(),
    /** And/or anyone by exact user name, several separated by commas. */
    val typed: String = "",
    val schedule: GroupSchedule = GroupSchedule(),
    /** Whether the server invites several people into one group (feature `invitebatch`); else one person. */
    val several: Boolean = true,
) {
    enum class Step(val title: String) {
        WHAT("What to watch"),
        WHO("Who with"),
        PACE("Release schedule"),
        SESSION("Group Watch Session"),
        TOGETHER("Together"),
    }

    /** What the page says under its heading: the first, that Group viewing is kept apart ([Viewing.EXPLAINED]). */
    val note: String? get() = if (step == Step.WHAT) Viewing.EXPLAINED else null

    /** "Step 2 of 5 · Who with". */
    val heading: String get() = "Step ${step.ordinal + 1} of ${Step.entries.size} · ${step.title}"

    /** Whether this page has what it needs to go on. */
    val canGoOn: Boolean get() = when (step) {
        Step.WHAT -> titles.isNotEmpty()
        Step.WHO -> invitees.isNotEmpty()
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

    /** Whether a title, or someone listed, is picked. */
    fun picked(id: String): Boolean = titles.any { it.id == id } || id in people

    /** The typed user names, one per comma (just the first without [several]). */
    val typedNames: List<String> get() = typed.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        .let { if (several) it else it.take(1) }

    /** Everyone to invite: (id, null) for someone listed, (null, name) for a typed name. */
    val invitees: List<Pair<String?, String?>> get() =
        (people.map { it to null } + typedNames.map { null to it }).let { if (several) it else it.take(1) }

    /** Ticks someone listed, or unticks them (without [several]: picks just them, clearing a typed name). */
    fun pick(id: String): GroupSetup = when {
        id in people -> copy(people = people - id)
        several -> copy(people = people + id)
        else -> copy(people = listOf(id), typed = "")
    }

    /** Types user names (without [several]: one, clearing whoever was picked). */
    fun type(name: String): GroupSetup = if (several) copy(typed = name) else copy(typed = name, people = if (name.isNotBlank()) emptyList() else people)

    /** What's chosen so far, for the last page: "Severance and The Bear · Thursdays 7pm, together". */
    val summary: String get() = WatchPartners.names(titles.map { it.name }) + " · " + schedule.label

    /**
     * Sends the invites for a new group ([MediaServer.askPartner] with the titles and the schedule):
     * everyone at once into one group (a shared batch id), so whoever accepts first makes it and the
     * others join it. Always "sent", one answer per person.
     */
    suspend fun send(server: MediaServer, batchId: String = newBatchId()): List<OutgoingRequest> {
        val first = titles.first()
        val batch = batchId.takeIf { invitees.size > 1 }
        return invitees.map { (id, name) -> server.askPartner(id, name, first.id, first.name, null, schedule, titles, batch = batch) }
    }

    companion object {
        /** A fresh batch id (a dashed UUID, as the server keeps ids). */
        @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
        fun newBatchId(): String = kotlin.uuid.Uuid.random().toString()

        /** What the invite says once sent (whether the account exists is never told). */
        const val SENT = "Sent. If that's someone on this server, they'll see it next time they open Nitrate."
    }
}
