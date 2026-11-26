package dev.nitrate.core.jellyfin

import dev.nitrate.core.partners.GroupWatchSession
import dev.nitrate.core.partners.SessionJoin
import dev.nitrate.core.partners.SessionMember
import dev.nitrate.core.partners.SessionNews
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// The Companion's Group Watch Sessions (feature `sessions`, `/Nitrate/Sessions`), and their news on
// the "Nitrate.Party" messages (kind `session`). Outside the OpenAPI contract test.

@Serializable internal data class SessionMemberDto(
    val id: String, val name: String = "", val me: Boolean = false, val positionMs: Long = 0, val paused: Boolean = false,
    val leader: Boolean = false, val together: Boolean = true,
)
@Serializable internal data class SessionDto(
    val id: String, val groupId: String, val itemId: String, val partLabel: String? = null, val title: String = "",
    val startedBy: String = "", val startedByMe: Boolean = false, val state: String = GroupWatchSession.WAITING,
    val startAt: String? = null, val startMs: Long = 0, val positionMs: Long = 0, val paused: Boolean = false,
    val gatherUntil: String? = null, val members: List<SessionMemberDto> = emptyList(), val joined: Boolean = false, val serverTime: String? = null,
) {
    fun toModel() = GroupWatchSession(
        normalizeId(id), normalizeId(groupId), normalizeId(itemId), partLabel, title, startedBy, startedByMe, state,
        startAt?.let(::sessionMs), startMs, positionMs, paused, gatherUntil?.let(::sessionMs) ?: 0L,
        members.map { SessionMember(normalizeId(it.id), it.name, it.me, it.positionMs, it.paused, it.leader, it.together) },
        joined, serverTime?.let(::sessionMs) ?: 0L,
    )
}
@Serializable internal data class SessionJoinDto(val session: SessionDto, val startMs: Long = 0, val together: Boolean = true)
@Serializable internal data class SessionPostDto(val groupId: String, val itemId: String, val startMs: Long = 0, val gatherMinutes: Int = 0)
@Serializable internal data class SessionJoinPostDto(val fromStart: Boolean = false)
@Serializable internal data class SessionReportDto(val positionMs: Long, val paused: Boolean = false, val event: String? = null)
@Serializable internal class SessionEmptyDto
@Serializable internal data class SessionNewsDto(
    val kind: String, val groupId: String, val seriesName: String = "", val others: List<String> = emptyList(),
    val byName: String? = null, val byMe: Boolean = false, val event: String = "", val positionMs: Long = 0, val session: SessionDto? = null,
)

private fun sessionMs(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)

/** A "Nitrate.Party" message's session news (null when it's party or schedule news, or not a message at all). */
internal fun decodeSessionNews(text: String): SessionNews? = runCatching {
    val kind = JellyfinJson.parseToJsonElement(text).let { (it as? kotlinx.serialization.json.JsonObject)?.get("Kind") as? kotlinx.serialization.json.JsonPrimitive }?.content
    if (kind != SessionNews.KIND) null
    else JellyfinJson.decodeFromString(SessionNewsDto.serializer(), text).let { d ->
        val s = d.session ?: return@let null
        SessionNews(d.event, normalizeId(d.groupId), d.seriesName, d.others, d.byName, d.byMe, d.positionMs, s.toModel())
    }
}.getOrNull()

/** The Companion's Group Watch Sessions (`/Nitrate/Sessions`); every answer carries the server's clock. */
internal class JellyfinSessions(private val client: JellyfinClient) {
    suspend fun mine(): List<GroupWatchSession> = call { client.get("Nitrate", "Sessions") }.decode<List<SessionDto>>().map { it.toModel() }

    suspend fun start(groupId: String, itemId: String, startMs: Long, gatherMinutes: Int = 0): SessionJoin =
        call { client.http.post(client.url("Nitrate", "Sessions")) { json(SessionPostDto(groupId, itemId, startMs, gatherMinutes)) } }.decode<SessionJoinDto>().toModel()

    suspend fun get(id: String): GroupWatchSession = call { client.get("Nitrate", "Sessions", id) }.decode<SessionDto>().toModel()

    suspend fun join(id: String, fromStart: Boolean): SessionJoin =
        call { client.http.post(client.url("Nitrate", "Sessions", id, "Join")) { json(SessionJoinPostDto(fromStart)) } }.decode<SessionJoinDto>().toModel()

    suspend fun startNow(id: String): GroupWatchSession =
        call { client.http.post(client.url("Nitrate", "Sessions", id, "Start")) { json(SessionEmptyDto()) } }.decode<SessionDto>().toModel()

    suspend fun report(id: String, positionMs: Long, paused: Boolean, event: String?): GroupWatchSession =
        call { client.http.post(client.url("Nitrate", "Sessions", id, "Report")) { json(SessionReportDto(positionMs, paused, event)) } }.decode<SessionDto>().toModel()

    suspend fun leave(id: String) {
        call { client.http.post(client.url("Nitrate", "Sessions", id, "Leave")) { json(SessionEmptyDto()) } }
    }

    suspend fun end(id: String) {
        call { client.http.post(client.url("Nitrate", "Sessions", id, "End")) { json(SessionEmptyDto()) } }
    }

    private fun SessionJoinDto.toModel() = SessionJoin(session.toModel(), startMs, together)

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
}
