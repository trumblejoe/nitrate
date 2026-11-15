package dev.nitrate.core.writing

/**
 * A change to a text, as a walk over it: keep (retain) n characters, insert a string, or delete
 * n characters, until the whole text has been passed over. Lengths count UTF-16 units, as Kotlin
 * and the Companion plugin's C# both index strings.
 *
 * Two people's changes made at the same time to the same text are reconciled with [transform]:
 * each is rewritten to apply after the other, and both orders end with the same text. This is
 * operational transformation as used by collaborative editors; shared scripts use it so writers
 * never overwrite each other.
 */
class TextOperation private constructor(val parts: List<Part>) {

    sealed interface Part
    data class Retain(val n: Int) : Part
    data class Insert(val text: String) : Part
    data class Delete(val n: Int) : Part

    /** Length of the text this applies to. */
    val baseLength: Int = parts.sumOf { if (it is Retain) it.n else if (it is Delete) it.n else 0 }

    /** Length of the text it makes. */
    val targetLength: Int = parts.sumOf { if (it is Retain) it.n else if (it is Insert) it.text.length else 0 }

    /** True when it changes nothing. */
    val isNoop: Boolean get() = parts.all { it is Retain }

    fun apply(text: String): String {
        require(text.length == baseLength) { "Operation is for a text of $baseLength characters, not ${text.length}" }
        val out = StringBuilder(targetLength)
        var at = 0
        for (p in parts) when (p) {
            is Retain -> { out.append(text, at, at + p.n); at += p.n }
            is Insert -> out.append(p.text)
            is Delete -> at += p.n
        }
        return out.toString()
    }

    /** Where a cursor at [index] in the base text ends up. [ownInsertsBefore]: an insert at the cursor goes before it. */
    fun transformIndex(index: Int, ownInsertsBefore: Boolean = false): Int {
        var at = 0
        var result = index
        for (p in parts) {
            if (at > index) break
            when (p) {
                is Retain -> at += p.n
                is Insert -> if (at < index || ownInsertsBefore) result += p.text.length
                is Delete -> { result -= minOf(p.n, index - at); at += p.n }
            }
        }
        return result
    }

    /** One operation doing this and then [next]. */
    fun compose(next: TextOperation): TextOperation {
        require(targetLength == next.baseLength) { "Can't compose: $targetLength then ${next.baseLength}" }
        val b = Builder()
        val first = Cursor(parts)
        val second = Cursor(next.parts)
        while (first.has || second.has) {
            val a = first.peek
            val c = second.peek
            when {
                a is Delete -> { b.delete(a.n); first.take(a.n) }
                c is Insert -> { b.insert(c.text); second.take(c.text.length) }
                a == null || c == null -> error("Compose ran off the end")
                else -> {
                    val n = minOf(first.size, second.size)
                    when {
                        a is Retain && c is Retain -> b.retain(n)
                        a is Retain && c is Delete -> b.delete(n)
                        a is Insert && c is Retain -> b.insert(first.text(n))
                        a is Insert && c is Delete -> Unit
                    }
                    first.take(n)
                    second.take(n)
                }
            }
        }
        return b.build()
    }

    override fun equals(other: Any?) = other is TextOperation && other.parts == parts
    override fun hashCode() = parts.hashCode()
    override fun toString() = parts.joinToString(prefix = "[", postfix = "]") {
        when (it) { is Retain -> "r${it.n}"; is Insert -> "i\"${it.text}\""; is Delete -> "d${it.n}" }
    }

    /** Walks an operation's parts, splitting them as needed. */
    private class Cursor(private val parts: List<Part>) {
        private var i = 0
        private var offset = 0
        val has get() = i < parts.size
        val peek: Part? get() = parts.getOrNull(i)
        val size: Int get() = when (val p = parts[i]) { is Retain -> p.n; is Delete -> p.n; is Insert -> p.text.length } - offset
        fun text(n: Int) = (parts[i] as Insert).text.substring(offset, offset + n)
        fun take(n: Int) {
            offset += n
            if (offset >= when (val p = parts[i]) { is Retain -> p.n; is Delete -> p.n; is Insert -> p.text.length }) { i++; offset = 0 }
        }
    }

    /** Builds a tidy operation: no empty parts, neighbours of a kind joined, inserts before deletes. */
    class Builder {
        private val parts = mutableListOf<Part>()

