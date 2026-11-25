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
    val noteId: String? = null,
    val itemId: String? = null,
)

@Serializable
internal data class ChatPageDto(val messages: List<ChatMessageDto> = emptyList(), val latest: Long = 0, val brought: Long = 0)

@Serializable
internal data class ChatPostDto(val text: String, val kind: String, val positionMs: Long?)

@Serializable
internal data class ComicPageDto(val name: String = "", val width: Int = 0, val height: Int = 0, val size: Long = 0)

@Serializable
internal data class ComicListingDto(val format: String = "", val pages: List<ComicPageDto> = emptyList(), val comicInfo: String? = null)

@Serializable
internal data class ComicInfoOutDto(val comicInfo: String = "")

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
@kotlinx.serialization.Serializable internal data class CreditSkipDto(val creditsStartMs: Long, val sceneStartMs: Long, val afterCredits: Boolean, val spoken: Boolean = false)
@kotlinx.serialization.Serializable internal data class CreditsDto(val skips: List<CreditSkipDto> = emptyList())
@kotlinx.serialization.Serializable internal data class VetoedStretchDto(val startMs: Long, val endMs: Long, val why: String = "")
@kotlinx.serialization.Serializable internal data class CreditReportDto(
    val runtimeMs: Long = 0,
    val startMs: Long = 0,
    val skips: List<CreditSkipDto> = emptyList(),
    val outroStartMs: Long? = null,
    val outroRejected: Boolean = false,
    val dialogue: List<List<Long>>? = null,
    val vetoed: List<VetoedStretchDto> = emptyList(),
    val rows: List<kotlinx.serialization.json.JsonArray> = emptyList(),
    val reading: Boolean = false,
    val note: String? = null,
)

internal class JellyfinCompanion(private val client: JellyfinClient) {

    suspend fun updates(): dev.nitrate.core.companion.CompanionUpdates =
        JellyfinJson.decodeFromString<UpdateStatusDto>(client.get("Nitrate", "Updates").bodyAsText()).toModel()

    suspend fun checkUpdates(): dev.nitrate.core.companion.CompanionUpdates =
        JellyfinJson.decodeFromString<UpdateStatusDto>(
            client.http.post(client.url("Nitrate", "Updates", "Check")) { with(client) { auth() } }.bodyAsText(),
        ).toModel()

