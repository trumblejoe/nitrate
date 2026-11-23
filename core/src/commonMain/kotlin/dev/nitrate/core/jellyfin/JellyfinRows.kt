package dev.nitrate.core.jellyfin

import dev.nitrate.core.jellyfin.JellyfinClient.Companion.BECAUSE_TTL_MS

import dev.nitrate.core.jellyfin.JellyfinClient.SavedRows

import dev.nitrate.core.jellyfin.JellyfinClient.Companion.LATEST_JOB

import dev.nitrate.core.jellyfin.JellyfinClient.Companion.ROW_FIELDS

import dev.nitrate.core.jellyfin.JellyfinClient.Companion.WIDE_DEFAULT

import dev.nitrate.core.jellyfin.JellyfinClient.Companion.NEXT_UP_QUERY

import dev.nitrate.core.jellyfin.JellyfinClient.Companion.ROW_LIMIT

import dev.nitrate.core.LibrarySort
import dev.nitrate.core.MediaServer
import dev.nitrate.core.model.CollectionKind
import dev.nitrate.core.model.DiscoveredServer
import dev.nitrate.core.model.ExternalSubtitle
import dev.nitrate.core.model.ImageKind
import dev.nitrate.core.model.ItemImages
import dev.nitrate.core.model.InvalidCredentialsException
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.MediaSegment
import dev.nitrate.core.model.MediaSource
import dev.nitrate.core.model.Person
import dev.nitrate.core.model.PlayMethod
import dev.nitrate.core.model.PlaybackPlan
import dev.nitrate.core.model.VersionOption
import dev.nitrate.core.model.QuickConnectRequest
import dev.nitrate.core.model.SavedAccount
import dev.nitrate.core.model.RemoteUserData
import dev.nitrate.core.model.SearchResults
import dev.nitrate.core.model.SegmentKind
import dev.nitrate.core.model.TrickplayInfo
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.ServerAddress
import dev.nitrate.core.model.ServerUser
import dev.nitrate.core.model.SessionState
import dev.nitrate.core.model.SavedServer
import dev.nitrate.core.pages.Codecs
import dev.nitrate.core.pages.StreamInfo
import dev.nitrate.core.pages.VideoStreamInfo
import kotlinx.coroutines.sync.withLock
import dev.nitrate.core.model.AddressCheck
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.model.SubtitleDelivery
import dev.nitrate.core.model.subtitleMimeType
import dev.nitrate.core.admin.AccountInfo
import dev.nitrate.core.admin.GeneralCommand
import dev.nitrate.core.events.ServerEvent
import dev.nitrate.core.playback.RemoteSubtitle
import dev.nitrate.core.admin.ActivityEntry
import dev.nitrate.core.admin.ImageType
import dev.nitrate.core.admin.ItemImage
import dev.nitrate.core.admin.ItemMetadata
import dev.nitrate.core.admin.RefreshMode
import dev.nitrate.core.admin.RemoteImage
import dev.nitrate.core.admin.RemoteMatch
import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.admin.ServerTask
import dev.nitrate.core.admin.SessionCommand
import dev.nitrate.core.admin.UserAccount
import dev.nitrate.core.admin.UserPermissions
import dev.nitrate.core.downloads.TranscodeSpec
import dev.nitrate.core.pages.ItemQuery
import dev.nitrate.core.pages.QuerySort
import dev.nitrate.core.pages.ResolutionFilter
import dev.nitrate.core.pages.WatchFilter
import dev.nitrate.core.playback.ChannelClip
import dev.nitrate.core.playback.ChannelTest
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.PlaybackSettingsRepository
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import dev.nitrate.core.rows.RowStyle
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import dev.nitrate.core.syncplay.ServerTimeSample
import dev.nitrate.core.syncplay.SyncPlayGroup
import dev.nitrate.core.syncplay.SyncPlayRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import io.ktor.client.call.save
import io.ktor.client.plugins.plugin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** "Because you watched" as the plugin made it overnight; null without the plugin (or if it fails). */
internal suspend fun JellyfinClient.companionBecause(): List<MediaRow>? {
    if (!companionHas("because")) return null
    return runCatching {
        get("Nitrate", "Because", query = mapOf("limit" to 16)).decode<List<BecauseBundleDto>>().toBecauseRows()
    }.getOrNull()
}

