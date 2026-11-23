package dev.nitrate.core.partners

/**
 * What the Companion tells the open apps about a group's schedule change, as it happens (sent
 * with the watch parties' header, [PartyNews.HEADER], and told apart by [kind]): a change someone
 * asked for ([ASK], never sent to the asker), or how one went ([DONE], to everyone in the group:
 * [outcome] [PASSED], [FAILED] or [LAPSED], and the schedule as it is now in [current], null =
 * binge with Together off).
 */
data class ScheduleNews(
    val kind: String,
    val groupId: String,
    val changeId: String,
    /** The group's titles, in order. */
    val titles: List<String>,
    /** The other members' names. */
    val others: List<String>,
    /** Who asked for the change. */
    val fromName: String,
    val fromMe: Boolean,
    /** The schedule asked for. */
    val proposed: GroupSchedule,
    val current: GroupSchedule? = null,
    val outcome: String? = null,
) {
    /** "Severance and The Bear". */
    val titleNames: String get() = WatchPartners.names(titles)

    companion object {
        const val ASK = "schedule-ask"
        const val DONE = "schedule-done"
        const val PASSED = "passed"
        const val FAILED = "failed"
        const val LAPSED = "lapsed"

        /**
         * The question for [group]'s waiting change, when the user hasn't answered it yet (the
         * asker counts as a yes, so never their own): a tapped notification, the background check.
         */
        fun askOf(group: WatchGroup): ScheduleNews? {
            val p = group.pending?.takeIf { !it.voted } ?: return null
            return ScheduleNews(ASK, group.id, p.id, group.titles.map { it.name }, group.others.map { it.name }, p.fromName, false, p.proposed, group.schedule)
        }

        /** The schedule news in a party message's text (null when it's party news, or nothing). */
        fun decode(text: String): ScheduleNews? = dev.nitrate.core.jellyfin.decodeScheduleNews(text)
    }
}

/**
 * What to show for [ScheduleNews]: a change to agree to ("Sam asks to change the schedule of The
 * Bear to Thursdays 7pm. Agree?": Yes / No, voted with `voteSchedule`), or how one went ("The
 * schedule of The Bear changed to Thursdays 7pm." / "… stays Binge.") with OK.
 */
data class SchedulePrompt(val groupId: String, val changeId: String, val title: String, val text: String, val asks: Boolean) {
    companion object {
        const val TITLE_ASK = "Change the schedule?"
        const val TITLE_CHANGED = "Schedule changed"
        const val TITLE_KEPT = "Schedule stays"

        /** The question for a change [from] asked for. */
        fun askText(from: String, titles: String, proposed: GroupSchedule) =
            "$from asks to change the schedule of $titles to ${proposed.label}. Agree?"

        /** The prompt for [news] (null: nothing to show, e.g. the user's own ask). */
        fun of(news: ScheduleNews): SchedulePrompt? = when (news.kind) {
            ScheduleNews.ASK -> if (news.fromMe) null else SchedulePrompt(
                news.groupId, news.changeId, TITLE_ASK, askText(news.fromName.ifBlank { "Someone" }, news.titleNames, news.proposed), asks = true,
            )
            ScheduleNews.DONE -> {
                val kept = news.current?.label ?: GroupSchedule().label
                when (news.outcome) {
                    ScheduleNews.PASSED -> SchedulePrompt(news.groupId, news.changeId, TITLE_CHANGED, "The schedule of ${news.titleNames} changed to ${news.proposed.label}.", asks = false)
                    ScheduleNews.FAILED -> SchedulePrompt(
                        news.groupId, news.changeId, TITLE_KEPT,
                        "Not enough of the group agreed${if (news.fromMe) "" else " to ${news.fromName}'s change"}. The schedule of ${news.titleNames} stays $kept.", asks = false,
                    )
                    ScheduleNews.LAPSED -> SchedulePrompt(
                        news.groupId, news.changeId, TITLE_KEPT,
                        "The change to ${news.proposed.label} lapsed after 7 days. The schedule of ${news.titleNames} stays $kept.", asks = false,
                    )
                    else -> null
                }
            }
            else -> null
        }
    }
}
