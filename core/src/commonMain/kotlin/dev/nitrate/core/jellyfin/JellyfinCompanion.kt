package dev.nitrate.core.jellyfin

import dev.nitrate.core.companion.ChatForbidden
import dev.nitrate.core.companion.ChatKind
import dev.nitrate.core.companion.ChatMessage
import dev.nitrate.core.companion.ChatPage
import dev.nitrate.core.companion.ChatRejected
import dev.nitrate.core.companion.CompanionInfo
import dev.nitrate.core.writing.NotScriptOwner
import dev.nitrate.core.writing.ScriptEdit
import dev.nitrate.core.writing.ScriptEditResult
import dev.nitrate.core.writing.ScriptEditsPage
import dev.nitrate.core.writing.ScriptPresence
import dev.nitrate.core.writing.ScriptTooFarBehind
import dev.nitrate.core.writing.ScriptUnavailable
import dev.nitrate.core.writing.SharedScriptDoc
import dev.nitrate.core.writing.SharedScriptInfo
import dev.nitrate.core.writing.TextOperation
import dev.nitrate.core.writing.UserRef
import dev.nitrate.core.writing.story.RecordChange
import dev.nitrate.core.writing.story.StoryRecord
import io.ktor.client.request.delete
import io.ktor.client.request.put
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

@Serializable
internal data class UserRefDto(val id: String, val name: String = "")

@Serializable
internal data class ScriptInfoDto(
    val id: String,
    val title: String = "Untitled",
    val owner: UserRefDto,
    val members: List<UserRefDto> = emptyList(),
    val version: Long = 0,
    val updatedAt: String? = null,
)

@Serializable
internal data class RecordOutDto(
    val id: String,
    val type: String,
    val fields: Map<String, String> = emptyMap(),
    val deleted: Boolean = false,
    val createdBy: String? = null,
    val createdByName: String? = null,
    val createdAt: String? = null,
    val updatedBy: String? = null,
    val updatedByName: String? = null,
    val updatedAt: String? = null,
)

@Serializable
internal data class RecordChangeDto(val id: String, val type: String, val set: Map<String, String?>? = null, val delete: Boolean = false)

@Serializable
internal data class ScriptDocDto(val info: ScriptInfoDto? = null, val text: String = "", val version: Long = 0, val records: List<RecordOutDto> = emptyList())

@Serializable
internal data class ScriptCreateDto(val text: String, val members: List<String>, val records: List<RecordChangeDto>)

@Serializable
internal data class RecordsPostDto(val editId: String, val baseVersion: Long, val records: List<RecordChangeDto>)

@Serializable
internal data class ScriptMembersDto(val members: List<String>)

@Serializable
internal data class EditPostDto(val editId: String, val baseVersion: Long, val ops: kotlinx.serialization.json.JsonArray, val cursor: Int?)

@Serializable
internal data class EditResultDto(val accepted: Boolean, val version: Long)

@Serializable
internal data class EditOutDto(
    val version: Long,
    val editId: String,
    val userId: String,
    val userName: String = "",
    val ops: kotlinx.serialization.json.JsonArray? = null,
    val records: List<RecordChangeDto>? = null,
    val at: String? = null,
    val sceneId: String? = null,
)

@Serializable
internal data class PresenceOutDto(val userId: String, val userName: String = "", val cursor: Int, val version: Long)

@Serializable
internal data class EditsOutDto(val edits: List<EditOutDto> = emptyList(), val version: Long, val presence: List<PresenceOutDto> = emptyList(), val stamp: Long = 0)

@Serializable
internal data class PresencePostDto(val cursor: Int, val version: Long)

@Serializable
internal data class SsoInfoDto(val enabled: Boolean = false, val name: String = "")

@Serializable
internal data class SsoStartDto(val deviceId: String, val deviceName: String, val app: String, val appVersion: String)

@Serializable
internal data class SsoStartedDto(val code: String, val secret: String, val url: String, val expiresInMs: Long = 600_000)

@Serializable
internal data class SsoPollPostDto(val code: String, val secret: String)

@Serializable
internal data class SsoPollDto(val state: String = "Failed", val message: String? = null, val accessToken: String? = null, val userId: String? = null, val userName: String? = null)

/**
 * Calls to the Nitrate Companion server plugin (companion/ in this repository): not Jellyfin's
 * own API, so it isn't in the OpenAPI contract test; its README lists the calls.
 */
@kotlinx.serialization.Serializable internal data class CreditSkipDto(val creditsStartMs: Long, val sceneStartMs: Long, val afterCredits: Boolean)
@kotlinx.serialization.Serializable internal data class CreditsDto(val skips: List<CreditSkipDto> = emptyList())

internal class JellyfinCompanion(private val client: JellyfinClient) {

