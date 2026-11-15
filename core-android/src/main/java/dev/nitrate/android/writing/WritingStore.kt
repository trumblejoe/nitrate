package dev.nitrate.android.writing

import android.content.Context
import dev.nitrate.core.MediaServer
import dev.nitrate.core.writing.Scripts
import dev.nitrate.core.writing.TransferDocs
import dev.nitrate.core.writing.WritingDoc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
private data class BackupEntry(val text: String, val modifiedMs: Long, val story: Map<String, dev.nitrate.core.writing.story.StoryRecord>? = null)

/**
 * A script here that is also a shared script: which one, on which account ("serverUrl|userId"),
 * and what the shared-script session keeps between runs.
 */
@Serializable
data class SharedLink(
    val sharedId: String,
    val account: String,
    val state: dev.nitrate.core.writing.SharedScriptState,
    /** Who else writes in it (names), as last heard from the server. */
    val members: List<String> = emptyList(),
    /** The owner's name when it's someone else's script shared with this user. */
    val ownerName: String? = null,
)

/**
 * Screenplays saved as Fountain files in the app's private storage, one file per document.
 * When backup is on, each document is also kept in the user's Jellyfin display preferences
 * (a slot of its own), so a reset TV or another TV can restore it. Newest copy wins.
 */
class WritingStore(context: Context, private val repository: MediaServer) {
    private val dir = File(context.filesDir, "writing").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val backupLock = Mutex()
    private val _docs = MutableStateFlow<List<WritingDoc>>(emptyList())
    /** The signed-in profile's scripts (each script belongs to the account that made it). */
    val docs: StateFlow<List<WritingDoc>> = _docs

    private fun file(id: String) = File(dir, "$id.fountain")

    /** Each script's story data (scene details, lists, links, comments), kept with it. */
    private val storyDir = File(dir, "story").apply { mkdirs() }
    private fun storyFile(id: String) = File(storyDir, "$id.json")

