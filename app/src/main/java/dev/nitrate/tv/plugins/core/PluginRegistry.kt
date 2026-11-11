package dev.nitrate.tv.plugins.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Every plugin in the app, and which are switched on. */
class PluginRegistry(val all: List<NitratePlugin>, val context: PluginContext) {
    init {
        require(all.map { it.id }.toSet().size == all.size) { "Plugin ids must be unique" }
    }

    val enabledIds: Flow<Set<String>> = context.store.enabledOverrides.map { overrides ->
        all.filter { overrides[it.id] ?: it.enabledByDefault }.map { it.id }.toSet()
    }

    val enabled: Flow<List<NitratePlugin>> = enabledIds.map { ids -> all.filter { it.id in ids } }

    suspend fun enabledNow(): List<NitratePlugin> = enabled.first()

    suspend fun setEnabled(id: String, on: Boolean) = context.store.setEnabled(id, on)
}
