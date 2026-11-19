package dev.nitrate.android.playback

import dev.nitrate.core.MediaServer
import dev.nitrate.core.playback.AudioDelivery
import dev.nitrate.core.playback.DeviceCapabilities
import dev.nitrate.core.playback.TestPlay
import dev.nitrate.core.playback.TestPlayResult
import kotlinx.coroutines.delay

/**
 * A test play for the playback overview, on either app's player: loads [itemId] a few minutes in
 * as an unreported clip, plays [TestPlay.SEEK_AT_MS], jumps ahead to check seeking (and that
 * surround still goes out untouched after), plays on, then gives the trail of what happened.
 * [onLeft] gets the time still to go for the player's countdown.
 */
suspend fun PlaybackSession.runTestPlay(server: MediaServer, itemId: String, caps: DeviceCapabilities, onLeft: (Long) -> Unit): TestPlayResult {
    val runtime = runCatching { server.getItem(itemId).runtimeMs }.getOrNull()
    val from = TestPlay.startAt(runtime)
    val seeks = TestPlay.seeks(runtime)
    val to = from + if (seeks) TestPlay.END_MS else TestPlay.PLAY_MS
    loadClip(itemId, from, to)
    val began = System.currentTimeMillis()
    var failed: String? = null
    var seekStarted: Long? = null
    var seekResumeMs: Long? = null
    var seekFailed = false
    var bitstreamBefore: Boolean? = null
    var passthroughAfter: Boolean? = null
    fun playedSoFar(at: Long) = if (seekStarted == null) at - from else TestPlay.SEEK_AT_MS + (at - (from + TestPlay.SEEK_TO_MS)).coerceAtLeast(0)
    while (true) {
        delay(250)
        val at = progress.value.positionMs
        if (plan.value != null) onLeft((TestPlay.PLAY_MS - playedSoFar(at)).coerceIn(0, TestPlay.PLAY_MS))
        failed = error.value
        if (failed != null) break
        val started = plan.value != null && hasShownPicture
        if (seeks && started && seekStarted == null && at >= from + TestPlay.SEEK_AT_MS) {
            bitstreamBefore = audioPath.value.delivery == AudioDelivery.BITSTREAM
            seekStarted = System.currentTimeMillis()
            player.seekTo(from + TestPlay.SEEK_TO_MS)
        }
        val sinceSeek = seekStarted?.let { System.currentTimeMillis() - it }
        if (seekStarted != null && seekResumeMs == null && !seekFailed) {
            if (player.isPlaying && at > from + TestPlay.SEEK_TO_MS + 300) {
                seekResumeMs = sinceSeek
            } else if ((sinceSeek ?: 0) > 20_000) seekFailed = true
        }
        // Surround after the jump: checked once it has played a couple of seconds again.
        if (seekResumeMs != null && passthroughAfter == null && bitstreamBefore == true && at > from + TestPlay.SEEK_TO_MS + 2_500) {
            passthroughAfter = audioPath.value.delivery == AudioDelivery.BITSTREAM
        }
        if (seekFailed || (plan.value != null && at >= to - 600)) break
        if (System.currentTimeMillis() - began > TestPlay.END_MS + 120_000) { failed = "It didn't get going within two minutes"; break }
    }
    val trail = runCatching {
        trail(caps) { s -> s.copy(seekResumeMs = seekResumeMs, seekFailed = seekFailed, passthroughAfterSeek = passthroughAfter) }
    }.getOrNull()
    return TestPlayResult(
        itemId = itemId,
        name = trail?.title ?: item.value?.name ?: itemId,
        atMs = System.currentTimeMillis(),
        trail = trail,
        droppedFrames = droppedFrameCount,
        playedMs = playedSoFar(progress.value.positionMs).coerceAtLeast(0),
        error = failed,
    )
}
