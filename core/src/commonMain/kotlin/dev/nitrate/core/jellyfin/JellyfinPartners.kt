package dev.nitrate.core.jellyfin

import dev.nitrate.core.partners.IncomingRequest
import dev.nitrate.core.partners.OutgoingRequest
import dev.nitrate.core.partners.PartnerProgress
import dev.nitrate.core.partners.PartnerShow
import dev.nitrate.core.partners.PartnersInfo
import dev.nitrate.core.partners.Privacy
import dev.nitrate.core.partners.WatchPartner
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
@Serializable internal data class SharedShowDto(val seriesId: String, val name: String = "")
@Serializable internal data class PartnerViewDto(val id: String, val userId: String, val userName: String = "", val shows: List<SharedShowDto> = emptyList(), val since: String? = null)
@Serializable internal data class IncomingDto(val id: String, val fromId: String, val fromName: String = "", val seriesId: String, val seriesName: String = "", val at: String? = null)
@Serializable internal data class OutgoingDto(val id: String, val to: String = "", val seriesId: String, val seriesName: String = "", val at: String? = null)
@Serializable internal data class PartnersDto(val partners: List<PartnerViewDto> = emptyList(), val incoming: List<IncomingDto> = emptyList(), val outgoing: List<OutgoingDto> = emptyList())
@Serializable internal data class AskDto(val toUserId: String?, val toName: String?, val seriesId: String, val seriesName: String)
@Serializable internal data class ShowPostDto(val seriesId: String, val name: String)
@Serializable internal data class ProgressDto(val userId: String, val userName: String = "", val watched: List<String> = emptyList(), val last: String? = null, val nextId: String? = null)
@Serializable internal data class TogetherDto(val itemId: String, val partnerIds: List<String>)
@Serializable internal data class TogetherOutDto(val markedFor: List<String> = emptyList())

/** The Companion plugin's watch partners and privacy (`/Nitrate/Partners`, `/Nitrate/Privacy`); outside the OpenAPI contract test. */
internal class JellyfinPartners(private val client: JellyfinClient) {

    suspend fun privacy(): Privacy = call { client.get("Nitrate", "Privacy") }.decode<PrivacyDto>().let { Privacy(it.private, it.byAdmin) }

    suspend fun setPrivacy(on: Boolean): Privacy = call {
        client.http.put(client.url("Nitrate", "Privacy")) { json(PrivacyPutDto(on)) }
    }.decode<PrivacyDto>().let { Privacy(it.private, it.byAdmin) }

    suspend fun people(): List<UserRef> = call { client.get("Nitrate", "Partners", "People") }.decode<List<PeopleDto>>().map { UserRef(normalizeId(it.id), it.name) }

    suspend fun list(): PartnersInfo = call { client.get("Nitrate", "Partners") }.decode<PartnersDto>().let { d ->
        PartnersInfo(
            d.partners.map { it.toModel() },
            d.incoming.map { IncomingRequest(normalizeId(it.id), normalizeId(it.fromId), it.fromName, normalizeId(it.seriesId), it.seriesName, ms(it.at)) },
            d.outgoing.map { it.toModel() },
        )
    }

    suspend fun ask(toUserId: String?, toName: String?, seriesId: String, seriesName: String): OutgoingRequest = call {
        client.http.post(client.url("Nitrate", "Partners", "Requests")) { json(AskDto(toUserId, toName?.trim(), seriesId, seriesName)) }
    }.decode<OutgoingDto>().toModel()

    suspend fun accept(id: String): WatchPartner = call { client.http.post(client.url("Nitrate", "Partners", "Requests", id, "Accept")) { with(client) { auth() } } }.decode<PartnerViewDto>().toModel()

    suspend fun decline(id: String) {
        call { client.http.post(client.url("Nitrate", "Partners", "Requests", id, "Decline")) { with(client) { auth() } } }
    }

    suspend fun cancel(id: String) {
        call { client.http.delete(client.url("Nitrate", "Partners", "Requests", id)) { with(client) { auth() } } }
    }

    suspend fun addShow(id: String, seriesId: String, name: String): WatchPartner =
        call { client.http.post(client.url("Nitrate", "Partners", id, "Shows")) { json(ShowPostDto(seriesId, name)) } }.decode<PartnerViewDto>().toModel()

    suspend fun removeShow(id: String, seriesId: String): WatchPartner =
        call { client.http.delete(client.url("Nitrate", "Partners", id, "Shows", seriesId)) { with(client) { auth() } } }.decode<PartnerViewDto>().toModel()

    suspend fun end(id: String) {
        call { client.http.delete(client.url("Nitrate", "Partners", id)) { with(client) { auth() } } }
    }

    suspend fun progress(seriesId: String): List<PartnerProgress> = try {
        client.get("Nitrate", "Partners", "Progress", query = mapOf("seriesId" to seriesId)).decode<List<ProgressDto>>()
            .map { PartnerProgress(normalizeId(it.userId), it.userName, it.watched.map(::normalizeId).toSet(), it.last, it.nextId?.let(::normalizeId)) }
    } catch (e: ClientRequestException) {
        // No Companion plugin (or an older one): nobody to keep in step with.
        if (e.response.status == HttpStatusCode.NotFound) emptyList() else throw e
    }

    suspend fun together(itemId: String, partnerIds: List<String>): List<String> =
        call { client.http.post(client.url("Nitrate", "Partners", "Together")) { json(TogetherDto(itemId, partnerIds)) } }.decode<TogetherOutDto>().markedFor.map(::normalizeId)

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

    private fun PartnerViewDto.toModel() = WatchPartner(normalizeId(id), normalizeId(userId), userName, shows.map { PartnerShow(normalizeId(it.seriesId), it.name) }, ms(since))

    private fun OutgoingDto.toModel() = OutgoingRequest(normalizeId(id), to, normalizeId(seriesId), seriesName, ms(at))
}
