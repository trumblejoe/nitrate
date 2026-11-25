package dev.nitrate.core.jellyfin

import dev.nitrate.core.diary.DiaryEntry
import dev.nitrate.core.lines.SpokenLine
import dev.nitrate.core.model.ItemKind
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

@Serializable internal data class DiaryEntryDto(
    val id: String,
    val itemId: String,
    val kind: String = "Movie",
    val name: String = "",
    val seriesId: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val runtimeMs: Long? = null,
    val genres: List<String> = emptyList(),
    val watchedAt: Long = 0,
    val rating: Int? = null,
    val note: String? = null,
    val logged: Boolean = false,
    val groupIds: List<String>? = null,
)
@Serializable internal data class LinesStatusDto(val titles: Int = 0, val lines: Long = 0, val enabled: Boolean = true)
@Serializable internal data class DiaryPostDto(val itemId: String, val watchedAt: Long?, val rating: Int?, val note: String?)
@Serializable internal data class DiaryEditDto(val rating: Int?, val note: String?, val watchedAt: Long?)
@Serializable internal data class SpokenLineDto(
    val itemId: String,
    val kind: String = "Movie",
    val name: String = "",
    val seriesId: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val startMs: Long = 0,
    val endMs: Long = 0,
    val text: String = "",
    val language: String? = null,
    val before: String? = null,
    val after: String? = null,
)

/** The Companion plugin's watch diary (`/Nitrate/Diary`) and spoken-line search (`/Nitrate/Lines`); outside the OpenAPI contract test. */
internal class JellyfinDiary(private val client: JellyfinClient) {
    suspend fun diary(fromMs: Long?, toMs: Long?): List<DiaryEntry> =
        call { client.get("Nitrate", "Diary", query = listOfNotNull(fromMs?.let { "from" to "$it" }, toMs?.let { "to" to "$it" }).toMap()) }
            .decode<List<DiaryEntryDto>>().map { it.toModel() }

    suspend fun log(itemId: String, watchedAtMs: Long?, rating: Int?, note: String?): DiaryEntry =
        call { client.http.post(client.url("Nitrate", "Diary")) { json(DiaryPostDto(itemId, watchedAtMs, rating, note)) } }.decode<DiaryEntryDto>().toModel()

    suspend fun edit(id: String, rating: Int?, note: String?, watchedAtMs: Long?): DiaryEntry =
        call { client.http.put(client.url("Nitrate", "Diary", id)) { json(DiaryEditDto(rating, note, watchedAtMs)) } }.decode<DiaryEntryDto>().toModel()

    suspend fun delete(id: String) {
        call { client.http.delete(client.url("Nitrate", "Diary", id)) { with(client) { auth() } } }
    }

    suspend fun searchLines(query: String, limit: Int): List<SpokenLine> = try {
        client.get("Nitrate", "Lines", query = mapOf("query" to query, "limit" to "$limit")).decode<List<SpokenLineDto>>().map { d ->
            SpokenLine(
                normalizeId(d.itemId), kind(d.kind), d.name, d.seriesId?.let(::normalizeId), d.seriesName, d.season, d.episode, d.year,
                d.startMs, d.endMs, d.text, d.language, d.before, d.after,
            )
        }
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) emptyList() else throw e
    }

    /** Titles and lines spoken-line search has indexed (null: not there). */
    suspend fun linesStatus(): Pair<Int, Long>? = try {
        client.get("Nitrate", "Lines", "Status").decode<LinesStatusDto>().takeIf { it.enabled }?.let { it.titles to it.lines }
    } catch (e: ClientRequestException) {
        if (e.response.status == HttpStatusCode.NotFound) null else throw e
    }

    private fun DiaryEntryDto.toModel() = DiaryEntry(
        normalizeId(id), normalizeId(itemId), kind(kind), name, seriesId?.let(::normalizeId), seriesName, season, episode, year, runtimeMs,
        genres, watchedAt, rating, note, logged, groupIds.orEmpty().map(::normalizeId),
    )

    private fun kind(k: String) = if (k.equals("Episode", ignoreCase = true)) ItemKind.EPISODE else ItemKind.MOVIE

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