/**
 * Home's heaviest rows in one request to the plugin, kept as the answers Home's own requests
 * would get, so its row jobs (and app rows on other pages) find them already there. Nothing
 * happens without the plugin; a failure just leaves the jobs to ask as usual.
 */
internal suspend fun JellyfinClient.homeBundle(skip: Set<String>, combine: Boolean) {
    if (!companionHas("home")) return
    val wantsResume = combine || "home/continue_watching" !in skip
    val wantsNextUp = combine || "home/next_up" !in skip
    val wantsBecause = dev.nitrate.core.rows.BecauseYouWatched.slots().keys.any { it !in skip }
    val libraries = lastViews().orEmpty().filter { "home/latest/${it.id}" !in skip }.mapNotNull { v ->
        when (v.collection) {
            CollectionKind.MOVIES -> v to "Movie"
            CollectionKind.TV_SHOWS -> v to "Series"
            else -> null
        }
    }
    runCatching {
        val bundle = withQueryOriginHere("Home in one request") {
            get(
                "Nitrate", "Home",
                query = mapOf(
                    "resume" to wantsResume, "nextUp" to wantsNextUp, "because" to wantsBecause, "limit" to ROW_LIMIT,
                    "latest" to libraries.joinToString(",") { (v, kind) -> "${dashless(listOf(v.id))}:$kind" }.ifEmpty { null },
                ),
            ).decode<HomeBundleDto>()
        }
        bundle.resume?.let { keepList(arrayOf("UserItems", "Resume"), resumeQuery(null, null), it.map { d -> d.toMediaItem() }) }
        bundle.nextUp?.let { keepList(arrayOf("Shows", "NextUp"), NEXT_UP_QUERY, it.map { d -> d.toMediaItem() }) }
        bundle.latest.forEach { l ->
            val id = normalizeId(l.libraryId)
            val (view, kind) = libraries.firstOrNull { it.first.id == id } ?: return@forEach
            keepList(arrayOf("Items"), queryParams(view.id, kind, if (kind == "Movie") "DateCreated" else "DateLastContentAdded", "Descending", null, null), l.items.map { d -> d.toMediaItem() })
        }
        bundle.because?.takeIf { it.isNotEmpty() }?.let { answers.put(becauseKey(), dev.nitrate.core.cache.AnswerKind.SIMILAR, it.toBecauseRows() to false) }
    }
}

internal fun JellyfinClient.becauseKey() = "because|${accountKey ?: "$baseUrl|$userId"}"

/** What a row asked the server, so the row can be continued ([moreOfRow]). */
internal fun JellyfinClient.rowMore(vararg segments: String, query: Map<String, Any?>): dev.nitrate.core.rows.RowMore =
    dev.nitrate.core.rows.RowMore(segments.toList(), query.filterValues { it != null }.mapValues { it.value.toString() })

internal fun JellyfinClient.viewsKey() = "views/${accountKey ?: "$baseUrl|$userId"}"

/** The library list as last read (this run or a previous one), or null. */
internal suspend fun JellyfinClient.lastViews(): List<MediaItem>? {
    val key = viewsKey()
    knownViews?.takeIf { it.first == key }?.let { return it.second }
    return textCache?.get(key)?.let { runCatching { JellyfinJson.decodeFromString(ListSerializer(MediaItem.serializer()), it) }.getOrNull() }
}

/**
 * Home's rows as independent jobs, all started at once: Continue Watching, Next Up, "Latest
 * in…" each library and "Because you watched". The libraries themselves aren't a Home row:
 * each app lists them already (the phone's Libraries tab, the TV menu).
 */
