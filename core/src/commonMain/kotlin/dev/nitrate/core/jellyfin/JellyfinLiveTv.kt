package dev.nitrate.core.jellyfin

import dev.nitrate.core.livetv.LiveChannel
import dev.nitrate.core.livetv.TvProgram
import dev.nitrate.core.model.MediaItem
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Live TV for [JellyfinClient]: channels with what's on, the guide, recordings and scheduling. */
@OptIn(ExperimentalTime::class)
internal class JellyfinLiveTv(private val c: JellyfinClient) {
    private suspend fun json(vararg segments: String, query: Map<String, Any?> = emptyMap()) =
        JellyfinJson.parseToJsonElement(c.get(*segments, query = query).bodyAsText())

    suspend fun available(): Boolean = runCatching {
        (json("LiveTv", "Info").jsonObject["IsEnabled"] as? JsonPrimitive)?.booleanOrNull == true
    }.getOrDefault(false)

    suspend fun channels(): List<LiveChannel> {
        val result = json(
            "LiveTv", "Channels",
            query = mapOf("userId" to c.userId, "addCurrentProgram" to true, "enableImages" to true, "sortBy" to "SortName", "enableFavoriteSorting" to true),
        ).jsonObject
        return items(result).map { o ->
            val dto = JellyfinJson.decodeFromJsonElement(ItemDto.serializer(), o)
            LiveChannel(
                item = dto.toMediaItem(),
                number = o.s("ChannelNumber") ?: o.s("Number"),
                now = (o["CurrentProgram"] as? JsonObject)?.let(::program),
            )
        }
    }

    suspend fun guide(channelIds: List<String>, fromMs: Long, toMs: Long): List<TvProgram> {
        if (channelIds.isEmpty()) return emptyList()
        val result = json(
            "LiveTv", "Programs",
            query = mapOf(
                "userId" to c.userId,
                "channelIds" to channelIds.joinToString(",") { it.replace("-", "") },
                "minEndDate" to Instant.fromEpochMilliseconds(fromMs).toString(),
                "maxStartDate" to Instant.fromEpochMilliseconds(toMs).toString(),
                "sortBy" to "StartDate",
                "enableImages" to false,
            ),
        ).jsonObject
        return items(result).mapNotNull(::program)
    }

    suspend fun recordings(): List<MediaItem> =
        items(json("LiveTv", "Recordings", query = mapOf("userId" to c.userId, "enableImages" to true)).jsonObject)
            .map { JellyfinJson.decodeFromJsonElement(ItemDto.serializer(), it).toMediaItem() }

    /** Schedules [programId] with the server's default timer settings (padding, keep-until...). */
    suspend fun record(programId: String) {
        val defaults = c.get("LiveTv", "Timers", "Defaults", query = mapOf("programId" to programId)).bodyAsText()
        with(c) { http.post(url("LiveTv", "Timers")) { auth(); setBody(TextContent(defaults, ContentType.Application.Json)) } }
    }

    suspend fun cancelRecording(timerId: String) {
        with(c) { http.delete(url("LiveTv", "Timers", timerId)) { auth() } }
    }

    private fun items(result: JsonObject): List<JsonObject> = (result["Items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun program(o: JsonObject): TvProgram? {
        val start = o.s("StartDate")?.let(::parseDateMs) ?: return null
        val end = o.s("EndDate")?.let(::parseDateMs) ?: return null
        return TvProgram(
            id = normalizeId(o.s("Id") ?: return null),
            channelId = normalizeId(o.s("ChannelId").orEmpty()),
            title = o.s("Name").orEmpty(),
            startMs = start,
            endMs = end,
            episodeTitle = o.s("EpisodeTitle"),
            overview = o.s("Overview"),
            timerId = o.s("TimerId")?.takeIf { it.isNotBlank() },
            isMovie = o.b("IsMovie"),
            isSeries = o.b("IsSeries"),
            isNews = o.b("IsNews"),
            isKids = o.b("IsKids"),
            isSports = o.b("IsSports"),
        )
    }

    private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.b(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonArray?.orEmpty() = this ?: emptyList()
}
