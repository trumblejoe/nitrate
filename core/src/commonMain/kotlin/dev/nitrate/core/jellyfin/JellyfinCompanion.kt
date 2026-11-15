package dev.nitrate.core.jellyfin

import dev.nitrate.core.companion.ChatForbidden
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.ChatMessage
import dev.nitrate.core.companion.ChatPage
import dev.nitrate.core.companion.ChatRejected
import dev.nitrate.core.companion.CompanionInfo
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
internal data class CompanionInfoDto(val version: String = "0", val features: List<String> = emptyList())

@Serializable
internal data class ChatMessageDto(
    val seq: Long,
    val userId: String,
    val userName: String = "",
    val kind: String = "Text",
    val text: String = "",
    val positionMs: Long? = null,
    val sentAt: String? = null,
)

@Serializable
internal data class ChatPageDto(val messages: List<ChatMessageDto> = emptyList(), val latest: Long = 0)

@Serializable
internal data class ChatPostDto(val text: String, val kind: String, val positionMs: Long?)

@Serializable
internal data class ProblemDto(val detail: String? = null)

/**
 * Calls to the Nitrate Companion server plugin (companion/ in this repository): not Jellyfin's
 * own API, so it isn't in the OpenAPI contract test; its README lists the calls.
 */
internal class JellyfinCompanion(private val client: JellyfinClient) {

    suspend fun info(): CompanionInfo? = try {
        val dto: CompanionInfoDto = JellyfinJson.decodeFromString(client.get("Nitrate", "Companion").bodyAsText())
        CompanionInfo(dto.version, dto.features.toSet())
    } catch (e: ClientRequestException) {
        // No plugin: the server doesn't know the path.
        if (e.response.status == HttpStatusCode.NotFound) null else throw e
    }

    suspend fun messages(groupId: String, after: Long, waitMs: Int): ChatPage {
        val response = try {
            client.http.get(client.url("Nitrate", "Chat", groupId, "Messages", query = mapOf("after" to after, "waitMs" to waitMs))) {
                with(client) { auth() }
                // Long polling: the server holds the request up to waitMs.
                timeout {
                    requestTimeoutMillis = waitMs + 15_000L
                    socketTimeoutMillis = waitMs + 15_000L
                }
            }
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.Forbidden || e.response.status == HttpStatusCode.NotFound) throw ChatForbidden()
            throw e
        }
        val dto: ChatPageDto = JellyfinJson.decodeFromString(response.bodyAsText())
        return ChatPage(dto.messages.map { it.toModel() }, dto.latest)
    }

    suspend fun send(groupId: String, kind: ChatKind, text: String, positionMs: Long?): ChatMessage {
        val response = try {
            client.http.post(client.url("Nitrate", "Chat", groupId, "Messages")) {
                with(client) { auth() }
                contentType(ContentType.Application.Json)
                setBody(ChatPostDto(text, if (kind == ChatKind.REACTION) "Reaction" else "Text", positionMs))
            }
        } catch (e: ClientRequestException) {
            when (e.response.status) {
                HttpStatusCode.Forbidden, HttpStatusCode.NotFound -> throw ChatForbidden()
                HttpStatusCode.BadRequest, HttpStatusCode.TooManyRequests -> {
                    val why = runCatching { JellyfinJson.decodeFromString<ProblemDto>(e.response.bodyAsText()).detail }.getOrNull()
                    throw ChatRejected(why ?: "The server didn't take that message.", tooFast = e.response.status == HttpStatusCode.TooManyRequests)
                }
                else -> throw e
            }
        }
        return JellyfinJson.decodeFromString<ChatMessageDto>(response.bodyAsText()).toModel()
    }

    private fun ChatMessageDto.toModel() = ChatMessage(
        seq = seq,
        userId = normalizeId(userId),
        userName = userName,
        kind = if (kind.equals("Reaction", ignoreCase = true)) ChatKind.REACTION else ChatKind.TEXT,
        text = text,
        positionMs = positionMs,
        sentAtMs = sentAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L,
    )
}
