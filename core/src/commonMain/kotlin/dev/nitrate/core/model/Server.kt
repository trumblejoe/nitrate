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
