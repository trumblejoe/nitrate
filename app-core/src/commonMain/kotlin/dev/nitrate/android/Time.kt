package dev.nitrate.android

/** Now, in milliseconds since 1970 (System.currentTimeMillis on every platform). */
@OptIn(kotlin.time.ExperimentalTime::class)
fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
