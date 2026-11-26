package dev.nitrate.core.jellyfin

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
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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

/**
 * [MediaServer] for Jellyfin, in common Kotlin over Ktor: the same code serves Android (OkHttp
 * engine) and iOS (Darwin engine). Only the endpoints Nitrate uses are implemented; they're
 * checked against Jellyfin's OpenAPI description in the contract tests.
 */
class JellyfinClient(
    engine: HttpClientEngine,
    private val identity: ClientIdentity,
    private val sessionStorage: SessionStorage,
    private val capabilities: () -> DeviceCapabilities,
    private val playbackSettings: PlaybackSettingsRepository,
    /** Keeps slow-to-make rows ("Because you watched") between app runs. */
    internal val textCache: dev.nitrate.core.cache.TextCache? = null,
    /** Every request made, for the query analyzer. */
    override val queryLog: dev.nitrate.core.diagnostics.QueryLog = dev.nitrate.core.diagnostics.QueryLog(),
) : MediaServer {
    internal val http = HttpClient(engine) {
        expectSuccess = true
        install(ContentNegotiation) { json(JellyfinJson) }
        install(WebSockets)
        // Pin the casing: a request with no Accept header gets camelCase from newer servers.
        defaultRequest { header(HttpHeaders.Accept, "application/json; profile=PascalCase") }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 60_000
        }
    }

    /**
     * For addresses outside the server (image providers' files): no Authorization, no tags, no
     * Accept pinning; nothing that says who's asking.
     */
    private val outside by lazy {
        HttpClient(engine) {
            expectSuccess = true
            install(HttpTimeout) { connectTimeoutMillis = 6_000; requestTimeoutMillis = 15_000 }
        }
    }
    private val remoteSizes = mutableMapOf<String, dev.nitrate.core.admin.ImageFacts?>()
    private val remoteSizesLock = kotlinx.coroutines.sync.Mutex()

    override suspend fun remoteImageFacts(url: String): dev.nitrate.core.admin.ImageFacts? {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return null
        remoteSizesLock.withLock { if (url in remoteSizes) return remoteSizes[url] }
        val size = runCatching {
            outside.prepareGet(url) {
                // Only the start of the file: its header says the size.
                header(HttpHeaders.Range, "bytes=0-${dev.nitrate.core.comics.ImageSize.READ_BYTES - 1}")
                header(HttpHeaders.Accept, "image/*")
            }.execute { response ->
                val channel = response.bodyAsChannel()
                val buf = ByteArray(dev.nitrate.core.comics.ImageSize.READ_BYTES)
                var n = 0
                var size: Pair<Int, Int>? = null
                var clear: Boolean? = null
                // Until both the size and see-through-or-not are known (a PNG's tRNS can come a few KB in).
                while (n < buf.size) {
                    val got = channel.readAvailable(buf, n, buf.size - n)
                    if (got <= 0) break
                    n += got
                    val head = buf.copyOf(n)
                    if (size == null) size = dev.nitrate.core.comics.ImageSize.of(head)
                    if (clear == null) clear = dev.nitrate.core.comics.ImageSize.transparent(head)
                    if (size != null && clear != null) break
                }
                val head = buf.copyOf(n)
                if (clear == null) clear = dev.nitrate.core.comics.ImageSize.transparent(head)
                val known = size?.takeIf { it.first > 0 && it.second > 0 }
                if (known == null && clear == null) null else dev.nitrate.core.admin.ImageFacts(known?.first, known?.second, clear)
            }
        }.getOrNull()
        remoteSizesLock.withLock { remoteSizes[url] = size }
        return size
    }

    init {
        // Every request is timed and put down to where it came from (the query analyzer), and
        // tagged so the Companion plugin can time it on the server too. Anything sent that
        // changes the server lets go of kept answers showing watch state.
        http.plugin(io.ktor.client.plugins.HttpSend).intercept { request ->
            val protocol = request.url.protocol.name
            if (protocol == "ws" || protocol == "wss") return@intercept execute(request)
            val (id, tag) = queryLog.nextTag()
            request.headers.append(QUERY_TAG_HEADER, tag)
            val origin = dev.nitrate.core.diagnostics.QueryLog.originHere()
            val started = kotlin.time.TimeSource.Monotonic.markNow()
            val method = request.method.value
            val path = request.url.pathSegments.filter { it.isNotEmpty() }.let { segs ->
                // A server at an address with a path (/jellyfin) is named from its own root.
                val base = baseUrl?.let { b -> runCatching { io.ktor.http.Url(b).segments.filter { it.isNotEmpty() } }.getOrNull() }.orEmpty()
                if (base.isNotEmpty() && segs.take(base.size) == base) segs.drop(base.size) else segs
            }.joinToString("/")
            val params = request.url.parameters.build().entries().associate { it.key to it.value.joinToString(",") }
            // Long polls wait on purpose: not costs.
            val counted = params.keys.none { it.equals("waitMs", ignoreCase = true) }
            fun record(outcome: dev.nitrate.core.diagnostics.QueryOutcome, status: Int?, bytes: Long?, error: String? = null) {
                if (!counted) return
                val ms = started.elapsedNow().inWholeMilliseconds
                val endpoint = dev.nitrate.core.diagnostics.QueryLog.endpointOf(path)
                queryLog.add(
                    dev.nitrate.core.diagnostics.QueryRecord(
                        id = id, tag = tag, atMs = nowEpochMs(), method = method, endpoint = endpoint,
                        shape = (if (method == "GET") "" else "$method ") + dev.nitrate.core.diagnostics.QueryLog.shapeOf(endpoint, params),
                        origin = origin, ms = ms, outcome = outcome, status = status, bytes = bytes,
                        hints = dev.nitrate.core.diagnostics.QueryHints.of(endpoint, params, ms, bytes),
                        error = error,
                        request = requestOf(path, params),
                    ),
                )
            }
            val call = try {
                execute(request).let { if (counted) it.save() else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Refused: the server's status (the response check throws before it's returned here); else why nothing came back.
                val refused = (e as? io.ktor.client.plugins.ResponseException)?.response?.status?.value
                record(dev.nitrate.core.diagnostics.QueryOutcome.FAILED, refused, null, if (refused == null) (e::class.simpleName ?: "error") + (e.message?.let { ": " + it.take(80) } ?: "") else null)
                throw e
            }
            val status = call.response.status.value
            val bytes = if (counted) runCatching { call.response.readRawBytes().size.toLong() }.getOrNull() else null
            record(if (status < 400) dev.nitrate.core.diagnostics.QueryOutcome.FETCHED else dev.nitrate.core.diagnostics.QueryOutcome.FAILED, status, bytes)
            // Something sent: lists whose titles depend on watch state go now; the server's
            // UserDataChanged then fixes the marks in the rest.
            if (method != "GET" && status < 400) {
                answers.forget { key, kind -> dependsOnWatchState(key, kind) || kind == dev.nitrate.core.cache.AnswerKind.COMPANION }
                // Anything sent may change the groups (marks, resume points, invites): the rows read them afresh.
                forgetPartners()
                // A playlist made, added to or removed: whoever keeps the list of them hears it.
                if ("/Playlists" in call.request.url.encodedPath) listsListener?.invoke()
            }
            call
        }
    }

    @OptIn(ExperimentalTime::class)
    internal fun nowEpochMs() = Clock.System.now().toEpochMilliseconds()

    /** Answers kept a little while and shared between pages and rows ([items]). */
    /** The clock kept answers are timed by (a test can move it on). */
    internal var clock: () -> Long = { nowEpochMs() }
    internal val answers = dev.nitrate.core.cache.AnswerCache({ clock() })

    /**
     * Watch state changed (here or on another device). Lists whose titles depend on it (what's
     * part way, what's next, unwatched or favourites only, by date played) go; every other kept
     * list keeps its titles with each changed one's marks and progress put right in place, so
     * nothing is asked again. Without the states (an older server), every list showing marks goes.
     */
    internal suspend fun watchStateChanged(states: Map<String, dev.nitrate.core.model.UserState>) {
        quietLock.withLock { quietRows.clear() }
        if (states.isEmpty()) { answers.userDataChanged(); return }
        answers.forget { key, kind -> dependsOnWatchState(key, kind) }
        answers.patch { value -> patchUserData(value, states) }
    }

    /** Let [value] (a kept list of titles) show [states]; the same object when nothing in it changed. */
    @Suppress("UNCHECKED_CAST")
    private fun patchUserData(value: Any, states: Map<String, dev.nitrate.core.model.UserState>): Any {
        val list = value as? List<*> ?: return value
        if (list.none { it is MediaItem && it.id in states }) return value
        return (list as List<Any?>).map { if (it is MediaItem) states[it.id]?.let { s -> it.copy(user = s) } ?: it else it }
    }

    /** Whether a kept answer's titles (not just their marks) follow watch state. */
    private fun dependsOnWatchState(key: String, kind: dev.nitrate.core.cache.AnswerKind): Boolean =
        kind == dev.nitrate.core.cache.AnswerKind.WATCHING || WATCH_STATE_FILTERS.any { key.contains(it, ignoreCase = true) }

    /** Lets go of every kept answer, for a page refreshed by hand. */
    override suspend fun forgetAnswers() = answers.clear()

    /** Outlives screens, for fire-and-forget reports such as playback stopped. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal var baseUrl: String? = null
    internal var token: String? = null

    override var userId: String? = null
        private set
    override val serverUrl: String? get() = baseUrl

    private fun base() = baseUrl ?: error("No server selected")

    internal fun url(vararg segments: String, query: Map<String, Any?> = emptyMap()): String =
        URLBuilder(base()).apply {
            appendPathSegments(*segments)
            query.forEach { (k, v) -> if (v != null) parameters.append(k, v.toString()) }
        }.buildString()

    internal fun HttpRequestBuilder.auth() {
        header("Authorization", authorizationHeader())
    }

    internal suspend fun get(vararg segments: String, query: Map<String, Any?> = emptyMap()): HttpResponse =
        http.get(url(*segments, query = query)) { auth() }

    private suspend inline fun <reified T> post(vararg segments: String, body: Any? = null, query: Map<String, Any?> = emptyMap()): HttpResponse =
        http.post(url(*segments, query = query)) {
            auth()
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body as T)
            }
        }

    internal suspend inline fun <reified T> HttpResponse.decode(): T = JellyfinJson.decodeFromString(bodyAsText())

    // ---- Session ----

    override suspend fun restoreSession(): SessionState {
        var session = sessionStorage.load() ?: return SessionState.NONE
        // Start on the primary server (last account used there) if one is set.
        val primary = sessionStorage.all().firstOrNull { it.primary }
        if (primary != null && !session.sameServer(primary)) session = sessionStorage.select(primary.key) ?: session
        use(session)
        return reconnect()
    }

    // ---- Casting through Jellyfin's receiver ----

    private fun castSignIn(): CastSignIn? {
        val user = userId ?: return null
        val t = token ?: return null
        val base = baseUrl ?: return null
        return CastSignIn(user, identity.deviceId, t, base, serverId ?: current?.serverId)
    }

    override fun castPlay(play: dev.nitrate.core.cast.CastPlay): String? =
        if (play.items.isEmpty()) null else castSignIn()?.let { JellyfinCast.play(it, play) }

    override fun castCommand(command: dev.nitrate.core.cast.CastCommand, value: Long?): String? = castSignIn()?.let { JellyfinCast.command(it, command, value) }

    override fun castStatus(message: String): dev.nitrate.core.cast.CastStatus? = JellyfinCast.status(message)

    /** The signed-in account as stored, for its addresses. */
    private var current: StoredSession? = null

    /** The server's id once seen, so an address leading to a different server is never used. */
    private var serverId: String? = null

    private fun use(session: StoredSession) {
        if (current?.key != session.key) serverId = null
        current = session
        baseUrl = session.serverUrl
        token = session.accessToken
        userId = session.userId
        accountKey = session.key
    }

    internal var accountKey: String? = null
    override val currentAccountKey: String? get() = accountKey

    override suspend fun savedAccounts(): List<SavedAccount> = sessionStorage.all().map { s ->
        SavedAccount(
            key = s.key,
            serverUrl = s.serverUrl,
            serverName = s.serverName.ifBlank { s.serverUrl.substringAfter("://") },
            userId = s.userId,
            userName = s.userName,
            imageUrl = s.imageTag?.let { tag ->
                URLBuilder(s.serverUrl).apply {
                    appendPathSegments("UserImage")
                    parameters.append("userId", s.userId.replace("-", ""))
                    parameters.append("tag", tag)
                }.buildString()
            },
        )
    }

    override suspend fun switchAccount(key: String): SessionState {
        val session = sessionStorage.select(key) ?: return SessionState.NONE
        use(session)
        return reconnect()
    }

    // ---- Addresses (home and away) ----

    override suspend fun sendClientLog(text: String): String {
        val response = http.post(url("ClientLog", "Document")) {
            auth()
            contentType(ContentType.Text.Plain)
            setBody(text)
        }
        return response.decode<ClientLogDocumentResponseDto>().fileName ?: error("The server didn't say where it saved the log")
    }

    override suspend fun servers(): List<SavedServer> {
        val accounts = savedAccounts()
        return sessionStorage.all().groupBy { it.serverUrl.trimEnd('/') }.map { (url, sessions) ->
            // Every account on a server keeps the same addresses.
            val s = sessions.first()
            SavedServer(
                url = url,
                name = s.serverName.ifBlank { url.substringAfter("://") },
                local = s.localUrl, vpn = s.vpnUrl, remote = s.remoteUrl,
                primary = sessions.any { it.primary },
                accounts = accounts.filter { it.serverUrl.trimEnd('/') == url },
                active = baseUrl.takeIf { current?.serverUrl?.trimEnd('/') == url },
            )
        }.sortedByDescending { it.primary }
    }

    /** Applies [change] to every saved account on [serverUrl]'s server, keeping the current one current. */
    private suspend fun updateServer(serverUrl: String, change: (StoredSession) -> StoredSession) {
        sessionStorage.all().filter { it.serverUrl.trimEnd('/') == serverUrl.trimEnd('/') }.forEach { sessionStorage.update(change(it)) }
        current = current?.let { c -> sessionStorage.all().firstOrNull { it.key == c.key } ?: c }
    }

    override suspend fun saveServerAddresses(serverUrl: String, local: String?, vpn: String?, remote: String?): SessionState {
        fun clean(typed: String) = addressCandidates(typed).firstOrNull { it.startsWith("http") }
        // Keep the form that answers as this server; otherwise what was typed, with a scheme.
        suspend fun resolve(input: String?): String? {
            val typed = input?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
            return (testAddress(typed, serverUrl) as? AddressCheck.Reached)?.takeIf { it.sameServer }?.url ?: clean(typed)
        }
        val l = resolve(local)
        val v = resolve(vpn)
        val r = resolve(remote)
        updateServer(serverUrl) { it.copy(localUrl = l, vpnUrl = v, remoteUrl = r) }
        val isCurrent = current?.serverUrl?.trimEnd('/') == serverUrl.trimEnd('/')
        return if (isCurrent) reconnect() else SessionState.VALID
    }

    override suspend fun setPrimaryServer(serverUrl: String) {
        sessionStorage.all().forEach { s -> sessionStorage.update(s.copy(primary = s.serverUrl.trimEnd('/') == serverUrl.trimEnd('/'))) }
        current = current?.let { c -> sessionStorage.all().firstOrNull { it.key == c.key } ?: c }
    }

    override suspend fun testAddress(input: String, serverUrl: String?): AddressCheck {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        val found = probe(input, PROBE_TIMEOUT_MS) ?: return AddressCheck.Failed("No Jellyfin server answered at \"${input.trim()}\"")
        val millis = start.elapsedNow().inWholeMilliseconds
        val expected = when {
            serverUrl == null || serverUrl.trimEnd('/') == current?.serverUrl?.trimEnd('/') -> serverId ?: current?.serverId
            else -> sessionStorage.all().firstOrNull { it.serverUrl.trimEnd('/') == serverUrl.trimEnd('/') }?.serverId
        }
        return AddressCheck.Reached(found.url, found.name, millis, sameServer = expected == null || found.id == expected)
    }

    override suspend fun reconnect(): SessionState {
        val s = current ?: return checkSession()
        if (serverId == null) serverId = s.serverId
        pickAddress(s.addresses)?.let { baseUrl = it }
        val state = checkSession()
        // Learn which server this is, so other addresses are only ever used for the same one.
        if (state == SessionState.VALID && serverId == null) {
            serverId = runCatching { JellyfinJson.decodeFromString<PublicSystemInfoDto>(http.get(url("System", "Info", "Public")).bodyAsText()).id }.getOrNull()
        }
        if (serverId != null && s.serverId != serverId) {
            val id = serverId
            runCatching { updateServer(s.serverUrl) { it.copy(serverId = id) } }
        }
        return state
    }

    private data class Probed(val url: String, val name: String, val id: String?)

    /** The first of [input]'s forms (scheme, port) where a Jellyfin server answers. */
    private suspend fun probe(input: String, timeoutMs: Long): Probed? = coroutineScope {
        val probes = addressCandidates(input).map { candidate ->
            async {
                withTimeoutOrNull(timeoutMs) {
                    runCatching {
                        val info = http.get("$candidate/System/Info/Public").bodyAsText().let { JellyfinJson.decodeFromString<PublicSystemInfoDto>(it) }
                        if (info.id != null) Probed(candidate, info.serverName ?: candidate, info.id) else null
                    }.getOrNull()
                }
            }
        }
        probes.awaitAll().firstNotNullOfOrNull { it }
    }

    /**
     * Tries every address at once and takes the first in order (local before remote) that answers
     * as this account's server. The local one gets a short wait: away from home it never answers.
     */
    private suspend fun pickAddress(addresses: List<String>): String? = coroutineScope {
        if (addresses.size < 2) return@coroutineScope null
        val tries = addresses.mapIndexed { i, url ->
            async {
                withTimeoutOrNull(if (i == 0) LOCAL_PROBE_TIMEOUT_MS else PROBE_TIMEOUT_MS) {
                    runCatching {
                        val info = http.get("$url/System/Info/Public").bodyAsText().let { JellyfinJson.decodeFromString<PublicSystemInfoDto>(it) }
                        info.takeIf { it.id != null && (serverId == null || it.id == serverId) }
                    }.getOrNull()
                }
            }
        }
        for ((i, t) in tries.withIndex()) {
            val info = t.await() ?: continue
            tries.drop(i + 1).forEach { it.cancel() }
            serverId = info.id
            return@coroutineScope addresses[i]
        }
        null
    }

    override suspend fun forgetAccount(key: String) {
        require(key != accountKey) { "Sign out of the current account instead" }
        val session = sessionStorage.all().firstOrNull { it.key == key } ?: return
        sessionStorage.remove(key)
        forgetRemembered(key)
        // Revoke its token so the server's device list stays tidy; fine if the server is away.
        runCatching {
            http.post("${session.serverUrl.trimEnd('/')}/Sessions/Logout") {
                header("Authorization", authHeader(session.accessToken))
            }
        }
    }

    override suspend fun checkSession(): SessionState = try {
        get("Users", "Me")
        SessionState.VALID
    } catch (e: ClientRequestException) {
        // The server answered: the token was revoked or the user removed.
        if (e.response.status.value == 401 || e.response.status.value == 403) SessionState.EXPIRED else SessionState.UNREACHABLE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SessionState.UNREACHABLE
    }

    override suspend fun findServer(input: String): ServerAddress = coroutineScope {
        // Probe every candidate at once; take the first that answers, in preference order.
        val probes = addressCandidates(input).map { candidate ->
            async {
                withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    runCatching {
                        val info = http.get("$candidate/System/Info/Public").bodyAsText().let { JellyfinJson.decodeFromString<PublicSystemInfoDto>(it) }
                        if (info.id != null) ServerAddress(candidate, info.serverName ?: candidate) else null
                    }.getOrNull()
                }
            }
        }
        probes.awaitAll().firstNotNullOfOrNull { it } ?: error("No Jellyfin server found at \"$input\"")
    }

    override fun discoverServers(): Flow<DiscoveredServer> = discoverJellyfinServers()

    override suspend fun publicUsers(serverUrl: String): List<ServerUser> = runCatching {
        selectServer(serverUrl)
        get("Users", "Public").decode<List<UserDto>>().map { u ->
            ServerUser(
                id = normalizeId(u.id),
                name = u.name.orEmpty(),
                hasPassword = u.hasPassword,
                imageUrl = u.primaryImageTag?.let { url("UserImage", query = mapOf("userId" to u.id, "tag" to it)) },
            )
        }
    }.getOrDefault(emptyList())

    private fun selectServer(serverUrl: String) {
        baseUrl = serverUrl.trimEnd('/')
        token = null
        accountKey = null
    }

    override suspend fun login(serverUrl: String, username: String, password: String) {
        selectServer(serverUrl)
        val result = try {
            post<AuthenticateByNameDto>("Users", "AuthenticateByName", body = AuthenticateByNameDto(username, password)).decode<AuthenticationResultDto>()
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 401) throw InvalidCredentialsException() else throw e
        }
        finishLogin(result)
    }

    override suspend fun quickConnectEnabled(serverUrl: String): Boolean = runCatching {
        selectServer(serverUrl)
        get("QuickConnect", "Enabled").bodyAsText().trim() == "true"
    }.getOrDefault(false)

    override suspend fun startQuickConnect(serverUrl: String): QuickConnectRequest {
        selectServer(serverUrl)
        val result = post<Unit>("QuickConnect", "Initiate").decode<QuickConnectResultDto>()
        return QuickConnectRequest(result.code, result.secret)
    }

    override suspend fun quickConnectApproved(secret: String): Boolean =
        get("QuickConnect", "Connect", query = mapOf("secret" to secret)).decode<QuickConnectResultDto>().authenticated

    // ---- Single sign-on (Nitrate Companion) ----

    override suspend fun ssoProvider(serverUrl: String): dev.nitrate.core.model.SsoProvider? = runCatching {
        selectServer(serverUrl)
        get("Nitrate", "Sso").decode<SsoInfoDto>().takeIf { it.enabled }?.let { dev.nitrate.core.model.SsoProvider(it.name) }
    }.getOrNull()

    override suspend fun startSso(serverUrl: String): dev.nitrate.core.model.SsoRequest {
        selectServer(serverUrl)
        val started = try {
            post<SsoStartDto>("Nitrate", "Sso", "Start", body = SsoStartDto(identity.deviceId, identity.deviceName, identity.clientName, identity.version)).decode<SsoStartedDto>()
        } catch (e: ClientRequestException) {
            error(e.response.bodyAsText().trim('"').ifBlank { "Single sign-on isn't available on this server." })
        } catch (e: io.ktor.client.plugins.ServerResponseException) {
            error(e.response.bodyAsText().trim('"').ifBlank { "Single sign-on isn't available on this server." })
        }
        return dev.nitrate.core.model.SsoRequest(started.code, started.secret, started.url, started.expiresInMs)
    }

    override suspend fun pollSso(serverUrl: String, request: dev.nitrate.core.model.SsoRequest): dev.nitrate.core.model.SsoStatus {
        selectServer(serverUrl)
        val poll = post<SsoPollPostDto>("Nitrate", "Sso", "Poll", body = SsoPollPostDto(request.code, request.secret)).decode<SsoPollDto>()
        return when (poll.state) {
            "Waiting" -> dev.nitrate.core.model.SsoStatus.Waiting
            "Done" -> {
                val token = requireNotNull(poll.accessToken) { "Server returned no access token" }
                // Read the account with its new token, as a password sign-in returns it.
                token(token)
                val me = get("Users", "Me").decode<UserDto>()
                finishLogin(AuthenticationResultDto(me, token))
                dev.nitrate.core.model.SsoStatus.Done
            }
            "Expired" -> dev.nitrate.core.model.SsoStatus.Failed(poll.message ?: "This sign-in has lapsed. Start again.", expired = true)
            else -> dev.nitrate.core.model.SsoStatus.Failed(poll.message ?: "The sign-in was refused.")
        }
    }

    private fun token(value: String) {
        token = value
    }

    // ---- Live TV ----

    private val liveTv = JellyfinLiveTv(this)
    override suspend fun liveTvAvailable() = liveTv.available()
    override suspend fun liveChannels() = liveTv.channels()
    override suspend fun guide(channelIds: List<String>, fromMs: Long, toMs: Long) = liveTv.guide(channelIds, fromMs, toMs)
    override suspend fun recordings() = liveTv.recordings()
    override suspend fun record(programId: String) = liveTv.record(programId)
    override suspend fun cancelRecording(timerId: String) = liveTv.cancelRecording(timerId)

    // ---- Music ----

    override suspend fun albumTracks(albumId: String): List<MediaItem> = items(
        "Items",
        query = mapOf("userId" to userId, "parentId" to albumId, "includeItemTypes" to "Audio", "recursive" to true, "sortBy" to "ParentIndexNumber,IndexNumber,SortName", "fields" to "MediaSources"),
    )

    override suspend fun artistAlbums(artistId: String): List<MediaItem> = items(
        "Items",
        query = mapOf("userId" to userId, "albumArtistIds" to artistId, "includeItemTypes" to "MusicAlbum", "recursive" to true, "sortBy" to "ProductionYear,SortName", "sortOrder" to "Descending,Ascending"),
    )

    override suspend fun albumArtists(libraryId: String, startIndex: Int, limit: Int): Pair<List<MediaItem>, Int> {
        val result = get("Artists", "AlbumArtists", query = mapOf("userId" to userId, "parentId" to libraryId, "startIndex" to startIndex, "limit" to limit, "sortBy" to "SortName", "enableTotalRecordCount" to true))
            .decode<QueryResultDto<ItemDto>>()
        return result.items.map { it.toMediaItem() } to result.totalRecordCount
    }

    override suspend fun instantMix(itemId: String): List<MediaItem> =
        items("Items", itemId, "InstantMix", query = mapOf("userId" to userId, "limit" to 100))

    // ---- Collections and playlists ----

    internal fun dashless(ids: List<String>) = ids.joinToString(",") { it.replace("-", "") }

    override suspend fun createCollection(name: String, itemIds: List<String>): String =
        normalizeId(post<Unit>("Collections", query = mapOf("name" to name, "ids" to dashless(itemIds).ifEmpty { null })).decode<CreationResultDto>().id)

    override suspend fun addToCollection(collectionId: String, itemIds: List<String>) {
        post<Unit>("Collections", collectionId, "Items", query = mapOf("ids" to dashless(itemIds)))
    }

    override suspend fun removeFromCollection(collectionId: String, itemIds: List<String>) {
        http.delete(url("Collections", collectionId, "Items", query = mapOf("ids" to dashless(itemIds)))) { auth() }
    }

    override suspend fun createPlaylist(name: String, itemIds: List<String>, audio: Boolean): String =
        normalizeId(post<CreatePlaylistDto>("Playlists", body = CreatePlaylistDto(name, itemIds, userId, if (audio) "Audio" else "Video")).decode<CreationResultDto>().id)

    override suspend fun renamePlaylist(playlistId: String, name: String) {
        post<UpdatePlaylistDto>("Playlists", playlistId, body = UpdatePlaylistDto(name))
    }

    override suspend fun addToPlaylist(playlistId: String, itemIds: List<String>) {
        post<Unit>("Playlists", playlistId, "Items", query = mapOf("ids" to dashless(itemIds), "userId" to userId))
    }

    override suspend fun removeFromPlaylist(playlistId: String, entryIds: List<String>) {
        http.delete(url("Playlists", playlistId, "Items", query = mapOf("entryIds" to entryIds.joinToString(",")))) { auth() }
    }

    override suspend fun movePlaylistItem(playlistId: String, entryId: String, newIndex: Int) {
        post<Unit>("Playlists", playlistId, "Items", entryId, "Move", newIndex.toString())
    }

    override suspend fun deleteList(itemId: String) {
        http.delete(url("Items", itemId)) { auth() }
    }

    override suspend fun recentEpisodes(limit: Int): List<MediaItem> = items(
        "Items",
        query = mapOf(
            "userId" to userId,
            "includeItemTypes" to "Episode",
            "recursive" to true,
            "sortBy" to "DateCreated",
            "sortOrder" to "Descending",
            "limit" to limit,
            "fields" to "DateCreated,$ROW_FIELDS",
        ),
    )

    override suspend fun followedSeries(): Set<String> = coroutineScope {
        val favourites = async {
            items("Items", query = mapOf("userId" to userId, "includeItemTypes" to "Series", "recursive" to true, "isFavorite" to true)).map { it.id }
        }
        val watching = async { items("Shows", "NextUp", query = mapOf("userId" to userId, "limit" to 200)).mapNotNull { it.seriesId } }
        (favourites.await() + watching.await()).toSet()
    }

    override suspend fun serverSettings(): dev.nitrate.core.account.ServerSettings = coroutineScope {
        val me = async { get("Users", "Me").decode<JsonObject>() }
        val info = async { runCatching { get("System", "Info", "Public").decode<JsonObject>() }.getOrNull() }
        // Hidden ones too: a library left out of My Media is missing from the usual list.
        val libraries = async { runCatching { items("UserViews", query = mapOf("includeHidden" to true)).associate { it.id to it.name } }.getOrDefault(emptyMap()) }
        val companion = async { runCatching { companionApi.info() } }
        val indexed = async { runCatching { diaryApi.linesStatus() }.getOrNull() }
        val user = me.await()
        // How the server converts (admins only: everyone else is refused it).
        val admin = ((user["Policy"] as? JsonObject)?.get("IsAdministrator") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        val encoding = if (admin) runCatching { get("System", "Configuration", "encoding").decode<JsonObject>() }.getOrNull() else null
        val settings = JellyfinServerSettings.from(user, info.await(), libraries.await())
        settings.copy(groups = settings.groups + listOfNotNull(encoding?.let(JellyfinServerSettings::converting)) + dev.nitrate.core.account.CompanionStatus.group(companion.await(), indexed.await()))
    }

    override suspend fun serverConversion(): dev.nitrate.core.playback.ServerConversion? {
        val sessions = get("Sessions", query = mapOf("deviceId" to identity.deviceId, "activeWithinSeconds" to 120)).decode<kotlinx.serialization.json.JsonArray>()
        val info = sessions.firstNotNullOfOrNull { (it as? JsonObject)?.get("TranscodingInfo") as? JsonObject } ?: return null
        fun str(k: String) = (info[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        fun prim(k: String) = info[k] as? kotlinx.serialization.json.JsonPrimitive
        return dev.nitrate.core.playback.ServerConversion(
            videoDirect = prim("IsVideoDirect")?.content?.toBooleanStrictOrNull(),
            audioDirect = prim("IsAudioDirect")?.content?.toBooleanStrictOrNull(),
            videoCodec = str("VideoCodec"),
            audioCodec = str("AudioCodec"),
            container = str("Container"),
            width = prim("Width")?.content?.toIntOrNull(),
            height = prim("Height")?.content?.toIntOrNull(),
            audioChannels = prim("AudioChannels")?.content?.toIntOrNull(),
            bitrate = prim("Bitrate")?.content?.toIntOrNull(),
            hardware = str("HardwareAccelerationType"),
            fps = prim("Framerate")?.content?.toFloatOrNull(),
            completion = prim("CompletionPercentage")?.content?.toDoubleOrNull(),
            reasons = (info["TranscodeReasons"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty(),
        )
    }

    override suspend fun watchedSeries(): Set<String> =
        // A show's user data counts its watched episodes, so one light list covers every show.
        get("Items", query = mapOf("userId" to userId, "includeItemTypes" to "Series", "recursive" to true, "enableImages" to false))
            .decode<QueryResultDto<ItemDto>>().items
            .filter { it.userData?.let { u -> u.played || (u.playedPercentage ?: 0.0) > 0 } == true }
            .map { normalizeId(it.id) }.toSet()

    override suspend fun authorizeQuickConnect(code: String) {
        val ok = try {
            post<Unit>("QuickConnect", "Authorize", query = mapOf("code" to code.trim())).bodyAsText().trim()
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            when (e.response.status.value) {
                404 -> error("No device is waiting with that code. Check it, or start Quick Connect again on the TV.")
                401, 403 -> error("Quick Connect is switched off on this server.")
                else -> throw e
            }
        }
        if (ok == "false") error("That code wasn't accepted. Check it and try again.")
    }

    override suspend fun finishQuickConnect(serverUrl: String, secret: String) {
        finishLogin(post<QuickConnectDto>("Users", "AuthenticateWithQuickConnect", body = QuickConnectDto(secret)).decode())
    }

    private suspend fun finishLogin(result: AuthenticationResultDto) {
        val accessToken = requireNotNull(result.accessToken) { "Server returned no access token" }
        val user = requireNotNull(result.user) { "Server returned no user" }
        val serverName = runCatching { get("System", "Info", "Public").decode<PublicSystemInfoDto>().serverName }.getOrNull().orEmpty()
        val session = StoredSession(base(), accessToken, normalizeId(user.id), user.name.orEmpty(), serverName, user.primaryImageTag)
        use(session)
        sessionStorage.save(session)
    }

    /** Lets go of what this client remembered on the device for [key] (its library list, "Because you watched"). */
    private suspend fun forgetRemembered(key: String) {
        runCatching { textCache?.remove("views/$key") }
        runCatching { textCache?.remove("views-at/$key") }
        runCatching { textCache?.remove("because/$key") }
        answers.clear()
    }

    override suspend fun logout() {
        accountKey?.let { forgetRemembered(it) }
        runCatching { post<Unit>("Sessions", "Logout") }
        sessionStorage.clear()
        token = null
        accountKey = null
    }

    // ---- Per-user storage ----

    // Stored preferences read lately (account|id → when, what): sign-in, backups and setup each read
    // the device index within the same minute; one read serves them. A save here replaces it at once.
    private val prefsKept = mutableMapOf<String, Pair<kotlin.time.TimeMark, Map<String, String>>>()
    private val prefsLock = kotlinx.coroutines.sync.Mutex()

    override suspend fun loadCustomPrefs(prefsId: String): Map<String, String> {
        val key = "${accountKey ?: baseUrl}|$prefsId"
        prefsLock.withLock { prefsKept[key]?.takeIf { it.first.elapsedNow().inWholeMilliseconds < PREFS_KEPT_MS }?.let { return it.second } }
        val prefs = get("DisplayPreferences", prefsId, query = mapOf("client" to PREFS_CLIENT)).decode<JsonObject>()
        val custom = prefs["CustomPrefs"] as? JsonObject ?: return emptyMap<String, String>().also { keepPrefs(key, it) }
        return custom.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { k to it.content } }.toMap().also { keepPrefs(key, it) }
    }

    private suspend fun keepPrefs(key: String, prefs: Map<String, String>) = prefsLock.withLock {
        prefsKept[key] = kotlin.time.TimeSource.Monotonic.markNow() to prefs
    }

    override suspend fun saveCustomPrefs(prefs: Map<String, String>, prefsId: String) {
        // Round-trip the whole object so fields Nitrate doesn't know about are preserved (read fresh:
        // another device may have just written it).
        val current = get("DisplayPreferences", prefsId, query = mapOf("client" to PREFS_CLIENT)).decode<JsonObject>()
        val updated = JsonObject(current + ("CustomPrefs" to JsonObject(prefs.mapValues { JsonPrimitive(it.value) })))
        post<JsonObject>("DisplayPreferences", prefsId, body = updated, query = mapOf("client" to PREFS_CLIENT))
        keepPrefs("${accountKey ?: baseUrl}|$prefsId", prefs)
    }

    // ---- Browsing ----

    /**
     * A list of titles, from the shared answers when another page or row asked the same lately
     * (or is asking right now), else from the server. Shared answers are put down in the query
     * log too, so the analyzer can show what sharing saved.
     */
    internal suspend fun items(vararg segments: String, query: Map<String, Any?>, kind: dev.nitrate.core.cache.AnswerKind? = null): List<MediaItem> {
        // A list read here never shows a total, so the server needn't count every match (a
        // second query over the whole filter); only where Jellyfin takes the switch.
        @Suppress("NAME_SHADOWING")
        val query = listQuery(segments, query)
        return shared(kind ?: answerKind(segments), segments, query) {
            get(*segments, query = query).decode<QueryResultDto<ItemDto>>().items.map { it.toMediaItem() }
        }
    }

    /**
     * Any GET answer through the shared answers ([dev.nitrate.core.cache.AnswerCache]): asked once,
     * joined by whoever asks meanwhile and kept for [kind]'s time, put down in the query log as
     * shared when it didn't go to the server. [load] makes the request itself.
     */
    internal suspend fun <T : Any> shared(kind: dev.nitrate.core.cache.AnswerKind, segments: Array<out String>, query: Map<String, Any?> = emptyMap(), load: suspend () -> T): T {
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        val (value, source) = answers.get(answerKey(segments, query), kind, load)
        if (source != dev.nitrate.core.cache.AnswerSource.FETCHED) {
            val (id, tag) = queryLog.nextTag()
            val path = segments.joinToString("/")
            val endpoint = dev.nitrate.core.diagnostics.QueryLog.endpointOf(path)
            val params = query.filterValues { it != null }.mapValues { it.value.toString() }
            queryLog.add(
                dev.nitrate.core.diagnostics.QueryRecord(
                    id = id, tag = tag, atMs = nowEpochMs(), method = "GET", endpoint = endpoint,
                    shape = dev.nitrate.core.diagnostics.QueryLog.shapeOf(endpoint, params),
                    origin = dev.nitrate.core.diagnostics.QueryLog.originHere(),
                    ms = started.elapsedNow().inWholeMilliseconds,
                    outcome = if (source == dev.nitrate.core.cache.AnswerSource.JOINED) dev.nitrate.core.diagnostics.QueryOutcome.JOINED else dev.nitrate.core.diagnostics.QueryOutcome.KEPT,
                    request = requestOf(path, params),
                ),
            )
        }
        return value
    }

    /** A request as the query log tells repeats apart: path and sorted parameters, paging included. */
    private fun requestOf(path: String, params: Map<String, String>) =
        path + params.entries.sortedBy { it.key.lowercase() }.joinToString("&", prefix = "?") { "${it.key.lowercase()}=${it.value}" }

    private fun listQuery(segments: Array<out String>, query: Map<String, Any?>): Map<String, Any?> =
        if (segments.joinToString("/") in COUNTING_LISTS && query.keys.none { it.equals("enableTotalRecordCount", true) })
            query + ("enableTotalRecordCount" to false) else query

    /** What a list of titles is kept under: the account and the whole request. */
    private fun answerKey(segments: Array<out String>, query: Map<String, Any?>): String =
        (accountKey ?: "$baseUrl|$userId") + "|" + url(*segments, query = listQuery(segments, query))

    /** Keeps [items] as the answer to that list request, as if just asked (from the Companion's bundle). */
    internal suspend fun keepList(segments: Array<out String>, query: Map<String, Any?>, items: List<MediaItem>) =
        answers.put(answerKey(segments, query), answerKind(segments), items)

    // ---- The Companion plugin's lighter ways (each falls back to the usual requests) ----

    private var companionFeatures: Pair<String, Set<String>>? = null
    private var companionFeaturesAt = kotlin.time.TimeSource.Monotonic.markNow()

    /** Whether the Companion plugin offers [feature], asked at most every few minutes per account. */
    internal suspend fun companionHas(feature: String): Boolean {
        val account = accountKey ?: "$baseUrl|$userId"
        val known = companionFeatures?.takeIf { it.first == account && companionFeaturesAt.elapsedNow().inWholeMilliseconds < COMPANION_FEATURES_MS }
        // A failed ask isn't kept: the next one tries again (a server without the Companion is kept).
        val features = known?.second ?: runCatching { companionApi.info()?.features.orEmpty() }.onSuccess {
            companionFeatures = account to it
            companionFeaturesAt = kotlin.time.TimeSource.Monotonic.markNow()
        }.getOrDefault(emptySet())
        return feature in features
    }

    internal suspend fun <T> withQueryOriginHere(part: String, block: suspend () -> T): T = dev.nitrate.core.diagnostics.withQueryOrigin(part, block)

    override suspend fun queryTrends(days: Int): List<dev.nitrate.core.companion.QueryTrend> =
        if (!companionHas("querytimes")) emptyList()
        else runCatching {
            get("Nitrate", "QueryTimes", "Trends", query = mapOf("days" to days)).decode<List<QueryTrendDto>>()
                .map { t -> dev.nitrate.core.companion.QueryTrend(t.shape, t.days.map { dev.nitrate.core.companion.QueryTrendDay(it.day, it.count, it.medianMs, it.slowMs) }) }
        }.getOrDefault(emptyList())

    override suspend fun segmentFeedback(itemId: String, part: dev.nitrate.core.companion.SegmentPart) {
        if (!companionHas("segmentfeedback")) return
        val name = part.name.lowercase().replaceFirstChar { it.uppercase() }
        runCatching { post<SegmentFeedbackDto>("Nitrate", "Segments", "Feedback", body = SegmentFeedbackDto(dashless(listOf(itemId)), name)) }
    }

    override suspend fun sittings(fromMs: Long, toMs: Long): List<dev.nitrate.core.companion.Sitting> =
        if (!companionHas("watching")) emptyList()
        else runCatching {
            get("Nitrate", "Watching", query = mapOf("from" to fromMs, "to" to toMs)).decode<List<SittingDto>>()
                .map { dev.nitrate.core.companion.Sitting(normalizeId(it.itemId), it.kind, it.name, it.seriesName, it.startedAt, it.endedAt, it.watchedMs) }
        }.getOrDefault(emptyList())

    /** Titles already reported from this device this run, so each is sent once. */
    private val reportedProblems = mutableSetOf<String>()

    override suspend fun reportProblem(itemId: String, failed: Boolean, reason: String?) {
        val kind = if (failed) "Failed" else "Converted"
        if (!reportedProblems.add("$itemId|$kind") || !companionHas("problems")) return
        runCatching {
            post<ProblemPostDto>("Nitrate", "Problems", body = ProblemPostDto(dashless(listOf(itemId)), identity.deviceId, identity.deviceName, kind, reason?.take(200)))
        }
    }

    override suspend fun problemTitles(): List<dev.nitrate.core.companion.ProblemTitle> =
        if (!companionHas("problems")) emptyList()
        else runCatching {
            get("Nitrate", "Problems").decode<List<ProblemTitleDto>>()
                .map { dev.nitrate.core.companion.ProblemTitle(normalizeId(it.itemId), it.name, it.devices, it.failed, it.converted, it.reasons, it.deviceNames, it.lastAt) }
        }.getOrDefault(emptyList())

    private fun MomentDto.toMoment() = dev.nitrate.core.companion.Moment(normalizeId(id), normalizeId(userId), userName, normalizeId(itemId), positionMs, note, groupId?.let(::normalizeId), at)

    override suspend fun moments(itemId: String?): List<dev.nitrate.core.companion.Moment> =
        if (!companionHas("moments")) emptyList()
        else runCatching {
            get("Nitrate", "Moments", query = mapOf("itemId" to itemId?.let { dashless(listOf(it)) })).decode<List<MomentDto>>().map { it.toMoment() }
        }.getOrDefault(emptyList())

    private fun NoteDto.toNote() = dev.nitrate.core.companion.TimelineNote(
        normalizeId(id), normalizeId(userId), userName, normalizeId(itemId), positionMs,
        if (kind == "Reaction") dev.nitrate.core.companion.ChatKind.REACTION else dev.nitrate.core.companion.ChatKind.TEXT,
        text, groupIds.map(::normalizeId), at, moment, brought = brought, chatId = chatId?.let(::normalizeId),
    )

    override suspend fun titleNotes(itemId: String): dev.nitrate.core.companion.TitleNotes? = when {
        companionHas("notes") ->
            get("Nitrate", "Notes", query = mapOf("itemId" to dashless(listOf(itemId)))).decode<NotesPageDto>().let { page ->
                dev.nitrate.core.companion.TitleNotes(page.groups.map { dev.nitrate.core.companion.NoteGroup(normalizeId(it.id), it.title) }, page.notes.map { it.toNote() })
            }
        // Before notes: the moments, read as notes (nothing can be written).
        companionHas("moments") -> dev.nitrate.core.companion.TitleNotes(emptyList(), moments(itemId).map {
            dev.nitrate.core.companion.TimelineNote(it.id, it.userId, it.userName, it.itemId, it.positionMs, dev.nitrate.core.companion.ChatKind.TEXT, it.note.orEmpty(), listOfNotNull(it.groupId), it.atMs, moment = true)
        })
        else -> null
    }

    override suspend fun pinNote(itemId: String, positionMs: Long, kind: dev.nitrate.core.companion.ChatKind, text: String, sessionId: String?): dev.nitrate.core.companion.TimelineNote =
        pinNote(itemId, positionMs, kind, text, sessionId, null)

    override suspend fun pinNote(itemId: String, positionMs: Long, kind: dev.nitrate.core.companion.ChatKind, text: String, sessionId: String?, groupIds: List<String>?): dev.nitrate.core.companion.TimelineNote =
        companionApi.pinNote(
            NotePostDto(
                dashless(listOf(itemId)), positionMs, text, if (kind == dev.nitrate.core.companion.ChatKind.REACTION) "Reaction" else "Text", sessionId,
                groupIds = groupIds?.takeIf { companionHas("notegroups") }?.map { dashless(listOf(it)) },
            ),
        ).toNote()

    override suspend fun bringNotes(sessionId: String, groupId: String?) {
        if (!companionHas("livenotes")) throw UnsupportedOperationException("Not supported by this server")
        try {
            http.put(url("Nitrate", "Notes", "Brought", sessionId)) {
                auth()
                contentType(ContentType.Application.Json)
                setBody(BringPostDto(groupId?.let { dashless(listOf(it)) }))
            }
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status == HttpStatusCode.Forbidden || e.response.status == HttpStatusCode.NotFound) throw dev.nitrate.core.companion.ChatForbidden()
            throw e
        }
    }

    override suspend fun titleThreads(itemId: String): dev.nitrate.core.companion.TitleThreads? =
        if (!companionHas("threads")) null
        else get("Nitrate", "Threads", query = mapOf("itemId" to dashless(listOf(itemId)))).decode<ThreadsPageDto>().let { page ->
            dev.nitrate.core.companion.TitleThreads(
                page.groups.map { dev.nitrate.core.companion.NoteGroup(normalizeId(it.id), it.title) },
                page.threads.map { g ->
                    dev.nitrate.core.companion.ThreadGroup(
                        normalizeId(g.groupId), g.title, normalizeId(g.titleId), g.canStart,
                        g.threads.map { dev.nitrate.core.companion.ChatThread(normalizeId(it.id), it.started, it.count, it.current, it.favourite) },
                    )
                },
                page.notes.map { it.toNote() },
            )
        }

    override suspend fun threadNotes(itemId: String, threadId: String): List<dev.nitrate.core.companion.TimelineNote> =
        if (!companionHas("threads")) emptyList()
        else try {
            get("Nitrate", "Threads", dashless(listOf(threadId)), "Notes", query = mapOf("itemId" to dashless(listOf(itemId)))).decode<List<NoteDto>>().map { it.toNote() }
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound || e.response.status == HttpStatusCode.Forbidden) emptyList() else throw e
        }

    override suspend fun startThread(itemId: String, groupId: String) {
        if (!companionHas("threads")) throw UnsupportedOperationException("Not supported by this server")
        try {
            http.post(url("Nitrate", "Threads", "New")) {
                auth()
                contentType(ContentType.Application.Json)
                setBody(ThreadPostDto(dashless(listOf(itemId)), dashless(listOf(groupId))))
            }
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status == HttpStatusCode.Forbidden || e.response.status == HttpStatusCode.NotFound) throw dev.nitrate.core.companion.ChatForbidden()
            throw e
        }
    }

    override suspend fun favouriteThread(itemId: String, threadId: String, favourite: Boolean) {
        if (!companionHas("threads")) throw UnsupportedOperationException("Not supported by this server")
        try {
            http.put(url("Nitrate", "Threads", dashless(listOf(threadId)), "Favourite")) {
                auth()
                contentType(ContentType.Application.Json)
                setBody(FavouritePostDto(dashless(listOf(itemId)), favourite))
            }
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status == HttpStatusCode.Forbidden || e.response.status == HttpStatusCode.NotFound) throw dev.nitrate.core.companion.ChatForbidden()
            throw e
        }
    }

    override suspend fun playAlong(sessionId: String, itemId: String, chatId: String?) {
        if (!companionHas("threads")) throw UnsupportedOperationException("Not supported by this server")
        try {
            http.put(url("Nitrate", "Chats", "Session", sessionId)) {
                auth()
                contentType(ContentType.Application.Json)
                setBody(AlongPostDto(dashless(listOf(itemId)), chatId?.let { dashless(listOf(it)) }))
            }
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status == HttpStatusCode.Forbidden || e.response.status == HttpStatusCode.NotFound) throw dev.nitrate.core.companion.ChatForbidden()
            throw e
        }
    }

    override suspend fun sessionChat(sessionId: String, itemId: String): dev.nitrate.core.companion.SessionChat? =
        if (!companionHas("threads")) null
        else runCatching {
            get("Nitrate", "Chats", "Session", sessionId, query = mapOf("itemId" to dashless(listOf(itemId)))).decode<SessionChatDto>().let { page ->
                val by = page.by?.let(::normalizeId)
                dev.nitrate.core.companion.SessionChat(
                    page.chatId?.let(::normalizeId), page.along?.let(::normalizeId), by,
                    page.notes.map { n -> n.toNote().let { if (it.brought) it.copy(broughtBy = by) else it } },
                )
            }
        }.getOrNull()

    override suspend fun broughtNotes(sessionId: String, itemId: String): List<dev.nitrate.core.companion.TimelineNote> =
        if (!companionHas("livenotes")) emptyList()
        else runCatching {
            get("Nitrate", "Notes", "Brought", sessionId, query = mapOf("itemId" to dashless(listOf(itemId)))).decode<BroughtNotesDto>().let { page ->
                page.notes.map { it.toNote().copy(brought = true, broughtBy = page.by?.let(::normalizeId)) }
            }
        }.getOrDefault(emptyList())

    override suspend fun deleteNote(itemId: String, id: String) {
        http.delete(url("Nitrate", "Notes", dashless(listOf(id)))) {
            auth()
            url { parameters.append("itemId", dashless(listOf(itemId))) }
        }
    }

    override suspend fun chatHistory(itemId: String): List<dev.nitrate.core.companion.ChatRecord> =
        if (!companionHas("chathistory")) emptyList()
        else runCatching {
            get("Nitrate", "Chat", "History", query = mapOf("itemId" to dashless(listOf(itemId)))).decode<List<ChatRecordDto>>()
                .map { dev.nitrate.core.companion.ChatRecord(normalizeId(it.from), it.name, it.kind == "Reaction", it.text, it.positionMs, it.at) }
        }.getOrDefault(emptyList())

    /** Every person's requests by kind (Companion, admins only); empty without it. */
    override suspend fun householdQueries(): List<dev.nitrate.core.diagnostics.HouseholdQuery> =
        if (!companionHas("querytimes")) emptyList()
        else runCatching {
            get("Nitrate", "QueryTimes", "Household").decode<List<HouseholdQueryDto>>()
                .map { dev.nitrate.core.diagnostics.HouseholdQuery(it.shape, it.count, it.people, it.totalMs, it.medianMs, it.slowMs, it.maxMs) }
        }.getOrDefault(emptyList())

    private fun answerKind(segments: Array<out String>): dev.nitrate.core.cache.AnswerKind = when (segments.lastOrNull()) {
        "Similar" -> dev.nitrate.core.cache.AnswerKind.SIMILAR
        "UserViews" -> dev.nitrate.core.cache.AnswerKind.VIEWS
        "Latest" -> dev.nitrate.core.cache.AnswerKind.LATEST
        "NextUp", "Resume" -> dev.nitrate.core.cache.AnswerKind.WATCHING
        // A show's seasons and a season's episodes, the collections a title is in.
        "Seasons", "Episodes", "Collections" -> dev.nitrate.core.cache.AnswerKind.STRUCTURE
        else -> dev.nitrate.core.cache.AnswerKind.ITEMS
    }

    override suspend fun moreOfRow(more: dev.nitrate.core.rows.RowMore, startIndex: Int, limit: Int): List<MediaItem> =
        items(*more.path.toTypedArray(), query = more.params + mapOf("startIndex" to startIndex, "limit" to limit))

    override suspend fun views(): List<MediaItem> = items("UserViews", query = emptyMap()).also { v ->
        knownViews = viewsKey() to v
        runCatching { textCache?.put(viewsKey(), JellyfinJson.encodeToString(ListSerializer(MediaItem.serializer()), v)) }
        val at = nowEpochMs()
        viewsReadAt = viewsKey() to at
        runCatching { textCache?.put("views-at/${viewsKey().removePrefix("views/")}", at.toString()) }
    }

    /** When the library list was last read from the server (this account), this run or kept from an earlier one. */
    internal var viewsReadAt: Pair<String, Long>? = null

    /**
     * Whether the library list kept on the device is recent enough to use without asking: read within
     * [VIEWS_KEEP_MS], with no library change heard since. Libraries are added or renamed rarely, and
     * asking costs seconds on a busy server.
     */
    internal suspend fun viewsRecent(): Boolean {
        val key = viewsKey()
        val at = viewsReadAt?.takeIf { it.first == key }?.second
            ?: textCache?.get("views-at/${key.removePrefix("views/")}")?.toLongOrNull()?.also { viewsReadAt = key to it }
            ?: return false
        return nowEpochMs() - at in 0 until VIEWS_KEEP_MS
    }

    /** A library changed: the kept library list is read again next time it's needed. */
    private suspend fun viewsStale() {
        val key = viewsKey()
        viewsReadAt = key to 0L
        runCatching { textCache?.put("views-at/${key.removePrefix("views/")}", "0") }
    }

    internal var knownViews: Pair<String, List<MediaItem>>? = null

    /**
     * Rows that came back empty and are costly to ask (Continue Reading and Listening sort the whole
     * library by date played): "account|row key" → until when they're taken as still empty. Any change in
     * watch state (reading or listening progress included, from any app) lets them all go.
     */
    internal val quietRows = HashMap<String, Long>()
    internal val quietLock = kotlinx.coroutines.sync.Mutex()

    @kotlinx.serialization.Serializable
    internal data class SavedRows(val savedAtMs: Long, val rows: List<MediaRow>)

    internal var savedBecause: SavedRows? = null

    internal var savedBecauseKey: String? = null

    /**
     * One of the app's own rows for any page ([dev.nitrate.core.rows.AppRows]), through the same
     * requests as Home's, so a page showing it alongside Home shares Home's answers.
     */
    override suspend fun appRow(key: String, libraryId: String?): MediaRow? {
        val rows = dev.nitrate.core.rows.AppRows
        fun row(items: List<MediaItem>, title: String, display: RowDisplay = WIDE_DEFAULT, more: dev.nitrate.core.rows.RowMore? = null) =
            if (items.isEmpty()) null else MediaRow("app/$key", title, items, display, more)
        val nextUpQuery = mapOf("limit" to ROW_LIMIT, "fields" to ROW_FIELDS, "parentId" to libraryId)
        return when (key) {
            rows.CONTINUE_WATCHING -> row(resumeItems(libraryId, null), "Continue Watching", more = resumeMore(libraryId, null))
            rows.NEXT_UP -> row(items("Shows", "NextUp", query = nextUpQuery), "Next Up", more = rowMore("Shows", "NextUp", query = nextUpQuery))
            rows.CONTINUE_AND_NEXT -> coroutineScope {
                val resume = async { resumeItems(libraryId, null) }
                val next = async { items("Shows", "NextUp", query = nextUpQuery) }
                row(dev.nitrate.core.rows.ContinueAndNext.merge(resume.await(), next.await()), dev.nitrate.core.rows.ContinueAndNext.TITLE)
            }
            rows.BECAUSE_1, rows.BECAUSE_2 -> {
                val slot = if (key == rows.BECAUSE_1) 0 else 1
                val key0 = dev.nitrate.core.rows.BecauseYouWatched.slots().keys.toList().getOrNull(slot)
                becauseYouWatched(false).firstOrNull { it.key == key0 }
            }
            rows.LATEST -> {
                val id = libraryId ?: return null
                val view = (lastViews() ?: views()).firstOrNull { it.id == id } ?: views().firstOrNull { it.id == id } ?: return null
                row(latestIn(view), "Latest in ${view.name}", RowDisplay(), latestMore(view))
            }
            rows.CONTINUE_LISTENING -> row(
                items("UserItems", "Resume", query = mapOf("limit" to ROW_LIMIT, "fields" to ROW_FIELDS, "mediaTypes" to "Audio", "includeItemTypes" to "AudioBook")),
                "Continue Listening", RowDisplay(),
            )
            rows.CONTINUE_READING -> row(continueReading(libraryId), "Continue Reading", RowDisplay())
            rows.WATCHING_TOGETHER -> row(watchingTogether(), dev.nitrate.core.partners.TogetherRow.TITLE)
            else -> null
        }
    }

    override fun homeRowsProgress(skip: Set<String>): Flow<dev.nitrate.core.rows.RowsProgress> = dev.nitrate.core.rows.ProgressiveRows.run(homeJobs(skip = skip), skip)

    override fun homeRowsProgress(skip: Set<String>, combineContinueNextUp: Boolean): Flow<dev.nitrate.core.rows.RowsProgress> =
        dev.nitrate.core.rows.ProgressiveRows.run(homeJobs(skip = skip, combine = combineContinueNextUp), skip)

    override suspend fun homeRows(): List<MediaRow> = homeRowsProgress().last().rows

    override suspend fun watchingRows(latest: Boolean): List<MediaRow> {
        // Only the jobs these rows come from: Continue Watching, Next Up and (when asked) each "Latest in…".
        val wanted = homeJobs().filter { it.key == "home/continue_watching" || it.key == "home/next_up" || (latest && it.name == LATEST_JOB) }
        return dev.nitrate.core.rows.ProgressiveRows.run(wanted).last().rows
    }

    /** Movies and episodes finished most recently. */
    private suspend fun recentlyWatched(): List<MediaItem> = items(
        "Items",
        query = mapOf(
            "userId" to userId,
            "includeItemTypes" to "Movie,Episode",
            "recursive" to true,
            "filters" to "IsPlayed",
            "sortBy" to "DatePlayed",
            "sortOrder" to "Descending",
            "limit" to 20,
        ),
    )

    override fun libraryRowsProgress(library: MediaItem, skip: Set<String>): Flow<dev.nitrate.core.rows.RowsProgress> = dev.nitrate.core.rows.ProgressiveRows.run(libraryJobs(library), skip)

    override suspend fun libraryRows(library: MediaItem): List<MediaRow> = libraryRowsProgress(library).last().rows

    override suspend fun continueReading(libraryId: String?): List<MediaItem> = coroutineScope {
        val reading = async { books(libraryId, "IsResumable", ROW_LIMIT) }
        val finished = async { books(libraryId, "IsPlayed", 10) }
        dev.nitrate.core.comics.ComicSeries.continueReading(reading.await(), finished.await(), issuesOf = ::seriesIssues)
    }

    override suspend fun seriesIssues(book: MediaItem): List<MediaItem> {
        val parent = book.parentId ?: return listOf(book)
        val siblings = items(
            "Items",
            query = mapOf("userId" to userId, "parentId" to parent, "includeItemTypes" to "Book", "sortBy" to "SortName", "fields" to ROW_FIELDS, "limit" to 1000),
        )
        return dev.nitrate.core.comics.ComicSeries.of(book, siblings.ifEmpty { listOf(book) })
    }

    override suspend fun libraryPage(library: MediaItem, sort: LibrarySort, startIndex: Int, limit: Int, includeTags: Boolean): Pair<List<MediaItem>, Int> {
        val types = when (library.collection) {
            CollectionKind.MOVIES -> "Movie"
            CollectionKind.TV_SHOWS -> "Series"
            CollectionKind.BOX_SETS -> "BoxSet"
            CollectionKind.PLAYLISTS -> "Playlist"
            else -> null
        }
        val (field, order) = when (sort) {
            LibrarySort.NAME -> "SortName" to "Ascending"
            LibrarySort.DATE_ADDED -> "DateCreated" to "Descending"
            LibrarySort.RELEASE_DATE -> "PremiereDate" to "Descending"
            LibrarySort.RATING -> "CommunityRating" to "Descending"
            LibrarySort.RANDOM -> "Random" to "Ascending"
        }
        val result = get(
            "Items",
            query = mapOf(
                "parentId" to library.id,
                "includeItemTypes" to types,
                "recursive" to (types != null),
                "sortBy" to "$field,SortName",
                "sortOrder" to "$order,Ascending",
                "startIndex" to startIndex,
                "limit" to limit,
                "fields" to if (includeTags) "$ROW_FIELDS,Tags" else ROW_FIELDS,
                // Only the first page counts every match; later pages know the total already.
                "enableTotalRecordCount" to (startIndex == 0),
            ),
        ).decode<QueryResultDto<ItemDto>>()
        return result.items.map { it.toMediaItem() } to pageTotal(startIndex, limit, result.items.size, result.totalRecordCount)
    }

    override suspend fun getItem(id: String): MediaItem {
        if (refused("item", id)) return itemById(id) ?: throw dev.nitrate.core.ItemUnavailable(id)
        return try {
            get("Items", id).decode<ItemDto>().toMediaItem()
        } catch (e: ClientRequestException) {
            // Jellyfin answers 404 for a playlist someone shared with the user (it checks only the
            // owner there), though lists and the playlist's own items show it: found by id instead.
            if (e.response.status != HttpStatusCode.NotFound) throw e
            refuse("item", id)
            itemById(id) ?: throw dev.nitrate.core.ItemUnavailable(id)
        }
    }

    // Lookups the server answered 404 to, for this account (kind:id → when), so each page load
    // doesn't ask again: a shared playlist's single lookup and its items (Jellyfin checks only the
    // owner there), or a title that's gone. Forgotten after [REFUSED_MS].
    private val refusals = mutableMapOf<String, Long>()
    private val refusalLock = kotlinx.coroutines.sync.Mutex()

    private fun refusalKey(kind: String, id: String) = "${accountKey ?: baseUrl}|$kind|$id"

    private suspend fun refused(kind: String, id: String): Boolean = refusalLock.withLock {
        val key = refusalKey(kind, id)
        val at = refusals[key] ?: return@withLock false
        if (nowEpochMs() - at < REFUSED_MS) true else { refusals.remove(key); false }
    }

    private suspend fun refuse(kind: String, id: String) = refusalLock.withLock {
        if (refusals.size > 500) refusals.clear()
        refusals[refusalKey(kind, id)] = nowEpochMs()
    }

    /** One item through the list lookup (which goes by what the user can see, shares included), or null. */
    internal suspend fun itemById(id: String): MediaItem? =
        get("Items", query = mapOf("ids" to dashless(listOf(id)), "fields" to BY_ID_FIELDS, "enableTotalRecordCount" to false))
            .decode<QueryResultDto<ItemDto>>().items.firstOrNull()?.toMediaItem()

    override suspend fun seasons(seriesId: String) = items("Shows", seriesId, "Seasons", query = mapOf("fields" to "Overview"))

    override suspend fun episodes(seriesId: String, seasonId: String) =
        items("Shows", seriesId, "Episodes", query = mapOf("seasonId" to seasonId, "fields" to "Overview,PrimaryImageAspectRatio"))

    override suspend fun allEpisodes(seriesId: String): List<MediaItem> {
        val all = get("Shows", seriesId, "Episodes", query = mapOf("enableUserData" to true)).decode<QueryResultDto<ItemDto>>().items
        // Some servers list season 0 only when asked for it directly.
        val specials = runCatching {
            get("Shows", seriesId, "Episodes", query = mapOf("season" to 0, "enableUserData" to true)).decode<QueryResultDto<ItemDto>>().items
        }.getOrDefault(emptyList())
        // Placeholder "missing" episodes are excluded even if the server creates them.
        return (all + specials).distinctBy { it.id }.filter { it.locationType != "Virtual" }.map { it.toMediaItem() }
    }

    override suspend fun nextUpFor(seriesId: String): MediaItem? =
        items("Shows", "NextUp", query = mapOf("seriesId" to seriesId, "limit" to 1, "enableResumable" to true)).firstOrNull()

    override suspend fun similar(itemId: String) = items("Items", itemId, "Similar", query = mapOf("limit" to 16, "fields" to "PrimaryImageAspectRatio"))

    override suspend fun playlistItems(playlistId: String): List<MediaItem> = playlistEntries(playlistId, "Overview,PrimaryImageAspectRatio,Genres,Tags")

    /**
     * A title's episodes or entries for working out where people are in it (group standings,
     * Watching together): a playlist's entries without their descriptions, genres and tags, which
     * a big one makes most of the answer.
     */
    override suspend fun titleItems(titleId: String): List<MediaItem> {
        val title = getItem(titleId)
        if (title.kind != ItemKind.PLAYLIST) return super.titleItems(titleId)
        return playlistEntries(titleId, "PrimaryImageAspectRatio").flatMap { if (it.kind == ItemKind.SERIES) allEpisodes(it.id) else listOf(it) }
    }

    private suspend fun playlistEntries(playlistId: String, fields: String): List<MediaItem> {
        if (refused("playlist", playlistId)) throw dev.nitrate.core.ItemUnavailable(playlistId)
        return try {
            items("Playlists", playlistId, "Items", query = mapOf("fields" to fields))
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) { refuse("playlist", playlistId); throw dev.nitrate.core.ItemUnavailable(playlistId) }
            throw e
        }
    }

    override suspend fun playlistFacts(sinceMs: Long): List<dev.nitrate.core.playlists.PlaylistFacts>? {
        if (!companionHas("playlistfacts")) return null
        return shared(dev.nitrate.core.cache.AnswerKind.WATCHING, arrayOf("Nitrate", "Playlists", "Watching"), mapOf("since" to sinceMs)) {
            get("Nitrate", "Playlists", "Watching", query = mapOf("since" to sinceMs)).decode<List<PlaylistFactsDto>>().map { it.toFacts() }
        }
    }

    override suspend fun cardsFor(ids: List<String>): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val found = items(
            "Items",
            query = mapOf("ids" to dashless(ids), "userId" to userId, "fields" to ROW_FIELDS, "enableTotalRecordCount" to false),
        ).associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    override suspend fun groupPlay(itemId: String, groupIds: List<String>, before: dev.nitrate.core.model.UserState) {
        if (!companionHas("groupplay")) return
        groupPlays.update { it + (itemId to before) }
        post<GroupPlayDto>(
            "Nitrate", "Partners", "GroupPlay",
            body = GroupPlayDto(
                dashless(listOf(itemId)), groupIds.map { dashless(listOf(it)) },
                OwnStateDto(before.played, before.positionMs, before.playCount, before.lastPlayedMs),
            ),
        )
    }

    override suspend fun changeCheck(since: String?): dev.nitrate.core.rows.ChangeCheck? {
        if (!companionHas("changes")) return null
        return runCatching {
            // Never shared or kept: the point is what's true this moment.
            val c = get("Nitrate", "Changes").decode<ChangesDto>()
            val token = dev.nitrate.core.rows.RowChanges.token(c.epoch, c.library, c.views, c.playlists, c.watch, c.because)
            dev.nitrate.core.rows.ChangeCheck(token, dev.nitrate.core.rows.RowChanges.compare(since, token))
        }.getOrNull()
    }

    override suspend fun withFreshMarks(rows: List<MediaRow>): List<MediaRow> = runCatching { withFreshUserData(rows) }.getOrDefault(rows)

    override suspend fun playlistRow(playlistId: String, limit: Int): List<MediaItem> {
        if (refused("playlist", playlistId)) throw dev.nitrate.core.ItemUnavailable(playlistId)
        return items("Playlists", playlistId, "Items", query = mapOf("fields" to ROW_FIELDS, "startIndex" to 0, "limit" to limit))
    }

    override fun moreOfPlaylist(playlistId: String): dev.nitrate.core.rows.RowMore =
        rowMore("Playlists", playlistId, "Items", query = mapOf("fields" to ROW_FIELDS))

    override suspend fun playlistMembers(playlistId: String) =
        items("Playlists", playlistId, "Items", query = mapOf("enableImages" to false, "enableUserData" to false))

    override suspend fun recentlyPlayed(limit: Int): List<MediaItem> = items(
        "Items",
        query = mapOf(
            "userId" to userId,
            "includeItemTypes" to "Movie,Episode",
            "recursive" to true,
            "sortBy" to "DatePlayed",
            "sortOrder" to "Descending",
            "limit" to limit,
            "enableTotalRecordCount" to false,
            "enableImages" to false,
        ),
    ).filter { it.user.lastPlayedMs != null }

    override suspend fun setPlayed(itemId: String, played: Boolean) {
        saying { if (played) post<Unit>("UserPlayedItems", itemId) else http.delete(url("UserPlayedItems", itemId)) { auth() } }
        watchStateFromServer(listOf(itemId))
    }

    override suspend fun setFavorite(itemId: String, favorite: Boolean) = saying {
        if (favorite) post<Unit>("UserFavoriteItems", itemId) else http.delete(url("UserFavoriteItems", itemId)) { auth() }
    }

    /** [block], a refusal said in words: "the server said 403 Forbidden: <its reason>" (for a page to show). */
    private suspend fun saying(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: io.ktor.client.plugins.ResponseException) {
            val why = runCatching { e.response.bodyAsText().trim().trim('"') }.getOrNull()?.takeIf { it.isNotBlank() && it.length < 300 }
            throw IllegalStateException("the server said ${e.response.status.value} ${e.response.status.description}" + (why?.let { ": $it" } ?: ""), e)
        }
    }

    // ---- Images (public on Jellyfin; tags make them cache-safe) ----

    internal fun image(itemId: String, type: String, tag: String, index: Int? = null, maxWidth: Int? = null, maxHeight: Int? = null): String =
        url(*listOfNotNull("Items", itemId, "Images", type, index?.toString()).toTypedArray(), query = mapOf("maxWidth" to maxWidth, "maxHeight" to maxHeight, "tag" to tag))

    override fun imageUrl(item: MediaItem, kind: ImageKind, maxHeight: Int): String? {
        if (kind == ImageKind.BACKDROP) return backdropUrl(item)
        if (kind == ImageKind.LOGO) return logoUrl(item)
        val type = if (kind == ImageKind.THUMB) "Thumb" else "Primary"
        val tag = if (kind == ImageKind.THUMB) item.images.thumb else item.images.primary
        val series = item.seriesId
        return when {
            tag != null -> image(item.id, type, tag, maxHeight = maxHeight)
            // Episodes without art of their own fall back to the series poster.
            kind == ImageKind.PRIMARY && series != null && item.images.seriesPrimary != null -> image(series, type, item.images.seriesPrimary, maxHeight = maxHeight)
            // Tracks without art of their own show their album's cover.
            kind == ImageKind.PRIMARY && item.albumId != null && item.images.albumPrimary != null -> image(item.albumId, type, item.images.albumPrimary, maxHeight = maxHeight)
            else -> null
        }
    }

    override fun backdropUrls(item: MediaItem): List<String> {
        if (item.images.backdrops.isNotEmpty()) return item.images.backdrops.mapIndexed { i, tag -> image(item.id, "Backdrop", tag, i, maxWidth = 1920) }
        val parent = item.images.parentBackdropItemId ?: return emptyList()
        return item.images.parentBackdrops.mapIndexed { i, tag -> image(parent, "Backdrop", tag, i, maxWidth = 1920) }
    }

    override fun backdropUrl(item: MediaItem): String? = backdropUrl(item, 1920)

    override fun backdropUrl(item: MediaItem, maxWidth: Int): String? {
        item.images.backdrops.firstOrNull()?.let { return image(item.id, "Backdrop", it, maxWidth = maxWidth) }
        val parent = item.images.parentBackdropItemId ?: return null
        return item.images.parentBackdrops.firstOrNull()?.let { image(parent, "Backdrop", it, maxWidth = maxWidth) }
    }

    override fun logoUrl(item: MediaItem): String? {
        item.images.logo?.let { return image(item.id, "Logo", it, maxWidth = 800) }
        val parent = item.images.parentLogoItemId ?: return null
        return item.images.parentLogo?.let { image(parent, "Logo", it, maxWidth = 800) }
    }

    override fun personImageUrl(person: Person): String? = person.imageTag?.let { image(person.id, "Primary", it, maxHeight = 300) }

    // ---- Playback ----

    override fun authorizationHeader(): String = authHeader(token)

    override fun fileUrl(itemId: String): String = url("Items", itemId, "Download")

    private fun authHeader(token: String?): String = buildString {
        fun param(name: String, value: String) = append(name).append("=\"").append(value.encodeURLParameter()).append('"')
        append("MediaBrowser ")
        param("Client", identity.clientName); append(", ")
        param("Device", identity.deviceName); append(", ")
        param("DeviceId", identity.deviceId); append(", ")
        param("Version", identity.version)
        token?.let { append(", "); param("Token", it) }
    }

    override suspend fun channelTestClips(): List<ChannelClip> {
        val items = get(
            "Items",
            query = mapOf(
                "searchTerm" to ChannelTest.SERVER_TAG,
                "recursive" to true,
                "includeItemTypes" to "Movie,Video,Episode,Audio,MusicVideo",
                "fields" to "MediaSources,MediaStreams",
                "limit" to 50,
            ),
        ).decode<QueryResultDto<ItemDto>>().items
        return items.filter { it.name?.contains(ChannelTest.SERVER_TAG, ignoreCase = true) == true }.map { item ->
            val source = item.mediaSources?.firstOrNull()
            val streams = source?.mediaStreams.orEmpty()
            val audio = streams.firstOrNull { it.index == source?.defaultAudioStreamIndex } ?: streams.firstOrNull { it.type == "Audio" }
            // Always the original file: the point is to send the real bitstream to the receiver.
            val url = streamUrl(normalizeId(item.id), item.type == "Audio", source?.id, null, null)
            ChannelTest.fromServer(normalizeId(item.id), item.name.orEmpty(), url, audio?.channels, audio?.displayTitle ?: audio?.codec?.uppercase() ?: "Unknown audio")
        }
    }

    private fun streamUrl(itemId: String, isAudio: Boolean, mediaSourceId: String?, playSessionId: String?, tag: String?) =
        url(if (isAudio) "Audio" else "Videos", itemId, "stream", query = mapOf("static" to true, "mediaSourceId" to mediaSourceId, "playSessionId" to playSessionId, "tag" to tag))

    override suspend fun resolvePlayback(
        itemId: String,
        isAudio: Boolean,
        startMs: Long,
        forceTranscode: Boolean,
        audioIndex: Int?,
        subtitleIndex: Int?,
        mediaSourceId: String?,
        deviceCaps: DeviceCapabilities?,
    ): PlaybackPlan {
        val settings = playbackSettings.current()
        val directPlay = !forceTranscode && settings.allowDirectPlay
        val directStream = !forceTranscode && settings.allowDirectStream
        val info = post<PlaybackInfoRequestDto>(
            "Items", itemId, "PlaybackInfo",
            body = PlaybackInfoRequestDto(
                deviceProfile = JellyfinDeviceProfile.create(deviceCaps ?: capabilities(), settings),
                maxStreamingBitrate = settings.maxBitrate,
                startTimeTicks = startMs.msToTicks(),
                audioStreamIndex = audioIndex,
                subtitleStreamIndex = subtitleIndex,
                mediaSourceId = mediaSourceId,
                enableDirectPlay = directPlay,
                enableDirectStream = directStream,
                enableTranscoding = true,
                allowVideoStreamCopy = directStream,
                allowAudioStreamCopy = directStream,
                autoOpenLiveStream = true,
            ),
        ).decode<PlaybackInfoResponseDto>()
        info.errorCode?.let { error("Server can't play this item: $it") }
        // With several versions and none chosen, the one this device plays best: one it can play
        // as it is, then one it only needs repackaged, then the first.
        val dto = (if (mediaSourceId != null) info.mediaSources.firstOrNull { it.id == mediaSourceId } else null)
            ?: info.mediaSources.firstOrNull { it.supportsDirectPlay && directPlay }
            ?: info.mediaSources.firstOrNull { it.supportsDirectStream && directStream && it.transcodingUrl != null }
            ?: info.mediaSources.firstOrNull() ?: error("Item has no playable media")
        val source = dto.toMediaSource()

        val streams = source.streams
        val chosenAudio = audioIndex ?: dto.defaultAudioStreamIndex ?: streams.firstOrNull { it.kind == StreamKind.AUDIO }?.index
        val chosenSubtitle = subtitleIndex ?: dto.defaultSubtitleStreamIndex ?: -1
        val external = streams
            .filter { it.kind == StreamKind.SUBTITLE && it.delivery == SubtitleDelivery.EXTERNAL && it.deliveryUrl != null }
            .mapNotNull { s ->
                val mime = subtitleMimeType(s.codec) ?: return@mapNotNull null
                ExternalSubtitle(s.index, absoluteUrl(s.deliveryUrl!!), mime, s.language, s.title ?: s.language ?: "Subtitle ${s.index}")
            }

        val transcodingUrl = dto.transcodingUrl
        val (url, method) = when {
            dto.supportsDirectPlay && directPlay -> streamUrl(itemId, isAudio, dto.id, info.playSessionId, dto.eTag) to PlayMethod.DIRECT_PLAY
            transcodingUrl != null -> absoluteUrl(transcodingUrl) to
                if (dto.supportsDirectStream && directStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
            else -> error("Server offered no way to play this item")
        }

        return PlaybackPlan(
            itemId = itemId,
            url = url,
            method = method,
            playSessionId = info.playSessionId,
            source = source,
            isHls = method != PlayMethod.DIRECT_PLAY && dto.transcodingSubProtocol.equals("hls", ignoreCase = true),
            audioIndex = chosenAudio,
            subtitleIndex = chosenSubtitle,
            externalSubtitles = external,
            transcodeReasons = transcodingUrl?.let(::transcodeReasons).orEmpty(),
            versions = info.mediaSources.mapNotNull { m ->
                VersionOption(m.id ?: return@mapNotNull null, m.name, m.supportsDirectPlay && directPlay, m.supportsDirectStream && directStream, m.bitrate)
            },
        )
    }

    // ---- Administration (implemented in JellyfinAdmin.kt) ----

    override suspend fun account(): AccountInfo = admin.account()
    override suspend fun metadata(itemId: String): ItemMetadata = admin.metadata(itemId)
    override suspend fun saveMetadata(metadata: ItemMetadata) = admin.saveMetadata(metadata)
    override suspend fun remoteSearch(item: MediaItem, name: String, year: Int?, providerIds: Map<String, String>): List<RemoteMatch> = admin.remoteSearch(item, name, year, providerIds)
    override suspend fun externalIdFields(itemId: String) = admin.externalIdFields(itemId)
    override suspend fun libraryMetadataSettings() = admin.libraryMetadataSettings()
    override suspend fun saveLibraryMetadataSettings(settings: dev.nitrate.core.admin.LibraryMetadataSettings) = admin.saveLibraryMetadataSettings(settings)
    override suspend fun metadataLanguages() = admin.metadataLanguages()
    override suspend fun metadataCountries() = admin.metadataCountries()
    override suspend fun imageProviders(itemId: String) = admin.imageProviders(itemId)
    override suspend fun moveImage(itemId: String, type: ImageType, index: Int, newIndex: Int) = admin.moveImage(itemId, type, index, newIndex)
    override suspend fun applyMatch(itemId: String, match: RemoteMatch, replaceImages: Boolean) = admin.applyMatch(itemId, match, replaceImages)
    override suspend fun refreshItem(itemId: String, mode: RefreshMode, replaceImages: Boolean, regenerateTrickplay: Boolean) =
        admin.refreshItem(itemId, mode, replaceImages, regenerateTrickplay)
    override suspend fun itemImages(itemId: String): List<ItemImage> = admin.itemImages(itemId)
    override suspend fun remoteImages(itemId: String, type: ImageType, provider: String?, allLanguages: Boolean): List<RemoteImage> = admin.remoteImages(itemId, type, provider, allLanguages)
    override suspend fun downloadRemoteImage(itemId: String, type: ImageType, url: String) = admin.downloadRemoteImage(itemId, type, url)
    override suspend fun deleteImage(itemId: String, type: ImageType, index: Int?) = admin.deleteImage(itemId, type, index)
    override suspend fun uploadImage(itemId: String, type: ImageType, bytes: ByteArray, mimeType: String) = admin.uploadImage(itemId, type, bytes, mimeType)
    override suspend fun scanAllLibraries() = admin.scanAll()
    override suspend fun tasks(): List<ServerTask> = admin.tasks()
    override suspend fun startTask(taskId: String) = admin.startTask(taskId)
    override suspend fun stopTask(taskId: String) = admin.stopTask(taskId)
    override suspend fun activity(limit: Int): List<ActivityEntry> = admin.activity(limit)
    override suspend fun sessions(): List<ServerSession> = admin.sessions()
    override suspend fun sendMessage(sessionId: String, header: String, text: String) = admin.sendMessage(sessionId, header, text)
    override suspend fun sessionCommand(sessionId: String, command: SessionCommand) = admin.sessionCommand(sessionId, command)
    override suspend fun users(): List<UserAccount> = admin.users()
    override suspend fun setPermissions(userId: String, permissions: UserPermissions) = admin.setPermissions(userId, permissions)
    override suspend fun createUser(name: String, password: String): UserAccount = admin.createUser(name, password)
    override suspend fun deleteUser(userId: String) = admin.deleteUser(userId)
    override suspend fun setPassword(userId: String, newPassword: String) = admin.setPassword(userId, newPassword)
    override suspend fun canEditMetadata(itemId: String): Boolean = admin.canEditMetadata(itemId)
    override suspend fun metadataEditors() = admin.metadataEditors()
    override suspend fun setMetadataEditor(userId: String, scope: dev.nitrate.core.admin.MetadataEditorScope) = admin.setMetadataEditor(userId, scope)
    override suspend fun metadataEdits(limit: Int, before: String?) = admin.metadataEdits(limit, before)
    override suspend fun undoMetadataEdit(editId: String) = admin.undoMetadataEdit(editId)

    private val admin = JellyfinAdmin(this)

    // ---- Subtitles from providers ----

    override suspend fun searchSubtitles(itemId: String, language: String): List<RemoteSubtitle> =
        get("Items", itemId, "RemoteSearch", "Subtitles", language).decode<List<RemoteSubtitleDto>>().map {
            RemoteSubtitle(it.id, it.name ?: it.id, it.threeLetterISOLanguageName, it.providerName, it.format, it.downloadCount, it.communityRating,
                it.isHashMatch == true, it.forced == true, it.hearingImpaired == true, it.machineTranslated == true || it.aiTranslated == true)
        }.sortedWith(compareByDescending<RemoteSubtitle> { it.hashMatch }.thenByDescending { it.downloads ?: 0 })

    override suspend fun downloadSubtitle(itemId: String, subtitleId: String) {
        post<Unit>("Items", itemId, "RemoteSearch", "Subtitles", subtitleId)
    }

    // ---- Remote control ----

    override val deviceId: String get() = identity.deviceId

    private var extraCommands: List<String> = emptyList()

    override fun acceptCommands(commands: List<String>) { extraCommands = commands }

    override suspend fun reportCapabilities() {
        runCatching {
            post<CapabilitiesDto>(
                "Sessions", "Capabilities", "Full",
                body = CapabilitiesDto(
                    playableMediaTypes = listOf("Video", "Audio"),
                    supportedCommands = listOf("DisplayMessage", "VolumeUp", "VolumeDown", "ToggleMute", "SetAudioStreamIndex", "SetSubtitleStreamIndex", "PlayState", "Play") + extraCommands,
                    supportsMediaControl = true,
                ),
            )
        }
    }

    override suspend fun playOn(sessionId: String, itemIds: List<String>, startMs: Long, audioIndex: Int?, subtitleIndex: Int?, mediaSourceId: String?) {
        post<Unit>(
            "Sessions", sessionId, "Playing",
            query = mapOf(
                "playCommand" to "PlayNow", "itemIds" to dashless(itemIds), "startPositionTicks" to startMs.msToTicks(),
                "audioStreamIndex" to audioIndex, "subtitleStreamIndex" to subtitleIndex, "mediaSourceId" to mediaSourceId,
            ),
        )
    }

    override suspend fun controllableSessions(): List<ServerSession> = admin.sessions(controllableBy = userId?.replace("-", ""))

    override suspend fun setSessionTrack(sessionId: String, kind: dev.nitrate.core.model.StreamKind, index: Int) {
        val name = if (kind == dev.nitrate.core.model.StreamKind.AUDIO) "SetAudioStreamIndex" else "SetSubtitleStreamIndex"
        post<GeneralCommandDto>("Sessions", sessionId, "Command", body = GeneralCommandDto(name, mapOf("Index" to index.toString())))
    }

    override suspend fun seekSession(sessionId: String, positionMs: Long) {
        post<Unit>("Sessions", sessionId, "Playing", "Seek", query = mapOf("seekPositionTicks" to positionMs.msToTicks()))
    }

    override suspend fun sendGeneralCommand(sessionId: String, command: GeneralCommand) {
        post<Unit>("Sessions", sessionId, "Command", command.wire)
    }

    override suspend fun showOn(sessionId: String, item: MediaItem) {
        val type = when (item.kind) {
            ItemKind.LIBRARY -> "CollectionFolder"
            ItemKind.SEASON -> "Season"
            else -> itemTypeName(item.kind) ?: "Folder"
        }
        post<GeneralCommandDto>("Sessions", sessionId, "Command", body = GeneralCommandDto(
            GeneralCommand.DISPLAY_CONTENT, mapOf("ItemId" to item.id.replace("-", ""), "ItemType" to type, "ItemName" to item.name),
        ))
    }

    override suspend fun typeOn(sessionId: String, text: String) {
        post<GeneralCommandDto>("Sessions", sessionId, "Command", body = GeneralCommandDto(GeneralCommand.SEND_STRING, mapOf("String" to text)))
    }

    override fun events(): Flow<ServerEvent> = socketEvents { token != null }.onEach {
        when (it) {
            is ServerEvent.LibraryChanged -> { answers.clear(); viewsStale() }
            is ServerEvent.UserDataChanged -> {
                pushed.tryEmit(it)
                withoutRestoring(it)?.let { e -> watchStateChanged(e.states); if (!alreadySaid(e)) watchFlow.tryEmit(e) }
            }
            else -> Unit
        }
    }

    private val watchFlow = kotlinx.coroutines.flow.MutableSharedFlow<ServerEvent.UserDataChanged>(extraBufferCapacity = 16)

    override fun watchChanges(): Flow<ServerEvent.UserDataChanged> = watchFlow

    override suspend fun parentIds(ids: List<String>): Map<String, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        // Asked straight: which show a just-watched episode is in, not worth keeping.
        return get(
            "Items",
            query = mapOf("ids" to dashless(ids.distinct()), "userId" to userId, "enableImages" to false, "enableUserData" to false, "enableTotalRecordCount" to false),
        ).decode<QueryResultDto<ItemDto>>().items.map { it.toMediaItem() }
            .associate { it.id to listOfNotNull(it.seriesId, it.seasonId) }.filterValues { it.isNotEmpty() }
    }

    // ---- Nitrate Companion (implemented in JellyfinCompanion.kt) ----

    private val companionApi = JellyfinCompanion(this)
    private val channelsApi = JellyfinChannels(this)
    private val partnersApi = JellyfinPartners(this)
    private val diaryApi = JellyfinDiary(this)

    override suspend fun diary(fromMs: Long?, toMs: Long?) = diaryApi.diary(fromMs, toMs)
    override suspend fun logWatch(itemId: String, watchedAtMs: Long?, rating: Int?, note: String?) = diaryApi.log(itemId, watchedAtMs, rating, note)
    override suspend fun editDiary(id: String, rating: Int?, note: String?, watchedAtMs: Long?) = diaryApi.edit(id, rating, note, watchedAtMs)
    override suspend fun deleteDiary(id: String) = diaryApi.delete(id)
    override suspend fun searchLines(query: String, limit: Int): List<dev.nitrate.core.lines.SpokenLine> {
        val term = query.trim()
        return shared(dev.nitrate.core.cache.AnswerKind.SEARCH, arrayOf("Nitrate", "Lines"), mapOf("query" to term, "limit" to limit)) { diaryApi.searchLines(term, limit) }
    }

    override suspend fun privacy() = partnersApi.privacy()
    override suspend fun setPrivacy(private: Boolean) = partnersApi.setPrivacy(private)
    override suspend fun partnerPeople() = partnersApi.people()
    override suspend fun partners() = partnersApi.list().also { info ->
        // The groups changed (someone joined, left, or moved on): each show's group progress is asked afresh.
        if (lastPartners?.let { it.first == accountKey && it.second == info } != true) {
            if (lastPartners != null) answers.forget { key, kind -> kind == dev.nitrate.core.cache.AnswerKind.COMPANION && "Partners/Progress" in key }
            lastPartners = accountKey to info
        }
        partnersListener?.invoke(info)
        // The rows read it too (featuresAndPartners): a fresh read serves them.
        togetherLock.withLock { togetherKept = Triple(kotlin.time.TimeSource.Monotonic.markNow(), accountKey, cachedFeatures() to info) }
    }

    private var lastPartners: Pair<String?, dev.nitrate.core.partners.PartnersInfo>? = null

    /** The Companion's features as [companionHas] keeps them (asked at most every few minutes). */
    private suspend fun cachedFeatures(): Set<String> {
        companionHas("")
        return companionFeatures?.second.orEmpty()
    }

    private var partnersListener: ((dev.nitrate.core.partners.PartnersInfo) -> Unit)? = null

    private val togetherLock = kotlinx.coroutines.sync.Mutex()
    private var togetherKept: Triple<kotlin.time.TimeMark, String?, Pair<Set<String>, dev.nitrate.core.partners.PartnersInfo?>>? = null

    /**
     * The Companion's features (kept a few minutes, [companionHas]) and the user's groups, for the
     * rows: the latest read serves for [TOGETHER_KEPT_MS] (Home and Watch groups asked again within
     * the minute). Any change the app sends lets it go, and every read of the groups (after a
     * change or the Companion's news, `refreshPartners`) replaces it, so it's never older than that.
     */
    internal suspend fun featuresAndPartners(): Pair<Set<String>, dev.nitrate.core.partners.PartnersInfo?> {
        togetherLock.withLock {
            togetherKept?.takeIf { it.second == accountKey && it.first.elapsedNow().inWholeMilliseconds < TOGETHER_KEPT_MS }?.let { return it.third }
        }
        val features = cachedFeatures()
        val info = if (dev.nitrate.core.partners.WatchPartners.FEATURE in features) partners() else null
        return features to info
    }

    /** Lets go of the rows' groups (a change was sent). */
    internal fun forgetPartners() { togetherKept = null }

    override fun onPartnersLoaded(listener: (dev.nitrate.core.partners.PartnersInfo) -> Unit) { partnersListener = listener }

    private var listsListener: (() -> Unit)? = null
    override fun onPlaylistsChanged(listener: () -> Unit) { listsListener = listener }
    override suspend fun askPartner(toUserId: String?, toName: String?, seriesId: String, seriesName: String, groupId: String?, schedule: dev.nitrate.core.partners.GroupSchedule?, titles: List<dev.nitrate.core.partners.GroupTitle>?, batch: String?) =
        partnersApi.ask(toUserId, toName, seriesId, seriesName, groupId, schedule, titles, batch)
    override suspend fun addGroupTitle(groupId: String, titleId: String, name: String) = partnersApi.addTitle(groupId, titleId, name)
    override suspend fun removeGroupTitle(groupId: String, titleId: String) = partnersApi.removeTitle(groupId, titleId)
    override suspend fun markForGroups(groupIds: List<String>, itemIds: List<String>, watched: Boolean) = partnersApi.mark(groupIds, itemIds, watched)
    override suspend fun sessionForGroup(groupId: String, sessionId: String) = partnersApi.sessionFor(groupId, sessionId)
    override suspend fun setGroupResume(groupId: String, itemId: String, positionMs: Long) = partnersApi.resume(groupId, itemId, positionMs)
    private val sessionsApi = JellyfinSessions(this)
    override suspend fun watchSessions() = sessionsApi.mine()
    override suspend fun watchAsks() = sessionsApi.asks()
    override suspend fun askToWatch(groupId: String, itemId: String, laterMinutes: Int) = sessionsApi.ask(groupId, itemId, laterMinutes)
    override suspend fun answerAsk(askId: String, take: Boolean) = sessionsApi.answerAsk(askId, take)
    override suspend fun cancelAsk(askId: String) = sessionsApi.cancelAsk(askId)
    override suspend fun startWatchSession(groupId: String, itemId: String, startMs: Long) = sessionsApi.start(groupId, itemId, startMs)
    override suspend fun startWatchSession(groupId: String, itemId: String, startMs: Long, gatherMinutes: Int) = sessionsApi.start(groupId, itemId, startMs, gatherMinutes)
    override suspend fun watchSession(sessionId: String) = sessionsApi.get(sessionId)
    override suspend fun joinWatchSession(sessionId: String, fromStart: Boolean) = sessionsApi.join(sessionId, fromStart)
    override suspend fun startWatchSessionNow(sessionId: String) = sessionsApi.startNow(sessionId)
    override suspend fun reportWatchSession(sessionId: String, positionMs: Long, paused: Boolean, event: String?) = sessionsApi.report(sessionId, positionMs, paused, event)
    override suspend fun leaveWatchSession(sessionId: String) = sessionsApi.leave(sessionId)
    override suspend fun endWatchSession(sessionId: String) = sessionsApi.end(sessionId)
    override suspend fun proposeSchedule(groupId: String, schedule: dev.nitrate.core.partners.GroupSchedule) = partnersApi.proposeSchedule(groupId, schedule)
    override suspend fun voteSchedule(groupId: String, changeId: String, yes: Boolean) = partnersApi.voteSchedule(groupId, changeId, yes)
    override suspend fun watchParty(groupId: String) = partnersApi.party(groupId)
    override suspend fun partyLater(groupId: String, minutes: Int) = partnersApi.partyLater(groupId, minutes)
    override suspend fun partySkip(groupId: String) = partnersApi.partySkip(groupId)
    override suspend fun partyCantMake(groupId: String, cant: Boolean) = partnersApi.partyCantMake(groupId, cant)
    override suspend fun partyVote(groupId: String, requestId: String, yes: Boolean) = partnersApi.partyVote(groupId, requestId, yes)
    override suspend fun partyJoin(groupId: String) = partnersApi.partyJoin(groupId)
    override suspend fun partySession(groupId: String, sessionId: String) = partnersApi.partySession(groupId, sessionId)
    override suspend fun acceptPartner(requestId: String) = partnersApi.accept(requestId)
    override suspend fun declinePartner(requestId: String) = partnersApi.decline(requestId)
    override suspend fun cancelPartnerRequest(requestId: String) = partnersApi.cancel(requestId)
    override suspend fun leaveGroup(groupId: String) = partnersApi.leave(groupId)
    override suspend fun partnerProgress(seriesId: String) =
        shared(dev.nitrate.core.cache.AnswerKind.COMPANION, arrayOf("Nitrate", "Partners", "Progress"), mapOf("seriesId" to seriesId)) { partnersApi.progress(seriesId) }
    override suspend fun watchedTogether(itemId: String, groupId: String) = partnersApi.together(itemId, groupId)
    override suspend fun watchedWithGroup(groupId: String, itemIds: List<String>, present: List<String>) = partnersApi.watchedWith(groupId, itemIds, present)
    override suspend fun inviteToWatch(groupId: String, userIds: List<String>, itemId: String?, itemName: String?) = partnersApi.invite(groupId, userIds, itemId, itemName)
    // Not kept: looking every couple of minutes is how a new one is found.
    override suspend fun watchInvites() = partnersApi.invites()
    override suspend fun dismissWatchInvite(id: String) = partnersApi.dismissInvite(id)

    override suspend fun groupStandings(): Map<String, dev.nitrate.core.partners.KeepUp> = coroutineScope {
        if (companion()?.features?.contains(dev.nitrate.core.partners.WatchPartners.FEATURE) != true) return@coroutineScope emptyMap()
        val groups = partners().groups
        groups.flatMap { g -> g.titles.map { it.id } }.distinct().map { id ->
            // Each group's own record is the user's place in it (KeepUp.own).
            async { runCatching { dev.nitrate.core.partners.KeepUp.of(id, titleItems(id), partnerProgress(id), groups) }.getOrNull() }
        }.mapNotNull { it.await() }.associateBy { it.seriesId }
    }

    override suspend fun houseChannels() = channelsApi.list()
    override suspend fun channelGuide(fromMs: Long, toMs: Long) = channelsApi.guide(fromMs, toMs)
    override suspend fun tuneChannel(channelId: String, count: Int) = channelsApi.tune(channelId, count)
    override suspend fun saveChannel(channel: dev.nitrate.core.channels.HouseChannel, startOver: Boolean) = channelsApi.save(channel, startOver)
    override suspend fun deleteChannel(channelId: String) = channelsApi.delete(channelId)
    override suspend fun companion() = companionApi.info()
    override suspend fun companionUpdates() =
        shared(dev.nitrate.core.cache.AnswerKind.COMPANION, arrayOf("Nitrate", "Updates")) { companionApi.updates() }
    override suspend fun checkCompanionUpdates() = companionApi.checkUpdates()
    override suspend fun restartForCompanionUpdate() = companionApi.restartForUpdate()

    /** The Companion plugin's own times for this run's requests (tag → ms), the caller's only. */
    override suspend fun serverQueryTimes(run: String): Map<String, Long> =
        get("Nitrate", "QueryTimes", query = mapOf("run" to run)).decode<List<QueryTimeDto>>().associate { it.tag to it.ms }

    override suspend fun comicListing(itemId: String) =
        if (companion()?.features?.contains(dev.nitrate.core.comics.ComicListing.FEATURE) == true) companionApi.comicListing(itemId) else null

    override fun comicPageUrl(itemId: String, index: Int) = companionApi.comicPageUrl(itemId, index)

    private val catalogsApi = JellyfinCatalogs(this)

    override suspend fun comicCatalogs() =
        if (companion()?.features?.contains(dev.nitrate.core.catalogs.ComicCatalog.FEATURE) == true) catalogsApi.list() else emptyList()

    override fun catalogLink(catalog: dev.nitrate.core.catalogs.ComicCatalog) = catalogsApi.link(catalog)

    override suspend fun saveComicInfo(itemId: String, changes: Map<String, String?>) = companionApi.saveComicInfo(itemId, changes)
    override suspend fun checkClips() = companionApi.checkClips()
    override suspend fun serverCreditScenes(itemId: String) = companionApi.creditScenes(itemId)
    override suspend fun creditsReport(itemId: String) = companionApi.creditsReport(itemId)
    override suspend fun fileTimingIssues() = companionApi.fileTiming()
    override suspend fun sendDeviceSummary(summary: dev.nitrate.core.playback.DeviceSummary) = companionApi.sendDeviceSummary(identity.deviceId, identity.deviceName, summary)
    override suspend fun householdDevices() = companionApi.householdDevices()
    override suspend fun imageOverview(ask: dev.nitrate.core.admin.ImageOverviewAsk) = companionApi.imageOverview(ask)
    override suspend fun forgetHouseholdDevice(userId: String, deviceId: String) = companionApi.forgetHouseholdDevice(userId, deviceId)
    override fun checkClipUrl(id: String): String = url("Nitrate", "TestClips", id)
    override suspend fun chatMessages(groupId: String, after: Long, waitMs: Int) = companionApi.messages(groupId, after, waitMs)
    override suspend fun sendChat(groupId: String, kind: dev.nitrate.core.companion.ChatKind, text: String, positionMs: Long?) =
        companionApi.send(groupId, kind, text, positionMs)
    override suspend fun sharingUsers(name: String?) = companionApi.sharingUsers(name)
    override suspend fun sharedScripts() = companionApi.sharedScripts()
    override suspend fun createSharedScript(text: String, members: List<String>, records: List<dev.nitrate.core.writing.story.RecordChange>) =
        companionApi.createSharedScript(text, members, records)
    override suspend fun sendScriptRecords(id: String, editId: String, baseVersion: Long, records: List<dev.nitrate.core.writing.story.RecordChange>) =
        companionApi.sendScriptRecords(id, editId, baseVersion, records)
    override suspend fun scriptLog(id: String, from: Long, limit: Int) = companionApi.scriptLog(id, from, limit)
    override suspend fun scriptAt(id: String, version: Long) = companionApi.scriptAt(id, version)
    override suspend fun sharedScript(id: String) = companionApi.sharedScript(id)
    override suspend fun setScriptMembers(id: String, members: List<String>) = companionApi.setScriptMembers(id, members)
    override suspend fun leaveSharedScript(id: String) = companionApi.leaveSharedScript(id)
    override suspend fun deleteSharedScript(id: String) = companionApi.deleteSharedScript(id)
    override suspend fun sendScriptEdit(id: String, editId: String, baseVersion: Long, op: dev.nitrate.core.writing.TextOperation, cursor: Int?) =
        companionApi.sendScriptEdit(id, editId, baseVersion, op, cursor)
    override suspend fun scriptEdits(id: String, after: Long, seen: Long?, waitMs: Int) = companionApi.scriptEdits(id, after, seen, waitMs)
    override suspend fun sendScriptPresence(id: String, cursor: Int, version: Long) = companionApi.sendScriptPresence(id, cursor, version)

    // ---- SyncPlay ----

    override suspend fun syncPlayGroups(): List<SyncPlayGroup> =
        get("SyncPlay", "List").decode<kotlinx.serialization.json.JsonArray>().mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.let(::parseGroupInfo) }

    override suspend fun syncPlayCreate(name: String) {
        post<NewGroupRequestDto>("SyncPlay", "New", body = NewGroupRequestDto(name))
    }

    override suspend fun syncPlayJoin(groupId: String) {
        post<JoinGroupRequestDto>("SyncPlay", "Join", body = JoinGroupRequestDto(groupId))
    }

    override suspend fun syncPlayLeave() {
        post<Unit>("SyncPlay", "Leave")
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    override suspend fun syncPlay(request: SyncPlayRequest) {
        fun iso(ms: Long) = kotlin.time.Instant.fromEpochMilliseconds(ms).toString()
        when (request) {
            is SyncPlayRequest.SetQueue -> post<PlayRequestDto>("SyncPlay", "SetNewQueue", body = PlayRequestDto(request.itemIds, request.index, request.startMs.msToTicks()))
            SyncPlayRequest.Pause -> post<Unit>("SyncPlay", "Pause")
            SyncPlayRequest.Unpause -> post<Unit>("SyncPlay", "Unpause")
            SyncPlayRequest.Stop -> post<Unit>("SyncPlay", "Stop")
            is SyncPlayRequest.Seek -> post<SeekRequestDto>("SyncPlay", "Seek", body = SeekRequestDto(request.positionMs.msToTicks()))
            is SyncPlayRequest.Status -> post<ReadyRequestDto>(
                "SyncPlay", if (request.buffering) "Buffering" else "Ready",
                body = ReadyRequestDto(iso(request.whenMs), request.positionMs.msToTicks(), request.isPlaying, request.playlistItemId),
            )
            is SyncPlayRequest.NextItem -> post<NextItemRequestDto>("SyncPlay", "NextItem", body = NextItemRequestDto(request.playlistItemId))
            is SyncPlayRequest.Ping -> post<PingRequestDto>("SyncPlay", "Ping", body = PingRequestDto(request.roundTripMs))
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    override suspend fun serverTime(): ServerTimeSample {
        val dto = get("GetUtcTime").decode<UtcTimeResponseDto>()
        return ServerTimeSample(
            kotlin.time.Instant.parse(dto.requestReceptionTime).toEpochMilliseconds(),
            kotlin.time.Instant.parse(dto.responseTransmissionTime).toEpochMilliseconds(),
        )
    }

    // ---- Custom rows ----

    override suspend fun queryItems(query: ItemQuery): List<MediaItem> = items("Items", query = itemQueryParams(query))

    // Codec filters are applied here, not by the server, so such a row can't simply be continued.
    override fun moreOfQuery(query: ItemQuery): dev.nitrate.core.rows.RowMore? =
        if (query.filtersCodecs) null else rowMore("Items", query = itemQueryParams(query))

    override suspend fun queryPage(query: ItemQuery, startIndex: Int, limit: Int): Pair<List<MediaItem>, Int> {
        if (query.filtersCodecs) return codecFilteredPage(query, startIndex, limit)
        val params = itemQueryParams(query) + mapOf("startIndex" to startIndex, "limit" to limit, "enableTotalRecordCount" to (startIndex == 0), "userId" to userId)
        val result = get("Items", query = params).decode<QueryResultDto<ItemDto>>()
        return result.items.map { it.toMediaItem() } to pageTotal(startIndex, limit, result.items.size, result.totalRecordCount)
    }

    /**
     * The total a page reports: the server's count on the first page; on later pages (not counted:
     * the server would go over every match again) where the list ends when this page is short, else
     * -1, "as before" ([dev.nitrate.core.MediaServer.queryPage]).
     */
    private fun pageTotal(startIndex: Int, limit: Int, got: Int, counted: Int) = when {
        startIndex == 0 -> counted
        got < limit -> startIndex + got
        else -> -1
    }

    // ---- Codec filters (Jellyfin has none: Nitrate reads the streams and filters) ----

    /** Video streams and audio codecs per item id; shows carry their episodes'. */
    internal class CodecIndex(val streams: Map<String, StreamInfo>, val builtMs: Long)

    internal val codecIndexes = mutableMapOf<String, CodecIndex>()
    internal val codecIndexLock = kotlinx.coroutines.sync.Mutex()

    override suspend fun watchHistory(max: Int): List<MediaItem> {
        val page = 1_000
        val out = mutableListOf<MediaItem>()
        while (out.size < max) {
            val got = items(
                "Items",
                query = mapOf(
                    "recursive" to true, "includeItemTypes" to "Movie,Episode,Audio", "filters" to "IsPlayed",
                    "sortBy" to "DatePlayed", "sortOrder" to "Descending", "fields" to "Genres",
                    "startIndex" to out.size, "limit" to page, "enableImages" to false, "enableTotalRecordCount" to false,
                ),
            )
            out += got
            if (got.size < page) break
        }
        return out.take(max)
    }

    override suspend fun lyrics(itemId: String): dev.nitrate.core.music.Lyrics? {
        // No lyrics: the server answers 404.
        val response = try {
            get("Audio", itemId.replace("-", ""), "Lyrics")
        } catch (e: ClientRequestException) {
            if (e.response.status.value == 404) return null else throw e
        }
        val dto = response.decode<LyricsDto>()
        val lines = dto.lyrics.map { dev.nitrate.core.music.LyricLine(it.text.orEmpty(), it.start?.ticksToMs()) }
        // Timed when the lines carry their times (12.4 often leaves IsSynced out), unless the server says otherwise.
        val synced = dto.metadata?.isSynced != false && lines.filter { it.text.isNotBlank() }.let { l -> l.isNotEmpty() && l.all { it.startMs != null } }
        return dev.nitrate.core.music.Lyrics(lines, synced).takeIf { !it.isEmpty }
    }

    override suspend fun crewOf(ids: List<String>): Map<String, List<dev.nitrate.core.model.Person>> {
        if (ids.isEmpty()) return emptyMap()
        return items(
            "Items",
            query = mapOf("ids" to dashless(ids), "userId" to userId, "fields" to "People", "enableImages" to false, "enableTotalRecordCount" to false),
            kind = dev.nitrate.core.cache.AnswerKind.STRUCTURE,
        ).associate { it.id to it.people.filter { p -> p.type == "Director" || p.type == "Writer" } }
    }

    override suspend fun collectionsOf(itemId: String): List<MediaItem> = items("Items", itemId.replace("-", ""), "Collections", query = mapOf("fields" to ROW_FIELDS))

    override suspend fun collectionItems(collectionId: String): List<MediaItem> = items(
        "Items",
        query = mapOf("parentId" to collectionId, "sortBy" to "ProductionYear,SortName", "sortOrder" to "Ascending,Ascending", "fields" to ROW_FIELDS, "limit" to 100),
        kind = dev.nitrate.core.cache.AnswerKind.STRUCTURE,
    )

    override suspend fun userStates(ids: List<String>): Map<String, dev.nitrate.core.model.UserState> {
        if (ids.isEmpty()) return emptyMap()
        // Asked straight (never a kept answer): this is for marks that may have just changed.
        return get(
            "Items",
            query = mapOf(
                "ids" to dashless(ids.distinct()), "userId" to userId, "enableImages" to false,
                "enableTotalRecordCount" to false,
            ),
        ).decode<QueryResultDto<ItemDto>>().items.map { it.toMediaItem() }.associate { it.id to it.user }
    }

    override suspend fun genres(libraryId: String?): List<String> =
        get("Genres", query = mapOf("parentId" to libraryId, "sortBy" to "SortName", "sortOrder" to "Ascending", "enableTotalRecordCount" to false))
            .decode<QueryResultDto<ItemDto>>().items.mapNotNull { it.name }

    override suspend fun studios(libraryId: String?): List<String> =
        get("Studios", query = mapOf("parentId" to libraryId, "userId" to userId, "enableImages" to false, "enableTotalRecordCount" to false))
            .decode<QueryResultDto<ItemDto>>().items.mapNotNull { it.name }

    override suspend fun collections(): List<MediaItem> = listOfType("BoxSet")

    // With each one's etag (made from when it was last saved): its contents kept on the device stay good until it changes.
    override suspend fun playlists(): List<MediaItem> = listOfType("Playlist", "PrimaryImageAspectRatio,Etag")

    private suspend fun listOfType(type: String, fields: String = "PrimaryImageAspectRatio") =
        // Names and pictures only: no total count and no watch data, which the server would
        // otherwise work out for every list.
        items(
            "Items",
            query = mapOf(
                "includeItemTypes" to type, "recursive" to true, "sortBy" to "SortName", "sortOrder" to "Ascending",
                "fields" to fields, "limit" to 500,
                "enableTotalRecordCount" to false, "enableUserData" to false, "imageTypeLimit" to 1, "enableImageTypes" to "Primary",
            ),
        )

    // ---- Speed check ----

    override suspend fun speedCheck(onStep: (dev.nitrate.core.diagnostics.SpeedStep) -> Unit): List<dev.nitrate.core.diagnostics.SpeedStep> {
        val clock = kotlin.time.TimeSource.Monotonic
        val steps = mutableListOf<dev.nitrate.core.diagnostics.SpeedStep>()
        suspend fun step(id: String, label: String, detail: String, times: Int, extra: () -> String? = { null }, block: suspend () -> Unit): dev.nitrate.core.diagnostics.SpeedStep {
            val samples = mutableListOf<Long>()
            val result = try {
                repeat(times) {
                    val mark = clock.markNow()
                    block()
                    samples += mark.elapsedNow().inWholeMilliseconds
                }
                dev.nitrate.core.diagnostics.SpeedStep(id, label, detail, samples, extra())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dev.nitrate.core.diagnostics.SpeedStep(id, label, detail, samples, error = e.message ?: e::class.simpleName)
            }
            steps += result
            onStep(result)
            return result
        }
        val S = dev.nitrate.core.diagnostics.SpeedCheck

        step(S.PING, "Round trip", "Time to reach the server and back, with no work for it to do", 5) { http.get(url("System", "Ping")).bodyAsText() }
        step(S.SIGNED_IN, "Signed-in call", "A small request that checks your sign-in (your account)", 3) { get("Users", "Me").bodyAsText() }
        step(S.LIBRARIES, "Library list", "Your libraries, as the menu shows them", 3) { get("UserViews").bodyAsText() }
        // The same list without plugin channels, which the server asks live: tells whether they're what's slow.
        step(S.LIBRARIES_NO_CHANNELS, "Library list without plugin channels", "The same list, leaving out channels that server plugins add", 3) {
            get("UserViews", query = mapOf("includeExternalContent" to false)).bodyAsText()
        }

        var biggest = ""
        var total: Int? = null
        step(S.SEARCH, "Library search", "Searching the whole library for 100 titles with details: the server's database at work", 3, { total?.let { "$it items in the library" } }) {
            biggest = get(
                "Items",
                query = mapOf(
                    "userId" to userId, "recursive" to true, "includeItemTypes" to "Movie,Series,Episode",
                    "sortBy" to "SortName", "limit" to 100, "fields" to ROW_FIELDS,
                ),
            ).bodyAsText()
            total = runCatching { JellyfinJson.decodeFromString<QueryResultDto<ItemDto>>(biggest).totalRecordCount }.getOrNull()
        }
        var rowCount = 0
        val parts = mutableListOf<dev.nitrate.core.rows.RowTiming>()
        val home = step(S.HOME, "Home page", "Loading every row of Home, all at once as Nitrate does; each part timed on its own", 2, { "$rowCount rows" }) {
            parts.clear()
            rowCount = dev.nitrate.core.rows.ProgressiveRows.run(homeJobs(fresh = true)) { t -> parts += t }.last().rows.size
        }
        if (parts.isNotEmpty()) {
            val withParts = home.copy(parts = parts.sortedByDescending { it.ms }.map { it.name to it.ms })
            steps[steps.indexOf(home)] = withParts
            onStep(withParts)
        }
        val bytes = 2_000_000
        var mbps: Double? = null
        val dl = step(S.DOWNLOAD, "Download speed", "Downloading 2 MB from the server", 2) {
            val mark = clock.markNow()
            val got = http.get(url("Playback", "BitrateTest", query = mapOf("size" to bytes))) { auth() }.readRawBytes().size
            val secs = mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(1) / 1000.0
            val now = got * 8 / secs / 1_000_000
            mbps = maxOf(mbps ?: 0.0, now)
        }
        if (dl.error == null) {
            val withSpeed = dl.copy(mbps = mbps)
            steps[steps.lastIndex] = withSpeed
            onStep(withSpeed)
        }
        step(S.PARSE, "Reading on this device", "Turning the library search's answer (${biggest.length / 1024} KB) into titles, here on this device", 3) {
            if (biggest.isEmpty()) error("no answer to read")
            JellyfinJson.decodeFromString<QueryResultDto<ItemDto>>(biggest).items.map { it.toMediaItem() }
        }
        return steps
    }

    // ---- Search ----

    override suspend fun additionalParts(itemId: String): List<MediaItem> =
        items("Videos", itemId, "AdditionalParts", query = mapOf("userId" to userId))

    override suspend fun extras(itemId: String): List<MediaItem> = coroutineScope {
        val trailers = async { runCatching { get("Items", itemId, "LocalTrailers", query = mapOf("userId" to userId)).decode<List<ItemDto>>().map { it.toMediaItem().copy(extraType = it.extraType?.let(::extraLabel) ?: "Trailer") } }.getOrDefault(emptyList()) }
        val features = async { runCatching { get("Items", itemId, "SpecialFeatures", query = mapOf("userId" to userId)).decode<List<ItemDto>>().map { it.toMediaItem() } }.getOrDefault(emptyList()) }
        (trailers.await() + features.await()).distinctBy { it.id }
    }

    override suspend fun mergeVersions(itemIds: List<String>) {
        post<Unit>("Videos", "MergeVersions", query = mapOf("ids" to itemIds.joinToString(",")))
    }

    override suspend fun healthScan(libraryId: String?): dev.nitrate.core.diagnostics.HealthScan = coroutineScope {
        fun streams(s: List<MediaStreamDto>?) = StreamInfo(
            s.orEmpty().filter { it.type == "Video" }.map { VideoStreamInfo(Codecs.normalize(it.codec), it.bitDepth, Codecs.rangeTags(it.videoRangeType, it.dvProfile)) }.toSet(),
            s.orEmpty().filter { it.type == "Audio" }.mapNotNull { Codecs.normalize(it.codec) }.toSet(),
        )
        suspend fun all(types: String, fields: String?, extra: Map<String, Any?> = emptyMap()): List<ItemDto> {
            val out = mutableListOf<ItemDto>()
            var start = 0
            while (true) {
                val page = get(
                    "Items",
                    query = mapOf(
                        "userId" to userId, "parentId" to libraryId, "recursive" to true, "includeItemTypes" to types,
                        "fields" to fields, "startIndex" to start, "limit" to HEALTH_PAGE, "enableUserData" to false,
                        "sortBy" to "SortName", "sortOrder" to "Ascending",
                    ) + extra,
                ).decode<QueryResultDto<ItemDto>>().items
                out += page
                if (page.size < HEALTH_PAGE) break
                start += page.size
            }
            return out
        }
        val titles = async { all("Movie,Series", "Overview,ProviderIds,MediaStreams", mapOf("enableImageTypes" to "Primary,Backdrop", "imageTypeLimit" to 1)) }
        val episodes = async { all("Episode", "MediaStreams", mapOf("enableImages" to false, "isMissing" to false)) }
        val missing = async { runCatching { all("Episode", null, mapOf("enableImages" to false, "isMissing" to true)) }.getOrDefault(emptyList()) }
        val bySeries = episodes.await().filter { it.seriesId != null && !it.mediaStreams.isNullOrEmpty() }.groupBy { normalizeId(it.seriesId!!) }
        dev.nitrate.core.diagnostics.HealthScan(
            titles = titles.await().map { dto ->
                val item = dto.toMediaItem()
                dev.nitrate.core.diagnostics.HealthTitle(
                    item = item,
                    hasOverview = !dto.overview.isNullOrBlank(),
                    matched = dto.providerIds.orEmpty().any { (k, v) -> !v.isNullOrBlank() && k.lowercase() in MATCH_PROVIDERS },
                    files = if (item.kind == ItemKind.SERIES) bySeries[item.id].orEmpty().map { it.toMediaItem() to streams(it.mediaStreams) }
                    else listOfNotNull(dto.mediaStreams?.takeIf { it.isNotEmpty() }?.let { item to streams(it) }),
                )
            },
            missingEpisodes = missing.await().map { it.toMediaItem() },
        )
    }

    override suspend fun playbackSources(libraryId: String?, onProgress: (Int, Int) -> Unit): List<Pair<MediaItem, MediaSource>> {
        val out = mutableListOf<Pair<MediaItem, MediaSource>>()
        var start = 0
        while (true) {
            val page = get(
                "Items",
                query = mapOf(
                    "userId" to userId, "parentId" to libraryId, "recursive" to true, "includeItemTypes" to "Movie,Episode",
                    "fields" to "MediaSources", "startIndex" to start, "limit" to OVERVIEW_PAGE, "enableUserData" to false,
                    "enableImages" to false, "isMissing" to false,
                    "sortBy" to "SeriesSortName,ParentIndexNumber,IndexNumber,SortName", "sortOrder" to "Ascending",
                ),
            ).decode<QueryResultDto<ItemDto>>()
            page.items.forEach { dto -> dto.mediaSources?.firstOrNull()?.let { out += dto.toMediaItem() to it.toMediaSource() } }
            start += page.items.size
            onProgress(start, page.totalRecordCount)
            if (page.items.size < OVERVIEW_PAGE || start >= page.totalRecordCount) break
        }
        return out
    }

    override suspend fun themeSongs(itemId: String): List<MediaItem> =
        items("Items", itemId, "ThemeSongs", query = mapOf("userId" to userId, "inheritFromParent" to true))

    override suspend fun themeMusicInfo(itemId: String): dev.nitrate.core.model.ThemeMusicInfo {
        val dto = get("Items", itemId, "ThemeSongs", query = mapOf("userId" to userId, "inheritFromParent" to true)).decode<ThemeMediaDto>()
        return dev.nitrate.core.model.ThemeMusicInfo(dto.items.map { it.toMediaItem() }, dto.ownerId?.let(::normalizeId))
    }

    override suspend fun intros(itemId: String): List<MediaItem> =
        items("Items", itemId, "Intros", query = mapOf("userId" to userId))

    override fun audioStreamUrl(itemId: String): String = url("Audio", itemId, "stream", query = mapOf("static" to true))

    override fun listenUrl(itemId: String, convert: Boolean, playSessionId: String): String = url(
        "Audio", itemId, "stream",
        query = if (!convert) mapOf("static" to true, "playSessionId" to playSessionId, "deviceId" to identity.deviceId, "mediaSourceId" to itemId.replace("-", ""))
        else mapOf(
            "container" to dev.nitrate.core.music.ListenStream.CONVERT_CODEC, "audioCodec" to dev.nitrate.core.music.ListenStream.CONVERT_CODEC,
            "audioBitRate" to dev.nitrate.core.music.ListenStream.CONVERT_BITRATE, "playSessionId" to playSessionId, "deviceId" to identity.deviceId,
            "mediaSourceId" to itemId.replace("-", ""),
        ),
    )

    override suspend fun reportListen(itemId: String, playSessionId: String, positionMs: Long, paused: Boolean, converted: Boolean, start: Boolean) {
        val body = PlaybackStartDto(
            itemId, playSessionId, itemId.replace("-", ""), positionMs.msToTicks(), isPaused = paused,
            playMethod = (if (converted) PlayMethod.TRANSCODE else PlayMethod.DIRECT_PLAY).wire(),
        )
        runCatching { if (start) post<PlaybackStartDto>("Sessions", "Playing", body = body) else post<PlaybackStartDto>("Sessions", "Playing", "Progress", body = body) }
    }

    override fun reportListenStopped(itemId: String, playSessionId: String, positionMs: Long) {
        appScope.launch {
            runCatching { post<PlaybackStopDto>("Sessions", "Playing", "Stopped", body = PlaybackStopDto(itemId, playSessionId, itemId.replace("-", ""), positionMs.msToTicks())) }
        }
    }

    override fun chapterImageUrl(item: MediaItem, index: Int, maxWidth: Int): String? {
        val tag = item.chapters.getOrNull(index)?.imageTag ?: return null
        return url("Items", item.id, "Images", "Chapter", index.toString(), query = mapOf("tag" to tag, "maxWidth" to maxWidth, "quality" to 80))
    }

    override suspend fun libraryFilters(libraryId: String?, types: List<ItemKind>): dev.nitrate.core.model.LibraryFilters {
        val f = get(
            "Items", "Filters",
            query = mapOf("userId" to userId, "parentId" to libraryId, "includeItemTypes" to types.mapNotNull(::itemTypeName).joinToString(",").ifEmpty { null }),
        ).decode<QueryFiltersLegacyDto>()
        return dev.nitrate.core.model.LibraryFilters(
            genres = f.genres.orEmpty().sortedBy { it.lowercase() },
            years = f.years.orEmpty().sortedDescending(),
            officialRatings = f.officialRatings.orEmpty(),
            tags = f.tags.orEmpty().sortedBy { it.lowercase() },
        )
    }

    override suspend fun showsMissingEpisodes(): Boolean =
        runCatching { (get("Users", "Me").decode<JsonObject>()["Configuration"] as? JsonObject)?.get("DisplayMissingEpisodes")?.let { (it as JsonPrimitive).content == "true" } }.getOrNull() ?: false

    override suspend fun setShowMissingEpisodes(show: Boolean) {
        // Round-trip the account's own settings, changing only this one.
        val config = get("Users", "Me").decode<JsonObject>()["Configuration"] as? JsonObject ?: error("No account settings")
        post<JsonObject>("Users", "Configuration", body = JsonObject(config + ("DisplayMissingEpisodes" to JsonPrimitive(show))), query = mapOf("userId" to userId))
    }

    override suspend fun searchHints(query: String): SearchResults {
        val term = query.trim()
        if (term.isEmpty()) return SearchResults()
        val hintQuery = mapOf(
            "searchTerm" to term, "userId" to userId, "limit" to 40,
            "includeItemTypes" to "Movie,Series,Episode,BoxSet,Person",
            "includePeople" to true, "includeMedia" to true,
        )
        val hints = shared(dev.nitrate.core.cache.AnswerKind.SEARCH, arrayOf("Search", "Hints"), hintQuery) {
            get("Search", "Hints", query = hintQuery).decode<SearchHintResultDto>().searchHints
        }
        val items = hints.filter { it.type != "Person" }.mapNotNull { h ->
            val id = h.id ?: h.itemId ?: return@mapNotNull null
            MediaItem(
                id = normalizeId(id),
                name = h.name.orEmpty(),
                kind = itemKind(h.type),
                productionYear = h.productionYear,
                seriesName = h.series,
                seasonNumber = h.parentIndexNumber,
                indexNumber = h.indexNumber,
                images = ItemImages(primary = h.primaryImageTag, thumb = h.thumbImageTag, backdrops = listOfNotNull(h.backdropImageTag?.takeIf { h.backdropImageItemId == null })),
            )
        }
        val people = hints.filter { it.type == "Person" }.mapNotNull { h -> (h.id ?: h.itemId)?.let { Person(normalizeId(it), h.name.orEmpty(), null, h.primaryImageTag) } }
        return SearchResults(
            movies = items.filter { it.kind == ItemKind.MOVIE },
            shows = items.filter { it.kind == ItemKind.SERIES },
            episodes = items.filter { it.kind == ItemKind.EPISODE },
            collections = items.filter { it.kind == ItemKind.BOX_SET },
            people = people,
        )
    }

    override suspend fun findTitles(term: String, kinds: List<ItemKind>, limit: Int): List<MediaItem> {
        val t = term.trim()
        val types = kinds.mapNotNull(::itemTypeName)
        if (t.isEmpty() || types.isEmpty()) return emptyList()
        return items(
            "Items",
            query = mapOf(
                "searchTerm" to t, "recursive" to true, "includeItemTypes" to types.joinToString(","),
                "limit" to limit, "fields" to "PrimaryImageAspectRatio", "enableTotalRecordCount" to false,
            ),
        )
    }

    override suspend fun recentlyWatched(limit: Int): List<MediaItem> {
        val played = items(
            "Items",
            query = mapOf(
                "recursive" to true, "includeItemTypes" to "Episode,Movie",
                "sortBy" to "DatePlayed", "sortOrder" to "Descending",
                "limit" to limit * 5, "enableTotalRecordCount" to false,
            ),
        ).filter { it.user.lastPlayedMs != null }
        // Each show once, where its latest episode puts it.
        val order = played.mapNotNull { if (it.kind == ItemKind.EPISODE) it.seriesId else it.id }.distinct().take(limit)
        val films = played.filter { it.kind == ItemKind.MOVIE }.associateBy { it.id }
        val showIds = order.filter { it !in films }
        val shows = if (showIds.isEmpty()) emptyMap() else items(
            "Items",
            query = mapOf("ids" to showIds.joinToString(",") { it.replace("-", "") }, "enableTotalRecordCount" to false),
        ).associateBy { it.id }
        return order.mapNotNull { films[it] ?: shows[it] }
    }

    override suspend fun search(query: String): SearchResults = coroutineScope {
        val term = query.trim()
        if (term.isEmpty()) return@coroutineScope SearchResults()
        val items = async {
            items(
                "Items",
                kind = dev.nitrate.core.cache.AnswerKind.SEARCH,
                query = mapOf(
                    "searchTerm" to term,
                    "recursive" to true,
                    "includeItemTypes" to "Movie,Series,Episode,BoxSet",
                    "limit" to 60,
                    "fields" to "PrimaryImageAspectRatio",
                    "enableTotalRecordCount" to false,
                ),
            )
        }
        // People come with the quick matches (asked first, and kept): no search of its own.
        val people = async { runCatching { searchHints(term).people }.getOrDefault(emptyList()) }
        val found = items.await()
        SearchResults(
            movies = found.filter { it.kind == ItemKind.MOVIE },
            shows = found.filter { it.kind == ItemKind.SERIES },
            episodes = found.filter { it.kind == ItemKind.EPISODE },
            collections = found.filter { it.kind == ItemKind.BOX_SET },
            people = people.await(),
        )
    }

    override suspend fun itemsWithPerson(personId: String): List<MediaItem> = items(
        "Items",
        query = mapOf(
            "personIds" to personId,
            "recursive" to true,
            "includeItemTypes" to "Movie,Series",
            "sortBy" to "PremiereDate,SortName",
            "sortOrder" to "Descending,Ascending",
            "fields" to ROW_FIELDS,
            "limit" to 100,
        ),
    )

    // ---- Player extras ----

    override suspend fun mediaSegments(itemId: String): List<MediaSegment> = runCatching {
        get("MediaSegments", itemId).decode<QueryResultDto<MediaSegmentDto>>().items.map { s ->
            val kind = when (s.type) {
                "Intro" -> SegmentKind.INTRO
                "Outro" -> SegmentKind.OUTRO
                "Recap" -> SegmentKind.RECAP
                "Preview" -> SegmentKind.PREVIEW
                "Commercial" -> SegmentKind.COMMERCIAL
                else -> SegmentKind.UNKNOWN
            }
            MediaSegment(kind, s.startTicks.ticksToMs(), s.endTicks.ticksToMs())
        }.sortedBy { it.startMs }
    }.getOrDefault(emptyList())

    override suspend fun trickplay(itemId: String, mediaSourceId: String?): TrickplayInfo? = runCatching {
        val bySource = get("Items", itemId).decode<ItemDto>().trickplay ?: return null
        val wanted = mediaSourceId?.replace("-", "")
        val (sourceId, sizes) = bySource.entries.firstOrNull { it.key.replace("-", "").equals(wanted, ignoreCase = true) }?.toPair()
            ?: bySource.entries.firstOrNull()?.toPair() ?: return null
        // The smallest size that still looks sharp in a phone's scrubbing preview.
        val info = sizes.values.filter { it.width >= TRICKPLAY_MIN_WIDTH }.minByOrNull { it.width } ?: sizes.values.maxByOrNull { it.width } ?: return null
        TrickplayInfo(info.width, info.height, info.tileWidth, info.tileHeight, info.thumbnailCount, info.interval.toLong(), sourceId)
    }.getOrNull()

    override fun trickplaySheetUrl(itemId: String, info: TrickplayInfo, sheet: Int): String =
        url("Videos", itemId, "Trickplay", info.width.toString(), "$sheet.jpg", query = mapOf("mediaSourceId" to info.mediaSourceId))

    // ---- Downloads ----

    override suspend fun mediaSource(itemId: String): MediaSource =
        get("Items", itemId).decode<ItemDto>().mediaSources?.firstOrNull()?.toMediaSource() ?: error("Item has no media")

    override fun downloadUrl(itemId: String, source: MediaSource, transcode: TranscodeSpec?, audioIndex: Int?, sessionId: String, burnInSubtitle: Int?): String {
        // The original file, with range support so interrupted downloads resume. Honours the
        // user's "allow media downloading" permission on the server.
        if (transcode == null) return url("Items", itemId, "Download")
        return url(
            "Videos", itemId, "stream.mp4",
            query = mapOf(
                "static" to false,
                "mediaSourceId" to source.id,
                "playSessionId" to sessionId,
                "deviceId" to identity.deviceId,
                "videoCodec" to transcode.videoCodec,
                "audioCodec" to "aac",
                "videoBitRate" to transcode.videoBitrate,
                "audioBitRate" to transcode.audioBitrate,
                "audioChannels" to transcode.audioChannels,
                "maxWidth" to transcode.maxWidth,
                "maxHeight" to transcode.maxHeight,
                "audioStreamIndex" to audioIndex,
                // Drawn into the picture by the server (libass for ASS: fonts and effects kept).
                "subtitleStreamIndex" to burnInSubtitle,
                "subtitleMethod" to burnInSubtitle?.let { "Encode" },
            ),
        )
    }

    override fun attachmentUrl(itemId: String, source: MediaSource, index: Int): String =
        url("Videos", itemId, source.id ?: itemId, "Attachments", index.toString())

    override fun subtitleFileUrl(itemId: String, source: MediaSource, streamIndex: Int, format: String): String =
        url("Videos", itemId, source.id ?: itemId, "Subtitles", streamIndex.toString(), "Stream.$format")

    @OptIn(ExperimentalTime::class)
    override suspend fun userData(itemId: String): RemoteUserData {
        val data = get("UserItems", itemId, "UserData").decode<UserDataDto>()
        return RemoteUserData(
            played = data.played,
            positionMs = data.playbackPositionTicks.ticksToMs(),
            lastPlayedMs = data.lastPlayedDate?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
        )
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun saveUserData(itemId: String, positionMs: Long, played: Boolean, playedAtMs: Long) {
        post<UpdateUserDataDto>(
            "UserItems", itemId, "UserData",
            body = UpdateUserDataDto(positionMs.msToTicks(), played, Instant.fromEpochMilliseconds(playedAtMs).toString()),
        )
    }

    private fun absoluteUrl(path: String) = if (path.startsWith("http")) path else base().trimEnd('/') + path

    private fun transcodeReasons(url: String): List<String> =
        Regex("[?&]TranscodeReasons=([^&]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
            ?.replace("%2C", ",", ignoreCase = true)
            ?.split(',')?.filter(String::isNotBlank).orEmpty()

    private fun startInfo(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) =
        PlaybackStartDto(
            plan.itemId, plan.playSessionId, plan.mediaSourceId, positionMs.msToTicks(), isPaused = isPaused, playMethod = plan.method.wire(),
            audioStreamIndex = plan.audioIndex, subtitleStreamIndex = plan.subtitleIndex,
        )

    override suspend fun reportStart(plan: PlaybackPlan, positionMs: Long) {
        runCatching { post<PlaybackStartDto>("Sessions", "Playing", body = startInfo(plan, positionMs, false)) }
    }

    override suspend fun reportProgress(plan: PlaybackPlan, positionMs: Long, isPaused: Boolean) {
        runCatching { post<PlaybackStartDto>("Sessions", "Playing", "Progress", body = startInfo(plan, positionMs, isPaused)) }
    }

    override fun reportStopped(plan: PlaybackPlan, positionMs: Long) {
        val id = plan.itemId
        val group = groupPlays.value[id]
        val job = appScope.launch {
            // A group play's own state is put back by the Companion just after the server has
            // marked it: listen for that before saying the stop is done.
            // Meanwhile the server's pushes for it (watched, then put back) aren't passed on.
            if (group != null) restoring.update { it + id }
            try {
                val restored = group?.let { before ->
                    async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        kotlinx.coroutines.withTimeoutOrNull(GROUP_RESTORE_WAIT_MS) {
                            pushed.first { e -> e.states[id]?.let { it.played == before.played && it.positionMs == before.positionMs } == true }
                        }
                    }
                }
                val sent = runCatching {
                    post<PlaybackStopDto>("Sessions", "Playing", "Stopped", body = PlaybackStopDto(id, plan.playSessionId, plan.mediaSourceId, positionMs.msToTicks()))
                }.isSuccess
                if (!sent) { restored?.cancel(); return@launch }
                groupPlays.update { it - id }
                restored?.await()
            } finally {
                if (group != null) restoring.update { it - id }
            }
            watchStateFromServer(listOf(id))
        }
        stops.update { it + job }
        job.invokeOnCompletion { stops.update { s -> s - job } }
    }

    override suspend fun stopsSettled() {
        kotlinx.coroutines.withTimeoutOrNull(STOPS_SETTLE_MS) { stops.value.forEach { it.join() } }
    }

    /** Every watch-state push from the server, as it came (a group play's stop listens for its state put back). */
    private val pushed = kotlinx.coroutines.flow.MutableSharedFlow<ServerEvent.UserDataChanged>(extraBufferCapacity = 16)

    /** Titles whose group play just stopped, their own state not yet put back: the server's pushes for them wait. */
    private val restoring = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /** [e] without the titles whose own state is being put back; null when nothing's left of it. */
    private fun withoutRestoring(e: ServerEvent.UserDataChanged): ServerEvent.UserDataChanged? {
        val held = restoring.value
        if (held.isEmpty() || e.itemIds.none { it in held }) return e
        val ids = e.itemIds.filterNot { it in held }
        return if (ids.isEmpty()) null else ServerEvent.UserDataChanged(ids, e.states - held)
    }

    /** "Stopped" reports on their way. */
    private val stops = kotlinx.coroutines.flow.MutableStateFlow<Set<kotlinx.coroutines.Job>>(emptySet())

    /** Titles playing for a group, with the user's own state from before (put back when they stop). */
    private val groupPlays = kotlinx.coroutines.flow.MutableStateFlow<Map<String, dev.nitrate.core.model.UserState>>(emptyMap())

    /** Watch states said here from the server's own answer, and when: the server's push repeating one isn't said twice. */
    private val saidStates = mutableMapOf<String, Pair<dev.nitrate.core.model.UserState, Long>>()
    private val saidLock = kotlinx.coroutines.sync.Mutex()

    /**
     * [ids]' watch state read from the server and said as a change, at once (its push comes a
     * moment later): marks and rows follow what the server now has, never what the app expects.
     */
    private suspend fun watchStateFromServer(ids: List<String>) {
        val states = runCatching { userStates(ids) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        saidLock.withLock { val now = clock(); states.forEach { (k, v) -> saidStates[k] = v to now } }
        watchStateChanged(states)
        watchFlow.emit(ServerEvent.UserDataChanged(states.keys.toList(), states))
    }

    /** Whether the server's push [e] only repeats states said here a moment ago. */
    private suspend fun alreadySaid(e: ServerEvent.UserDataChanged): Boolean = saidLock.withLock {
        val now = clock()
        saidStates.entries.removeAll { now - it.value.second > SAID_KEPT_MS }
        e.itemIds.isNotEmpty() && e.itemIds.all { id -> saidStates[id]?.first?.let { s -> e.states[id]?.let { sameMarks(it, s) } } == true }
    }

    private fun sameMarks(a: dev.nitrate.core.model.UserState, b: dev.nitrate.core.model.UserState) =
        a.played == b.played && a.positionMs == b.positionMs && a.isFavorite == b.isFavorite && a.unplayedCount == b.unplayedCount

    internal companion object {
        const val PREFS_CLIENT = "nitrate"
        /** Request parts that make a list's titles follow watch state. */
        val WATCH_STATE_FILTERS = listOf("IsPlayed", "IsUnplayed", "IsResumable", "IsFavorite", "DatePlayed", "isPlayed=", "isFavorite=")

        /** Home's Next Up request (the plugin's bundle is kept under it too). */
        val NEXT_UP_QUERY: Map<String, Any?> = mapOf("limit" to ROW_LIMIT, "fields" to ROW_FIELDS)

        /** The longest a page reloading waits for "stopped" reports on their way (`stopsSettled`). */
        const val STOPS_SETTLE_MS = 3_000L

        /** How long a group play's stop waits for the Companion to put the user's own state back. */
        const val GROUP_RESTORE_WAIT_MS = 2_500L

        /** How long a state said here stops the server's push of the same one being said again. */
        const val SAID_KEPT_MS = 5_000L

        /** How long the rows reuse the latest read of the user's groups (`featuresAndPartners`). */
        const val TOGETHER_KEPT_MS = 30_000L

        /** How long stored preferences read are shared (`loadCustomPrefs`). */
        const val PREFS_KEPT_MS = 60_000L

        /** How long a lookup the server answered 404 to isn't asked again (`refused`). */
        const val REFUSED_MS = 30 * 60_000L

        /** How long the Companion plugin's feature list is trusted. */
        const val COMPANION_FEATURES_MS = 5 * 60_000L

        /** Lists that count every match unless told not to. */
        val COUNTING_LISTS = setOf("Items", "UserItems/Resume", "Shows/NextUp")

        /** Each request's tag, so the Companion plugin can time it under the app's own name. */
        const val QUERY_TAG_HEADER = "X-Nitrate-Query"
        const val ROW_LIMIT = 24
        const val ROW_FIELDS = "Overview,PrimaryImageAspectRatio,Genres"
        const val PROBE_TIMEOUT_MS = 5_000L
        /** How long "Because you watched" is reused before it's worked out again. */
        /** "Because you watched" is kept a day: it barely changes, and finding it is costly for the server. */
        const val BECAUSE_TTL_MS = 24 * 60 * 60 * 1000L

        /** How long the library list kept on the device is used without asking (unless a library change is heard). */
        const val VIEWS_KEEP_MS = 30 * 60_000L

        /** The job that makes every library's "Latest in…" row on Home. */
        internal const val LATEST_JOB = "Latest in each library"
        /** A home server answers in well under a second; away from home this is what startup waits for it. */
        const val LOCAL_PROBE_TIMEOUT_MS = 1_200L

        /** How long a library's codec index is reused before it's read again. */
        const val CODEC_INDEX_TTL_MS = 15 * 60_000L
        const val HEALTH_PAGE = 500
        /** Items read per page for the playback overview (each carries its file's streams). */
        const val OVERVIEW_PAGE = 300
        /** Online databases a title counts as identified by. */
        val MATCH_PROVIDERS = setOf("tmdb", "imdb", "tvdb", "tvmaze", "tmdbcollection")
        const val CODEC_INDEX_PAGE = 1000
        const val TRICKPLAY_MIN_WIDTH = 240
        val WIDE_DEFAULT = RowDisplay(style = RowStyle.WIDE, showTitles = true)
    }
}

/** Jellyfin's type name for a kind Nitrate queries by. */
internal fun itemTypeName(kind: ItemKind): String? = when (kind) {
    ItemKind.MOVIE -> "Movie"
    ItemKind.SERIES -> "Series"
    ItemKind.EPISODE -> "Episode"
    ItemKind.BOX_SET -> "BoxSet"
    ItemKind.PLAYLIST -> "Playlist"
    ItemKind.BOOK -> "Book"
    ItemKind.AUDIOBOOK -> "AudioBook"
    ItemKind.VIDEO -> "Video"
    ItemKind.MUSIC_VIDEO -> "MusicVideo"
    ItemKind.MUSIC_ALBUM -> "MusicAlbum"
    ItemKind.AUDIO -> "Audio"
    ItemKind.MUSIC_ARTIST -> "MusicArtist"
    else -> null
}

/** Jellyfin /Items parameters for a saved search. */
internal fun itemQueryParams(q: ItemQuery): Map<String, Any?> {
    val types = q.types.mapNotNull(::itemTypeName).ifEmpty { if (q.libraryId == null) listOf("Movie", "Series") else emptyList() }
    val sort = when (q.sort) {
        QuerySort.DATE_ADDED -> "DateCreated"
        QuerySort.RELEASE_DATE -> "PremiereDate"
        QuerySort.NAME -> "SortName"
        QuerySort.RATING -> "CommunityRating"
        QuerySort.CRITIC_RATING -> "CriticRating"
        QuerySort.RUNTIME -> "Runtime"
        QuerySort.DATE_PLAYED -> "DatePlayed"
        QuerySort.RANDOM -> "Random"
    }
    val filters = listOfNotNull(
        when (q.watched) {
            WatchFilter.UNWATCHED -> "IsUnplayed"
            WatchFilter.WATCHED -> "IsPlayed"
            WatchFilter.IN_PROGRESS -> "IsResumable"
            WatchFilter.ANY -> null
        },
        "IsFavorite".takeIf { q.favoritesOnly },
    )
    val years = if (q.yearFrom != null && q.yearTo != null && q.yearTo >= q.yearFrom && q.yearTo - q.yearFrom <= 200) (q.yearFrom..q.yearTo).joinToString(",") else null
    return mapOf(
        "parentId" to q.libraryId,
        "recursive" to true,
        "includeItemTypes" to types.joinToString(",").ifEmpty { null },
        // Jellyfin takes several genres, tags and studios separated by "|".
        "genres" to q.genres.joinToString("|").ifEmpty { null },
        "tags" to q.tags.joinToString("|").ifEmpty { null },
        "studios" to q.studios.joinToString("|").ifEmpty { null },
        "officialRatings" to q.officialRatings.joinToString("|").ifEmpty { null },
        "personIds" to q.personId,
        "nameStartsWithOrGreater" to q.nameFrom,
        "nameLessThan" to q.nameBefore,
        "years" to years,
        "minPremiereDate" to (if (years == null) q.yearFrom?.let { "$it-01-01T00:00:00Z" } else null),
        "maxPremiereDate" to (if (years == null) q.yearTo?.let { "$it-12-31T23:59:59Z" } else null),
        "minCommunityRating" to q.minRating,
        "filters" to filters.joinToString(",").ifEmpty { null },
        "isHd" to (if (q.resolution == ResolutionFilter.HD) true else null),
        "is4K" to (if (q.resolution == ResolutionFilter.UHD) true else null),
        "sortBy" to if (q.sort == QuerySort.RANDOM) sort else "$sort,SortName",
        "sortOrder" to if (q.descending) "Descending,Ascending" else "Ascending,Ascending",
        "limit" to q.limit,
        // SortName lets A–Z jumps check where each letter really starts.
        "fields" to "Overview,PrimaryImageAspectRatio,Genres,SortName,DateCreated",
        "enableTotalRecordCount" to false,
    )
}

/** What [JellyfinClient.itemById] asks for: what a details page shows (the single lookup gives everything). */
private const val BY_ID_FIELDS = "Overview,Genres,Tags,ChildCount,PrimaryImageAspectRatio,People,Studios,ProviderIds"