    suspend fun restartForUpdate() {
        try {
            client.http.post(client.url("Nitrate", "Updates", "Restart")) { with(client) { auth() } }
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            // 409: nothing waiting, or something playing; the plugin says which.
            if (e.response.status.value != 409) throw e
            throw dev.nitrate.core.companion.RestartRefused(e.response.bodyAsText().trim().trim('"').ifBlank { "The server wasn't restarted." })
        }
    }

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
            .skips.map { dev.nitrate.core.playback.CreditSkip(it.creditsStartMs, it.sceneStartMs, it.afterCredits, it.spoken) }
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) null else throw e
    }

    /** The admins' Credits check (feature `creditsreport`); `reading` while the server reads the title (202). */
    suspend fun creditsReport(itemId: String): dev.nitrate.core.playback.CreditsReport {
        val text = client.get("Nitrate", "Credits", itemId, "Report").bodyAsText()
        val dto = JellyfinJson.decodeFromString<CreditReportDto>(text)
        return dev.nitrate.core.playback.CreditsReport(
            runtimeMs = dto.runtimeMs,
            startMs = dto.startMs,
            skips = dto.skips.map { dev.nitrate.core.playback.CreditSkip(it.creditsStartMs, it.sceneStartMs, it.afterCredits, it.spoken) },
            outroStartMs = dto.outroStartMs,
            outroRejected = dto.outroRejected,
            dialogueLines = dto.dialogue?.size,
            vetoed = dto.vetoed.map { dev.nitrate.core.playback.VetoedStretch(it.startMs, it.endMs, it.why) },
            rows = dto.rows.size,
            reading = dto.reading,
            note = dto.note,
            json = text,
        )
    }

    /** Files whose timing the plugin found wrong; empty without the feature or for non-admins. */
    suspend fun fileTiming(): List<dev.nitrate.core.diagnostics.FileTiming> = try {
        JellyfinJson.decodeFromString<List<TimingDto>>(client.get("Nitrate", "Timing").bodyAsText())
            .map { dev.nitrate.core.diagnostics.FileTiming(normalizeId(it.itemId), it.issues.map { i -> dev.nitrate.core.diagnostics.TimingIssue(i.kind, i.atMs, i.amountMs, i.text) }) }
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound || e.response.status == HttpStatusCode.Forbidden) emptyList() else throw e
    }

    /** The book's pages as the plugin serves them; null when it can't (no plugin, not a file it reads). */
    suspend fun comicListing(itemId: String): dev.nitrate.core.comics.ComicListing? = try {
        val dto: ComicListingDto = JellyfinJson.decodeFromString(client.get("Nitrate", "Comics", itemId).bodyAsText())
        dev.nitrate.core.comics.ComicListing(dto.format, dto.pages.map { dev.nitrate.core.comics.ComicPageInfo(it.name, it.width, it.height, it.size) }, dto.comicInfo)
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound || e.response.status.value == 422) null else throw e
    }

    /** Changes a CBZ's ComicInfo (admins); the new ComicInfo.xml. */
    suspend fun saveComicInfo(itemId: String, changes: Map<String, String?>): String {
        val response = try {
            client.http.put(client.url("Nitrate", "Comics", itemId, "Info")) {
                with(client) { auth() }
                contentType(ContentType.Application.Json)
                setBody(kotlinx.serialization.json.JsonObject(changes.mapValues { (_, v) -> kotlinx.serialization.json.JsonPrimitive(v) }).toString())
            }
        } catch (e: ClientRequestException) {
            val why = runCatching { JellyfinJson.decodeFromString<ProblemDto>(e.response.bodyAsText()).detail }.getOrNull()
            throw IllegalStateException(why ?: "The server didn't accept the change (${e.response.status.value}).")
        }
        return JellyfinJson.decodeFromString<ComicInfoOutDto>(response.bodyAsText()).comicInfo
    }

    fun comicPageUrl(itemId: String, index: Int) = client.url("Nitrate", "Comics", itemId, "Pages", index.toString())

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
        return ChatPage(dto.messages.map { it.toModel() }, dto.latest, dto.brought)
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

    /** Pins a note on a title's timeline (feature `notes`): refused when not in a watch group on it, or the message is turned down. */
    internal suspend fun pinNote(post: NotePostDto): NoteDto {
        val response = try {
            client.http.post(client.url("Nitrate", "Notes")) {
                with(client) { auth() }
                contentType(ContentType.Application.Json)
                setBody(post)
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
        return JellyfinJson.decodeFromString<NoteDto>(response.bodyAsText())
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
        noteId = noteId?.let(::normalizeId),
        itemId = itemId?.let(::normalizeId),
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

@Serializable
internal data class CatalogInfoDto(val id: String = "", val name: String = "", val url: String = "", val kind: String = "OPDS")

@Serializable
internal data class CatalogPositionDto(val page: Int? = null)

/** The comic catalogs the admin set up in the Companion plugin, reached through it (`/Nitrate/Catalogs`). */
internal class JellyfinCatalogs(private val client: JellyfinClient) {
    suspend fun list(): List<dev.nitrate.core.catalogs.ComicCatalog> =
        JellyfinJson.decodeFromString<List<CatalogInfoDto>>(client.get("Nitrate", "Catalogs").bodyAsText()).mapNotNull { d ->
            if (d.id.isBlank() || d.url.isBlank()) return@mapNotNull null
            dev.nitrate.core.catalogs.ComicCatalog(
                d.id, d.name, d.url,
                if (d.kind.equals("CLU", ignoreCase = true)) dev.nitrate.core.catalogs.CatalogKind.CLU else dev.nitrate.core.catalogs.CatalogKind.OPDS,
            )
        }

    fun link(catalog: dev.nitrate.core.catalogs.ComicCatalog): dev.nitrate.core.catalogs.CatalogLink = object : dev.nitrate.core.catalogs.CatalogLink {
        override val catalog = catalog
        override val authorization get() = client.authorizationHeader()
        private val id = catalog.id

        override suspend fun text(url: String): String = try {
            client.get("Nitrate", "Catalogs", id, "Fetch", query = mapOf("url" to url)).bodyAsText()
        } catch (e: ClientRequestException) {
            throw dev.nitrate.core.catalogs.CatalogException(problem(e) ?: "The catalog answered ${e.response.status.value}.")
        } catch (e: io.ktor.client.plugins.ServerResponseException) {
            throw dev.nitrate.core.catalogs.CatalogException(runCatching { JellyfinJson.decodeFromString<ProblemDto>(e.response.bodyAsText()).detail }.getOrNull() ?: "The catalog couldn't be reached.")
        }

        override fun fetchUrl(url: String) = client.url("Nitrate", "Catalogs", id, "Fetch", query = mapOf("url" to url))

        override suspend fun book(url: String): dev.nitrate.core.comics.ComicListing? = try {
            val dto: ComicListingDto = JellyfinJson.decodeFromString(client.get("Nitrate", "Catalogs", id, "Book", query = mapOf("url" to url)).bodyAsText())
            dev.nitrate.core.comics.ComicListing(dto.format, dto.pages.map { dev.nitrate.core.comics.ComicPageInfo(it.name, it.width, it.height, it.size) }, dto.comicInfo)
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 422) null else throw dev.nitrate.core.catalogs.CatalogException(problem(e) ?: "The server couldn't fetch this book (${e.response.status.value}).")
        } catch (e: io.ktor.client.plugins.ServerResponseException) {
            throw dev.nitrate.core.catalogs.CatalogException(runCatching { JellyfinJson.decodeFromString<ProblemDto>(e.response.bodyAsText()).detail }.getOrNull() ?: "The catalog couldn't be reached.")
        }

        override fun pageUrl(url: String, index: Int) = client.url("Nitrate", "Catalogs", id, "Book", "Pages", index.toString(), query = mapOf("url" to url))

        override fun fileUrl(url: String) = client.url("Nitrate", "Catalogs", id, "Book", "File", query = mapOf("url" to url))

        override suspend fun position(bookId: String): Int? =
            JellyfinJson.decodeFromString<CatalogPositionDto>(client.get("Nitrate", "Catalogs", id, "Position", query = mapOf("book" to bookId)).bodyAsText()).page

        override suspend fun savePosition(bookId: String, page: Int) {
            client.http.put(client.url("Nitrate", "Catalogs", id, "Position", query = mapOf("book" to bookId))) {
                with(client) { auth() }
                contentType(ContentType.Application.Json)
                setBody("""{"Page":$page}""")
            }
        }

        private suspend fun problem(e: ClientRequestException) = runCatching { JellyfinJson.decodeFromString<ProblemDto>(e.response.bodyAsText()).detail }.getOrNull()
    }
}

@kotlinx.serialization.Serializable
internal data class TimingIssueDto(val kind: String, val atMs: Long? = null, val amountMs: Long? = null, val text: String = "")

@kotlinx.serialization.Serializable
internal data class TimingDto(val itemId: String, val issues: List<TimingIssueDto> = emptyList())

@kotlinx.serialization.Serializable
internal data class UpdateStatusDto(
    val running: String = "?",
    val waiting: String? = null,
    val restartNeeded: Boolean = false,
    val playing: Int = 0,
    val lastCheckedAt: String? = null,
    val lastCheck: String? = null,
    val autoUpdate: Boolean = true,
) {
    fun toModel() = dev.nitrate.core.companion.CompanionUpdates(
        running, waiting, restartNeeded, playing,
        lastCheckedAt?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
        lastCheck, autoUpdate,
    )
}