internal fun JellyfinClient.homeJobs(fresh: Boolean = false, skip: Set<String> = emptySet(), combine: Boolean = false): List<dev.nitrate.core.rows.RowJob> {
    // With the Companion plugin, Home's heaviest rows come in one request first; each job then
    // finds its answer kept (or asks as usual when the plugin isn't there or didn't answer).
    val bundle = kotlinx.coroutines.sync.Mutex()
    var bundled = false
    val first: suspend () -> Unit = { bundle.withLock { if (!bundled) { bundled = true; homeBundle(skip, combine) } } }
    return homeJobList(fresh, skip, combine).map { it.after(first) }
}

/** How long Continue Watching waits for what's watched together before showing without it. */
internal const val TOGETHER_WAIT_MS = 4_000L

internal fun JellyfinClient.homeJobList(fresh: Boolean, skip: Set<String>, combine: Boolean): List<dev.nitrate.core.rows.RowJob> {
    suspend fun nextUp() = items("Shows", "NextUp", query = NEXT_UP_QUERY)
    // What's watched together goes into Continue Watching (Home has no row of its own for it), asked
    // once for both rows; a slow answer never holds Continue Watching back for long.
    val togetherLock = kotlinx.coroutines.sync.Mutex()
    var togetherKept: dev.nitrate.core.partners.TogetherCards? = null
    suspend fun together(): dev.nitrate.core.partners.TogetherCards = togetherLock.withLock {
        togetherKept ?: (kotlinx.coroutines.withTimeoutOrNull(TOGETHER_WAIT_MS) { runCatching { togetherCards() }.getOrNull() } ?: dev.nitrate.core.partners.TogetherCards())
            .also { togetherKept = it }
    }
    // Continue Watching and Next Up as one row, or the two rows.
    val watching = if (combine) listOf(
        dev.nitrate.core.rows.RowJob(dev.nitrate.core.rows.ContinueAndNext.TITLE, dev.nitrate.core.rows.ContinueAndNext.KEY) {
            coroutineScope {
                val resume = async { resumeItems(parentId = null, types = null) }
                val next = async { nextUp() }
                val shared = async { together() }
                dev.nitrate.core.rows.ContinueAndNext.withTogether(resume.await(), next.await(), shared.await()).let {
                    if (it.isEmpty()) emptyList() else listOf(MediaRow(dev.nitrate.core.rows.ContinueAndNext.KEY, dev.nitrate.core.rows.ContinueAndNext.TITLE, it, WIDE_DEFAULT))
                }
            }
        },
    ) else listOf(
        dev.nitrate.core.rows.RowJob("Continue Watching", "home/continue_watching") {
            coroutineScope {
                val shared = async { together() }
                dev.nitrate.core.rows.ContinueAndNext.continueWithTogether(resumeItems(parentId = null, types = null), shared.await())
            }.let { if (it.isEmpty()) emptyList() else listOf(MediaRow("home/continue_watching", "Continue Watching", it, WIDE_DEFAULT, resumeMore(null, null))) }
        },
        dev.nitrate.core.rows.RowJob("Next Up", "home/next_up") {
            coroutineScope {
                val shared = async { together() }
                dev.nitrate.core.rows.ContinueAndNext.nextUpWithout(nextUp(), shared.await())
            }.let { if (it.isEmpty()) emptyList() else listOf(MediaRow("home/next_up", "Next Up", it, WIDE_DEFAULT, rowMore("Shows", "NextUp", query = mapOf("limit" to ROW_LIMIT, "fields" to ROW_FIELDS)))) }
        },
    )
    return watching + listOf(
        dev.nitrate.core.rows.RowJob("Continue Listening", "home/continue_listening") {
            items(
                "UserItems", "Resume",
                query = mapOf("limit" to ROW_LIMIT, "fields" to ROW_FIELDS, "mediaTypes" to "Audio", "includeItemTypes" to "AudioBook"),
            ).let { if (it.isEmpty()) emptyList() else listOf(MediaRow("home/continue_listening", "Continue Listening", it, RowDisplay())) }
        },
        dev.nitrate.core.rows.RowJob("Continue Reading", "home/continue_reading") {
            continueReading(null).let { if (it.isEmpty()) emptyList() else listOf(MediaRow("home/continue_reading", "Continue Reading", it, RowDisplay())) }
        },
        dev.nitrate.core.rows.RowJob.showing(LATEST_JOB) { show ->
            coroutineScope {
                // A library's "Latest in…" row the page hides is never asked for.
                fun wanted(view: MediaItem) = view.collection in setOf(CollectionKind.MOVIES, CollectionKind.TV_SHOWS, CollectionKind.HOME_VIDEOS, null) &&
                    "home/latest/${view.id}" !in skip
                // Some servers take seconds to list libraries: show the rows for the list from
                // last time straight away, then add (or drop) whatever the fresh list changes.
                val started = mutableMapOf<String, kotlinx.coroutines.Deferred<List<MediaItem>>>()
                fun start(view: MediaItem) = started.getOrPut(view.id) { async { latestIn(view) } }
                suspend fun rowsFor(list: List<MediaItem>) = list.mapNotNull { view ->
                    start(view).await().takeIf { it.isNotEmpty() }?.let { MediaRow("home/latest/${view.id}", "Latest in ${view.name}", it, RowDisplay(), latestMore(view)) }
                }
                val known = lastViews()?.filter(::wanted)
                known?.forEach { start(it) }
                val fresh = async { try { views() } catch (e: Exception) { if (e is CancellationException || known == null) throw e; known } }
                if (known != null && !fresh.isCompleted) runCatching { show(rowsFor(known)) }
                val list = fresh.await().filter(::wanted)
                val ids = list.map { it.id }.toSet()
                started.filterKeys { it !in ids }.values.forEach { it.cancel() }
                rowsFor(list)
            }
        },
        dev.nitrate.core.rows.RowJob.making("Because you watched", dev.nitrate.core.rows.BecauseYouWatched.slots()) { becauseYouWatched(fresh) },
    )
}

