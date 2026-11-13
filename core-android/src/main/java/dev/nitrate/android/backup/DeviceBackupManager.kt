package dev.nitrate.android.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.nitrate.android.storage.PlaybackSettingsStore
import dev.nitrate.android.storage.PluginStore
import dev.nitrate.core.MediaServer
import dev.nitrate.core.backup.BackupCloud
import dev.nitrate.core.backup.BackupSummary
import dev.nitrate.core.backup.DeviceBackup
import dev.nitrate.core.backup.DeviceBackups
import dev.nitrate.core.backup.DeviceSettings
import dev.nitrate.core.backup.Difference
import dev.nitrate.core.plugins.NitratePlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.backupDataStore by preferencesDataStore(name = "device_backup")

/** Offered after signing in on a device that hasn't backed up for this account yet. */
data class SetupOffer(val accountKey: String, val backups: List<BackupSummary>)

/** A backup ready to apply: what this device has, what it would have, and the differences. */
data class RestorePreview(
    val source: BackupSummary,
    val current: DeviceSettings,
    val next: DeviceSettings,
    val differences: List<Difference>,
    /** Hardware results come too (the backup is from the same model). */
    val sameModel: Boolean,
)

/**
 * Backs this device's own settings (playback and plugins) up to the signed-in user's Jellyfin data
 * a few seconds after any change, and restores another device's backup when the user chooses one,
 * after showing what would change. Never applies anything by itself, and never overwrites a
 * backup before the user has answered the first "set this device up like…" question.
 */
class DeviceBackupManager(
    context: Context,
    private val server: MediaServer,
    private val playback: PlaybackSettingsStore,
    private val plugins: PluginStore,
    private val pluginList: () -> List<NitratePlugin>,
    private val deviceName: String,
    private val model: () -> String,
    private val app: String,
    private val version: String,
) {
    private val store = context.applicationContext.backupDataStore
    private val cloud = BackupCloud(server)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pushJob: Job? = null
    private val settledKey = stringSetPreferencesKey("settled")

    private val _offer = MutableStateFlow<SetupOffer?>(null)
    /** The first-time "set this device up like…" question, when there is one. */
    val offer: StateFlow<SetupOffer?> = _offer

    /** When this account's backup was last saved from this device. */
    val lastSaved = store.data.map { it[savedKey(server.currentAccountKey)] }

    val deviceId: String get() = server.deviceId.orEmpty()

    init {
        scope.launch {
            combine(playback.settings, plugins.enabledOverrides, plugins.allRaw) { p, e, r -> DeviceSettings(p, e, r) }
                .distinctUntilChanged()
                .collect { schedulePush() }
        }
    }

    /** After signing in or switching account: back up, or ask once how to set this device up. */
    fun onSignedIn() {
        val key = server.currentAccountKey ?: return
        scope.launch {
            if (isSettled(key)) { push(); return@launch }
            // Offline: ask next time instead of guessing.
            val backups = runCatching { cloud.list() }.getOrNull() ?: return@launch
            if (backups.isEmpty()) {
                settle(key)
                push()
            } else {
                _offer.value = SetupOffer(key, backups)
            }
        }
    }

    /** Every backup of this account, newest first (this device's own included). */
    suspend fun backups(): List<BackupSummary> = cloud.list()

    /** What restoring [summary]'s backup would change here. */
    suspend fun preview(summary: BackupSummary): RestorePreview? {
        val backup = cloud.load(summary.deviceId) ?: return null
        return preview(backup)
    }

    /** What restoring a backup read from a file would change here; null if the file isn't one. */
    suspend fun previewFile(bytes: ByteArray): RestorePreview? {
        val backup = runCatching { DeviceBackups.json.decodeFromString(DeviceBackup.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        return preview(backup)
    }

    private suspend fun preview(backup: DeviceBackup): RestorePreview {
        val current = currentSettings()
        val same = DeviceBackups.sameModel(backup.summary, model())
        val next = DeviceBackups.restored(current, backup.settings, same)
        val list = pluginList()
        val diffs = DeviceBackups.differences(current, next, list.associate { it.id to it.name }, list.associate { it.id to it.enabledByDefault })
        return RestorePreview(backup.summary, current, next, diffs, same)
    }

    /** Applies [preview] to this device, then backs the result up. */
    suspend fun apply(preview: RestorePreview) {
        playback.update { preview.next.playback }
        preview.next.pluginsEnabled.forEach { (id, on) -> if (preview.current.pluginsEnabled[id] != on) plugins.setEnabled(id, on) }
        preview.next.pluginSettings.forEach { (id, raw) -> if (preview.current.pluginSettings[id] != raw) plugins.saveRaw(id, raw) }
        finishOffer()
        push()
    }

    /** "Not now": this device starts backing up, keeping its own earlier backup (from before a reinstall) apart. */
    suspend fun notNow() {
        val offer = _offer.value
        if (offer != null && offer.backups.any { it.deviceId == deviceId }) runCatching { cloud.keepEarlier(deviceId) }
        finishOffer()
        push()
    }

    /** Backs up now, for the Settings button. */
    suspend fun backUpNow(): Result<Unit> {
        val key = server.currentAccountKey ?: return Result.failure(IllegalStateException("Not signed in"))
        settle(key)
        _offer.value = null
        return runCatching { cloud.save(backupOfThisDevice()) }.onSuccess { markSaved(key) }
    }

    /** This device's settings as a file. */
    suspend fun exportFile(): ByteArray = DeviceBackups.json.encodeToString(DeviceBackup.serializer(), backupOfThisDevice()).encodeToByteArray()

    private suspend fun finishOffer() {
        (_offer.value?.accountKey ?: server.currentAccountKey)?.let { settle(it) }
        _offer.value = null
    }

    private fun schedulePush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DELAY_MS)
            push()
        }
    }

    private suspend fun push() {
        val key = server.currentAccountKey ?: return
        if (server.userId == null || !isSettled(key)) return
        runCatching { cloud.save(backupOfThisDevice()) }.onSuccess { markSaved(key) }
    }

    private suspend fun currentSettings() = DeviceSettings(playback.current(), plugins.enabledOverrides.first(), plugins.allRaw.first())

    private suspend fun backupOfThisDevice() = DeviceBackup(
        deviceId = deviceId,
        deviceName = deviceName,
        model = model(),
        app = app,
        appVersion = version,
        savedAtMs = System.currentTimeMillis(),
        settings = currentSettings(),
    )

    private suspend fun isSettled(key: String) = key in store.data.first()[settledKey].orEmpty()
    private suspend fun settle(key: String) { store.edit { it[settledKey] = it[settledKey].orEmpty() + key } }
    private suspend fun markSaved(key: String) { store.edit { it[savedKey(key)] = System.currentTimeMillis() } }
    private fun savedKey(account: String?) = longPreferencesKey("saved_${account.orEmpty()}")

    private companion object {
        const val PUSH_DELAY_MS = 3_000L
    }
}
