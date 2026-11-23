package dev.nitrate.core.partners

/**
 * A watch group's recurring watch party (Companion feature `party`): one or more weekdays
 * ([days], 0 = Sunday) at [hour]:[minute] in the server's time zone, kept with the group's
 * schedule ([GroupSchedule.party]) and changed by a majority like the rest of it. It holds nothing
 * back: it's when the group meets to watch its next part together in one watch party.
 */
data class PartyTime(val days: List<Int>, val hour: Int = 20, val minute: Int = 0) {
    /** "Thursdays 8pm", "Mondays and Thursdays 8:30pm", "Every day 8pm". */
    val label: String get() {
        val d = days.distinct().sorted().filter { it in 0..6 }
        val when_ = when {
            d.size == 7 -> "Every day"
            else -> WatchPartners.names(d.map { GroupSchedule.DAYS[it] + "s" })
        }
        return "$when_ ${GroupSchedule.clockOf(hour, minute)}"
    }

    /**
     * The first time after [nowMs] it's set for, with the server's time zone [offsetMinutes] from
     * UTC (as the server last said; a clock change between loads is put right at the next load).
     */
    fun nextAfter(nowMs: Long, offsetMinutes: Int): Long? {
        if (days.isEmpty()) return null
        val offsetMs = offsetMinutes * 60_000L
        val localNow = nowMs + offsetMs
        val today = localNow.floorDiv(DAY_MS)
        for (i in 0..7) {
            val day = today + i
            if (weekday(day) !in days) continue
            val at = day * DAY_MS + (hour * 60 + minute) * 60_000L - offsetMs
            if (at > nowMs) return at
        }
        return null
    }

    companion object {
        const val DAY_MS = 86_400_000L

        /** 0 = Sunday for a day count since 1 January 1970 (a Thursday). */
        fun weekday(day: Long): Int = (day + 4).mod(7L).toInt()
    }
}

/** A postponement that took effect: "Suzy pushed it back 15 min". */
data class PartyDelay(val name: String, val minutes: Int, val mine: Boolean)

/** A postponement (+30, +60) or "Not tonight" waiting for the group's votes ([voted]: the user answered; [mine]: the user asked). */
data class PartyAsk(val id: String, val fromName: String, val kind: String, val minutes: Int, val yes: List<String>, val no: List<String>, val voted: Boolean, val mine: Boolean) {
    val skip get() = kind == SKIP

    /** "Suzy postponed 30 minutes", "Suzy asks to skip tonight's watch party". */
    val text: String get() = if (skip) "$fromName asks to skip tonight's watch party" else "$fromName postponed $minutes minutes"

    companion object {
        const val LATER = "later"
        const val SKIP = "skip"
    }
}

/**
 * A group's next (or current) watch party as the user sees it: set for [atMs], starting at
 * [startsAtMs] after postponements, playing [partId] ("S1:E4" or a film's name in [partLabel]),
 * and what happened to it. [nextAtMs] is the one after, so a phone can set its reminder for it
 * too; [offsetMinutes] is the server's time zone's offset, for showing times as the group set them.
 */
