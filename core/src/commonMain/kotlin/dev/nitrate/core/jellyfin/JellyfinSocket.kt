package dev.nitrate.core.jellyfin

import dev.nitrate.core.events.ServerEvent
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Jellyfin's live connection (/socket): keeps itself alive as the server asks, reconnects with
 * back-off while [active], and turns messages into [ServerEvent]s.
 */
@OptIn(ExperimentalTime::class)
internal fun JellyfinClient.socketEvents(active: () -> Boolean): Flow<ServerEvent> = flow {
    var backoff = 2_000L
    while (active()) {
        val base = serverUrl ?: break
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://").trimEnd('/') + "/socket"
        try {
            http.webSocket(wsUrl, request = { auth() }) {
                emit(ServerEvent.Connected)
                backoff = 2_000L
                var keepAlive: kotlinx.coroutines.Job? = null
                try {
                    for (frame in incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        val msg = runCatching { JellyfinJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                        val type = msg.s("MessageType")
                        val data = msg["Data"]
                        when (type) {
                            "ForceKeepAlive" -> {
                                val seconds = (data as? JsonPrimitive)?.intOrNull ?: 60
                                keepAlive?.cancel()
                                keepAlive = launch {
                                    while (isActive) {
                                        send(Frame.Text("""{"MessageType":"KeepAlive"}"""))
                                        delay(seconds * 500L)
                                    }
                                }
                            }
                            else -> parseEvent(type, data)?.let { emit(it) }
                        }
                    }
                } finally {
                    keepAlive?.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fall through to reconnect.
        }
        emit(ServerEvent.Disconnected)
        if (!active()) break
        delay(backoff)
        backoff = (backoff * 2).coerceAtMost(60_000L)
    }
}

@OptIn(ExperimentalTime::class)
internal fun parseEvent(type: String?, data: kotlinx.serialization.json.JsonElement?): ServerEvent? {
    val o = data as? JsonObject
    return when (type) {
        "Play" -> o?.let {
            ServerEvent.Play(
                itemIds = it.list("ItemIds").map(::normalizeId),
                startMs = it.l("StartPositionTicks")?.ticksToMs(),
                command = it.s("PlayCommand") ?: "PlayNow",
                audioIndex = it.i("AudioStreamIndex"),
                subtitleIndex = it.i("SubtitleStreamIndex"),
            )
        }
        "Playstate" -> o?.let { ServerEvent.Playstate(it.s("Command") ?: return null, it.l("SeekPositionTicks")?.ticksToMs()) }
        "GeneralCommand" -> o?.let { g ->
            ServerEvent.General(
                g.s("Name") ?: return null,
                (g["Arguments"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
            )
        }
        "LibraryChanged" -> o?.let { ServerEvent.LibraryChanged(it.list("ItemsAdded").map(::normalizeId), it.list("ItemsUpdated").map(::normalizeId), it.list("ItemsRemoved").map(::normalizeId)) }
        "UserDataChanged" -> o?.let { u ->
            ServerEvent.UserDataChanged((u["UserDataList"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.s("ItemId")?.let(::normalizeId) })
        }
        "SyncPlayCommand" -> o?.let {
            ServerEvent.SyncPlayCommand(
                groupId = it.s("GroupId").orEmpty(),
                command = it.s("Command") ?: return null,
                whenMs = it.s("When")?.let { w -> runCatching { Instant.parse(w).toEpochMilliseconds() }.getOrNull() },
                positionMs = it.l("PositionTicks")?.ticksToMs(),
                playlistItemId = it.s("PlaylistItemId"),
            )
        }
        "SyncPlayGroupUpdate" -> o?.let { ServerEvent.SyncPlayGroupUpdate(it.s("GroupId").orEmpty(), it.s("Type").orEmpty(), (it["Data"] ?: JsonPrimitive("")).toString()) }
        else -> null
    }
}

private fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.l(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.i(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
private fun JsonObject.list(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
private fun JsonObject?.orEmpty(): Map<String, kotlinx.serialization.json.JsonElement> = this ?: emptyMap()
