package dev.nitrate.core.jellyfin

import kotlinx.serialization.Serializable

// The Companion's watch parties (feature `party`): the group view's `Party`, the party calls'
// answers, and the "Nitrate.Party" messages it sends to open apps. Outside the OpenAPI contract test.

@Serializable internal data class PartyDelayDto(val name: String = "", val minutes: Int = 0, val mine: Boolean = false)
@Serializable internal data class PartyAskDto(
    val id: String, val fromName: String = "", val kind: String = dev.nitrate.core.partners.PartyAsk.LATER, val minutes: Int = 0,
    val yes: List<String> = emptyList(), val no: List<String> = emptyList(), val voted: Boolean = false, val mine: Boolean = false,
)
@Serializable internal data class PartyDto(
    val groupId: String, val at: String, val startsAt: String, val partId: String? = null, val partLabel: String? = null,
    val pushes: List<PartyDelayDto> = emptyList(), val canPushBack: Boolean = false, val request: PartyAskDto? = null,
    val cantMake: List<String> = emptyList(), val iCantMake: Boolean = false, val skipped: Boolean = false, val started: Boolean = false,
    val startedBy: String? = null, val sessionId: String? = null, val nextAt: String? = null, val offsetMinutes: Int = 0,
) {
    fun toModel(): dev.nitrate.core.partners.WatchParty {
        val at = partyMs(at)
        return dev.nitrate.core.partners.WatchParty(
            normalizeId(groupId), at, partyMs(startsAt), partId?.let(::normalizeId), partLabel,
            pushes.map { dev.nitrate.core.partners.PartyDelay(it.name, it.minutes, it.mine) }, canPushBack,
            request?.let { dev.nitrate.core.partners.PartyAsk(it.id, it.fromName, it.kind, it.minutes, it.yes, it.no, it.voted, it.mine) },
            cantMake, iCantMake, skipped, started, startedBy, sessionId, nextAt?.let(::partyMs) ?: (at + 7 * dev.nitrate.core.partners.PartyTime.DAY_MS), offsetMinutes,
        )
    }
}
@Serializable internal data class PartyNewsDto(
    val kind: String, val groupId: String, val seriesName: String = "", val others: List<String> = emptyList(),
    val byName: String? = null, val byMe: Boolean = false, val minutes: Int = 0, val party: PartyDto? = null,
) {
    fun toModel() = dev.nitrate.core.partners.PartyNews(kind, normalizeId(groupId), seriesName, others, byName, byMe, minutes, party?.toModel())
}
@Serializable internal data class PartyTimeDto(val days: List<Int> = emptyList(), val hour: Int = 20, val minute: Int = 0)
@Serializable internal data class PartyJoinDto(val start: Boolean = false, val sessionId: String? = null, val partId: String? = null)

private fun partyMs(iso: String): Long = runCatching { kotlin.time.Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)

/** A "Nitrate.Party" message's text (null when it isn't one). */
internal fun decodePartyNews(text: String): dev.nitrate.core.partners.PartyNews? =
    runCatching { JellyfinJson.decodeFromString(PartyNewsDto.serializer(), text).toModel() }.getOrNull()

// Schedule changes, sent with the same header ("schedule-ask"/"schedule-done"; the party fields
// come along so older apps read it as party news that changes nothing).
@Serializable internal data class ScheduleNewsDto(
    val kind: String, val groupId: String, val changeId: String = "", val titles: List<String> = emptyList(), val seriesName: String = "",
    val others: List<String> = emptyList(), val fromName: String? = null, val fromMe: Boolean = false,
    val proposed: ScheduleDto = ScheduleDto(), val current: ScheduleDto? = null, val outcome: String? = null,
) {
    fun toModel() = dev.nitrate.core.partners.ScheduleNews(
        kind, normalizeId(groupId), normalizeId(changeId), titles.ifEmpty { listOf(seriesName) }, others, fromName.orEmpty(), fromMe,
        proposed.toScheduleModel(), current?.toScheduleModel(), outcome,
    )
}

internal fun ScheduleDto.toScheduleModel() = dev.nitrate.core.partners.GroupSchedule(
    cadence, day, hour, minute, softLock, together,
    party?.takeIf { it.days.isNotEmpty() }?.let { dev.nitrate.core.partners.PartyTime(it.days, it.hour, it.minute) },
    locked = locked,
    weekdays = weekdays, everyWeeks = everyWeeks, everyDays = everyDays, monthDay = monthDay, perRelease = perRelease,
    firstLocked = firstLocked, sequential = sequential, titleOrder = titleOrder.map(::normalizeId),
)

/** A "Nitrate.Party" message's schedule news (null when it's party news, or not a message at all). */
internal fun decodeScheduleNews(text: String): dev.nitrate.core.partners.ScheduleNews? = runCatching {
    val kind = JellyfinJson.parseToJsonElement(text).let { (it as? kotlinx.serialization.json.JsonObject)?.get("Kind") as? kotlinx.serialization.json.JsonPrimitive }?.content
    if (kind != dev.nitrate.core.partners.ScheduleNews.ASK && kind != dev.nitrate.core.partners.ScheduleNews.DONE) null
    else JellyfinJson.decodeFromString(ScheduleNewsDto.serializer(), text).toModel()
}.getOrNull()

/**
 * Whether a "Nitrate.Party" message only says "you have a new invite into a watch group" (kind
 * `request`; nothing else in it: the app reads its invites as usual).
 */
fun isInviteNews(text: String): Boolean = runCatching {
    val o = JellyfinJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
    (o?.get("Kind") as? kotlinx.serialization.json.JsonPrimitive)?.content == INVITE_NEWS_KIND
}.getOrDefault(false)

/** The kind of the Companion's "new invite" message (`PartyPush.RequestKind`). */
const val INVITE_NEWS_KIND = "request"
