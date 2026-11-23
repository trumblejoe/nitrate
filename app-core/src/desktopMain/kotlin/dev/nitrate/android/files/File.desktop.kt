package dev.nitrate.android.files

actual fun setFileModified(path: String, ms: Long): Boolean = java.io.File(path).setLastModified(ms)

actual fun freeSpace(path: String): Long = java.io.File(path).usableSpace