        fun retain(n: Int) = apply {
            if (n <= 0) return@apply
            val last = parts.lastOrNull()
            if (last is Retain) parts[parts.lastIndex] = Retain(last.n + n) else parts += Retain(n)
        }

        fun insert(text: String) = apply {
            if (text.isEmpty()) return@apply
            val last = parts.lastOrNull()
            when {
                last is Insert -> parts[parts.lastIndex] = Insert(last.text + text)
                last is Delete -> {
                    // Keep inserts before deletes, so equal changes always look the same.
                    val before = parts.getOrNull(parts.lastIndex - 1)
                    if (before is Insert) parts[parts.lastIndex - 1] = Insert(before.text + text) else parts.add(parts.lastIndex, Insert(text))
                }
                else -> parts += Insert(text)
            }
        }

        fun delete(n: Int) = apply {
            if (n <= 0) return@apply
            val last = parts.lastOrNull()
            if (last is Delete) parts[parts.lastIndex] = Delete(last.n + n) else parts += Delete(n)
        }

        fun build(): TextOperation {
            // A trailing retain-only tail is still part of the walk: keep it (it gives baseLength).
            return TextOperation(parts.toList())
        }
    }

    companion object {
        fun of(vararg parts: Part): TextOperation = Builder().also { b ->
            parts.forEach { when (it) { is Retain -> b.retain(it.n); is Insert -> b.insert(it.text); is Delete -> b.delete(it.n) } }
        }.build()

        /** Changes nothing in a text of [length]. */
        fun identity(length: Int) = Builder().retain(length).build()

        /** The change from [old] to [new]: one replaced stretch between what they share at each end. */
        fun diff(old: String, new: String): TextOperation {
            var prefix = 0
            val max = minOf(old.length, new.length)
            while (prefix < max && old[prefix] == new[prefix]) prefix++
            // Don't split a surrogate pair.
            if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix--
            var suffix = 0
            while (suffix < max - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            if (suffix > 0 && old[old.length - suffix].isLowSurrogate()) suffix--
            return Builder()
                .retain(prefix)
                .insert(new.substring(prefix, new.length - suffix))
                .delete(old.length - prefix - suffix)
                .retain(suffix)
                .build()
        }

        /**
         * Rewrites two changes made at the same time to the same text so each applies after the
         * other: returns (a', b') with b'(a(text)) == a'(b(text)). Where both insert at the same
         * spot, [a]'s text comes first.
         */
        fun transform(a: TextOperation, b: TextOperation): Pair<TextOperation, TextOperation> {
            require(a.baseLength == b.baseLength) { "Can't transform: ${a.baseLength} vs ${b.baseLength}" }
            val aOut = Builder()
            val bOut = Builder()
            val x = Cursor(a.parts)
            val y = Cursor(b.parts)
            while (x.has || y.has) {
                val p = x.peek
                val q = y.peek
                when {
                    p is Insert -> { aOut.insert(p.text); bOut.retain(p.text.length); x.take(p.text.length) }
                    q is Insert -> { aOut.retain(q.text.length); bOut.insert(q.text); y.take(q.text.length) }
                    p == null || q == null -> error("Transform ran off the end")
                    else -> {
                        val n = minOf(x.size, y.size)
                        when {
                            p is Retain && q is Retain -> { aOut.retain(n); bOut.retain(n) }
                            p is Delete && q is Delete -> Unit
                            p is Delete && q is Retain -> aOut.delete(n)
                            p is Retain && q is Delete -> bOut.delete(n)
                        }
                        x.take(n)
                        y.take(n)
                    }
                }
            }
            return aOut.build() to bOut.build()
        }

        /** The wire form: a positive number keeps, a negative one deletes, a string inserts. */
        fun toWire(op: TextOperation): List<Any> = op.parts.map {
            when (it) { is Retain -> it.n; is Delete -> -it.n; is Insert -> it.text }
        }

        fun fromWire(items: List<Any>): TextOperation = Builder().also { b ->
            for (item in items) when (item) {
                is String -> b.insert(item)
                is Number -> item.toInt().let { if (it > 0) b.retain(it) else b.delete(-it) }
                else -> throw IllegalArgumentException("Not an operation part: $item")
            }
        }.build()
    }
}
