package dev.nitrate.core.downloads

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Makes a converted download seekable. The server streams conversions as fragmented MP4 with an
 * empty header (it can't know the length ahead), so a player sees no duration and can't seek:
 * no progress bar. This writes a segment index (`sidx`) in front of the fragments, from each
 * fragment's size and timing, which players (Media3 included) use for the length and for
 * seeking. The fragments' absolute data offsets move by the index's size and are corrected; the
 * trailing `mfra` (absolute offsets, unused by players that have an index) is dropped.
 */
object Mp4Index {
    /** What a file needs: [Status.FRAGMENTED_NO_INDEX] is the only one [addIndex] changes. */
    enum class Status { NOT_MP4, PLAIN, INDEXED, FRAGMENTED_NO_INDEX }

    private class Box(val type: String, val offset: Long, val header: Int, val size: Long)

    fun status(file: Path): Status {
        val boxes = runCatching { topLevel(file) }.getOrNull() ?: return Status.NOT_MP4
        if (boxes.firstOrNull()?.type != "ftyp") return Status.NOT_MP4
        val types = boxes.map { it.type }
        val firstMoof = types.indexOf("moof")
        return when {
            firstMoof < 0 -> Status.PLAIN
            types.take(firstMoof).contains("sidx") -> Status.INDEXED
            else -> Status.FRAGMENTED_NO_INDEX
        }
    }

    /**
     * Rewrites [file] with an index, through a temporary file beside it (the original is only
     * replaced once the new one is complete). Returns false, leaving the file alone, when it isn't
     * a fragmented MP4 without one or can't be read as one.
     */
    fun addIndex(file: Path): Boolean {
        if (status(file) != Status.FRAGMENTED_NO_INDEX) return false
        val boxes = topLevel(file)
        val moov = boxes.first { it.type == "moov" }
        val track = readBox(file, moov).let(::referenceTrack) ?: return false
        val fragments = fragments(file, boxes, track) ?: return false
        if (fragments.isEmpty() || fragments.size > 0xFFFF) return false

        val sidx = sidx(track, fragments)
        val firstMoof = boxes.indexOfFirst { it.type == "moof" }
        val tmp = Path(file.parent!!, file.name + ".indexing")
        try {
            SystemFileSystem.source(file).buffered().use { input ->
                SystemFileSystem.sink(tmp).buffered().use { out ->
                    var at = 0L
                    for ((i, box) in boxes.withIndex()) {
                        input.skip(box.offset - at)
                        at = box.offset
                        if (i == firstMoof) out.write(sidx)
                        when (box.type) {
                            // Players with an index don't need it, and its offsets would be wrong.
                            "mfra" -> { input.skip(box.size); at += box.size }
                            "moof" -> {
                                val bytes = input.readByteArray(box.size.toInt())
                                at += box.size
                                shiftBaseOffsets(bytes, sidx.size.toLong())
                                out.write(bytes)
                            }
                            else -> { input.readTo(out, box.size); at += box.size }
                        }
                    }
                }
            }
            SystemFileSystem.atomicMove(tmp, file)
            return true
        } catch (e: Exception) {
            SystemFileSystem.delete(tmp, mustExist = false)
            throw e
        }
    }

    // ---- Reading ----

    private fun topLevel(file: Path): List<Box> {
        val length = SystemFileSystem.metadataOrNull(file)?.size ?: return emptyList()
        val boxes = mutableListOf<Box>()
        SystemFileSystem.source(file).buffered().use { s ->
            var at = 0L
            while (at + 8 <= length) {
                var size = s.readInt().toLong() and 0xFFFFFFFFL
                val type = s.readByteArray(4).decodeToString()
                var header = 8
                if (size == 1L) { size = s.readLong(); header = 16 }
                if (size == 0L) size = length - at
                require(size >= header && at + size <= length) { "Damaged MP4 box $type at $at" }
                boxes += Box(type, at, header, size)
                s.skip(size - header)
                at += size
            }
        }
        return boxes
    }

    private fun readBox(file: Path, box: Box): ByteArray = SystemFileSystem.source(file).buffered().use { s ->
        s.skip(box.offset)
        s.readByteArray(box.size.toInt())
    }

    /** Child boxes of the box whose content is [from] until [to] in [b]: type → (content start, end). */
    private fun children(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = mutableListOf<Triple<String, Int, Int>>()
        var i = from
        while (i + 8 <= to) {
            var size = u32(b, i)
            var header = 8
            if (size == 1L) { size = u64(b, i + 8); header = 16 }
            if (size == 0L) size = (to - i).toLong()
            if (size < header || i + size > to) break
            out += Triple(b.decodeToString(i + 4, i + 8), i + header, (i + size).toInt())
            i += size.toInt()
        }
        return out
    }

    private class Track(val id: Long, val timescale: Long, val defaultDuration: Long)

    /** The video track (else the first), whose time the index is in. */
    private fun referenceTrack(moov: ByteArray): Track? {
        val top = children(moov, 8, moov.size)
        val defaults = top.filter { it.first == "mvex" }.flatMap { (_, s, e) -> children(moov, s, e) }
            .filter { it.first == "trex" }.associate { (_, s, _) -> u32(moov, s + 4) to u32(moov, s + 12) }
        val tracks = top.filter { it.first == "trak" }.mapNotNull { (_, s, e) ->
            val trak = children(moov, s, e)
            val tkhd = trak.firstOrNull { it.first == "tkhd" } ?: return@mapNotNull null
            val id = if (moov[tkhd.second].toInt() == 1) u32(moov, tkhd.second + 20) else u32(moov, tkhd.second + 12)
            val mdia = trak.firstOrNull { it.first == "mdia" } ?: return@mapNotNull null
            val parts = children(moov, mdia.second, mdia.third)
            val mdhd = parts.firstOrNull { it.first == "mdhd" } ?: return@mapNotNull null
            val timescale = if (moov[mdhd.second].toInt() == 1) u32(moov, mdhd.second + 20) else u32(moov, mdhd.second + 12)
            val handler = parts.firstOrNull { it.first == "hdlr" }?.let { moov.decodeToString(it.second + 8, it.second + 12) }
            Triple(Track(id, timescale, defaults[id] ?: 0), handler, Unit)
        }
        return (tracks.firstOrNull { it.second == "vide" } ?: tracks.firstOrNull())?.first?.takeIf { it.timescale > 0 }
    }

    /** One fragment: from its moof to the next one, starting at [time] and lasting [duration] (track time). */
    private class Fragment(val size: Long, val time: Long?, val duration: Long)

    private fun fragments(file: Path, boxes: List<Box>, track: Track): List<Fragment>? {
        val moofs = boxes.withIndex().filter { it.value.type == "moof" }
        val out = mutableListOf<Fragment>()
        for ((n, entry) in moofs.withIndex()) {
            val (index, moof) = entry
            // Up to the next moof, or the end of the media (anything after the last mdat isn't media).
            val end = if (n + 1 < moofs.size) moofs[n + 1].value.offset else {
                boxes.drop(index).lastOrNull { it.type == "mdat" }?.let { it.offset + it.size } ?: return null
            }
            val bytes = readBox(file, moof)
            val timing = trafTiming(bytes, track) ?: return null
            out += Fragment(end - moof.offset, timing.first, timing.second)
        }
        // Where each fragment's decode time is known, it's the difference to the next one.
        return out.mapIndexed { i, f ->
            val next = out.getOrNull(i + 1)?.time
            if (f.time != null && next != null && next > f.time) Fragment(f.size, f.time, next - f.time) else f
        }
    }

    /** The reference track's start time (tfdt) and summed sample durations in one moof. */
    private fun trafTiming(moof: ByteArray, track: Track): Pair<Long?, Long>? {
        for ((type, s, e) in children(moof, 8, moof.size)) {
            if (type != "traf") continue
            val parts = children(moof, s, e)
            val tfhd = parts.firstOrNull { it.first == "tfhd" } ?: continue
            val flags = u24(moof, tfhd.second + 1)
            if (u32(moof, tfhd.second + 4) != track.id) continue
            var p = tfhd.second + 8
            if (flags and 0x1 != 0) p += 8
            if (flags and 0x2 != 0) p += 4
            val defaultDuration = if (flags and 0x8 != 0) u32(moof, p) else track.defaultDuration
            val time = parts.firstOrNull { it.first == "tfdt" }?.let { (_, ts, _) -> if (moof[ts].toInt() == 1) u64(moof, ts + 4) else u32(moof, ts + 4) }
            var duration = 0L
            for ((_, rs, _) in parts.filter { it.first == "trun" }) {
                val rf = u24(moof, rs + 1)
                val count = u32(moof, rs + 4)
                var q = rs + 8
                if (rf and 0x1 != 0) q += 4
                if (rf and 0x4 != 0) q += 4
                val perSample = listOf(0x100, 0x200, 0x400, 0x800).count { rf and it != 0 } * 4
                if (rf and 0x100 == 0) duration += count * defaultDuration
                else for (k in 0 until count) duration += u32(moof, (q + k * perSample).toInt())
            }
            return time to duration
        }
        return null
    }

    // ---- Writing ----

    private fun sidx(track: Track, fragments: List<Fragment>): ByteArray {
        val b = Buffer()
        b.writeInt(40 + 12 * fragments.size)
        b.write("sidx".encodeToByteArray())
        b.writeInt(1 shl 24) // version 1, no flags
        b.writeInt(track.id.toInt())
        b.writeInt(track.timescale.toInt())
        b.writeLong(fragments.first().time ?: 0L) // earliest presentation time
        b.writeLong(0L) // first fragment right after the index
        b.writeShort(0)
        b.writeShort(fragments.size.toShort())
        for (f in fragments) {
            b.writeInt(f.size.toInt()) // a media reference
            b.writeInt(f.duration.coerceIn(0, 0xFFFFFFFFL).toInt())
            b.writeInt(0x90000000.toInt()) // starts with a keyframe (SAP type 1)
        }
        return b.readByteArray()
    }

    /** Moves every track fragment's absolute data offset ([tfhd] base) on by [delta]. */
    private fun shiftBaseOffsets(moof: ByteArray, delta: Long) {
        for ((type, s, e) in children(moof, 8, moof.size)) {
            if (type != "traf") continue
            val tfhd = children(moof, s, e).firstOrNull { it.first == "tfhd" } ?: continue
            if (u24(moof, tfhd.second + 1) and 0x1 == 0) continue
            val at = tfhd.second + 8
            putU64(moof, at, u64(moof, at) + delta)
        }
    }

    private fun u24(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 16) or ((b[i + 1].toInt() and 0xFF) shl 8) or (b[i + 2].toInt() and 0xFF)
    private fun u32(b: ByteArray, i: Int): Long = (0 until 4).fold(0L) { acc, k -> (acc shl 8) or (b[i + k].toLong() and 0xFF) }
    private fun u64(b: ByteArray, i: Int): Long = (0 until 8).fold(0L) { acc, k -> (acc shl 8) or (b[i + k].toLong() and 0xFF) }
    private fun putU64(b: ByteArray, i: Int, v: Long) { for (k in 0 until 8) b[i + k] = (v ushr (56 - 8 * k)).toByte() }

    private inline fun <T> Source.use(block: (Source) -> T): T = try { block(this) } finally { close() }
}