/**
 * "Because you watched" rows. They're the slowest thing on Home for a large library (sorting
 * everything played by date, then asking for similar titles several times) and barely change
 * hour to hour, so they're remembered for [BECAUSE_TTL_MS], on the device, unless [fresh].
 */
/**
 * "Because you watched", worked out once however many pages show it at the same moment. Rows
 * kept from earlier get their cards' watched marks and progress read afresh (one small request,
 * itself shared and let go when watch state changes), so a day-old row never shows old ticks.
 */
internal suspend fun JellyfinClient.becauseYouWatched(fresh: Boolean): List<MediaRow> {
    val (rows, saved) = if (fresh) becauseYouWatchedNow(true)
    else answers.get(becauseKey(), dev.nitrate.core.cache.AnswerKind.SIMILAR) { becauseYouWatchedNow(false) }.first
    // None yet (the plugin is still making them after a restart): asked again next time, not kept.
    if (rows.isEmpty()) answers.forget { key, _ -> key == becauseKey() }
    return if (saved) runCatching { withFreshUserData(rows) }.getOrDefault(rows) else rows
}

/** [rows] with each card's watch state as the server has it now (nothing else asked for). */
internal suspend fun JellyfinClient.withFreshUserData(rows: List<MediaRow>): List<MediaRow> {
    val ids = rows.flatMap { r -> r.items.map { it.id } }.distinct()
    if (ids.isEmpty()) return rows
    val now = items(
        "Items",
        query = mapOf(
            "ids" to dashless(ids), "userId" to userId, "enableImages" to false,
            "enableTotalRecordCount" to false, "fields" to null,
        ),
    ).associateBy { it.id }
    return rows.map { r -> r.copy(items = r.items.map { item -> now[item.id]?.let { item.copy(user = it.user) } ?: item }) }
}

