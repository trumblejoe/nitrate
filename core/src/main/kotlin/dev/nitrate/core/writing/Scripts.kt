package dev.nitrate.core.writing

/** A saved screenplay. */
data class WritingDoc(val id: String, val title: String, val modifiedMs: Long)

object Scripts {
    /** The Title: from the title page, else the first line with text. */
    fun titleOf(text: String): String {
        val lines = text.lineSequence().map { it.trim() }
        return lines.firstOrNull { it.startsWith("Title:", ignoreCase = true) }?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
            ?: lines.firstOrNull { it.isNotEmpty() }?.take(60)
            ?: "Untitled"
    }

    fun fileName(text: String, format: ExportFormat): String =
        titleOf(text).replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Untitled" } + "." + format.extension

    /** Fountain text from an imported file: `.fadein` ZIPs and OSF XML are converted, anything else is read as text. */
    fun importText(bytes: ByteArray): String {
        val head = bytes.take(64).toByteArray().decodeToString().trimStart()
        val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return if (isZip || head.startsWith("<?xml") || head.startsWith("<document")) FadeIn.import(bytes) else bytes.decodeToString()
    }
}

enum class ExportFormat(val extension: String, val mime: String) {
    FOUNTAIN("fountain", "text/plain"),
    FADE_IN("fadein", "application/octet-stream"),
    ;

    fun bytes(text: String): ByteArray = when (this) {
        FOUNTAIN -> text.toByteArray()
        FADE_IN -> FadeIn.export(text)
    }
}
