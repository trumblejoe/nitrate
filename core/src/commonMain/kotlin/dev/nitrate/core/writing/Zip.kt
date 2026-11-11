package dev.nitrate.core.writing

/**
 * Just enough ZIP for .fadein files, in pure Kotlin so it runs on every platform:
 * writes stored (uncompressed) entries, reads stored and deflated ones.
 */
internal object Zip {
    fun write(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = Bytes()
        val central = Bytes()
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = Crc32.of(data)
            val offset = out.size
            // Local file header
            out.u32(0x04034b50); out.u16(20); out.u16(0x0800); out.u16(0) // version, UTF-8 names, stored
            out.u16(0); out.u16(0x21) // time 00:00, date 1980-01-01
            out.u32(crc); out.u32(data.size); out.u32(data.size)
            out.u16(nameBytes.size); out.u16(0)
            out.bytes(nameBytes); out.bytes(data)
            // Central directory record
            central.u32(0x02014b50); central.u16(20); central.u16(20); central.u16(0x0800); central.u16(0)
            central.u16(0); central.u16(0x21)
            central.u32(crc); central.u32(data.size); central.u32(data.size)
            central.u16(nameBytes.size); central.u16(0); central.u16(0); central.u16(0); central.u16(0)
            central.u32(0); central.u32(offset)
            central.bytes(nameBytes)
        }
        val centralOffset = out.size
        out.bytes(central.toByteArray())
        // End of central directory
        out.u32(0x06054b50); out.u16(0); out.u16(0)
        out.u16(entries.size); out.u16(entries.size)
        out.u32(central.size); out.u32(centralOffset); out.u16(0)
        return out.toByteArray()
    }

    /** Reads every entry via the central directory, so sizes are known even with data descriptors. */
    fun read(zip: ByteArray): List<Pair<String, ByteArray>> {
        val eocd = (zip.size - 22 downTo maxOf(0, zip.size - 22 - 65_535)).firstOrNull { zip.u32(it) == 0x06054b50L }
            ?: error("Not a ZIP file")
        val count = zip.u16(eocd + 10)
        var p = zip.u32(eocd + 16).toInt()
        val out = mutableListOf<Pair<String, ByteArray>>()
        repeat(count) {
            require(zip.u32(p) == 0x02014b50L) { "Corrupt ZIP directory" }
            val method = zip.u16(p + 10)
            val compressed = zip.u32(p + 20).toInt()
            val size = zip.u32(p + 24).toInt()
            val nameLen = zip.u16(p + 28)
            val extraLen = zip.u16(p + 30)
            val commentLen = zip.u16(p + 32)
            val local = zip.u32(p + 42).toInt()
            val name = zip.copyOfRange(p + 46, p + 46 + nameLen).decodeToString()
            val dataStart = local + 30 + zip.u16(local + 26) + zip.u16(local + 28)
            val raw = zip.copyOfRange(dataStart, dataStart + compressed)
            val data = when (method) {
                0 -> raw
                8 -> Inflate.inflate(raw, size)
                else -> error("Unsupported ZIP compression method $method")
            }
            out += name to data
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    private fun ByteArray.u16(i: Int) = (this[i].toInt() and 0xff) or ((this[i + 1].toInt() and 0xff) shl 8)
    private fun ByteArray.u32(i: Int): Long = (u16(i).toLong()) or (u16(i + 2).toLong() shl 16)
}

/** Growable byte buffer with little-endian writers. */
internal class Bytes {
    private var buf = ByteArray(256)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun byte(b: Int) { ensure(1); buf[size++] = b.toByte() }
    fun bytes(b: ByteArray) { ensure(b.size); b.copyInto(buf, size); size += b.size }
    fun u16(v: Int) { byte(v and 0xff); byte((v ushr 8) and 0xff) }
    fun u32(v: Int) { u16(v and 0xffff); u16((v ushr 16) and 0xffff) }
    fun u32(v: Long) = u32(v.toInt())
    operator fun get(i: Int) = buf[i]
    fun toByteArray() = buf.copyOf(size)
}

internal object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun of(data: ByteArray): Long {
        var c = -1
        for (b in data) c = table[(c xor b.toInt()) and 0xff] xor (c ushr 8)
        return (c.inv().toLong()) and 0xffffffffL
    }
}

/** Raw DEFLATE (RFC 1951) decoder, after zlib's reference decoder puff.c. */
internal object Inflate {
    private class Huffman(val count: IntArray, val symbol: IntArray)

    private class State(val input: ByteArray) {
        var pos = 0
        var bitBuf = 0
        var bitCount = 0
        val out = Bytes()

