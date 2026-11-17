package dev.nitrate.android.writing

import android.content.Context
import dev.nitrate.core.MediaServer

/** Scripts in the app's files folder, where Android has always kept them. */
fun WritingStore(context: Context, repository: MediaServer) = WritingStore(context.filesDir.absolutePath, repository)

/** Lets the transfer page list, download and add scripts. */
fun WritingStore.asTransferDocs(): dev.nitrate.core.writing.TransferDocs = object : dev.nitrate.core.writing.TransferDocs {
    override fun list(): List<dev.nitrate.core.writing.WritingDoc> = docs.value

    // Only ids of existing scripts: the id comes from a URL, so it must never reach other files.
    override suspend fun read(id: String): String? =
        if (docs.value.any { it.id == id }) this@asTransferDocs.read(id) else null

    override suspend fun create(text: String): String = this@asTransferDocs.create(text)
}