    suspend fun sendDeviceSummary(deviceId: String, deviceName: String, summary: dev.nitrate.core.playback.DeviceSummary) {
        client.http.post(client.url("Nitrate", "Devices", "Report")) {
            with(client) { auth() }
            contentType(ContentType.Application.Json)
            setBody(DeviceReportDto(deviceId, deviceName, JellyfinJson.encodeToJsonElement(dev.nitrate.core.playback.DeviceSummary.serializer(), summary)))
        }
    }

    suspend fun householdDevices(): List<dev.nitrate.core.playback.HouseholdDevice> =
        JellyfinJson.decodeFromString<List<HouseholdReportDto>>(client.get("Nitrate", "Devices").bodyAsText()).map { r ->
            dev.nitrate.core.playback.HouseholdDevice(
                userId = normalizeId(r.userId), userName = r.userName, deviceId = r.deviceId, deviceName = r.deviceName, atMs = r.at,
                summary = runCatching { JellyfinJson.decodeFromJsonElement(dev.nitrate.core.playback.DeviceSummary.serializer(), r.summary) }.getOrNull(),
            )
        }

    suspend fun forgetHouseholdDevice(userId: String, deviceId: String) {
        client.http.delete(client.url("Nitrate", "Devices", userId, deviceId)) { with(client) { auth() } }
    }

