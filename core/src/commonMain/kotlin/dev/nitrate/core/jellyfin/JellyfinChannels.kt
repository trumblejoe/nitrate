package dev.nitrate.core.jellyfin

import dev.nitrate.core.channels.ChannelBlock
import dev.nitrate.core.channels.ChannelGuide
import dev.nitrate.core.channels.ChannelMix
import dev.nitrate.core.channels.ChannelProgram
import dev.nitrate.core.channels.ChannelSource
import dev.nitrate.core.channels.HouseChannel
import dev.nitrate.core.channels.SourceKind
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
internal data class ChannelSourceDto(val kind: String, val id: String, val name: String = "", val shuffle: Boolean = false)

@Serializable
internal data class ChannelBlockDto(
    val name: String = "",
    val days: List<Int> = emptyList(),
    val startMinute: Int = 0,
    val endMinute: Int = 0,
    val sources: List<ChannelSourceDto> = emptyList(),
    val mix: String = "Rotate",
)

@Serializable
internal data class ChannelDto(
    val id: String = "",
    val name: String = "",
    val number: Int = 0,
    val sources: List<ChannelSourceDto> = emptyList(),
    val mix: String = "Rotate",
    val alignMinutes: Int = 0,
    val blocks: List<ChannelBlockDto> = emptyList(),
    val everyone: Boolean = true,
    val users: List<String> = emptyList(),
)

@Serializable
internal data class ChannelEditDto(
    val name: String,
    val number: Int,
    val sources: List<ChannelSourceDto>,
    val mix: String,
    val alignMinutes: Int,
    val blocks: List<ChannelBlockDto>,
    val everyone: Boolean,
    val users: List<String>,
)

@Serializable
internal data class GuideProgramDto(
    val itemId: String,
    val start: String,
    val end: String,
    val title: String = "",
    val subtitle: String? = null,
    val kind: String = "",
    val block: String? = null,
    val available: Boolean = true,
)

@Serializable
internal data class GuideChannelDto(val channel: ChannelDto, val programs: List<GuideProgramDto> = emptyList())

/** The Companion plugin's channels (`/Nitrate/Channels`); outside the OpenAPI contract test like its other calls. */
internal class JellyfinChannels(private val client: JellyfinClient) {

    suspend fun list(): List<HouseChannel> = call { client.get("Nitrate", "Channels") }.decode<List<ChannelDto>>().map { it.toModel() }

    suspend fun guide(fromMs: Long, toMs: Long): List<ChannelGuide> =
        call { client.get("Nitrate", "Channels", "Guide", query = mapOf("fromMs" to fromMs, "toMs" to toMs)) }
            .decode<List<GuideChannelDto>>().map { g -> ChannelGuide(g.channel.toModel(), g.programs.map { it.toModel() }) }

    suspend fun tune(id: String, count: Int): List<ChannelProgram> =
        call { client.get("Nitrate", "Channels", id, "Tune", query = mapOf("count" to count)) }.decode<List<GuideProgramDto>>().map { it.toModel() }

    suspend fun save(channel: HouseChannel, startOver: Boolean): HouseChannel {
        val segments = if (channel.id.isEmpty()) arrayOf("Nitrate", "Channels") else arrayOf("Nitrate", "Channels", channel.id)
        return call {
            client.http.post(client.url(*segments, query = mapOf("startOver" to startOver.takeIf { it }))) {
                with(client) { auth() }
                contentType(ContentType.Application.Json)
                setBody(channel.toEdit())
            }
        }.decode<ChannelDto>().toModel()
    }

    suspend fun delete(id: String) {
        call { client.http.delete(client.url("Nitrate", "Channels", id)) { with(client) { auth() } } }
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: ClientRequestException) {
        // The plugin says why in plain words (a 400/403/404 body).
        val why = runCatching { e.response.bodyAsText().trim().trim('"') }.getOrNull()
        error(why?.takeIf { it.isNotBlank() && !it.startsWith("{") } ?: "The server refused (${e.response.status.value}).")
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T = JellyfinJson.decodeFromString(bodyAsText())

    private fun ms(iso: String) = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrDefault(0L)

    private fun ChannelSourceDto.toModel() = ChannelSource(SourceKind.of(kind), normalizeId(id), name, shuffle)
    private fun ChannelSource.toDto() = ChannelSourceDto(kind.wire, id, name, shuffle)
    private fun ChannelBlockDto.toModel() = ChannelBlock(name, days, startMinute, endMinute, sources.map { it.toModel() }, ChannelMix.of(mix))
    private fun ChannelBlock.toDto() = ChannelBlockDto(name, days, startMinute, endMinute, sources.map { it.toDto() }, mix.wire)

    private fun ChannelDto.toModel() = HouseChannel(
        normalizeId(id), name, number, sources.map { it.toModel() }, ChannelMix.of(mix), alignMinutes,
        blocks.map { it.toModel() }, everyone, users.map(::normalizeId),
    )

    private fun HouseChannel.toEdit() = ChannelEditDto(name.trim(), number, sources.map { it.toDto() }, mix.wire, alignMinutes, blocks.map { it.toDto() }, everyone, users)

    private fun GuideProgramDto.toModel() = ChannelProgram(normalizeId(itemId), ms(start), ms(end), title, subtitle, kind, block, available)
}
