package dev.nitrate.mobile

import dev.nitrate.core.admin.GeneralCommand
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import dev.nitrate.core.model.StreamKind
import dev.nitrate.core.remote.RemotePrompt
import dev.nitrate.mobile.ui.RemoteTarget
import dev.nitrate.mobile.ui.RemoteView

/** A TV for the remote's tests: an episode part way through, asking "Still watching?", keeping what was asked of it. */
class FakeRemote : RemoteTarget {
    val asked = mutableListOf<String>()
    override val pollMs = 60_000L
    private val episode = MediaItem(
        "s2e4", "Episode title 4", ItemKind.EPISODE, seriesId = "series-1", seriesName = "The Night Reel", seasonId = "season-2",
        seasonNumber = 2, indexNumber = 4, runtimeMs = 2_700_000,
    )
    private var prompt: RemotePrompt? = RemotePrompt(
        "p1", RemotePrompt.Kind.STILL_WATCHING, "d1", "Still watching?", "Up next: Episode title 5 · S2:E5",
        listOf(RemotePrompt.Choice("keep", "Keep watching"), RemotePrompt.Choice("back", "Go back"), RemotePrompt.Choice("stop", "Stop")),
    )

    override suspend fun view() = RemoteView("Living room TV", episode, 1_380_000, paused = true, audioIndex = 1, subtitleIndex = -1, navigable = true, prompt = prompt)
    override suspend fun setPaused(paused: Boolean) { asked += if (paused) "pause" else "play" }
    override suspend fun seek(ms: Long) { asked += "seek $ms" }
    override suspend fun next() { asked += "next" }
    override suspend fun previous() { asked += "previous" }
    override suspend fun stop() { asked += "stop" }
    override suspend fun track(kind: StreamKind, index: Int) { asked += "$kind $index" }
    override suspend fun volume(command: GeneralCommand) { asked += command.wire }
    override suspend fun navigate(command: GeneralCommand) { asked += command.wire }
    override suspend fun play(item: MediaItem, startMs: Long) { asked += "play ${item.id}" }
    override suspend fun answer(prompt: RemotePrompt, choiceId: String) { asked += "answer $choiceId"; this.prompt = null }
}
