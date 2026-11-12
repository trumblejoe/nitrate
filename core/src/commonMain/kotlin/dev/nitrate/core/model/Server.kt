package dev.nitrate.core.model

enum class SessionState {
    /** No saved sign-in. */
    NONE,

    /** Signed in and the server answered. */
    VALID,

    /** The server rejected the saved sign-in. */
    EXPIRED,

    /** Saved sign-in, but the server can't be reached right now (offline, server down). */
    UNREACHABLE,
}

/** A server that answered on the local network. */
data class DiscoveredServer(val id: String, val name: String, val address: String)

/** A reachable server address resolved from what the user typed. */
data class ServerAddress(val address: String, val name: String)

/** A user the server lists on its login screen. */
data class ServerUser(val id: String, val name: String, val hasPassword: Boolean, val imageUrl: String?)

/** A pending Quick Connect request: show [code] to the user; poll with [secret]. */
data class QuickConnectRequest(val code: String, val secret: String)

/** The server rejected a username/password. */
class InvalidCredentialsException : Exception("Wrong username or password")

/** A saved sign-in on this device, for switching between servers and users. */
data class SavedAccount(
    val key: String,
    val serverUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val imageUrl: String?,
)

/** Where a saved server can be reached: [local] at home, [remote] away, and which is in use now. */
data class ServerAddresses(
    val signedInWith: String,
    val local: String?,
    val remote: String?,
    val active: String?,
)

/** What testing an address found. */
sealed interface AddressCheck {
    /** A Jellyfin server answered at [url] in [millis]; [sameServer] is false if it's a different server than this account's. */
    data class Reached(val url: String, val serverName: String, val millis: Long, val sameServer: Boolean) : AddressCheck
    data class Failed(val message: String) : AddressCheck
}

/** One line for showing a test's result. */
fun AddressCheck.summary(): String = when (this) {
    is AddressCheck.Reached ->
        if (sameServer) "Connected to $serverName in $millis ms ($url)"
        else "That's a different Jellyfin server ($serverName), not this account's"
    is AddressCheck.Failed -> message
}

/** Whether a test means the address is good to use. */
val AddressCheck.ok: Boolean get() = this is AddressCheck.Reached && sameServer

/** Which saved address is in use, in words. */
fun ServerAddresses.inUse(): String = when (active?.trimEnd('/')) {
    null -> "Not connected"
    local?.trimEnd('/') -> "Using the local address"
    remote?.trimEnd('/') -> "Using the remote address"
    else -> "Using ${active}"
}

/** A home-network address: private IPv4 ranges, .local names, or a bare hostname. */
fun looksLocal(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
    return host.startsWith("192.168.") || host.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) ||
        host.endsWith(".local") || host == "localhost" || !host.contains('.')
}
