package dev.nitrate.android.writing

import dev.nitrate.core.MediaServer
import dev.nitrate.core.companion.CompanionInfo
import dev.nitrate.core.writing.SharedScriptInfo
import dev.nitrate.core.writing.SharedScriptSession
import dev.nitrate.core.writing.SharedScriptState
import dev.nitrate.core.writing.UserRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Shared screenplays (with the Nitrate Companion plugin) on this device. A shared script is
 * always also an ordinary script here, so it's in this user's own backups and stays theirs if
 * they leave or the owner stops sharing; [WritingStore] keeps which script is linked to which
 * shared one, per account.
 */
class SharedWriting(
    private val store: WritingStore,
    private val server: MediaServer,
    private val companion: StateFlow<CompanionInfo?>,
    /** This user's name, shown on their story changes before the server confirms them. */
    private val selfName: () -> String? = { null },
) {
    /** Keeps what sessions save, outliving any one screen. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingSaves = mutableMapOf<String, Job>()

    /** True when the server has the Companion plugin with shared scripts. */
    val available: Flow<Boolean> = companion.map { it?.features?.contains(FEATURE) == true }

    val isAvailable: Boolean get() = companion.value?.features?.contains(FEATURE) == true

    /** The signed-in user, to tell the owner from the invited. */
    val userId: String? get() = server.userId

    /** The signed-in account ("serverUrl|userId"). */
    val accountKey: String? get() = server.currentAccountKey

    suspend fun people(): List<UserRef> = server.sharingUsers().filter { it.id != server.userId }

    suspend fun findPerson(name: String): UserRef? = server.sharingUsers(name).firstOrNull()

    /** Scripts shared with or by this user, on the server. */
    suspend fun list(): List<SharedScriptInfo> = server.sharedScripts()

    suspend fun info(localId: String): SharedScriptInfo? {
        val link = store.link(localId) ?: return null
        return list().firstOrNull { it.id == link.sharedId }
    }

    /**
     * Shares a script with [members]: one already synced just gets them invited; one only on
     * this device is put on the server first (as this user's, with its story data).
     */
    suspend fun share(localId: String, members: List<String>): SharedScriptInfo {
        store.link(localId)?.let { link ->
            return server.setScriptMembers(link.sharedId, members).also { info -> remember(localId, info) }
        }
        val records = store.readStory(localId).values.filter { !it.deleted }.map { dev.nitrate.core.writing.story.RecordChange(it.id, it.type, it.fields) }
        val doc = server.createSharedScript(store.read(localId), members, records)
        store.setDeviceOnly(localId, false)
        store.saveLink(localId, SharedLink(doc.info.id, account(), SharedScriptSession.fresh(doc), doc.info.members.map { it.name }))
        return doc.info
    }

    /** Keeps a link's names (who else writes in it, whose it is) as the server last said. */
    private suspend fun remember(localId: String, info: SharedScriptInfo) {
        val link = store.link(localId) ?: return
        val members = (listOf(info.owner) + info.members).filter { it.id != server.userId }.map { it.name }
        val ownerName = info.owner.name.takeIf { info.owner.id != server.userId }
        val shownMembers = if (ownerName != null) members - ownerName else members
        if (link.members != shownMembers || link.ownerName != ownerName) store.saveLink(localId, link.copy(members = shownMembers, ownerName = ownerName))
    }

    // ---- Syncing this profile's scripts with its server ----

    /** One account signed in here, and whether this device syncs its scripts with its server. */
    data class SyncOption(val account: dev.nitrate.core.model.SavedAccount, val primary: Boolean, val on: Boolean)

    private val syncLock = kotlinx.coroutines.sync.Mutex()

    /** Every account here with its sync choice: on by default only on the Primary server. */
    suspend fun syncOptions(): List<SyncOption> = server.servers().flatMap { s ->
        s.accounts.map { a -> SyncOption(a, s.primary, store.syncChoice(a.key) ?: s.primary) }
    }

    fun setSync(accountKey: String, on: Boolean) = store.setSyncChoice(accountKey, on)

    /** Whether the signed-in account's scripts sync (its choice, else whether its server is the Primary one). */
    suspend fun syncOn(): Boolean {
        val key = server.currentAccountKey ?: return false
        return store.syncChoice(key) ?: server.servers().firstOrNull { s -> s.accounts.any { it.key == key } }?.primary ?: false
    }

    /**
     * Brings this profile's scripts and its server together (with the Companion plugin, when
     * this account syncs): scripts only here are put on the server, privately; this user's
     * scripts made on other devices are brought here; names of who else writes in each are
     * refreshed; and a script the server no longer has for this user stays here as their own.
     * Changes themselves sync live while a script is open (see [session]).
     */
    suspend fun autoSync(): Unit = syncLock.withLock {
        if (!isAvailable || server.currentAccountKey == null) return@withLock
        val remote = runCatching { list() }.getOrNull() ?: return@withLock
        val byId = remote.associateBy { it.id }
        for ((localId, link) in store.links()) {
            val info = byId[link.sharedId]
            if (info == null) lost(localId) else remember(localId, info)
        }
        if (!syncOn()) return@withLock
        for (doc in store.docs.value) {
            if (doc.sharedId != null || store.isDeviceOnly(doc.id)) continue
            runCatching { share(doc.id, emptyList()) }
        }
        val linked = store.links().values.map { it.sharedId }.toSet()
        remote.filter { it.owner.id == server.userId && it.id !in linked }.forEach { runCatching { open(it.id) } }
    }

    /**
     * Moves a script to another of this device's accounts ([toAccount]), or keeps it on this
     * device only (null). Its server copy here is removed (the owner's only; sharing stops); the
     * other account's server gets it the next time that account is in use and syncs.
     */
    suspend fun move(localId: String, toAccount: String?) {
        val link = store.link(localId)
        if (link != null) {
            val info = runCatching { info(localId) }.getOrNull()
            if (info != null && !info.isOwner(server.userId)) error("Only the script's owner can move it.")
            server.deleteSharedScript(link.sharedId)
            store.unlink(localId)
        }
        if (toAccount == null) store.setDeviceOnly(localId, true)
        else {
            store.setDeviceOnly(localId, false)
            store.setOwner(localId, toAccount)
        }
    }

    /** The script here for a shared one, made (from the server's text) the first time it's opened. */
    suspend fun open(sharedId: String): String {
        store.links().entries.firstOrNull { it.value.sharedId == sharedId }?.let { return it.key }
        val doc = server.sharedScript(sharedId)
        val localId = store.create(doc.text)
        store.saveLink(localId, SharedLink(sharedId, account(), SharedScriptSession.fresh(doc)))
        remember(localId, doc.info)
        return localId
    }

    suspend fun setMembers(localId: String, members: List<String>): SharedScriptInfo? =
        store.link(localId)?.let { server.setScriptMembers(it.sharedId, members) }

    /** Stops writing in it with the others (members); the script stays here as this user's own. */
    suspend fun leave(localId: String) {
        val link = store.link(localId) ?: return
        server.leaveSharedScript(link.sharedId)
        store.unlink(localId)
    }

    /** Stops sharing it with others (owner): everyone keeps their own copy; it stays synced as this user's. */
    suspend fun stopSharing(localId: String) {
        val link = store.link(localId) ?: return
        server.setScriptMembers(link.sharedId, emptyList()).also { remember(localId, it) }
    }

    /** Called when the server says it's no longer shared with this user: it stays as their own. */
    suspend fun lost(localId: String) = store.unlink(localId)

    /**
     * A live session for a linked script, or null for an ordinary one (or without the plugin).
     * [scope] must run on the UI thread; close the session when the editor goes.
     */
    fun session(localId: String, scope: CoroutineScope): SharedScriptSession? {
        if (!isAvailable) return null
        val link = store.link(localId) ?: return null
        return SharedScriptSession(
            server = server,
            scope = scope,
            scriptId = link.sharedId,
            start = link.state,
            save = { state -> saveSoon(localId, link.copy(state = state)) },
            onConflict = { text -> io.launch { store.create(text) } },
            newEditId = { UUID.randomUUID().toString() },
            selfId = server.userId.orEmpty(),
            selfName = selfName().orEmpty(),
            now = { System.currentTimeMillis() },
        )
    }

    /** A script's story: the live session's when shared, else the one kept on the device. */
    fun storyFor(localId: String, session: SharedScriptSession?, scope: CoroutineScope): ScriptStory =
        session?.let(::SharedStory) ?: LocalStory(store, localId, scope, server.userId, selfName)

    /** Who can be given scenes in a script: this user, and the others it's shared with. */
    suspend fun writers(localId: String, selfName: String?): List<UserRef> {
        val me = listOfNotNull(server.userId?.let { UserRef(it, selfName ?: "Me") })
        val info = if (store.link(localId) != null) runCatching { info(localId) }.getOrNull() else null
        return me + (info?.let { i -> (listOf(i.owner) + i.members).filter { it.id != server.userId } }.orEmpty())
    }

    /** A script's history for the story screens: the server's when synced, else the one kept on this device. */
    fun history(localId: String): StoryHistory {
        if (store.link(localId) == null) return localHistory(localId)
        return object : StoryHistory {
            override suspend fun log() = this@SharedWriting.log(localId)
            override suspend fun textAt(version: Long) = at(localId, version)?.text.orEmpty()
            override suspend fun recordAt(version: Long, id: String) = at(localId, version)?.records?.firstOrNull { it.id == id }
        }
    }

    // ---- History (shared scripts) ----

    /** Every change made to a shared script, oldest first. */
    suspend fun log(localId: String): List<dev.nitrate.core.writing.ScriptEdit> {
        val link = store.link(localId) ?: return emptyList()
        val all = mutableListOf<dev.nitrate.core.writing.ScriptEdit>()
        var from = 1L
        while (true) {
            val page = server.scriptLog(link.sharedId, from)
            if (page.isEmpty()) break
            all += page
            from = page.last().version + 1
            if (page.size < 2_000) break
        }
        return all
    }

    /** A shared script as it was at [version]. */
    suspend fun at(localId: String, version: Long): dev.nitrate.core.writing.SharedScriptDoc? =
        store.link(localId)?.let { server.scriptAt(it.sharedId, version) }

    /** The history this device kept of a script that isn't synced. */
    private fun localHistory(localId: String) = object : StoryHistory {
        override suspend fun log() = store.localLog(localId).map { it.toScriptEdit() }
        override suspend fun textAt(version: Long) = replay(version)?.first.orEmpty()
        override suspend fun recordAt(version: Long, id: String) = replay(version)?.second?.records?.get(id)
        private suspend fun replay(version: Long): Pair<String, dev.nitrate.core.writing.story.Story>? {
            val start = store.historyStart(localId) ?: return null
            return dev.nitrate.core.writing.story.History.replay(start, log(), version)
        }
    }

    /** Who wrote each part of a script: from the server's history when synced, else this device's. */
    suspend fun authorship(localId: String): Pair<String, dev.nitrate.core.writing.story.Attribution>? {
        val link = store.link(localId) ?: run {
            val start = store.historyStart(localId) ?: return null
            return dev.nitrate.core.writing.story.History.attribution(start.text, start.authorId, store.localLog(localId).map { it.toScriptEdit() })
        }
        val owner = list().firstOrNull { it.id == link.sharedId }?.owner?.id ?: return null
        val start = server.scriptAt(link.sharedId, 0).text
        return dev.nitrate.core.writing.story.History.attribution(start, owner, log(localId))
    }

    /** Keeps the latest state, written a moment after typing stops (each write replaces the one before). */
    private fun saveSoon(localId: String, link: SharedLink) {
        synchronized(pendingSaves) {
            pendingSaves[localId]?.cancel()
            pendingSaves[localId] = io.launch {
                delay(SAVE_DELAY_MS)
                store.saveLink(localId, link)
            }
        }
    }

    private fun account(): String = server.currentAccountKey ?: error("Not signed in")

    companion object {
        const val FEATURE = "scripts"
        const val SAVE_DELAY_MS = 400L
    }
}
