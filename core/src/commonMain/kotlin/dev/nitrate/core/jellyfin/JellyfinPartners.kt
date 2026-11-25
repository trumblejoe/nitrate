package dev.nitrate.core.jellyfin

import dev.nitrate.core.partners.IncomingRequest
import dev.nitrate.core.partners.OutgoingRequest
import dev.nitrate.core.partners.PartnerProgress
import dev.nitrate.core.partners.PartnersInfo
import dev.nitrate.core.partners.Privacy
import dev.nitrate.core.partners.WatchGroup
import dev.nitrate.core.writing.UserRef
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable internal data class PrivacyDto(val private: Boolean = false, val byAdmin: Boolean = false)
@Serializable internal data class PrivacyPutDto(val private: Boolean)
@Serializable internal data class PeopleDto(val id: String, val name: String = "")
@Serializable internal data class ScheduleDto(
    val cadence: String = "binge", val day: Int = 4, val hour: Int = 19, val minute: Int = 0, val softLock: Boolean = false, val timeZone: String = "",
    val together: Boolean = false, val party: PartyTimeDto? = null, val locked: Boolean = false,
    val weekdays: List<Int> = emptyList(), val everyWeeks: Int = 1, val everyDays: Int = 2, val monthDay: Int = 1, val perRelease: Int = 1,
    val firstLocked: Boolean = false, val sequential: Boolean = false, val titleOrder: List<String> = emptyList(),
)
@Serializable internal data class ChangeDto(val id: String, val fromName: String = "", val proposed: ScheduleDto = ScheduleDto(), val yes: List<String> = emptyList(), val no: List<String> = emptyList(), val voted: Boolean = false, val at: String? = null)
@Serializable internal data class GroupDto(
    val id: String, val seriesId: String, val seriesName: String = "", val others: List<PeopleDto> = emptyList(), val since: String? = null,
    val schedule: ScheduleDto? = null, val pending: ChangeDto? = null, val lockedFromId: String? = null, val opensAt: String? = null, val opensDay: Int? = null,
    val togetherFromId: String? = null,
    val record: Boolean = false, val mine: List<String>? = null,
    val party: PartyDto? = null,
    val titles: List<TitleDto>? = null,
    val resume: List<ResumeDto> = emptyList(),
)
@Serializable internal data class ResumeDto(val itemId: String, val positionMs: Long = 0, val at: String? = null)
@Serializable internal data class ResumePutDto(val itemId: String, val positionMs: Long)
@Serializable internal data class TitleDto(val id: String, val name: String = "", val lockedFromId: String? = null, val opensAt: String? = null, val opensDay: Int? = null, val togetherFromId: String? = null, val reach: List<ReachDto> = emptyList())

@Serializable internal data class ReachDto(val name: String = "", val parts: Int = 0, val of: Int = 0)
@Serializable internal data class TitleRefDto(val id: String, val name: String = "")
@Serializable internal data class GroupMarkDto(val itemIds: List<String>, val watched: Boolean, val groupIds: List<String>? = null)
@Serializable internal data class GroupMarkOutDto(val groupIds: List<String> = emptyList())
@Serializable internal data class GroupSessionDto(val sessionId: String)
@Serializable internal data class IncomingDto(val id: String, val fromId: String, val fromName: String = "", val seriesId: String, val seriesName: String = "", val groupId: String? = null, val willSee: List<String> = emptyList(), val at: String? = null, val schedule: ScheduleDto? = null, val titles: List<TitleRefDto>? = null)
@Serializable internal data class VoteDto(val yes: Boolean)
@Serializable internal data class OutgoingDto(val id: String, val to: String = "", val seriesId: String, val seriesName: String = "", val groupId: String? = null, val at: String? = null, val titles: List<TitleRefDto>? = null)
@Serializable internal data class PartnersDto(val groups: List<GroupDto> = emptyList(), val incoming: List<IncomingDto> = emptyList(), val outgoing: List<OutgoingDto> = emptyList())
@Serializable internal data class AskDto(val toUserId: String?, val toName: String?, val seriesId: String, val seriesName: String, val groupId: String?, val schedule: ScheduleDto? = null, val titles: List<TitleRefDto>? = null, val batch: String? = null)
@Serializable internal data class ProgressDto(val groupId: String, val userId: String, val userName: String = "", val watched: List<String> = emptyList(), val last: String? = null, val nextId: String? = null)
@Serializable internal data class TogetherDto(val itemId: String, val groupId: String)
@Serializable internal data class TogetherOutDto(val markedFor: List<String> = emptyList())
@Serializable internal data class WatchedWithDto(val itemIds: List<String>, val present: List<String>)
@Serializable internal data class WatchedWithOutDto(val countedFor: List<String> = emptyList())
@Serializable internal data class InviteDto(val id: String, val fromUserId: String, val fromName: String = "", val groupId: String, val itemId: String? = null, val itemName: String? = null, val at: String? = null)
@Serializable internal data class PartyLaterDto(val minutes: Int)
@Serializable internal data class PartyCantDto(val cant: Boolean)
@Serializable internal data class PartySessionDto(val sessionId: String)
@Serializable internal class PartyEmptyDto
@Serializable internal data class InvitePostDto(val userIds: List<String>, val groupId: String, val itemId: String?, val itemName: String?)

