package dev.nitrate.core.backup

import dev.nitrate.core.MediaServer

/**
 * Device backups in the signed-in user's Jellyfin data (display preferences): one entry per
 * device, each written only by that device, plus a list of them. Nothing here applies a backup.
 */
class BackupCloud(private val server: MediaServer) {
    private val json = DeviceBackups.json

    /** The user's backed-up devices, newest first. */
    suspend fun list(): List<BackupSummary> =
        server.loadCustomPrefs(DeviceBackups.INDEX_PREFS).values
            .mapNotNull { runCatching { json.decodeFromString<BackupSummary>(it) }.getOrNull() }
            .sortedByDescending { it.savedAtMs }

    suspend fun load(deviceId: String): DeviceBackup? =
        server.loadCustomPrefs(DeviceBackups.prefsId(deviceId))[DeviceBackups.BACKUP_KEY]
            ?.let { runCatching { json.decodeFromString<DeviceBackup>(it) }.getOrNull() }

    /** Saves [backup] as its device's entry and lists it (re-reading the list so other devices' entries stay). */
    suspend fun save(backup: DeviceBackup) {
        server.saveCustomPrefs(mapOf(DeviceBackups.BACKUP_KEY to json.encodeToString(DeviceBackup.serializer(), backup)), DeviceBackups.prefsId(backup.deviceId))
        val index = server.loadCustomPrefs(DeviceBackups.INDEX_PREFS)
        server.saveCustomPrefs(index + (backup.deviceId to json.encodeToString(BackupSummary.serializer(), backup.summary)), DeviceBackups.INDEX_PREFS)
    }

    /**
     * Before a reinstalled device starts backing up again, keeps its old backup as a separate
     * "(earlier)" entry, so the new defaults don't replace settings the user may still want.
     */
    suspend fun keepEarlier(deviceId: String) {
        val old = load(deviceId) ?: return
        val earlierId = deviceId + DeviceBackups.EARLIER_SUFFIX
        save(old.copy(deviceId = earlierId, deviceName = old.deviceName + " (earlier)"))
    }
}
