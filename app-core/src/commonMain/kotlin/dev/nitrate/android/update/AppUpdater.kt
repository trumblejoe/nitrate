package dev.nitrate.android.update

import dev.nitrate.core.update.AppRelease
import dev.nitrate.core.update.UpdateChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where an update stands. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState

    /** The system installer is showing (or about to show) its confirmation. */
    data class Installing(val release: AppRelease) : UpdateState

    /** The system needs permission for Nitrate to install apps; [settings] opens that switch (an Android Intent). */
    data class NeedsPermission(val release: AppRelease, val settings: Any) : UpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState
}

/** Keeps the app up to date from the project's releases, where the platform allows it. */
interface AppUpdater {
    val state: StateFlow<UpdateState>
    val channel: UpdateChannel
    val versionName: String
    val autoCheck: StateFlow<Boolean>
    fun setAutoCheck(on: Boolean)

    /** Checks if it's been a while, when checking automatically. */
    fun checkIfDue()
    fun check(quiet: Boolean = false)
    fun skip(release: AppRelease)
    fun offer(release: AppRelease)
    fun dismiss()
    fun install(release: AppRelease)
}

/** For builds that don't update themselves (iOS sideloads are updated by reinstalling). */
class NoUpdates(override val versionName: String) : AppUpdater {
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)
    override val channel = UpdateChannel.NONE
    override val autoCheck: StateFlow<Boolean> = MutableStateFlow(false)
    override fun setAutoCheck(on: Boolean) = Unit
    override fun checkIfDue() = Unit
    override fun check(quiet: Boolean) = Unit
    override fun skip(release: AppRelease) = Unit
    override fun offer(release: AppRelease) = Unit
    override fun dismiss() = Unit
    override fun install(release: AppRelease) = Unit
}