        fun bits(n: Int): Int {
            var v = bitBuf
            while (bitCount < n) {
                require(pos < input.size) { "Truncated DEFLATE data" }
                v = v or ((input[pos++].toInt() and 0xff) shl bitCount)
                bitCount += 8
            }
            bitBuf = v ushr n
            bitCount -= n
            return v and ((1 shl n) - 1)
        }
    }

    private const val MAX_BITS = 15
    private val LENGTH_BASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
    private val LENGTH_EXTRA = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
    private val DIST_BASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
    private val DIST_EXTRA = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
    private val CODE_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

    private val fixed: Pair<Huffman, Huffman> by lazy {
        val lengths = IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } }
        build(lengths, 0, 288) to build(IntArray(30) { 5 }, 0, 30)
    }

    fun inflate(input: ByteArray, expectedSize: Int = -1): ByteArray {
        val s = State(input)
        do {
            val last = s.bits(1)
            when (s.bits(2)) {
                0 -> stored(s)
                1 -> codes(s, fixed.first, fixed.second)
                2 -> dynamic(s)
                else -> error("Invalid DEFLATE block type")
            }
        } while (last == 0)
        val out = s.out.toByteArray()
        require(expectedSize < 0 || out.size == expectedSize) { "DEFLATE size mismatch" }
        return out
    }

    private fun stored(s: State) {
        s.bitBuf = 0
        s.bitCount = 0
        require(s.pos + 4 <= s.input.size) { "Truncated stored block" }
        val len = (s.input[s.pos].toInt() and 0xff) or ((s.input[s.pos + 1].toInt() and 0xff) shl 8)
        val nlen = (s.input[s.pos + 2].toInt() and 0xff) or ((s.input[s.pos + 3].toInt() and 0xff) shl 8)
        require(len == (nlen.inv() and 0xffff)) { "Corrupt stored block" }
        s.pos += 4
        require(s.pos + len <= s.input.size) { "Truncated stored block" }
        s.out.bytes(s.input.copyOfRange(s.pos, s.pos + len))
        s.pos += len
    }

    private fun build(lengths: IntArray, offset: Int, n: Int): Huffman {
        val count = IntArray(MAX_BITS + 1)
        for (i in 0 until n) count[lengths[offset + i]]++
        val offs = IntArray(MAX_BITS + 1)
        for (len in 1 until MAX_BITS) offs[len + 1] = offs[len] + count[len]
        val symbol = IntArray(n)
        for (i in 0 until n) if (lengths[offset + i] != 0) symbol[offs[lengths[offset + i]]++] = i
        return Huffman(count, symbol)
    }

    private fun decode(s: State, h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (len in 1..MAX_BITS) {
            code = code or s.bits(1)
            val count = h.count[len]
            if (code - count < first) return h.symbol[index + (code - first)]
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        error("Invalid Huffman code")
    }

    private fun codes(s: State, lencode: Huffman, distcode: Huffman) {
        while (true) {
            var symbol = decode(s, lencode)
            when {
                symbol < 256 -> s.out.byte(symbol)
                symbol == 256 -> return
                else -> {
                    symbol -= 257
                    require(symbol < 29) { "Invalid length symbol" }
                    val len = LENGTH_BASE[symbol] + s.bits(LENGTH_EXTRA[symbol])
                    val d = decode(s, distcode)
                    require(d < 30) { "Invalid distance symbol" }
                    val dist = DIST_BASE[d] + s.bits(DIST_EXTRA[d])
                    require(dist <= s.out.size) { "Distance too far back" }
                    repeat(len) { s.out.byte(s.out[s.out.size - dist].toInt()) }
                }
            }
        }
    }

    private fun dynamic(s: State) {
        val nlen = s.bits(5) + 257
        val ndist = s.bits(5) + 1
        val ncode = s.bits(4) + 4
        val lengths = IntArray(320)
        for (i in 0 until ncode) lengths[CODE_ORDER[i]] = s.bits(3)
        val lencodeLengths = build(lengths, 0, 19)
        var index = 0
        val all = IntArray(nlen + ndist)
        while (index < nlen + ndist) {
            var symbol = decode(s, lencodeLengths)
            if (symbol < 16) {
                all[index++] = symbol
            } else {
                var len = 0
                when (symbol) {
                    16 -> { require(index > 0) { "Repeat with no first length" }; len = all[index - 1]; symbol = 3 + s.bits(2) }
                    17 -> symbol = 3 + s.bits(3)
                    else -> symbol = 11 + s.bits(7)
                }
                require(index + symbol <= nlen + ndist) { "Too many lengths" }
                repeat(symbol) { all[index++] = len }
            }
        }
        require(all[256] != 0) { "No end-of-block code" }
        codes(s, build(all, 0, nlen), build(all, nlen, ndist))
    }
}