    /** A script's own story data (a shared script's lives in its link while shared). */
    suspend fun readStory(id: String): Map<String, dev.nitrate.core.writing.story.StoryRecord> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<Map<String, dev.nitrate.core.writing.story.StoryRecord>>(storyFile(id).readText()) }.getOrDefault(emptyMap())
    }

    suspend fun saveStory(id: String, records: Map<String, dev.nitrate.core.writing.story.StoryRecord>) = withContext(Dispatchers.IO) {
        val temp = File(storyDir, "$id.json.tmp")
        temp.writeText(json.encodeToString(records))
        temp.renameTo(storyFile(id))
    }

    /** Links to shared scripts, one file per linked script (never backed up: they're per account). */
    private val linkDir = File(dir, "shared").apply { mkdirs() }
    private fun linkFile(id: String) = File(linkDir, "$id.json")

    /**
     * The signed-in account ("serverUrl|userId"): only its scripts are listed. Scripts from
     * before scripts had owners become the first signed-in account's.
     */
    @Volatile var account: String? = null
        set(value) {
            field = value
            if (value != null) claimUnowned(value)
            _docs.value = scan()
        }

    /** Who's writing here (user id, name), for the history kept on the device. */
    @Volatile var author: () -> Pair<String, String> = { "" to "" }

    // ---- Owners ----

    private val ownersFile = File(dir, "owners.json")
    private var owners: MutableMap<String, String> = runCatching { json.decodeFromString<Map<String, String>>(ownersFile.readText()) }.getOrDefault(emptyMap()).toMutableMap()

    private fun saveOwners() = synchronized(this) {
        val temp = File(dir, "owners.json.tmp")
        temp.writeText(json.encodeToString(owners.toMap()))
        temp.renameTo(ownersFile)
    }

    fun ownerOf(id: String): String? = synchronized(this) { owners[id] }

    private fun claimUnowned(account: String) = synchronized(this) {
        val unowned = dir.listFiles { f -> f.extension == "fountain" }.orEmpty().map { it.nameWithoutExtension }.filter { it !in owners }
        if (unowned.isNotEmpty()) {
            unowned.forEach { owners[it] = account }
            saveOwners()
        }
    }

    /** Gives a script to another account (moving it to that account's server). */
    suspend fun setOwner(id: String, account: String) = withContext(Dispatchers.IO) {
        synchronized(this@WritingStore) { owners[id] = account; saveOwners() }
        _docs.value = scan()
    }

    // ---- Sync choices (device-only) ----

    private val syncFile = File(dir, "sync.json")

    @Serializable
    private data class SyncChoices(val accounts: Map<String, Boolean> = emptyMap(), val deviceOnly: Set<String> = emptySet())

    private fun syncChoices(): SyncChoices = runCatching { json.decodeFromString<SyncChoices>(syncFile.readText()) }.getOrDefault(SyncChoices())
    private fun saveSyncChoices(c: SyncChoices) = synchronized(this) { syncFile.writeText(json.encodeToString(c)) }

    /** This device's choice for syncing [account]'s scripts (null: not chosen, so the default applies). */
    fun syncChoice(account: String): Boolean? = syncChoices().accounts[account]

    fun setSyncChoice(account: String, on: Boolean) = saveSyncChoices(syncChoices().let { it.copy(accounts = it.accounts + (account to on)) })

    /** Scripts kept on this device only (never synced, whatever the account's choice). */
    fun isDeviceOnly(id: String) = id in syncChoices().deviceOnly

    fun setDeviceOnly(id: String, deviceOnly: Boolean) = saveSyncChoices(syncChoices().let { it.copy(deviceOnly = if (deviceOnly) it.deviceOnly + id else it.deviceOnly - id) })

    // ---- History kept on the device (scripts not synced with a server) ----

    private val historyDir = File(dir, "history").apply { mkdirs() }
    private fun logFile(id: String) = File(historyDir, "$id.log")
    private fun startFile(id: String) = File(historyDir, "$id.start.json")

    /** Where this device's history of a script starts (made with the first change). */
    suspend fun historyStart(id: String): dev.nitrate.core.writing.story.LocalHistoryStart? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<dev.nitrate.core.writing.story.LocalHistoryStart>(startFile(id).readText()) }.getOrNull()
    }

    /** This device's history of a script, oldest first. */
    suspend fun localLog(id: String): List<dev.nitrate.core.writing.story.LocalEdit> = withContext(Dispatchers.IO) {
        logFile(id).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
            runCatching { json.decodeFromString<dev.nitrate.core.writing.story.LocalEdit>(line) }.getOrNull()
        }
    }

    /** Adds a change to the device's history (text from [before] to [after], and/or story changes). */
    private suspend fun record(id: String, before: String?, after: String?, changes: List<dev.nitrate.core.writing.story.RecordChange>) = withContext(Dispatchers.IO) {
        if (link(id) != null) return@withContext // a synced script's history is the server's
        val (userId, userName) = author()
        val now = System.currentTimeMillis()
        if (!startFile(id).exists()) {
            startFile(id).writeText(json.encodeToString(dev.nitrate.core.writing.story.LocalHistoryStart(before ?: read(id), readStory(id), now, userId)))
        }
        val next = (logFile(id).takeIf { it.exists() }?.useLines { it.count() } ?: 0) + 1L
        val edit = when {
            before != null && after != null -> dev.nitrate.core.writing.story.LocalEdit.text(next, userId, userName, now, before, after)?.copy(records = changes)
            changes.isNotEmpty() -> dev.nitrate.core.writing.story.LocalEdit(next, userId, userName, now, records = changes)
            else -> null
        } ?: return@withContext
        logFile(id).appendText(json.encodeToString(edit) + "\n")
        if (next > MAX_LOCAL_EDITS) compact(id)
    }

    /** Story changes made on the device, for its history. */
    suspend fun recordStory(id: String, changes: List<dev.nitrate.core.writing.story.RecordChange>) = record(id, null, null, changes)

    /** Folds the oldest changes into the start, so the history stays a reasonable size. */
    private fun compact(id: String) {
        val log = logFile(id).readLines().mapNotNull { runCatching { json.decodeFromString<dev.nitrate.core.writing.story.LocalEdit>(it) }.getOrNull() }
        val start = runCatching { json.decodeFromString<dev.nitrate.core.writing.story.LocalHistoryStart>(startFile(id).readText()) }.getOrNull() ?: return
        val drop = log.size - MAX_LOCAL_EDITS / 2
        if (drop <= 0) return
        val (text, story) = dev.nitrate.core.writing.story.History.replay(start, log.map { it.toScriptEdit() }, log[drop - 1].version)
        startFile(id).writeText(json.encodeToString(start.copy(text = text, records = story.records, atMs = log[drop - 1].atMs)))
        logFile(id).writeText(log.drop(drop).joinToString("") { json.encodeToString(it) + "\n" })
    }

    // Backups that haven't reached the server yet (offline, server down). Survive restarts.
    private val pendingFile = File(dir, "pending-backup.json")

    @Serializable
    private data class Pending(val upload: Set<String> = emptySet(), val delete: Set<String> = emptySet())

    private fun pending(): Pending = runCatching { json.decodeFromString<Pending>(pendingFile.readText()) }.getOrDefault(Pending())

    private fun setPending(p: Pending) = pendingFile.writeText(json.encodeToString(p))

    /** Number of scripts waiting to be backed up. */
    val pendingCount: Int get() = pending().let { it.upload.size + it.delete.size }

    init {
        _docs.value = scan()
    }

    private fun scan(): List<WritingDoc> {
        val links = links()
        val current = account
        val owned = synchronized(this) { owners.toMap() }
        return dir.listFiles { f -> f.extension == "fountain" }.orEmpty()
            .filter { f -> owned[f.nameWithoutExtension] == current }
            .map { f ->
                val link = links[f.nameWithoutExtension]
                WritingDoc(f.nameWithoutExtension, Scripts.titleOf(f.readText()), f.lastModified(), link?.sharedId, link?.members.orEmpty(), link?.ownerName)
            }
            .sortedByDescending { it.modifiedMs }
    }

    /** This account's links, by local script id. */
    fun links(): Map<String, SharedLink> {
        val current = account ?: return emptyMap()
        return linkDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
            runCatching { json.decodeFromString<SharedLink>(f.readText()) }.getOrNull()?.takeIf { it.account == current }?.let { f.nameWithoutExtension to it }
        }.toMap()
    }

    fun link(id: String): SharedLink? = runCatching { json.decodeFromString<SharedLink>(linkFile(id).readText()) }.getOrNull()?.takeIf { it.account == account }

    /** Links a script here to a shared one (or updates what the session keeps), and saves its text. */
    suspend fun saveLink(id: String, link: SharedLink) = withContext(Dispatchers.IO) {
        val temp = File(linkDir, "$id.json.tmp")
        temp.writeText(json.encodeToString(link))
        temp.renameTo(linkFile(id))
        if (file(id).let { !it.exists() || it.readText() != link.state.text }) file(id).writeText(link.state.text)
        _docs.value = scan()
    }

    /** Makes a script here plain again (it stays, as this user's own copy, with its story data). */
    suspend fun unlink(id: String) = withContext(Dispatchers.IO) {
        link(id)?.let { l ->
            var story = dev.nitrate.core.writing.story.Story(l.state.records)
            l.state.pendingRecords.forEach { p -> p.changes.forEach { story = story.apply(it, null, null, System.currentTimeMillis()) } }
            saveStory(id, story.records)
        }
        linkFile(id).delete()
        _docs.value = scan()
    }


    suspend fun read(id: String): String = withContext(Dispatchers.IO) { file(id).takeIf { it.exists() }?.readText().orEmpty() }

    suspend fun create(text: String = ""): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        file(id).writeText(text)
        account?.let { a -> synchronized(this@WritingStore) { owners[id] = a; saveOwners() } }
        _docs.value = scan()
        id
    }

    suspend fun save(id: String, text: String) = withContext(Dispatchers.IO) {
        val before = file(id).takeIf { it.exists() }?.readText()
        file(id).writeText(text)
        if (before != null && before != text) record(id, before, text, emptyList())
        _docs.value = scan()
    }

    suspend fun delete(id: String, backup: Boolean) {
        withContext(Dispatchers.IO) {
            file(id).delete(); linkFile(id).delete(); storyFile(id).delete(); logFile(id).delete(); startFile(id).delete()
            synchronized(this@WritingStore) { if (owners.remove(id) != null) saveOwners() }
        }
        _docs.value = scan()
        if (backup) {
            backupLock.withLock { withContext(Dispatchers.IO) { pending().let { setPending(it.copy(upload = it.upload - id, delete = it.delete + id)) } } }
            flushPending()
        }
    }

    /** Queues one document for the server copy and tries to send everything queued. */
    suspend fun backUp(id: String): Boolean {
        backupLock.withLock { withContext(Dispatchers.IO) { pending().let { setPending(it.copy(upload = it.upload + id)) } } }
        return flushPending()
    }

    /**
     * Sends queued uploads and deletions in one update. Returns true when nothing is left
     * waiting; on failure (e.g. offline) everything stays queued for the next attempt.
     */
    suspend fun flushPending(): Boolean = backupLock.withLock {
        // Each account's backup holds only its own scripts; others' wait for their account.
        val mine = account
        val queued = withContext(Dispatchers.IO) { pending() }.let { p -> p.copy(upload = p.upload.filter { ownerOf(it) == mine || ownerOf(it) == null }.toSet()) }
        if (queued.upload.isEmpty() && queued.delete.isEmpty()) return@withLock pendingCount == 0
        val ok = runCatching {
            val current = repository.loadCustomPrefs(PREFS_ID)
            val uploads = withContext(Dispatchers.IO) {
                queued.upload.mapNotNull { id ->
                    file(id).takeIf { it.exists() }?.let { f ->
                        // A shared script's story is backed up as it stands here, like its text.
                        val story = link(id)?.state?.records ?: readStory(id)
                        id to json.encodeToString(BackupEntry(f.readText(), f.lastModified(), story.takeIf { it.isNotEmpty() }))
                    }
                }.toMap()
            }
            repository.saveCustomPrefs(current - queued.delete + uploads, PREFS_ID)
        }.isSuccess
        if (ok) {
            // Only clear what was sent; edits made meanwhile stay queued.
            withContext(Dispatchers.IO) { pending().let { setPending(Pending(it.upload - queued.upload, it.delete - queued.delete)) } }
        }
        ok && withContext(Dispatchers.IO) { pending().let { it.upload.isEmpty() && it.delete.isEmpty() } }
    }

    /** Brings in server copies that are newer than, or missing from, this TV. Returns how many. */
    suspend fun restore(): Int {
        val remote = runCatching { repository.loadCustomPrefs(PREFS_ID) }.getOrNull() ?: return 0
        var restored = 0
        val deletedHere = withContext(Dispatchers.IO) { pending().delete }
        withContext(Dispatchers.IO) {
            for ((id, raw) in remote) {
                if (id in deletedHere) continue
                val entry = runCatching { json.decodeFromString<BackupEntry>(raw) }.getOrNull() ?: continue
                val local = file(id)
                // Someone else's script on this device keeps its owner; a backup never takes it.
                val owner = ownerOf(id)
                if (owner != null && owner != account) continue
                if (!local.exists() || local.lastModified() < entry.modifiedMs) {
                    account?.let { a -> synchronized(this@WritingStore) { owners[id] = a; saveOwners() } }
                    local.writeText(entry.text)
                    local.setLastModified(entry.modifiedMs)
                    entry.story?.let { if (link(id) == null) saveStory(id, it) }
                    restored++
                }
            }
        }
        _docs.value = scan()
        return restored
    }

    /** Lets the transfer page list, download and add scripts. */
    fun asTransferDocs(): TransferDocs = object : TransferDocs {
        override fun list(): List<WritingDoc> = docs.value

        // Only ids of existing scripts: the id comes from a URL, so it must never reach other files.
        override suspend fun read(id: String): String? =
            if (docs.value.any { it.id == id }) this@WritingStore.read(id) else null

        override suspend fun create(text: String): String = this@WritingStore.create(text)
    }

    companion object {
        const val PREFS_ID = "nitrate-writing"
        /** Changes kept in a device's history of one script before the oldest are folded in. */
        const val MAX_LOCAL_EDITS = 20_000

    }
}
