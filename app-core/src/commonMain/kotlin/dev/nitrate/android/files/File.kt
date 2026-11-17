package dev.nitrate.android.files

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * The few java.io.File calls the app's own storage uses, over okio so they work on iOS too.
 * Errors behave like java.io.File's: reads throw, the rest report failure quietly.
 */
class File(val path: Path) {
    constructor(path: String) : this(path.toPath())
    constructor(parent: File, name: String) : this(parent.path / name)

    private val fs get() = FileSystem.SYSTEM

    val name: String get() = path.name
    val extension: String get() = name.substringAfterLast('.', "")
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
    val absolutePath: String get() = path.toString()

    fun exists(): Boolean = fs.exists(path)

    fun mkdirs(): Boolean = runCatching { fs.createDirectories(path) }.isSuccess

    fun readText(): String = fs.read(path) { readUtf8() }

    fun readLines(): List<String> = readText().lines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }

    fun <T> useLines(block: (Sequence<String>) -> T): T = block(readLines().asSequence())

    fun writeText(text: String) {
        fs.write(path) { writeUtf8(text) }
    }

    fun appendText(text: String) {
        fs.appendingSink(path).buffer().use { it.writeUtf8(text) }
    }

    fun delete(): Boolean = runCatching { fs.delete(path, mustExist = true) }.isSuccess

    fun renameTo(target: File): Boolean = runCatching { fs.atomicMove(path, target.path) }.isSuccess

    fun lastModified(): Long = runCatching { fs.metadata(path).lastModifiedAtMillis }.getOrNull() ?: 0L

    fun setLastModified(ms: Long): Boolean = setFileModified(path.toString(), ms)

    fun listFiles(filter: (File) -> Boolean = { true }): List<File>? =
        runCatching { fs.list(path) }.getOrNull()?.map(::File)?.filter(filter)

    fun usableSpace(): Long = freeSpace(path.toString())

    override fun toString() = path.toString()
}

/** Sets a file's modification time (okio can only read it). */
expect fun setFileModified(path: String, ms: Long): Boolean

/** Bytes free on the volume holding [path]. */
expect fun freeSpace(path: String): Long