data class WatchParty(
    val groupId: String,
    val atMs: Long,
    val startsAtMs: Long,
    val partId: String? = null,
    val partLabel: String? = null,
    val delays: List<PartyDelay> = emptyList(),
    /** The user may still push it back 15 minutes (once each per night, before it starts). */
    val canPushBack: Boolean = true,
    val ask: PartyAsk? = null,
    /** The others who can't make it. */
    val cantMake: List<String> = emptyList(),
    val iCantMake: Boolean = false,
    val skipped: Boolean = false,
    val started: Boolean = false,
    val startedBy: String? = null,
    val sessionId: String? = null,
    val nextAtMs: Long = atMs + 7 * PartyTime.DAY_MS,
    val offsetMinutes: Int = 0,
) {
    /** "8:00", "8:15" (the group's time, as set). */
    val clock: String get() = clockAt(startsAtMs, offsetMinutes)

    /** When this party's reminder is due: 15 minutes before it starts. */
    val reminderAtMs: Long get() = startsAtMs - LEAD_MS

    /** Whether it's on and the user is going (else no reminder for it). */
    val going: Boolean get() = !skipped && !iCantMake

    /**
     * When this device should remind the user next (null: nothing to remind of): this party's
     * reminder while it's still ahead and on, else the next one's.
     */
    fun nextReminderAt(nowMs: Long): Long? = when {
        going && reminderAtMs > nowMs -> reminderAtMs
        nextAtMs - LEAD_MS > nowMs -> nextAtMs - LEAD_MS
        else -> null
    }

    /** Whether it's tonight's, near enough to join now (from an hour before until it's over). */
    fun joinable(nowMs: Long): Boolean = !skipped && nowMs >= startsAtMs - JOIN_EARLY_MS && nowMs < startsAtMs + GRACE_MS

    /** "Tonight's watch party: 8:15 · S1:E4", "Tonight's watch party is off", "Next watch party: Thursday 8:00". */
    fun line(nowMs: Long): String {
        val tonight = startsAtMs - nowMs < 18 * 3_600_000L
        if (skipped) return if (tonight) "Tonight's watch party is off" else "The next watch party is off"
        val head = if (tonight) "Tonight's watch party: $clock" else "Next watch party: ${dayOf(startsAtMs, offsetMinutes)} $clock"
        return listOfNotNull(head, partLabel).joinToString(" · ")
    }

    companion object {
        const val LEAD_MS = 15 * 60_000L
        const val JOIN_EARLY_MS = 60 * 60_000L
        const val GRACE_MS = 3 * 3_600_000L

        /** "8:00" at [ms] in a zone [offsetMinutes] from UTC. */
        fun clockAt(ms: Long, offsetMinutes: Int): String {
            val minutes = (ms + offsetMinutes * 60_000L).floorDiv(60_000L).mod(24 * 60L).toInt()
            val h = (minutes / 60).let { if (it % 12 == 0) 12 else it % 12 }
            return "$h:" + (minutes % 60).toString().padStart(2, '0')
        }

        /** "Thursday" at [ms] in a zone [offsetMinutes] from UTC. */
        fun dayOf(ms: Long, offsetMinutes: Int): String =
            GroupSchedule.DAYS[PartyTime.weekday((ms + offsetMinutes * 60_000L).floorDiv(PartyTime.DAY_MS))]
    }
}

/**
 * What the Companion tells a member's open apps about a group's watch party at once (a Jellyfin
 * message between sessions with the header [HEADER], never shown as a message): the reminder 15
 * minutes before ([REMINDER]), a postponement ([PUSHED]), a request to agree to ([ASKED]), tonight
 * being off ([SKIPPED]), the start-without question ([WITHOUT]), anything else ([CHANGED]).
 */
data class PartyNews(
    val kind: String,
    val groupId: String,
    val seriesName: String,
    val others: List<String>,
    val byName: String?,
    val byMe: Boolean,
    val minutes: Int,
    val party: WatchParty?,
) {
    companion object {
        const val HEADER = "Nitrate.Party"
        const val REMINDER = "reminder"
        const val PUSHED = "pushed"
        const val ASKED = "asked"
        const val SKIPPED = "skipped"
        const val WITHOUT = "without"
        const val CHANGED = "changed"

        /** Not from the server: the user opened tonight's party by hand (a card's group choices, Settings). */
        const val OPEN = "open"

        /** The news in a message's text (null when it isn't any). */
        fun decode(text: String): PartyNews? = dev.nitrate.core.jellyfin.decodePartyNews(text)
    }
}

/**
 * What to ask the user about a group's watch party, and the buttons: the reminder ("Watch party
 * with Tommy & Suzy · 8:00 · S1:E4": Join, +15/+30/+60 min, Not tonight, Can't make it), a
 * postponement to agree to, the start-without question, or news to acknowledge. Easy mode keeps
 * only Join and Not tonight.
 */