/** The Companion plugin's watch partners and privacy (`/Nitrate/Partners`, `/Nitrate/Privacy`); outside the OpenAPI contract test. */
internal class JellyfinPartners(private val client: JellyfinClient) {

    suspend fun privacy(): Privacy = call { client.get("Nitrate", "Privacy") }.decode<PrivacyDto>().let { Privacy(it.private, it.byAdmin) }

    suspend fun setPrivacy(on: Boolean): Privacy = call {
        client.http.put(client.url("Nitrate", "Privacy")) { json(PrivacyPutDto(on)) }
    }.decode<PrivacyDto>().let { Privacy(it.private, it.byAdmin) }

    suspend fun people(): List<UserRef> = call { client.get("Nitrate", "Partners", "People") }.decode<List<PeopleDto>>().map { UserRef(normalizeId(it.id), it.name) }

    suspend fun list(): PartnersInfo = call { client.get("Nitrate", "Partners") }.decode<PartnersDto>().let { d ->
        PartnersInfo(
            d.groups.map { it.toModel() },
            d.incoming.map {
                IncomingRequest(
                    normalizeId(it.id), normalizeId(it.fromId), it.fromName, normalizeId(it.seriesId), it.seriesName, it.groupId?.let(::normalizeId), it.willSee, ms(it.at), it.schedule?.toModel(),
                    titles(it.titles, it.seriesId, it.seriesName),
                )
            },
            d.outgoing.map { it.toModel() },
        )
    }

    suspend fun ask(
        toUserId: String?, toName: String?, seriesId: String, seriesName: String, groupId: String?,
        schedule: dev.nitrate.core.partners.GroupSchedule? = null, titles: List<dev.nitrate.core.partners.GroupTitle>? = null, batch: String? = null,
    ): OutgoingRequest = call {
        client.http.post(client.url("Nitrate", "Partners", "Requests")) {
            json(AskDto(toUserId, toName?.trim(), seriesId, seriesName, groupId, schedule?.toDto(), titles?.map { TitleRefDto(it.id, it.name) }, batch))
        }
    }.decode<OutgoingDto>().toModel()