    suspend fun checkClips(): List<dev.nitrate.core.playback.CheckClip> = try {
        JellyfinJson.decodeFromString(client.get("Nitrate", "TestClips").bodyAsText())
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) emptyList() else throw e
    }

    /** What the server found in [itemId]'s credits (feature `credits`); null when not checked yet (or no plugin). */
    suspend fun creditScenes(itemId: String): List<dev.nitrate.core.playback.CreditSkip>? = try {
        JellyfinJson.decodeFromString<CreditsDto>(client.get("Nitrate", "Credits", itemId).bodyAsText())
            .skips.map { dev.nitrate.core.playback.CreditSkip(it.creditsStartMs, it.sceneStartMs, it.afterCredits) }
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) null else throw e
    }

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

    // ---- Shared scripts ----

    suspend fun sharingUsers(name: String?): List<UserRef> =
        call { client.get("Nitrate", "Users", query = mapOf("name" to name)) }.decode<List<UserRefDto>>().map { it.toModel() }

    suspend fun sharedScripts(): List<SharedScriptInfo> =
        call { client.get("Nitrate", "Scripts") }.decode<List<ScriptInfoDto>>().map { it.toModel() }

    suspend fun createSharedScript(text: String, members: List<String>, records: List<RecordChange>): SharedScriptDoc =
        call { client.http.post(client.url("Nitrate", "Scripts")) { json(ScriptCreateDto(text, members, records.map { it.toDto() })) } }.decode<ScriptDocDto>().toModel()

    suspend fun sendScriptRecords(id: String, editId: String, baseVersion: Long, records: List<RecordChange>): ScriptEditResult {
        val dto = call { client.http.post(client.url("Nitrate", "Scripts", id, "Edits")) { json(RecordsPostDto(editId, baseVersion, records.map { it.toDto() })) } }.decode<EditResultDto>()
        return ScriptEditResult(dto.accepted, dto.version)
    }

    suspend fun scriptLog(id: String, from: Long, limit: Int): List<ScriptEdit> =
        call { client.get("Nitrate", "Scripts", id, "Log", query = mapOf("from" to from, "limit" to limit)) }.decode<List<EditOutDto>>().map { it.toModel() }

    suspend fun scriptAt(id: String, version: Long): SharedScriptDoc {
        val dto = call { client.get("Nitrate", "Scripts", id, "At", query = mapOf("version" to version)) }.decode<ScriptDocDto>()
        return SharedScriptDoc(SharedScriptInfo(id, "", UserRef("", ""), emptyList(), dto.version, 0), dto.text, dto.version, dto.records.map { it.toModel() })
    }

    suspend fun sharedScript(id: String): SharedScriptDoc =
        call { client.get("Nitrate", "Scripts", id) }.decode<ScriptDocDto>().toModel()

    suspend fun setScriptMembers(id: String, members: List<String>): SharedScriptInfo =
        call { client.http.put(client.url("Nitrate", "Scripts", id, "Members")) { json(ScriptMembersDto(members)) } }.decode<ScriptInfoDto>().toModel()

    suspend fun leaveSharedScript(id: String) {
        call { client.http.post(client.url("Nitrate", "Scripts", id, "Leave")) { with(client) { auth() } } }
    }

    suspend fun deleteSharedScript(id: String) {
        call { client.http.delete(client.url("Nitrate", "Scripts", id)) { with(client) { auth() } } }
    }

    suspend fun sendScriptEdit(id: String, editId: String, baseVersion: Long, op: TextOperation, cursor: Int?): ScriptEditResult {
        val body = EditPostDto(editId, baseVersion, opsToJson(op), cursor)
        val dto = call { client.http.post(client.url("Nitrate", "Scripts", id, "Edits")) { json(body) } }.decode<EditResultDto>()
        return ScriptEditResult(dto.accepted, dto.version)
    }

    suspend fun scriptEdits(id: String, after: Long, seen: Long?, waitMs: Int): ScriptEditsPage {
        val dto = call {
            client.http.get(client.url("Nitrate", "Scripts", id, "Edits", query = mapOf("after" to after, "seen" to seen, "waitMs" to waitMs))) {
                with(client) { auth() }
                timeout {
                    requestTimeoutMillis = waitMs + 15_000L
                    socketTimeoutMillis = waitMs + 15_000L
                }
            }
        }.decode<EditsOutDto>()
        return ScriptEditsPage(
            edits = dto.edits.map { it.toModel() },
            version = dto.version,
            presence = dto.presence.map { ScriptPresence(normalizeId(it.userId), it.userName, it.cursor, it.version) },
            stamp = dto.stamp,
        )
    }

    suspend fun sendScriptPresence(id: String, cursor: Int, version: Long) {
        call { client.http.post(client.url("Nitrate", "Scripts", id, "Presence")) { json(PresencePostDto(cursor, version)) } }
    }

    private inline fun <reified T> io.ktor.client.request.HttpRequestBuilder.json(body: T) {
        with(client) { auth() }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.decode(): T = JellyfinJson.decodeFromString(bodyAsText())

    /** Turns the plugin's refusals into what they mean. */
    private suspend fun call(block: suspend () -> io.ktor.client.statement.HttpResponse): io.ktor.client.statement.HttpResponse = try {
        block()
    } catch (e: ClientRequestException) {
        val why = runCatching { JellyfinJson.decodeFromString<ProblemDto>(e.response.bodyAsText()).detail }.getOrNull()
        throw when (e.response.status) {
            HttpStatusCode.NotFound -> ScriptUnavailable()
            HttpStatusCode.Forbidden -> NotScriptOwner(why ?: "Only the script's owner can do that.")
            HttpStatusCode.Gone -> ScriptTooFarBehind()
            HttpStatusCode.BadRequest -> IllegalArgumentException(why ?: "The server refused that change.")
            else -> e
        }
    }

    private fun opsToJson(op: TextOperation) = kotlinx.serialization.json.JsonArray(
        TextOperation.toWire(op).map { if (it is String) kotlinx.serialization.json.JsonPrimitive(it) else kotlinx.serialization.json.JsonPrimitive(it as Int) },
    )

    private fun opsFromJson(ops: kotlinx.serialization.json.JsonArray) = TextOperation.fromWire(
        ops.map { e ->
            val p = e as kotlinx.serialization.json.JsonPrimitive
            if (p.isString) p.content else p.content.toInt()
        },
    )

    private fun UserRefDto.toModel() = UserRef(normalizeId(id), name)

    private fun ScriptInfoDto.toModel() = SharedScriptInfo(
        id = normalizeId(id), title = title, owner = owner.toModel(), members = members.map { it.toModel() }, version = version,
        updatedAtMs = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L,
    )

    private fun ScriptDocDto.toModel() = SharedScriptDoc(info!!.toModel(), text, version, records.map { it.toModel() })

    private fun RecordChange.toDto() = RecordChangeDto(id, type, set.takeIf { it.isNotEmpty() }, delete)

    private fun RecordChangeDto.toModel() = RecordChange(id, type, set.orEmpty(), delete)

    private fun ms(iso: String?) = iso?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L

    private fun RecordOutDto.toModel() = StoryRecord(
        id = id, type = type, fields = fields, deleted = deleted,
        updatedBy = updatedBy?.let(::normalizeId), updatedByName = updatedByName, updatedAt = ms(updatedAt),
        createdBy = createdBy?.let(::normalizeId), createdAt = ms(createdAt),
    )

    private fun EditOutDto.toModel() = ScriptEdit(
        version = version, editId = editId, userId = normalizeId(userId), userName = userName,
        op = ops?.let(::opsFromJson), records = records.orEmpty().map { it.toModel() }, atMs = ms(at), sceneId = sceneId,
    )

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

@Serializable
internal data class DeviceReportDto(val deviceId: String, val deviceName: String, val summary: kotlinx.serialization.json.JsonElement)

@Serializable
internal data class HouseholdReportDto(
    val userId: String,
    val userName: String = "",
    val deviceId: String,
    val deviceName: String = "",
    val at: Long = 0,
    val summary: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonObject(emptyMap()),
)
