package dev.nitrate.android.playback

import dev.nitrate.core.playback.BufferingWatch
import dev.nitrate.core.playback.NetworkPlace
import dev.nitrate.core.playback.PlaybackSettings
import dev.nitrate.core.playback.QualityDecision
import dev.nitrate.core.playback.TravelQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Travel quality for the desktop and iPhone players (Android's `PlaybackSession` keeps its own):
 * the limit decided once per title ([maxBitrateFor], from `AppContainer.qualityDecision`), stalls
 * watched ([look], [stalled]) and "Lower the quality" offered ([offer]) or done at once with the
 * user's "Lower the quality automatically"; lowered for that title only.
 */
class QualityKeeper(
    private val decide: suspend () -> QualityDecision?,
    private val autoLower: suspend () -> Boolean,
    private val now: () -> Long = { dev.nitrate.android.nowMs() },
) {
    private val _quality = MutableStateFlow<QualityDecision?>(null)

    /** The limit this title plays with, for Info and reports. */
    val quality: StateFlow<QualityDecision?> = _quality
    private val _offer = MutableStateFlow<Int?>(null)

    /** "Lower the quality" to this (bps), while offered. */
    val offer: StateFlow<Int?> = _offer
    private var offeredAt = 0L
    private val watch = BufferingWatch()
    private var item: String? = null

    /**
     * The limit to ask the server for when [itemId]'s stream starts (decided on its first start; a
     * track change or a lowering keeps it). Null: the settings' own home limit.
     */
    suspend fun maxBitrateFor(itemId: String): Int? {
        if (item != itemId) {
            item = itemId
            _offer.value = null
            _quality.value = runCatching { decide() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        }
        watch.restart()
        return _quality.value?.takeIf { it.place != NetworkPlace.HOME || it.lowered }?.limitBps
    }

    /** A download plays: nothing to limit. */
    fun local() {
        item = null
        _quality.value = null
        _offer.value = null
    }

    fun seeked() = watch.seeked(now())

    /** One look at the player (every few hundred ms); the limit to lower to at once when that's the user's choice, else null. */
    suspend fun look(buffering: Boolean, playing: Boolean, streamBps: Int?): Int? {
        val at = now()
        if (_offer.value != null && at - offeredAt > OFFER_SHOWN_MS) _offer.value = null
        return if (watch.update(buffering, playing, at)) kept(streamBps, at) else null
    }

    /** A stall as it happens (Apple's player); as [look]. */
    suspend fun stalled(streamBps: Int?): Int? {
        val at = now()
        return if (watch.stalled(at)) kept(streamBps, at) else null
    }

    private suspend fun kept(streamBps: Int?, at: Long): Int? {
        val next = TravelQuality.stepDown(_quality.value?.limitBps ?: PlaybackSettings.NO_LIMIT, streamBps) ?: return null
        if (runCatching { autoLower() }.getOrDefault(false)) return next.also(::lowered)
        _offer.value = next
        offeredAt = at
        return null
    }

    /** Lowered to [bps] for this title (the caller restarts the stream from where it is). */
    fun lowered(bps: Int) {
        _offer.value = null
        val before = _quality.value ?: QualityDecision(NetworkPlace.HOME, PlaybackSettings.NO_LIMIT)
        _quality.value = before.copy(limitBps = bps, lowered = true)
    }

    fun dismiss() {
        _offer.value = null
    }

    companion object {
        const val OFFER_SHOWN_MS = 30_000L
    }
}
