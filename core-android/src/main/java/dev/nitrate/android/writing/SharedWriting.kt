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
) {
    /** Keeps what sessions save, outliving any one screen. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingSaves = mutableMapOf<String, Job>()

    /** True when the server has the Companion plugin with shared scripts. */
    val available: Flow<Boolean> = companion.map { it?.features?.contains(FEATURE) == true }

    val isAvailable: Boolean get() = companion.value?.features?.contains(FEATURE) == true

    /** The signed-in user, to tell the owner from the invited. */
    val userId: String? get() = server.userId

    suspend fun people(): List<UserRef> = server.sharingUsers().filter { it.id != server.userId }

    suspend fun findPerson(name: String): UserRef? = server.sharingUsers(name).firstOrNull()

    /** Scripts shared with or by this user, on the server. */
    suspend fun list(): List<SharedScriptInfo> = server.sharedScripts()

    suspend fun info(localId: String): SharedScriptInfo? {
        val link = store.link(localId) ?: return null
        return list().firstOrNull { it.id == link.sharedId }
    }

    /** Shares a script from here with [members]; it stays linked to the shared one. */
    suspend fun share(localId: String, members: List<String>): SharedScriptInfo {
        val doc = server.createSharedScript(store.read(localId), members)
        store.saveLink(localId, SharedLink(doc.info.id, account(), SharedScriptSession.fresh(doc)))
        return doc.info
    }

    /** The script here for a shared one, made (from the server's text) the first time it's opened. */
    suspend fun open(sharedId: String): String {
        store.links().entries.firstOrNull { it.value.sharedId == sharedId }?.let { return it.key }
        val doc = server.sharedScript(sharedId)
        val localId = store.create(doc.text)
        store.saveLink(localId, SharedLink(sharedId, account(), SharedScriptSession.fresh(doc)))
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

    /** Stops sharing it for everyone (owner); everyone keeps their own copy. */
    suspend fun stopSharing(localId: String) {
        val link = store.link(localId) ?: return
        server.deleteSharedScript(link.sharedId)
        store.unlink(localId)
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
        )
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
