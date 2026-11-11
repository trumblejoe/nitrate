package dev.nitrate.core.jellyfin

import dev.nitrate.core.model.DiscoveredServer
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readString

/**
 * Jellyfin's LAN discovery: broadcast "Who is JellyfinServer?" to UDP 7359; each server answers
 * with a JSON description. Collects answers for [timeoutMs], then completes.
 */
internal fun discoverJellyfinServers(timeoutMs: Long = 3_000): Flow<DiscoveredServer> = flow {
    val selector = SelectorManager(Dispatchers.Default)
    val socket = aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", 0)) { broadcast = true }
    try {
        socket.send(Datagram(buildPacket { writeText("Who is JellyfinServer?") }, InetSocketAddress("255.255.255.255", 7359)))
        val seen = mutableSetOf<String>()
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val reply = socket.receive()
                val dto = runCatching { JellyfinJson.decodeFromString<ServerDiscoveryDto>(reply.packet.readString()) }.getOrNull() ?: continue
                val address = dto.address?.trimEnd('/') ?: continue
                if (seen.add(dto.id ?: address)) emit(DiscoveredServer(dto.id.orEmpty(), dto.name ?: "Jellyfin", address))
            }
        }
    } finally {
        socket.close()
        selector.close()
    }
}

/**
 * Addresses to try for what the user typed: as given if it has a scheme, otherwise https and
 * http on the given port, or Jellyfin's defaults (8920/8096) and the standard ports.
 */
internal fun addressCandidates(input: String): List<String> {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return listOf(trimmed)
    val hostPart = trimmed.substringBefore('/')
    val path = trimmed.removePrefix(hostPart)
    val hasPort = Regex(":\\d+$").containsMatchIn(hostPart) && !hostPart.startsWith("[") || hostPart.contains("]:")
    return if (hasPort) {
        listOf("https://$trimmed", "http://$trimmed")
    } else {
        listOf("https://$hostPart:8920$path", "http://$hostPart:8096$path", "https://$hostPart$path", "http://$hostPart$path")
    }
}
