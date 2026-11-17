package dev.nitrate.ios

import dev.nitrate.mobile.ui.Mpv
import dev.nitrate.mobile.ui.MpvFactory
import dev.nitrate.mobile.ui.MpvPlayer
import platform.UIKit.UIView

/**
 * What the Swift app implements over MPVKit (see iosApp/Nitrate/MpvPlayerView.swift): one libmpv
 * player. Mirrors the player screen's [MpvPlayer], kept here so the framework's header names it
 * plainly for Swift.
 */
interface NitrateMpv {
    fun view(): UIView
    fun load(url: String, authorization: String, startSeconds: Double, audioId: Int, subtitleId: Int, subtitleUrls: List<String>)
    fun setAudio(id: Int)
    fun setSubtitle(id: Int)
    fun play()
    fun pause()
    fun seek(seconds: Double)
    fun position(): Double
    fun duration(): Double
    fun isPaused(): Boolean
    fun isEnded(): Boolean
    fun release()
}

/** Called once by the Swift app at start: mpv is built in, and [make] makes a player. */
fun useMpv(make: () -> NitrateMpv) {
    Mpv.factory = object : MpvFactory {
        override fun create(): MpvPlayer {
            val m = make()
            return object : MpvPlayer {
                override fun view() = m.view()
                override fun load(url: String, authorization: String, startSeconds: Double, audioId: Int, subtitleId: Int, subtitleUrls: List<String>) =
                    m.load(url, authorization, startSeconds, audioId, subtitleId, subtitleUrls)
                override fun setAudio(id: Int) = m.setAudio(id)
                override fun setSubtitle(id: Int) = m.setSubtitle(id)
                override fun play() = m.play()
                override fun pause() = m.pause()
                override fun seek(seconds: Double) = m.seek(seconds)
                override fun position() = m.position()
                override fun duration() = m.duration()
                override fun isPaused() = m.isPaused()
                override fun isEnded() = m.isEnded()
                override fun release() = m.release()
            }
        }
    }
}
