package dev.nitrate.core.jellyfin

import dev.nitrate.core.admin.AccountInfo
import dev.nitrate.core.admin.ActivityEntry
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.ItemImage
import dev.nitrate.core.admin.ItemMetadata
import dev.nitrate.core.admin.PersonCredit
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.admin.RemoteImage
import dev.nitrate.core.admin.RemoteMatch
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.ServerTask
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.admin.TaskState
import dev.nitrate.core.admin.UserAccount
import dev.nitrate.core.admin.UserPermissions
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Server administration for [JellyfinClient]. Item metadata and user policies are edited by
 * changing the server's own JSON and sending it back whole, so fields Nitrate doesn't know about
 * are kept as they were.
 */
internal class JellyfinAdmin(private val c: JellyfinClient) {
    private suspend fun json(vararg segments: String, query: Map<String, Any?> = emptyMap()): JsonElement =
        JellyfinJson.parseToJsonElement(c.get(*segments, query = query).bodyAsText())

    private suspend fun send(method: String, segments: Array<out String>, body: JsonElement? = null, query: Map<String, Any?> = emptyMap()): HttpResponse {
        val url = c.url(*segments, query = query)
        return with(c) {
            when (method) {
                "DELETE" -> http.delete(url) { auth() }
                else -> http.post(url) {
                    auth()
                    if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
                }
            }
        }
    }

    private suspend fun post(vararg segments: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap()) = send("POST", segments, body, query)
    private suspend fun delete(vararg segments: String) = send("DELETE", segments)

    // ---- Account ----

    suspend fun account(): AccountInfo {
        val me = json("Users", "Me").jsonObject
        val policy = me["Policy"]?.jsonObject
        return AccountInfo(
            id = normalizeId(me.str("Id").orEmpty()),
            name = me.str("Name").orEmpty(),
            isAdmin = policy?.bool("IsAdministrator") == true,
            canDelete = policy?.bool("EnableContentDeletion") == true || policy?.bool("IsAdministrator") == true,
            canManageCollections = policy?.bool("EnableCollectionManagement") == true || policy?.bool("IsAdministrator") == true,
        )
    }

    // ---- Metadata ----