data class PartyPrompt(val groupId: String, val title: String, val text: String, val choices: List<PartyChoice>, val party: WatchParty) {
    companion object {
        /** "Tommy", "Tommy & Suzy", "Tommy, Suzy & Kim". */
        fun names(others: List<String>): String = when (others.size) {
            0 -> "your group"
            1 -> others[0]
            else -> others.dropLast(1).joinToString(", ") + " & " + others.last()
        }

        /**
         * The prompt for [news] (null: nothing to ask, e.g. the user did it themselves, or it's
         * a change that only updates what's shown).
         */
        fun of(news: PartyNews, easy: Boolean = false): PartyPrompt? {
            val p = news.party ?: return null
            val with = names(news.others)
            val by = news.byName ?: "Someone"
            return when (news.kind) {
                PartyNews.REMINDER -> if (!p.going) null else reminder(news.groupId, with, news.seriesName, p, easy)
                PartyNews.OPEN -> if (p.skipped) PartyPrompt(
                    news.groupId, news.seriesName, "The watch party with $with is off this time. ${news.seriesName} still goes on as the group's schedule says.",
                    listOf(PartyChoice.Ok), p,
                ) else reminder(news.groupId, with, news.seriesName, p, easy)
                PartyNews.PUSHED -> if (news.byMe) null else PartyPrompt(
                    news.groupId, "Watch party moved", "$by pushed it back ${news.minutes} min (${p.clock})",
                    listOf(PartyChoice.Ok), p,
                )
                PartyNews.ASKED -> {
                    val ask = p.ask ?: return null
                    if (news.byMe || ask.voted) null
                    else PartyPrompt(
                        news.groupId, "Watch party with $with", "${ask.text}. Agree?",
                        listOf(PartyChoice.Vote(ask.id, true), PartyChoice.Vote(ask.id, false)), p,
                    )
                }
                PartyNews.SKIPPED -> if (news.byMe) null else PartyPrompt(
                    news.groupId, "No watch party tonight", "Tonight's watch party with $with is off. ${news.seriesName} still goes on as the group's schedule says.",
                    listOf(PartyChoice.Ok), p,
                )
                PartyNews.WITHOUT -> {
                    if (p.iCantMake || p.cantMake.isEmpty() || p.started) return null
                    val missing = names(p.cantMake)
                    PartyPrompt(
                        news.groupId, "Watch party with $with",
                        "$missing can't tonight. Start the watch party without ${if (p.cantMake.size == 1) "them" else "them all"}? $missing will fall behind.",
                        listOf(PartyChoice.Join, PartyChoice.NotTonight), p,
                    )
                }
                else -> null
            }
        }

        /** The reminder (also what opening tonight's party from a card or Settings shows). */
        fun reminder(groupId: String, with: String, title: String, p: WatchParty, easy: Boolean = false): PartyPrompt {
            val text = listOfNotNull("Watch party with $with", p.clock, p.partLabel).joinToString(" · ")
            val choices = buildList {
                add(PartyChoice.Join)
                if (!easy) {
                    if (p.canPushBack) add(PartyChoice.Later(15))
                    if (p.ask == null || p.ask.mine) {
                        add(PartyChoice.Later(30))
                        add(PartyChoice.Later(60))
                    }
                }
                if (p.ask == null || p.ask.mine) add(PartyChoice.NotTonight)
                if (!easy && !p.iCantMake) add(PartyChoice.CantMake)
                p.ask?.takeIf { !it.voted && !it.mine && !easy }?.let { add(PartyChoice.Vote(it.id, true)); add(PartyChoice.Vote(it.id, false)) }
            }
            val note = p.ask?.takeIf { !it.mine }?.let { "\n${it.text}." }.orEmpty() +
                p.delays.lastOrNull()?.let { d -> "\n${if (d.mine) "You" else d.name} pushed it back ${d.minutes} min." }.orEmpty()
            return PartyPrompt(groupId, title, text + note, choices, p)
        }
    }
}

/** A button of a [PartyPrompt]. */
sealed interface PartyChoice {
    val label: String

    /** Join (the first to join starts the watch party). */
    data object Join : PartyChoice { override val label = "Join" }

    /** Push it back: 15 at once, 30 and 60 ask the group. */
    data class Later(val minutes: Int) : PartyChoice { override val label = "+$minutes min" }

    /** Ask the group to skip tonight (if it doesn't pass, the user can't make it). */
    data object NotTonight : PartyChoice { override val label = "Not tonight" }

    /** Just the user can't make it; the others go ahead without them. */
    data object CantMake : PartyChoice { override val label = "Can't make it" }

    data class Vote(val requestId: String, val yes: Boolean) : PartyChoice { override val label = if (yes) "Yes" else "No" }

    data object Ok : PartyChoice { override val label = "OK" }
}

/** What joining tonight's party means: start it (the user's first), or join the live session already going (null while its starter sets it up). */
data class PartyJoin(val start: Boolean, val sessionId: String?, val partId: String?)
