package dev.nitrate.core.companion

import dev.nitrate.core.partners.GroupSchedule
import dev.nitrate.core.partners.PartyTime

/**
 * One chat history on a film or episode (Companion `chats`), as the viewer may see it: the messages
 * written in one playback or Group Watch Session, counting only those the viewer was in the
 * audience of. A chat written while replaying an earlier one keeps only its new messages and a link
 * to that one ([buildsOn]); nothing is copied. Notes kept before chat histories read as one chat per
 * session and one "Earlier notes" chat per watch group ([earlier]).
 */
data class NoteChat(
    val id: String,
    /** The watch group it was written for, when it's one of the viewer's. */
    val groupId: String? = null,
    /** When its first message the viewer may read was written. */
    val createdMs: Long = 0,
    /** The chat it builds on, when the viewer may see that one too. */
    val buildsOn: String? = null,
    /** The Group Watch Session it was written in, if any. */
    val sessionId: String? = null,
    /** Who wrote in it besides the viewer. */
    val people: List<String> = emptyList(),
    /** How many of its own messages the viewer may read (not counting what it builds on). */
    val count: Int = 0,
    /** Notes kept before chat histories, gathered per group ("Earlier notes"). */
    val earlier: Boolean = false,
)

/** A title's chat histories for the viewer, newest first, and their watch groups on it. */
data class TitleChats(val groups: List<NoteGroup>, val chats: List<NoteChat>)

/**
 * A Group Watch Session's chat on one title: the chat everyone there writes into ([chatId], null
 * until someone writes), and the earlier chat someone there picked to play along for everyone
 * ([along], by [by]) with its notes as the viewer gets them (brought ones only for the session).
 */
data class SessionChat(val chatId: String?, val along: String?, val by: String?, val notes: List<TimelineNote>)

/** Which chat a message goes into: this playback's ([chatId], once it has one) and the chat playing along ([buildsOn]). */
data class ChatWrite(val chatId: String?, val buildsOn: String?)

/** Chat histories' words and rules (no I/O). */
object NoteChats {
    /** The choice that writes into a fresh chat and plays nothing earlier. */
    const val NEW_CHAT = "New chat"

    /**
     * When a chat was begun, as people say it: "Tonight" (today from 5pm), "Today", "Yesterday",
     * the weekday within the week, else "Nov 12" (with the year when it isn't this one).
     */
    fun day(ms: Long, nowMs: Long, offsetMinutes: Int): String {
        val offset = offsetMinutes * 60_000L
        val day = (ms + offset).floorDiv(PartyTime.DAY_MS)
        val today = (nowMs + offset).floorDiv(PartyTime.DAY_MS)
        val hour = (ms + offset).mod(PartyTime.DAY_MS) / 3_600_000L
        return when {
            day == today && hour >= 17 -> "Tonight"
            day == today -> "Today"
            day == today - 1 -> "Yesterday"
            day in today - 6 until today -> GroupSchedule.DAYS[PartyTime.weekday(day)]
            else -> date(day, today)
        }
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun civil(day: Long): Triple<Long, Int, Int> {
        // Days since 1970-01-01 to year, month, day (Howard Hinnant's algorithm).
        val z = day + 719_468
        val era = z.floorDiv(146_097)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }

    private fun date(day: Long, today: Long): String {
        val (y, m, d) = civil(day)
        val thisYear = civil(today).first
        return "${MONTHS[m - 1]} $d" + if (y != thisYear) ", $y" else ""
    }

    /** "Tommy, Suzy" (who wrote besides the viewer), "You" when only the viewer did. */
    fun people(chat: NoteChat): String = chat.people.joinToString(", ").ifEmpty { "You" }

    /** "14 messages", "1 message". */
    fun count(n: Int): String = if (n == 1) "1 message" else "$n messages"

    /** The chat's name in the list: "Earlier notes" for older notes, else the day it was begun. */
    fun name(chat: NoteChat, nowMs: Long, offsetMinutes: Int): String =
        if (chat.earlier) "Earlier notes" else day(chat.createdMs, nowMs, offsetMinutes)

    /**
     * The chat as listed: "Tuesday · Tommy, Suzy · 14 messages", "Earlier notes · Tommy · 3
     * messages", or for one building on another "Tonight · builds on Tuesday · 3 new".
     */
    fun label(chat: NoteChat, all: List<NoteChat>, nowMs: Long, offsetMinutes: Int): String {
        val base = chat.buildsOn?.let { b -> all.firstOrNull { it.id == b } }
        return if (base != null) {
            "${name(chat, nowMs, offsetMinutes)} · builds on ${name(base, nowMs, offsetMinutes)} · ${chat.count} new"
        } else {
            "${name(chat, nowMs, offsetMinutes)} · ${people(chat)} · ${count(chat.count)}"
        }
    }

    /**
     * The panel's Chat line: "New chat" (nothing earlier plays), or what's playing along: "Playing
     * along: Tuesday · Tommy, Suzy", "Playing along: Earlier notes".
     */
    fun playingLabel(playing: String?, all: List<NoteChat>, nowMs: Long, offsetMinutes: Int): String {
        val chat = playing?.let { p -> all.firstOrNull { it.id == p } } ?: return if (playing == null) NEW_CHAT else "Playing along: an earlier chat"
        return "Playing along: " + name(chat, nowMs, offsetMinutes) + if (chat.earlier) "" else " · " + people(chat)
    }

    /**
     * The chat, then each it builds on in turn, among [all] (a loop, or a link to one not listed,
     * stops there).
     */
    fun chain(id: String, all: List<NoteChat>): List<String> {
        val byId = all.associateBy { it.id }
        val chain = mutableListOf<String>()
        var at: String? = id
        while (at != null && at !in chain && chain.size < MAX_CHAIN) {
            chain += at
            at = byId[at]?.buildsOn
        }
        return chain
    }

    /**
     * The earlier chats to offer for this playback, newest first: those for the groups it counts
     * for ([focus]; null: all, as in a Group Watch Session) or for no group, never the chat being
     * written now ([writing]).
     */
    fun offered(all: List<NoteChat>, focus: List<String>?, writing: String?): List<NoteChat> =
        all.filter { it.id != writing && (focus == null || it.groupId == null || it.groupId in focus) }
            .sortedByDescending { it.createdMs }

    /**
     * What a playback shows: the notes of the chat playing along (and those it builds on), then
     * this playback's own chat (what was written here, its live messages), each note once.
     */
    fun showing(along: List<TimelineNote>, here: List<TimelineNote>): List<TimelineNote> {
        val ids = HashSet<String>()
        return (along + here).filter { ids.add(it.id) }.sortedWith(compareBy({ it.positionMs }, { it.atMs }))
    }

    /** How deep a chain of chats building on each other is followed. */
    const val MAX_CHAIN = 50
}