/** The rows, and whether they were kept from earlier (true) rather than just made. */
@OptIn(ExperimentalTime::class)
internal suspend fun JellyfinClient.becauseYouWatchedNow(fresh: Boolean): Pair<List<MediaRow>, Boolean> {
    // Made overnight by the plugin, with today's watch state: nothing costly asked here.
    companionBecause()?.let { return it to false }
    val key = "because/${accountKey ?: "$baseUrl|$userId"}"
    val now = Clock.System.now().toEpochMilliseconds()
    if (!fresh) {
        val known = savedBecause?.takeIf { savedBecauseKey == key }
            ?: textCache?.get(key)
                ?.let { runCatching { JellyfinJson.decodeFromString(SavedRows.serializer(), it) }.getOrNull() }
                ?.also { savedBecause = it; savedBecauseKey = key }
        if (known != null && now - known.savedAtMs in 0 until BECAUSE_TTL_MS) return known.rows to true
    }
    val rows = dev.nitrate.core.rows.BecauseYouWatched.rows(lastWatchedSeeds(), ::similar)
    val saved = SavedRows(now, rows)
    savedBecause = saved
    savedBecauseKey = key
    runCatching { textCache?.put(key, JellyfinJson.encodeToString(SavedRows.serializer(), saved)) }
    return rows to false
}

/**
 * What "Because you watched" grows from: the few films and episodes finished most recently, asked
 * for as lightly as the server allows (no count of everything played, no pictures, no extra fields).
 */
internal suspend fun JellyfinClient.lastWatchedSeeds(): List<MediaItem> = items(
    "Items",
    query = mapOf(
        "userId" to userId,
        "includeItemTypes" to "Movie,Episode",
        "recursive" to true,
        "filters" to "IsPlayed",
        "sortBy" to "DatePlayed",
        "sortOrder" to "Descending",
        "limit" to 8,
        "enableTotalRecordCount" to false,
        "enableImages" to false,
    ),
)

/**
 * What's new in a library, as a plain sorted lookup where possible: films by when they were
 * added, shows by when they last got an episode. Jellyfin's own "latest" groups new episodes
 * into their shows across the whole library, which is slow on large libraries.
 */
internal suspend fun JellyfinClient.latestIn(view: MediaItem): List<MediaItem> = when (view.collection) {
    CollectionKind.MOVIES -> query(view.id, "Movie", "DateCreated")
    CollectionKind.TV_SHOWS -> query(view.id, "Series", "DateLastContentAdded")
    else -> latest(view.id)
}

/** How to continue a library's "Latest in…" row (Jellyfin's own grouped latest can't be). */
internal fun JellyfinClient.latestMore(view: MediaItem): dev.nitrate.core.rows.RowMore? = when (view.collection) {
    CollectionKind.MOVIES -> queryMore(view.id, "Movie", "DateCreated")
    CollectionKind.TV_SHOWS -> queryMore(view.id, "Series", "DateLastContentAdded")
    else -> null
}

internal suspend fun JellyfinClient.latest(parentId: String): List<MediaItem> =
    get("Items", "Latest", query = mapOf("parentId" to parentId, "fields" to ROW_FIELDS, "limit" to ROW_LIMIT))
        .decode<List<ItemDto>>().map { it.toMediaItem() }

