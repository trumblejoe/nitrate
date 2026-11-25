package dev.nitrate.android.viewmodel

import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.nitrate.android.storage.DisplaySettingsStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.pages.PageComposer
import dev.nitrate.core.pages.PageLayout
import dev.nitrate.core.pages.RowEntry
import dev.nitrate.core.pages.RowSource
import dev.nitrate.core.rows.MediaRow
import dev.nitrate.core.rows.RowDisplay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RowsState {
    data object Loading : RowsState
    data class Loaded(val rows: List<MediaRow>) : RowsState
    data class Error(val message: String) : RowsState
}

/**
 * One page of rows (Home, a library's overview, or a custom page): the app's own rows and the
 * user's custom rows, in the user's order with their hidden rows left out. Also edits the layout.
 */
class RowsViewModel(
    /** What the layout is saved against: PageKeys.HOME, PageKeys.library(id) or PageKeys.custom(id). */
    val pageKey: String,
    private val loadBuiltIn: suspend () -> List<MediaRow>,
    private val store: DisplaySettingsStore,
    private val server: MediaServer,
    /** Fires when the server says something changed (new items, watch state); rows reload quietly. */
    private val changes: Flow<Unit> = emptyFlow(),
    /** A row's look until the user picks one (e.g. artwork-first on the phone's Home). */
    private val defaults: (MediaRow) -> RowDisplay = { it.defaultDisplay },
    /** Rows as last loaded, shown at once while fresh ones load. */
    private val cache: dev.nitrate.android.storage.DataCache? = null,
    /** The built-in rows as each arrives (instead of [loadBuiltIn]), so the page needn't wait for its slowest row. */
    private val loadProgress: (() -> kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress>)? = null,
    /**
     * As [loadProgress], told which built-in rows the page hides, so they're never queried (a
     * hidden row is off, not just out of sight); preferred over [loadProgress] when given.
     */
    private val loadProgressSkipping: ((Set<String>) -> kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress>)? = null,
) : ViewModel() {
    /** The app rows as last loaded, for their own titles ("Because you watched Dune") and looks. */
    private val appRows = mutableMapOf<String, MediaRow>()

    /** What this page's requests are put down to in the query analyzer. */
    private val origin = dev.nitrate.core.diagnostics.QueryLog.pageLabel(pageKey)
    private val account = server.currentAccountKey ?: "${server.serverUrl}|${server.userId}"
    private val userId = server.userId
    private val _refreshing = MutableStateFlow(false)
    /** A reload is running (for a pull-to-refresh indicator). */
    val refreshing: StateFlow<Boolean> = _refreshing
    private val builtIn = MutableStateFlow<Result<List<MediaRow>>?>(null)
    private val custom = MutableStateFlow<Map<String, MediaRow>>(emptyMap())

    /** Titles of built-in rows seen so far, for the editor (empty rows aren't in [builtIn]). */
    private val knownTitles = mutableMapOf<String, String>()

    val layout: StateFlow<PageLayout> = (userId?.let { store.layout(it, pageKey) } ?: flowOf(PageLayout()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, PageLayout())

    val overrides: StateFlow<Map<String, RowDisplay>> =
        (userId?.let(store::overrides) ?: emptyFlow())
            // Retired styles show as what replaced them; what's stored stays as it is until changed.
            .map { all -> all.mapValues { it.value.normalized() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Every entry on the page, hidden ones included, in order: what the editor shows. */
    val entries: StateFlow<List<RowEntry>> = combine(layout, builtIn) { l, b -> PageComposer.entries(b?.getOrNull().orEmpty().map { it.key }, l) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<RowsState> = combine(builtIn, entries, custom) { b, e, c ->
        when {
            b == null -> RowsState.Loading
            b.isFailure && c.isEmpty() -> RowsState.Error(b.exceptionOrNull()?.message ?: "Failed to load")
            else -> RowsState.Loaded(PageComposer.rows(e, b.getOrNull().orEmpty(), c))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RowsState.Loading)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchChanges() = viewModelScope.launch {
        // A library scan sends many changes in a row: reload once things settle.
        changes.debounce(LIVE_REFRESH_DEBOUNCE_MS).collect { refresh(checked = true) }
    }

    init {
        // Last time's rows straight away (read first, so the load knows they're on screen); the
        // fresh page replaces them once it has arrived.
        viewModelScope.launch {
            cache?.read(account, pageKey)?.let { cached ->
                if (builtIn.value == null && cached.builtIn.isNotEmpty()) builtIn.value = Result.success(cached.builtIn.map { it.copy(defaultDisplay = defaults(it).normalized()) })
                if (custom.value.isEmpty()) custom.value = cached.custom
                if (lastRows == null) { lastRows = cached.builtIn; stamps = cached.stamps; customStamps.putAll(cached.customStamps) }
            }
            refresh(checked = true)
        }
        watchChanges()
        // Load custom rows whenever the page's custom entries change.
        viewModelScope.launch {
            layout.map { l -> l.entries.filter { it.isCustom } }.collect { loadCustom(it) }
        }
    }

    /** The built-in rows the saved layout hides (read from the store, not the not-yet-loaded [layout]). */
    private suspend fun hiddenBuiltIn(): Set<String> {
        val saved = userId?.let { runCatching { store.layout(it, pageKey).first() }.getOrNull() } ?: return emptySet()
        return saved.entries.filter { it.hidden && !it.isCustom }.map { it.rowKey }.toSet()
    }

    /** The built-in rows as last loaded whole (or kept from last time), and the server's change counters then. */
    private var lastRows: List<MediaRow>? = null
    private var stamps: String? = null

    /** Loads the page again, every row (pull to refresh, a setting changed). */
    fun refresh() = refresh(checked = false)

    /**
     * Loads the page. [checked] (opening it, or the server saying something changed): first asks the
     * server what changed since the rows on screen were loaded (`MediaServer.changeCheck`, one tiny
     * request with the Companion), and keeps the rows nothing has changed for, only their watched
     * marks read afresh when the user's watch state changed (`RowChanges`).
     */
    private fun refresh(checked: Boolean) {
        val skipping = loadProgressSkipping
        if (skipping != null) {
            viewModelScope.launch {
                _refreshing.value = true
                val check = runCatching { dev.nitrate.core.diagnostics.withQueryOrigin(origin) { server.changeCheck(stamps) } }.getOrNull()
                check?.let { checked(it.token) }
                val before = lastRows.orEmpty()
                val kept = if (!checked || check == null || stamps == null) emptyList() else {
                    val keep = dev.nitrate.core.rows.RowChanges.keepable(before, check.changed)
                    if (keep.isNotEmpty() && dev.nitrate.core.rows.ChangeArea.WATCH in check.changed)
                        dev.nitrate.core.diagnostics.withQueryOrigin(origin) { server.withFreshMarks(keep) } else keep
                }
                val keptKeys = kept.map { it.key }.toSet()
                load(check?.token) {
                    skipping(hiddenBuiltIn() + keptKeys).map { p ->
                        if (kept.isEmpty()) p else p.copy(rows = dev.nitrate.core.rows.RowChanges.merge(before, kept, p.rows), skipped = p.skipped - keptKeys)
                    }
                }
            }
            return
        }
        val progressive: (suspend () -> kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress>)? =
            loadProgress?.let { lp -> { lp.invoke() } }
        if (progressive != null) {
            viewModelScope.launch {
                _refreshing.value = true
                load(null, progressive)
            }
            return
        }
        refreshAll()
    }

    /** One load of the page's built-in rows as they arrive; [token] is saved with them once they're whole. */
    private suspend fun load(token: String?, progressive: suspend () -> kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress>) {
        var finished: List<MediaRow>? = null
        // Rows aren't shown one by one as they answer (the page would keep jumping): the
        // whole page swaps in at the end, or once at the patience mark if that's long.
        val reveal = dev.nitrate.core.rows.PageReveal
        var latest: dev.nitrate.core.rows.RowsProgress? = null
        var partialShown = false
        fun showPartial(p: dev.nitrate.core.rows.RowsProgress) {
            builtIn.value = Result.success(reveal.partial(builtIn.value?.getOrNull(), p))
            partialShown = true
        }
        val outcome = runCatching {
            kotlinx.coroutines.coroutineScope {
                val patience = launch {
                    kotlinx.coroutines.delay(reveal.patienceMs(builtIn.value?.getOrNull()))
                    latest?.let(::showPartial)
                }
                dev.nitrate.core.diagnostics.withQueryOrigin(origin) { progressive().collect { raw ->
                    val fresh = raw.rows.map { it.copy(defaultDisplay = defaults(it).normalized()) }
                    fresh.forEach { knownTitles[it.key] = it.title }
                    raw.skipped.forEach { (key, title) -> knownTitles.getOrPut(key) { title } }
                    val p = raw.copy(rows = fresh)
                    latest = p
                    when {
                        p.complete -> {
                            patience.cancel()
                            builtIn.value = Result.success(fresh)
                            finished = fresh
                        }
                        // Nothing had answered by the patience mark: show the first answer.
                        patience.isCompleted && !partialShown -> showPartial(p)
                    }
                } }
                patience.cancel()
            }
        }
        // Keep showing old rows if a background refresh fails.
        outcome.exceptionOrNull()?.let { e -> if (builtIn.value?.isSuccess != true) builtIn.value = Result.failure(e) }
        loadCustom(layout.value.entries.filter { it.isCustom })
        val rows = finished
        _refreshing.value = false
        if (rows != null) { lastRows = rows; stamps = token }
        if (cache != null && rows != null) cache.write(account, pageKey, dev.nitrate.android.storage.CachedPage(rows, custom.value, token, customStamps.toMap()))
    }

    private fun refreshAll() {
        viewModelScope.launch {
            _refreshing.value = true
            val result = runCatching { dev.nitrate.core.diagnostics.withQueryOrigin(origin) { loadBuiltIn() }.map { it.copy(defaultDisplay = defaults(it).normalized()) } }
            result.getOrNull()?.forEach { knownTitles[it.key] = it.title }
            // Keep showing old rows if a background refresh fails.
            if (result.isSuccess || builtIn.value?.isSuccess != true) builtIn.value = result
            loadCustom(layout.value.entries.filter { it.isCustom })
            val rows = result.getOrNull()
            _refreshing.value = false
            if (cache != null && rows != null) cache.write(account, pageKey, dev.nitrate.android.storage.CachedPage(rows, custom.value, stamps, customStamps.toMap()))
        }
    }

    /** Each custom row's change counters and source when it was loaded (`RowChanges.stampOf`). */
    private val customStamps = mutableMapOf<String, String>()
    private var lastToken: Pair<Long, String>? = null

    private fun checked(token: String) { lastToken = dev.nitrate.android.nowMs() to token }

    /** The server's change counters now (one tiny request, reused for a few seconds), or null without them. */
    private suspend fun tokenNow(): String? {
        lastToken?.takeIf { dev.nitrate.android.nowMs() - it.first < TOKEN_REUSE_MS }?.let { return it.second }
        return runCatching { dev.nitrate.core.diagnostics.withQueryOrigin(origin) { server.changeCheck(null) } }.getOrNull()?.token?.also(::checked)
    }

    // One custom-row load at a time: opening a page starts two (the layout arriving, the first load),
    // and the second then keeps what the first just read instead of reading it again.
    private val customLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun loadCustom(all: List<RowEntry>) = customLock.withLock { loadCustomNow(all) }

    private suspend fun loadCustomNow(all: List<RowEntry>) = coroutineScope {
        // A hidden custom row is off: never queried.
        val entries = all.filterNot { it.hidden }
        // Rows nothing has changed for since they were loaded are kept (`RowChanges.dependsOn`).
        val token = if (entries.isEmpty()) null else tokenNow()
        val before = custom.value
        val keep = if (token == null) emptySet() else entries.filter { e ->
            e.id in before && dev.nitrate.core.rows.RowChanges.keepCustom(e.source, customStamps[e.id], token)
        }.map { it.id }.toSet()
        // Their watched marks read afresh when watch state changed (one request for all of them).
        val kept = keep.takeIf { it.isNotEmpty() }?.let { ids ->
            val rows = ids.mapNotNull { id -> before[id]?.let { id to it } }
            val watchChanged = entries.filter { it.id in ids }.any { e ->
                customStamps[e.id]?.substringBefore('#')?.let { dev.nitrate.core.rows.ChangeArea.WATCH in dev.nitrate.core.rows.RowChanges.compare(it, token!!) } != false
            }
            if (!watchChanged) rows.toMap() else {
                val fresh = runCatching { dev.nitrate.core.diagnostics.withQueryOrigin(origin) { server.withFreshMarks(rows.map { it.second }) } }.getOrNull()
                if (fresh == null || fresh.size != rows.size) rows.toMap() else rows.map { it.first }.zip(fresh).toMap()
            }
        }.orEmpty()
        val loaded = entries.filter { it.id !in keep }.map { e ->
            async {
                val items = runCatching {
                    dev.nitrate.core.diagnostics.withQueryOrigin("$origin › ${titleOf(e)}") { when (val src = e.source) {
                        is RowSource.Query -> server.queryItems(src.query)
                        is RowSource.Collection -> server.collectionItems(src.id)
                        // Only the row's first page: a long playlist is never read whole for a row.
                        is RowSource.Playlist -> server.playlistRow(src.id, CUSTOM_ROW_LIMIT)
                        is RowSource.BuiltIn -> emptyList()
                        // The app's own rows answer from Home's shared requests.
                        is RowSource.AppRow -> server.appRow(src.key, src.libraryId)?.also { appRows[e.id] = it }?.items.orEmpty()
                    } }
                }.getOrNull()
                val app = appRows[e.id]?.takeIf { e.source is RowSource.AppRow }
                val more = when (val src = e.source) {
                    is RowSource.Query -> server.moreOfQuery(src.query)
                    is RowSource.Playlist -> server.moreOfPlaylist(src.id)
                    else -> null
                } ?: app?.more
                items?.let { e.id to MediaRow(e.rowKey, e.title?.takeIf { it.isNotBlank() } ?: app?.title ?: defaultTitle(e), it, (e.display ?: app?.defaultDisplay ?: PageComposer.defaultDisplay(e.source)).normalized(), more) }
            }
        }.awaitAll().filterNotNull().toMap()
        if (token != null) (kept.keys + loaded.keys).forEach { id -> entries.firstOrNull { it.id == id }?.let { customStamps[id] = dev.nitrate.core.rows.RowChanges.stampOf(it.source, token) } }
        // A kept row takes its entry's current title and look (only its cards were kept).
        val keptNow = kept.mapNotNull { (id, row) ->
            entries.firstOrNull { it.id == id }?.let { e -> id to row.copy(title = e.title?.takeIf { it.isNotBlank() } ?: row.title, defaultDisplay = (e.display ?: row.defaultDisplay).normalized()) }
        }.toMap()
        // Keep earlier results for rows that failed this time.
        custom.value = before.filterKeys { id -> entries.any { it.id == id } } + keptNow + loaded
    }

    /** What the editor calls a row. */
    fun titleOf(entry: RowEntry): String = entry.title?.takeIf { it.isNotBlank() } ?: defaultTitle(entry)

    private fun defaultTitle(e: RowEntry): String = when (val s = e.source) {
        is RowSource.BuiltIn -> knownTitles[s.key] ?: s.key.substringAfterLast('/').replace('_', ' ').replaceFirstChar(Char::uppercase)
        is RowSource.Query -> s.query.genres.firstOrNull() ?: s.query.describe()
        is RowSource.Collection -> s.name
        is RowSource.Playlist -> s.name
        is RowSource.AppRow -> dev.nitrate.core.rows.AppRows.title(s.key, null)
    }

    fun save(row: MediaRow, display: RowDisplay) {
        val user = userId ?: return
        // Custom rows keep their display in the layout entry, so it travels with the row.
        val entry = entries.value.firstOrNull { it.isCustom && it.rowKey == row.key }
        if (entry != null) updateEntries { list -> list.map { if (it.id == entry.id) it.copy(display = display) else it } }
        else viewModelScope.launch { store.save(user, row, display) }
    }

    // ---- Editing ----

    fun updateEntries(change: (List<RowEntry>) -> List<RowEntry>) {
        val user = userId ?: return
        viewModelScope.launch { store.saveLayout(user, pageKey, PageLayout(change(entries.value))) }
    }

    fun move(id: String, delta: Int) = updateEntries { PageComposer.move(it, id, delta) }
    fun setHidden(id: String, hidden: Boolean) {
        val user = userId ?: return
        viewModelScope.launch {
            store.saveLayout(user, pageKey, PageLayout(entries.value.map { if (it.id == id) it.copy(hidden = hidden) else it }))
            // Shown again: it was never loaded while hidden, so load the page (built-in rows) now.
            if (!hidden) refresh()
        }
    }
    fun rename(id: String, title: String?) = updateEntries { list -> list.map { if (it.id == id) it.copy(title = title?.takeIf(String::isNotBlank)) else it } }
    fun remove(id: String) = updateEntries { list -> list.filterNot { it.id == id && it.isCustom } }
    fun add(entry: RowEntry, atTop: Boolean = false) = updateEntries { if (atTop) listOf(entry) + it else it + entry }
    fun replace(entry: RowEntry) = updateEntries { list -> list.map { if (it.id == entry.id) entry else it } }

    /** Back to the app's own rows and order (custom rows are removed). */
    fun reset() {
        val user = userId ?: return
        viewModelScope.launch { store.saveLayout(user, pageKey, PageLayout()) }
    }

    /** Current rows once loaded, for one-off use. */
    suspend fun loadedRows(): List<MediaRow> = (state.first { it !is RowsState.Loading } as? RowsState.Loaded)?.rows.orEmpty()
}

private const val LIVE_REFRESH_DEBOUNCE_MS = 2_000L

/** How long the server's change counters, once asked, serve a page's custom rows without asking again. */
private const val TOKEN_REUSE_MS = 5_000L

/** How many titles a custom playlist row reads at first (more as it's scrolled, `RowMore`). */
private const val CUSTOM_ROW_LIMIT = 24

/**
 * Home's rows for [RowsViewModel] (`loadProgressSkipping`): the page's hidden rows left out, and
 * Continue Watching with Next Up as one row when the user's display settings say so. With [playlists],
 * "Continue your playlists" (where each video playlist was left) joins them after Next Up, made side by
 * side with the rest and never holding them up.
 */
fun homeRowsLoader(
    server: MediaServer,
    store: DisplaySettingsStore,
    playlists: dev.nitrate.android.playlists.PlaylistPlaying? = null,
): (Set<String>) -> kotlinx.coroutines.flow.Flow<dev.nitrate.core.rows.RowsProgress> = { hidden ->
    val key = dev.nitrate.android.playlists.PlaylistPlaying.ROW_KEY
    val title = "Continue your playlists"
    kotlinx.coroutines.flow.channelFlow {
        val combine = server.userId?.let { runCatching { store.prefs(it).first().combineContinueNextUp }.getOrNull() } ?: false
        val lock = kotlinx.coroutines.sync.Mutex()
        var latest: dev.nitrate.core.rows.RowsProgress? = null
        var extra: MediaRow? = null
        var extraDone = playlists == null || key in hidden
        suspend fun emitMerged() {
            val p = latest ?: return
            val rows = extra?.let { HomePlaylistsRow.insert(p.rows, it) } ?: p.rows
            send(p.copy(rows = rows, complete = p.complete && extraDone, skipped = if (key in hidden) p.skipped + (key to title) else p.skipped))
        }
        val job = if (!extraDone) launch {
            val row = dev.nitrate.core.diagnostics.withQueryOrigin("Home › $title") { runCatching { playlists!!.continueRow() }.getOrNull() }
            lock.withLock { extra = row; extraDone = true; emitMerged() }
        } else null
        server.homeRowsProgress(hidden, combine).collect { p -> lock.withLock { latest = p; emitMerged() } }
        job?.join()
    }
}

/** Where Home's "Continue your playlists" goes: after Next Up (or the combined row, else Continue Watching), else first. */
object HomePlaylistsRow {
    private val AFTER = listOf("home/next_up", "home/continue_and_next", "home/continue_watching")

    fun insert(rows: List<MediaRow>, row: MediaRow): List<MediaRow> {
        val without = rows.filter { it.key != row.key }
        val at = AFTER.firstNotNullOfOrNull { k -> without.indexOfFirst { it.key == k }.takeIf { it >= 0 } }
        return if (at == null) listOf(row) + without else without.take(at + 1) + row + without.drop(at + 1)
    }
}