    suspend fun metadata(itemId: String): ItemMetadata {
        val o = json("Items", itemId).jsonObject
        return ItemMetadata(
            id = normalizeId(o.str("Id") ?: itemId),
            name = o.str("Name").orEmpty(),
            originalTitle = o.str("OriginalTitle"),
            sortName = o.str("ForcedSortName") ?: o.str("SortName"),
            overview = o.str("Overview"),
            tagline = o["Taglines"]?.strings()?.firstOrNull(),
            productionYear = o.int("ProductionYear"),
            premiereDate = o.str("PremiereDate")?.take(10),
            endDate = o.str("EndDate")?.take(10),
            officialRating = o.str("OfficialRating"),
            communityRating = o.double("CommunityRating")?.toFloat(),
            criticRating = o.double("CriticRating")?.toFloat(),
            genres = o["Genres"]?.strings().orEmpty(),
            tags = o["Tags"]?.strings().orEmpty(),
            studios = (o["Studios"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("Name") },
            people = (o["People"] as? JsonArray).orEmpty().mapNotNull { p ->
                (p as? JsonObject)?.let { PersonCredit(it.str("Name") ?: return@let null, it.str("Role"), it.str("Type") ?: "Actor") }
            },
            indexNumber = o.int("IndexNumber"),
            parentIndexNumber = o.int("ParentIndexNumber"),
            airsBeforeSeason = o.int("AirsBeforeSeasonNumber"),
            airsBeforeEpisode = o.int("AirsBeforeEpisodeNumber"),
            airsAfterSeason = o.int("AirsAfterSeasonNumber"),
            providerIds = (o["ProviderIds"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap(),
            lockData = o.bool("LockData") == true,
            lockedFields = o["LockedFields"]?.strings().orEmpty(),
        )
    }

    suspend fun saveMetadata(m: ItemMetadata) {
        // Read-only parts the server doesn't take back (12.4 even fails to parse its own Trickplay).
        val current = JsonObject(json("Items", m.id).jsonObject - READ_ONLY_ITEM_FIELDS)
        val existingPeople = (current["People"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val updated = JsonObject(
            current + mapOf(
                "Name" to JsonPrimitive(m.name),
                "OriginalTitle" to m.originalTitle.json(),
                "ForcedSortName" to m.sortName.json(),
                "Overview" to m.overview.json(),
                "Taglines" to JsonArray(listOfNotNull(m.tagline?.takeIf { it.isNotBlank() }).map(::JsonPrimitive)),
                "ProductionYear" to (m.productionYear?.let(::JsonPrimitive) ?: JsonNull),
                "PremiereDate" to (m.premiereDate?.takeIf { it.isNotBlank() }?.let { JsonPrimitive("${it}T00:00:00.0000000Z") } ?: JsonNull),
                "EndDate" to (m.endDate?.takeIf { it.isNotBlank() }?.let { JsonPrimitive("${it}T00:00:00.0000000Z") } ?: JsonNull),
                "OfficialRating" to m.officialRating.json(),
                "CommunityRating" to (m.communityRating?.let(::JsonPrimitive) ?: JsonNull),
                "CriticRating" to (m.criticRating?.let(::JsonPrimitive) ?: JsonNull),
                "Genres" to JsonArray(m.genres.map(::JsonPrimitive)),
                "Tags" to JsonArray(m.tags.map(::JsonPrimitive)),
                "Studios" to JsonArray(m.studios.map { buildJsonObject { put("Name", it) } }),
                // Keep the server's person ids where the name is unchanged.
                "People" to JsonArray(m.people.map { p ->
                    val old = existingPeople.firstOrNull { it.str("Name") == p.name }
                    JsonObject((old ?: JsonObject(emptyMap())) + mapOf("Name" to JsonPrimitive(p.name), "Role" to p.role.json(), "Type" to JsonPrimitive(p.type)))
                }),
                "IndexNumber" to (m.indexNumber?.let(::JsonPrimitive) ?: JsonNull),
                "ParentIndexNumber" to (m.parentIndexNumber?.let(::JsonPrimitive) ?: JsonNull),
                "AirsBeforeSeasonNumber" to (m.airsBeforeSeason?.let(::JsonPrimitive) ?: JsonNull),
                "AirsBeforeEpisodeNumber" to (m.airsBeforeEpisode?.let(::JsonPrimitive) ?: JsonNull),
                "AirsAfterSeasonNumber" to (m.airsAfterSeason?.let(::JsonPrimitive) ?: JsonNull),
                "ProviderIds" to JsonObject(m.providerIds.mapValues { JsonPrimitive(it.value) }),
                "LockData" to JsonPrimitive(m.lockData),
                "LockedFields" to JsonArray(m.lockedFields.map(::JsonPrimitive)),
            ),
        )
        post("Items", m.id, body = updated)
    }

    // ---- Identify and refresh ----

    suspend fun remoteSearch(item: MediaItem, name: String, year: Int?): List<RemoteMatch> {
        val kind = when (item.kind) {
            ItemKind.MOVIE -> "Movie"
            ItemKind.SERIES -> "Series"
            ItemKind.BOX_SET -> "BoxSet"
            ItemKind.MUSIC_VIDEO -> "MusicVideo"
            ItemKind.PERSON -> "Person"
            else -> error("Only movies, shows, collections and music videos can be identified")
        }
        val body = buildJsonObject {
            put("SearchInfo", buildJsonObject {
                put("Name", name)
                year?.let { put("Year", it) }
                put("ProviderIds", JsonObject(emptyMap()))
            })
            put("ItemId", item.id.replace("-", ""))
            put("IncludeDisabledProviders", true)
        }
        val results = JellyfinJson.parseToJsonElement(post("Items", "RemoteSearch", kind, body = body).bodyAsText()).jsonArray
        return results.mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            RemoteMatch(
                name = o.str("Name").orEmpty(),
                year = o.int("ProductionYear"),
                overview = o.str("Overview"),
                imageUrl = o.str("ImageUrl"),
                providerName = o.str("SearchProviderName"),
                providerIds = (o["ProviderIds"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
                raw = o.toString(),
            )
        }
    }

    suspend fun applyMatch(itemId: String, match: RemoteMatch, replaceImages: Boolean) {
        post("Items", "RemoteSearch", "Apply", itemId, body = JellyfinJson.parseToJsonElement(match.raw), query = mapOf("replaceAllImages" to replaceImages))
    }

    suspend fun refreshItem(itemId: String, mode: RefreshMode, replaceImages: Boolean, regenerateTrickplay: Boolean) {
        val full = if (mode == RefreshMode.NEW_FILES) "Default" else "FullRefresh"
        post(
            "Items", itemId, "Refresh",
            query = mapOf(
                "metadataRefreshMode" to full,
                "imageRefreshMode" to if (replaceImages) "FullRefresh" else full,
                "replaceAllMetadata" to (mode == RefreshMode.REPLACE_ALL),
                "replaceAllImages" to replaceImages,
                "regenerateTrickplay" to regenerateTrickplay,
            ),
        )
    }

    // ---- Images ----

    suspend fun itemImages(itemId: String): List<ItemImage> = json("Items", itemId, "Images").jsonArray.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val type = ImageType.entries.firstOrNull { it.wire == o.str("ImageType") } ?: return@mapNotNull null
        val index = o.int("ImageIndex")
        ItemImage(type, index, o.int("Width"), o.int("Height"), c.image(itemId, type.wire, o.str("ImageTag").orEmpty(), index.takeIf { type == ImageType.BACKDROP }, maxWidth = 800))
    }

    suspend fun remoteImages(itemId: String, type: ImageType): List<RemoteImage> =
        (json("Items", itemId, "RemoteImages", query = mapOf("type" to type.wire, "includeAllLanguages" to true, "limit" to 80)).jsonObject["Images"] as? JsonArray).orEmpty()
            .mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                RemoteImage(o.str("Url") ?: return@mapNotNull null, o.str("ThumbnailUrl"), o.str("ProviderName"), o.int("Width"), o.int("Height"), o.str("Language"), o.double("CommunityRating"))
            }

    suspend fun downloadRemoteImage(itemId: String, type: ImageType, url: String) {
        post("Items", itemId, "RemoteImages", "Download", query = mapOf("type" to type.wire, "imageUrl" to url))
    }

    suspend fun deleteImage(itemId: String, type: ImageType, index: Int?) {
        if (index != null) delete("Items", itemId, "Images", type.wire, index.toString()) else delete("Items", itemId, "Images", type.wire)
    }

    /** Jellyfin takes uploaded images as base64 text with the image's own content type. */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun uploadImage(itemId: String, type: ImageType, bytes: ByteArray, mimeType: String) {
        val url = c.url("Items", itemId, "Images", type.wire)
        with(c) {
            http.post(url) {
                auth()
                setBody(TextContent(Base64.encode(bytes), ContentType.parse(mimeType)))
            }
        }
    }

    // ---- Server ----

    suspend fun scanAll() {
        post("Library", "Refresh")
    }

    suspend fun tasks(): List<ServerTask> = json("ScheduledTasks", query = mapOf("isHidden" to false)).jsonArray.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val last = o["LastExecutionResult"] as? JsonObject
        ServerTask(
            id = o.str("Id") ?: return@mapNotNull null,
            name = o.str("Name").orEmpty(),
            category = o.str("Category"),
            description = o.str("Description"),
            state = when (o.str("State")) {
                "Running" -> TaskState.RUNNING
                "Cancelling" -> TaskState.CANCELLING
                else -> TaskState.IDLE
            },
            progress = o.double("CurrentProgressPercentage"),
            lastResult = last?.str("Status"),
            lastError = last?.str("ErrorMessage"),
            lastRunEnded = last?.str("EndTimeUtc"),
        )
    }.sortedWith(compareBy({ it.category }, { it.name }))

    suspend fun startTask(id: String) {
        post("ScheduledTasks", "Running", id)
    }

    /** Stops a running task; one that finished just before is fine (the server would say 500). */
    suspend fun stopTask(id: String) {
        try {
            delete("ScheduledTasks", "Running", id)
        } catch (e: io.ktor.client.plugins.ServerResponseException) {
            if (tasks().firstOrNull { it.id == id }?.state == TaskState.RUNNING) throw e
        }
    }

    suspend fun activity(limit: Int): List<ActivityEntry> =
        (json("System", "ActivityLog", "Entries", query = mapOf("startIndex" to 0, "limit" to limit)).jsonObject["Items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            ActivityEntry(o.long("Id") ?: 0, o.str("Name").orEmpty(), o.str("ShortOverview") ?: o.str("Overview"), o.str("Type"), o.str("Date").orEmpty(), o.str("Severity"))
        }

    suspend fun sessions(): List<ServerSession> = json("Sessions", query = mapOf("activeWithinSeconds" to 960)).jsonArray.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val play = o["PlayState"] as? JsonObject
        ServerSession(
            id = o.str("Id") ?: return@mapNotNull null,
            userName = o.str("UserName"),
            client = o.str("Client"),
            deviceName = o.str("DeviceName"),
            deviceId = o.str("DeviceId"),
            nowPlaying = (o["NowPlayingItem"] as? JsonObject)?.let { runCatching { JellyfinJson.decodeFromJsonElement(ItemDto.serializer(), it).toMediaItem() }.getOrNull() },
            positionMs = play?.long("PositionTicks")?.ticksToMs(),
            isPaused = play?.bool("IsPaused") == true,
            playMethod = play?.str("PlayMethod"),
            remoteAddress = o.str("RemoteEndPoint"),
            lastActivity = o.str("LastActivityDate"),
            supportsRemoteControl = o.bool("SupportsRemoteControl") == true,
            supportsMediaControl = o.bool("SupportsMediaControl") == true,
        )
    }

    suspend fun sendMessage(sessionId: String, header: String, text: String) {
        post("Sessions", sessionId, "Message", body = buildJsonObject {
            put("Header", header)
            put("Text", text)
            put("TimeoutMs", 10_000)
        })
    }

    suspend fun sessionCommand(sessionId: String, command: SessionCommand) {
        post("Sessions", sessionId, "Playing", command.wire)
    }

    // ---- Users ----

    suspend fun users(): List<UserAccount> = json("Users").jsonArray.mapNotNull { (it as? JsonObject)?.toAccount() }

    private fun JsonObject.toAccount(): UserAccount? {
        val p = this["Policy"] as? JsonObject ?: JsonObject(emptyMap())
        return UserAccount(
            id = normalizeId(str("Id") ?: return null),
            name = str("Name").orEmpty(),
            hasPassword = bool("HasPassword") != false,
            lastLogin = str("LastLoginDate"),
            lastActivity = str("LastActivityDate"),
            permissions = UserPermissions(
                isAdministrator = p.bool("IsAdministrator") == true,
                isDisabled = p.bool("IsDisabled") == true,
                isHidden = p.bool("IsHidden") == true,
                enableRemoteAccess = p.bool("EnableRemoteAccess") != false,
                enableMediaPlayback = p.bool("EnableMediaPlayback") != false,
                enableVideoTranscoding = p.bool("EnableVideoPlaybackTranscoding") != false,
                enableAudioTranscoding = p.bool("EnableAudioPlaybackTranscoding") != false,
                enableRemuxing = p.bool("EnablePlaybackRemuxing") != false,
                enableContentDownloading = p.bool("EnableContentDownloading") != false,
                enableContentDeletion = p.bool("EnableContentDeletion") == true,
                enableLiveTvAccess = p.bool("EnableLiveTvAccess") != false,
                enableCollectionManagement = p.bool("EnableCollectionManagement") == true,
                enableSubtitleManagement = p.bool("EnableSubtitleManagement") == true,
                enableRemoteControlOfOtherUsers = p.bool("EnableRemoteControlOfOtherUsers") == true,
                enableAllFolders = p.bool("EnableAllFolders") != false,
            ),
        )
    }

    suspend fun setPermissions(userId: String, perms: UserPermissions) {
        val policy = json("Users", userId).jsonObject["Policy"] as? JsonObject ?: JsonObject(emptyMap())
        val updated = JsonObject(
            policy + mapOf(
                "IsAdministrator" to JsonPrimitive(perms.isAdministrator),
                "IsDisabled" to JsonPrimitive(perms.isDisabled),
                "IsHidden" to JsonPrimitive(perms.isHidden),
                "EnableRemoteAccess" to JsonPrimitive(perms.enableRemoteAccess),
                "EnableMediaPlayback" to JsonPrimitive(perms.enableMediaPlayback),
                "EnableVideoPlaybackTranscoding" to JsonPrimitive(perms.enableVideoTranscoding),
                "EnableAudioPlaybackTranscoding" to JsonPrimitive(perms.enableAudioTranscoding),
                "EnablePlaybackRemuxing" to JsonPrimitive(perms.enableRemuxing),
                "EnableContentDownloading" to JsonPrimitive(perms.enableContentDownloading),
                "EnableContentDeletion" to JsonPrimitive(perms.enableContentDeletion),
                "EnableLiveTvAccess" to JsonPrimitive(perms.enableLiveTvAccess),
                "EnableCollectionManagement" to JsonPrimitive(perms.enableCollectionManagement),
                "EnableSubtitleManagement" to JsonPrimitive(perms.enableSubtitleManagement),
                "EnableRemoteControlOfOtherUsers" to JsonPrimitive(perms.enableRemoteControlOfOtherUsers),
                "EnableAllFolders" to JsonPrimitive(perms.enableAllFolders),
            ),
        )
        post("Users", userId, "Policy", body = updated)
    }

    suspend fun createUser(name: String, password: String): UserAccount {
        val created = post("Users", "New", body = buildJsonObject {
            put("Name", name)
            put("Password", password)
        })
        return JellyfinJson.parseToJsonElement(created.bodyAsText()).jsonObject.toAccount() ?: error("Server returned no user")
    }

    suspend fun deleteUser(userId: String) {
        delete("Users", userId)
    }

    suspend fun setPassword(userId: String, newPassword: String) {
        post("Users", "Password", body = buildJsonObject {
            put("NewPw", newPassword)
            put("ResetPassword", false)
        }, query = mapOf("userId" to userId))
    }
}

private val READ_ONLY_ITEM_FIELDS = setOf("Trickplay", "MediaSources", "MediaStreams", "Chapters", "UserData", "ImageBlurHashes", "MediaSegments")

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
private fun JsonElement.strings(): List<String> = (this as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun String?.json(): JsonElement = this?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull
private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