/** A library's overview rows as independent jobs, all started at once. */
@OptIn(ExperimentalTime::class)
internal fun JellyfinClient.libraryJobs(library: MediaItem): List<dev.nitrate.core.rows.RowJob> {
    val id = library.id
    val prefix = "library/$id"
    val now = Clock.System.now().toString()
    val jobs = mutableListOf<dev.nitrate.core.rows.RowJob>()
    fun add(key: String, title: String, display: RowDisplay = RowDisplay(), more: dev.nitrate.core.rows.RowMore? = null, load: suspend () -> List<MediaItem>) {
        jobs += dev.nitrate.core.rows.RowJob(title, "$prefix/$key") { load().let { if (it.isEmpty()) emptyList() else listOf(MediaRow("$prefix/$key", title, it, display, more)) } }
    }

    when (library.collection) {
        CollectionKind.BOOKS -> {
            add("continue_reading", "Continue Reading") { continueReading(id) }
            // Audiobooks live in Books libraries too: carried on in the listening player.
            add("continue_listening", "Continue Listening") {
                items("UserItems", "Resume", query = mapOf("parentId" to id, "limit" to ROW_LIMIT, "fields" to ROW_FIELDS, "mediaTypes" to "Audio", "includeItemTypes" to "AudioBook"))
            }
            add("recently_added", "Recently Added", more = queryMore(id, "Book,AudioBook", "DateCreated")) { query(id, "Book,AudioBook", "DateCreated") }
            add("unread", "Not Read Yet") { query(id, "Book,AudioBook", "Random", filters = "IsUnplayed") }
            add("favorites", "Favorites", more = queryMore(id, "Book,AudioBook", "SortName", "Ascending", filters = "IsFavorite")) { query(id, "Book,AudioBook", "SortName", "Ascending", filters = "IsFavorite") }
        }
        CollectionKind.TV_SHOWS -> {
            add("continue_watching", "Continue Watching", WIDE_DEFAULT, more = resumeMore(id, "Episode")) { resumeItems(id, "Episode") }
            add("next_up", "Next Up", WIDE_DEFAULT, more = rowMore("Shows", "NextUp", query = mapOf("parentId" to id, "limit" to ROW_LIMIT, "fields" to ROW_FIELDS))) { items("Shows", "NextUp", query = mapOf("parentId" to id, "limit" to ROW_LIMIT, "fields" to ROW_FIELDS)) }
            add("recently_added", "Recently Added", more = queryMore(id, "Series", "DateLastContentAdded")) { query(id, "Series", "DateLastContentAdded") }
            add("recently_released", "Recently Aired", WIDE_DEFAULT, more = queryMore(id, "Episode", "PremiereDate", maxPremiere = now)) { query(id, "Episode", "PremiereDate", maxPremiere = now) }
            add("top_unwatched", "Top Unwatched", more = queryMore(id, "Series", "CommunityRating", filters = "IsUnplayed")) { query(id, "Series", "CommunityRating", filters = "IsUnplayed") }
            add("favorites", "Favorites", more = queryMore(id, "Series", "SortName", "Ascending", filters = "IsFavorite")) { query(id, "Series", "SortName", "Ascending", filters = "IsFavorite") }
        }
        else -> {
            val types = if (library.collection == CollectionKind.MOVIES) "Movie" else null
            add("continue_watching", "Continue Watching", WIDE_DEFAULT, more = resumeMore(id, types)) { resumeItems(id, types) }
            add("recently_released", "Recently Released", more = queryMore(id, types, "PremiereDate", maxPremiere = now)) { query(id, types, "PremiereDate", maxPremiere = now) }
            add("recently_added", "Recently Added", more = queryMore(id, types, "DateCreated")) { query(id, types, "DateCreated") }
            add("top_unwatched", "Top Unwatched", more = queryMore(id, types, "CommunityRating", filters = "IsUnplayed")) { query(id, types, "CommunityRating", filters = "IsUnplayed") }
            add("favorites", "Favorites", more = queryMore(id, types, "SortName", "Ascending", filters = "IsFavorite")) { query(id, types, "SortName", "Ascending", filters = "IsFavorite") }
        }
    }
    return jobs
}

internal suspend fun JellyfinClient.books(parentId: String?, filters: String, limit: Int) = items(
    "Items",
    query = mapOf(
        "userId" to userId, "parentId" to parentId, "includeItemTypes" to "Book", "recursive" to true,
        "filters" to filters, "sortBy" to "DatePlayed", "sortOrder" to "Descending", "limit" to limit, "fields" to ROW_FIELDS,
    ),
)

