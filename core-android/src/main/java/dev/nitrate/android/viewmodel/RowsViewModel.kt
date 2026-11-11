package dev.nitrate.android.viewmodel

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
) : ViewModel() {
    private val userId = server.userId
    private val builtIn = MutableStateFlow<Result<List<MediaRow>>?>(null)
    private val custom = MutableStateFlow<Map<String, MediaRow>>(emptyMap())

    /** Titles of built-in rows seen so far, for the editor (empty rows aren't in [builtIn]). */
    private val knownTitles = mutableMapOf<String, String>()

    val layout: StateFlow<PageLayout> = (userId?.let { store.layout(it, pageKey) } ?: flowOf(PageLayout()))
        .stateIn(viewModelScope, SharingStarted.Eagerly, PageLayout())

    val overrides: StateFlow<Map<String, RowDisplay>> =
        (userId?.let(store::overrides) ?: emptyFlow())
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
        changes.debounce(LIVE_REFRESH_DEBOUNCE_MS).collect { refresh() }
    }

    init {
        refresh()
        watchChanges()
        // Load custom rows whenever the page's custom entries change.
        viewModelScope.launch {
            layout.map { l -> l.entries.filter { it.isCustom } }.collect { loadCustom(it) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val result = runCatching { loadBuiltIn().map { it.copy(defaultDisplay = defaults(it)) } }
            result.getOrNull()?.forEach { knownTitles[it.key] = it.title }
            // Keep showing old rows if a background refresh fails.
            if (result.isSuccess || builtIn.value?.isSuccess != true) builtIn.value = result
            loadCustom(layout.value.entries.filter { it.isCustom })
        }
    }

    private suspend fun loadCustom(entries: List<RowEntry>) = coroutineScope {
        val loaded = entries.map { e ->
            async {
                val items = runCatching {
                    when (val src = e.source) {
                        is RowSource.Query -> server.queryItems(src.query)
                        is RowSource.Collection -> server.collectionItems(src.id)
                        is RowSource.Playlist -> server.playlistItems(src.id)
                        is RowSource.BuiltIn -> emptyList()
                    }
                }.getOrNull()
                items?.let { e.id to MediaRow(e.rowKey, e.title ?: defaultTitle(e), it, e.display ?: PageComposer.defaultDisplay(e.source)) }
            }
        }.awaitAll().filterNotNull().toMap()
        // Keep earlier results for rows that failed this time.
        custom.value = custom.value.filterKeys { id -> entries.any { it.id == id } } + loaded
    }

    /** What the editor calls a row. */
    fun titleOf(entry: RowEntry): String = entry.title?.takeIf { it.isNotBlank() } ?: defaultTitle(entry)

    private fun defaultTitle(e: RowEntry): String = when (val s = e.source) {
        is RowSource.BuiltIn -> knownTitles[s.key] ?: s.key.substringAfterLast('/').replace('_', ' ').replaceFirstChar(Char::uppercase)
        is RowSource.Query -> s.query.genres.firstOrNull() ?: s.query.describe()
        is RowSource.Collection -> s.name
        is RowSource.Playlist -> s.name
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
    fun setHidden(id: String, hidden: Boolean) = updateEntries { list -> list.map { if (it.id == id) it.copy(hidden = hidden) else it } }
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
