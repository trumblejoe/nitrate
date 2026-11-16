package dev.nitrate.core.remote

import dev.nitrate.core.admin.ServerSession
import dev.nitrate.core.model.ItemKind
import dev.nitrate.core.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteDevicesTest {
    private fun s(id: String, device: String, client: String, playing: Boolean = false, controllable: Boolean = true, name: String = id, at: String = "2026-11-16T10:00:00Z") =
        ServerSession(id, "joe", client, name, device, MediaItem("m", "Film", ItemKind.MOVIE).takeIf { playing }, null, false, null, null, at, controllable, controllable)

    @Test fun `other devices that take remote playback, playing ones and Nitrate first`() {
        val list = RemoteDevices.controllable(
            listOf(
                s("me", "phone", "Nitrate"),
                s("web", "browser", "Jellyfin Web", name = "Firefox"),
                s("tv-old", "tv", "Nitrate TV", name = "Living room TV", at = "2026-11-15T09:00:00Z"),
                s("tv", "tv", "Nitrate TV", name = "Living room TV"),
                s("bed", "tv2", "Jellyfin Android TV", playing = true, name = "Bedroom TV"),
                s("cli", "x", "curl", controllable = false),
            ),
            thisDevice = "phone",
        )
        assertEquals(listOf("bed", "tv", "web"), list.map { it.id })
        assertEquals(listOf("bed"), RemoteDevices.playing(list, "phone").map { it.id })
    }
}