internal fun JellyfinClient.resumeQuery(parentId: String?, types: String?) =
    mapOf("parentId" to parentId, "limit" to ROW_LIMIT, "fields" to ROW_FIELDS, "mediaTypes" to "Video", "includeItemTypes" to types)

internal suspend fun JellyfinClient.resumeItems(parentId: String?, types: String?) = items("UserItems", "Resume", query = resumeQuery(parentId, types))

internal fun JellyfinClient.resumeMore(parentId: String?, types: String?) = rowMore("UserItems", "Resume", query = resumeQuery(parentId, types))

internal suspend fun JellyfinClient.query(
    parentId: String,
    types: String?,
    sortBy: String,
    order: String = "Descending",
    filters: String? = null,
    maxPremiere: String? = null,
) = items("Items", query = queryParams(parentId, types, sortBy, order, filters, maxPremiere))

/** A sorted lookup's request, for rows that can be continued. */
internal fun JellyfinClient.queryMore(
    parentId: String,
    types: String?,
    sortBy: String,
    order: String = "Descending",
    filters: String? = null,
    maxPremiere: String? = null,
) = rowMore("Items", query = queryParams(parentId, types, sortBy, order, filters, maxPremiere))

internal fun JellyfinClient.queryParams(
    parentId: String,
    types: String?,
    sortBy: String,
    order: String,
    filters: String?,
    maxPremiere: String?,
) = mapOf(
        "parentId" to parentId,
        "includeItemTypes" to types,
        "recursive" to true,
        "sortBy" to "$sortBy,SortName",
        "sortOrder" to "$order,Ascending",
        "filters" to filters,
        "maxPremiereDate" to maxPremiere,
        "limit" to ROW_LIMIT,
        "fields" to ROW_FIELDS,
        "enableTotalRecordCount" to false,
    )

/**
 * Home's "Watching together" row ([dev.nitrate.core.partners.TogetherRow]): invites waiting, then
 * each shared show's next episode for the user by whose turn it is. Nothing without the
 * Companion plugin's partners.
 */
internal suspend fun JellyfinClient.watchingTogether(): List<MediaItem> =
    togetherCards().let { c -> if (c.isEmpty) emptyList() else c.invites + c.yours + c.waiting + dev.nitrate.core.partners.TogetherRow.start }

/**
 * What's watched together, for Continue Watching ([dev.nitrate.core.rows.ContinueAndNext.withTogether]):
 * invites, then the shared titles by whose move it is. Empty without the Companion plugin's partners.
 */
internal suspend fun JellyfinClient.togetherCards(): dev.nitrate.core.partners.TogetherCards = coroutineScope {
    if (companion()?.features?.contains(dev.nitrate.core.partners.WatchPartners.FEATURE) != true) return@coroutineScope dev.nitrate.core.partners.TogetherCards()
    val info = partners()
    suspend fun series(id: String, name: String) = runCatching { getItem(id) }.getOrElse { MediaItem(id, name, ItemKind.SERIES) }
    val invites = info.incoming.distinctBy { it.seriesId }.map { r -> async { series(r.seriesId, r.seriesName) } }
    val shows = info.groups.distinctBy { it.seriesId }.map { g ->
        async {
            runCatching {
                val items = titleItems(g.seriesId)
                val k = dev.nitrate.core.partners.KeepUp.of(g.seriesId, items, partnerProgress(g.seriesId), info.groups)
                // The title itself only for a card with nothing of the user's left (waiting on the others), in any of their places in it.
                val needsTitle = dev.nitrate.core.partners.TogetherRow.needsTitle(items, k)
                dev.nitrate.core.partners.SharedShow(if (needsTitle) series(g.seriesId, g.seriesName) else null, items, k)
            }.getOrNull()
        }
    }
    dev.nitrate.core.partners.TogetherRow.cards(invites.map { it.await() }, shows.mapNotNull { it.await() })
}