    /** Adds a title to the group (any member may). */
    suspend fun addTitle(groupId: String, titleId: String, name: String) {
        call { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Titles")) { json(TitleRefDto(titleId, name)) } }
    }

    /** Removes a title from the group (any member may; a group keeps at least one). */
    suspend fun removeTitle(groupId: String, titleId: String) {
        call { client.http.delete(client.url("Nitrate", "Partners", "Groups", groupId, "Titles", titleId)) { with(client) { auth() } } }
    }

    /** Marks [itemIds] watched (or not) for the user in each of [groupIds]' own records only; returns the groups marked. */
    suspend fun mark(groupIds: List<String>, itemIds: List<String>, watched: Boolean): List<String> =
        call { client.http.post(client.url("Nitrate", "Partners", "Mark")) { json(GroupMarkDto(itemIds, watched, groupIds)) } }
            .decode<GroupMarkOutDto>().groupIds.map(::normalizeId)

    /** Where the user stopped [itemId] watching it for group [groupId] (0: clear). */
    suspend fun resume(groupId: String, itemId: String, positionMs: Long) {
        call { client.http.put(client.url("Nitrate", "Partners", "Groups", groupId, "Resume")) { json(ResumePutDto(itemId, positionMs)) } }
    }

    /** Says live (SyncPlay) session [sessionId] was started for group [groupId]: what it plays to the end counts for the group. */
    suspend fun sessionFor(groupId: String, sessionId: String) {
        call { client.http.put(client.url("Nitrate", "Partners", "Groups", groupId, "Session")) { json(GroupSessionDto(sessionId)) } }
    }

    /** Asks the group to change its schedule (it takes effect once more than half agree). */
    suspend fun proposeSchedule(groupId: String, schedule: dev.nitrate.core.partners.GroupSchedule) {
        call { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Schedule")) { json(schedule.toDto()) } }
    }

    /** Says yes or no to the group's waiting schedule change. */
    suspend fun voteSchedule(groupId: String, changeId: String, yes: Boolean) {
        call { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Schedule", changeId, "Vote")) { json(VoteDto(yes)) } }
    }

    suspend fun accept(id: String): WatchGroup = call { client.http.post(client.url("Nitrate", "Partners", "Requests", id, "Accept")) { with(client) { auth() } } }.decode<GroupDto>().toModel()

    suspend fun decline(id: String) {
        call { client.http.post(client.url("Nitrate", "Partners", "Requests", id, "Decline")) { with(client) { auth() } } }
    }

    suspend fun cancel(id: String) {
        call { client.http.delete(client.url("Nitrate", "Partners", "Requests", id)) { with(client) { auth() } } }
    }

    suspend fun leave(groupId: String) {
        call { client.http.delete(client.url("Nitrate", "Partners", "Groups", groupId)) { with(client) { auth() } } }
    }

    suspend fun progress(seriesId: String): List<PartnerProgress> = try {
        client.get("Nitrate", "Partners", "Progress", query = mapOf("seriesId" to seriesId)).decode<List<ProgressDto>>()
            .map { PartnerProgress(normalizeId(it.groupId), normalizeId(it.userId), it.userName, it.watched.map(::normalizeId).toSet(), it.last, it.nextId?.let(::normalizeId)) }
    } catch (e: ClientRequestException) {
        // No Companion plugin (or an older one): nobody to keep in step with.
        if (e.response.status == HttpStatusCode.NotFound) emptyList() else throw e
    }

    suspend fun together(itemId: String, groupId: String): List<String> =
        call { client.http.post(client.url("Nitrate", "Partners", "Together")) { json(TogetherDto(itemId, groupId)) } }.decode<TogetherOutDto>().markedFor.map(::normalizeId)

    /** "We're in the same room": [itemIds] watched with group [groupId]'s members [present] (the user counts as there). */
    suspend fun watchedWith(groupId: String, itemIds: List<String>, present: List<String>): List<String> =
        call { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Watched")) { json(WatchedWithDto(itemIds, present)) } }
            .decode<WatchedWithOutDto>().countedFor.map(::normalizeId)

    /** Invites people to a watch-together group (`/Nitrate/Together/Invites`); always "sent". */
    suspend fun invite(groupId: String, userIds: List<String>, itemId: String?, itemName: String?) {
        call { client.http.post(client.url("Nitrate", "Together", "Invites")) { json(InvitePostDto(userIds, groupId, itemId, itemName)) } }
    }

    /** The signed-in user's open watch-together invites (none without a plugin that has them). */
    suspend fun invites(): List<dev.nitrate.core.partners.WatchInvite> = try {
        client.get("Nitrate", "Together", "Invites").decode<List<InviteDto>>().map {
            dev.nitrate.core.partners.WatchInvite(it.id, normalizeId(it.fromUserId), it.fromName, it.groupId, it.itemId?.let(::normalizeId), it.itemName, ms(it.at))
        }
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) emptyList() else throw e
    }

    suspend fun dismissInvite(id: String) {
        call { client.http.delete(client.url("Nitrate", "Together", "Invites", id)) { with(client) { auth() } } }
    }

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.json(body: T) {
        with(client) { auth() }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: ClientRequestException) {
        val why = runCatching { e.response.bodyAsText().trim().trim('"') }.getOrNull()
        error(why?.takeIf { it.isNotBlank() && !it.startsWith("{") } ?: "The server refused (${e.response.status.value}).")
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = JellyfinJson.decodeFromString(bodyAsText())

    private fun ms(iso: String?) = iso?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L

    private fun GroupDto.toModel() = WatchGroup(
        normalizeId(id), normalizeId(seriesId), seriesName, others.map { UserRef(normalizeId(it.id), it.name) }, ms(since),
        schedule?.toModel(),
        pending?.let { dev.nitrate.core.partners.ScheduleChange(it.id, it.fromName, it.proposed.toModel(), it.yes, it.no, it.voted, ms(it.at)) },
        lockedFromId?.let(::normalizeId), opensAt?.let(::ms), opensDay, togetherFromId?.let(::normalizeId),
        ownRecord = if (record) mine.orEmpty().map(::normalizeId).toSet() else null,
        party = party?.toModel(),
        resume = resume.associate { normalizeId(it.itemId) to dev.nitrate.core.partners.GroupResume(it.positionMs, ms(it.at)) },
        titleList = titles.orEmpty().map {
            dev.nitrate.core.partners.GroupTitle(normalizeId(it.id), it.name, it.lockedFromId?.let(::normalizeId), it.opensAt?.let(::ms), it.opensDay, it.togetherFromId?.let(::normalizeId), it.reach.map { r -> dev.nitrate.core.partners.MemberReach(r.name, r.parts, r.of) })
        },
    )

    private fun titles(dtos: List<TitleRefDto>?, seriesId: String, seriesName: String) =
        dtos?.takeIf { it.isNotEmpty() }?.map { dev.nitrate.core.partners.GroupTitle(normalizeId(it.id), it.name) }
            ?: listOf(dev.nitrate.core.partners.GroupTitle(normalizeId(seriesId), seriesName))

    private fun ScheduleDto.toModel() = toScheduleModel()

    private fun dev.nitrate.core.partners.GroupSchedule.toDto() =
        ScheduleDto(
            cadence, day, hour, minute, softLock, together = together, party = party?.let { PartyTimeDto(it.days, it.hour, it.minute) }, locked = locked,
            weekdays = weekdays, everyWeeks = everyWeeks, everyDays = everyDays, monthDay = monthDay, perRelease = perRelease,
            firstLocked = firstLocked, sequential = sequential, titleOrder = titleOrder,
        )

    // ---- Group Watch Sessions (Companion feature `party`; "party" in the API) ----

    /** The group's next (or current) watch party; null when it has none (or the server doesn't have them). */
    suspend fun party(groupId: String): dev.nitrate.core.partners.WatchParty? = try {
        val r = client.get("Nitrate", "Partners", "Groups", groupId, "Party")
        if (r.status == HttpStatusCode.NoContent) null else r.bodyAsText().takeIf { it.isNotBlank() && it != "null" }?.let { JellyfinJson.decodeFromString<PartyDto>(it).toModel() }
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) null else throw e
    }

    suspend fun partyLater(groupId: String, minutes: Int): dev.nitrate.core.partners.WatchParty? =
        partyCall { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Party", "Later")) { json(PartyLaterDto(minutes)) } }

    suspend fun partySkip(groupId: String): dev.nitrate.core.partners.WatchParty? =
        partyCall { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Party", "Skip")) { json(PartyEmptyDto()) } }

    suspend fun partyCantMake(groupId: String, cant: Boolean): dev.nitrate.core.partners.WatchParty? =
        partyCall { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Party", "CantMake")) { json(PartyCantDto(cant)) } }

    suspend fun partyVote(groupId: String, requestId: String, yes: Boolean): dev.nitrate.core.partners.WatchParty? =
        partyCall { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Party", "Requests", requestId, "Vote")) { json(VoteDto(yes)) } }

    suspend fun partyJoin(groupId: String): dev.nitrate.core.partners.PartyJoin =
        call { client.http.post(client.url("Nitrate", "Partners", "Groups", groupId, "Party", "Join")) { json(PartyEmptyDto()) } }
            .decode<PartyJoinDto>().let { dev.nitrate.core.partners.PartyJoin(it.start, it.sessionId, it.partId?.let(::normalizeId)) }

    suspend fun partySession(groupId: String, sessionId: String) {
        call { client.http.put(client.url("Nitrate", "Partners", "Groups", groupId, "Party", "Session")) { json(PartySessionDto(sessionId)) } }
    }

    private suspend fun partyCall(response: suspend () -> HttpResponse): dev.nitrate.core.partners.WatchParty? =
        call { response() }.bodyAsText().takeIf { it.isNotBlank() && it != "null" }?.let { JellyfinJson.decodeFromString<PartyDto>(it).toModel() }

    private fun OutgoingDto.toModel() = OutgoingRequest(normalizeId(id), to, normalizeId(seriesId), seriesName, groupId?.let(::normalizeId), ms(at), titles(titles, seriesId, seriesName))
}
