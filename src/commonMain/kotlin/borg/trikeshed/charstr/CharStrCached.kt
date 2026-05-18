@file:Suppress("INLINE_CLASS_DEPRECATED")

package borg.trikeshed.charstr

import borg.trikeshed.lib.*
import kotlin.concurrent.Volatile

/**
 * Configurable hot-op set for CharStr memoization.
 *
 * Determines which TextK ops get explicit fields (scalarized by C2)
 * vs identity-map probe vs cold recompute.
 * Configured per Corpus, not globally — delays the canonical choice
 * until the workload characteristics are known.
 */
 inline  class HotTextKSet(val ops: Set<TextK<*>>) {
    companion object {
        /** Default hot set: NFC + XXH3 + Bytes — the canonical equality ops. */
        val DEFAULT = HotTextKSet(setOf(TextK.SizeK.Bytes, TextK.HashK.XXH3, TextK.NormK.NFC))
    }

    operator fun contains(op: TextK<*>): Boolean = op in ops
}

/**
 * CharStrCached — the dispatcher.
 *
 * Hot ops as explicit fields (scalarized by C2, ~1 load).
 * Warm ops via IdentityHashMap (1 volatile read + 1 probe).
 * Cold ops recompute (no cache, pure function).
 *
 * The dispatcher lambda is non-capturing: dispatch(seq, op) is a static function
 * to keep escape analysis happy and avoid closure allocation.
 */
class CharStrCached(
    private val witness: CharSequence,
    private val hotSet: HotTextKSet = HotTextKSet.DEFAULT,
) : Join<TextK<*>, (TextK<*>) -> Any?> {

    // ── hot fields — explicit, scalarized by C2 ─────────────────
    // Racy-but-safe: same pattern as String.hash — one lazy field, computed once.
    @Volatile
    private var _sizeBytes: Int = -1
    @Volatile private var _sizeCp: Int = -1
    @Volatile private var _xxh3: Long = Long.MIN_VALUE

    // ── warm cache — identity-keyed map, 1 volatile read + 1 probe ──
    private val warmCache: MutableMap<TextK<*>, Any> = mutableMapOf()

    override val a: TextK<*> get() = TextK.Raw
    override val b: (TextK<*>) -> Any? = { op -> resolve(op) }

    private fun resolve(op: TextK<*>): Any? = when (op) {
        TextK.Raw              -> witness
        TextK.SizeK.Bytes      -> sizeBytes()
        TextK.SizeK.Codepoints -> sizeCp()
        TextK.SizeK.UTF16Units -> witness.length
        TextK.SizeK.Graphemes  -> computeGraphemes()
        TextK.HashK.XXH3       -> xxh3()
        TextK.HashK.FNV1a      -> computeHash(op as TextK.HashK)
        TextK.HashK.SipHash13  -> computeHash(op as TextK.HashK)
        TextK.HashK.CRC32C     -> computeHash(op as TextK.HashK)
        is TextK.NormK         -> warmCache.getOrPut(op) { computeNorm(op) }
        TextK.CaseFold         -> warmCache.getOrPut(op) { computeCaseFold() }
        is TextK.RopeK         -> warmCache.getOrPut(op) { computeRope(op) }
        is TextK.NgramK<*>     -> computeNgram(op)
        is TextK.FingerprintK  -> computeFingerprint(op)
    }

    // ── hot-path implementations ────────────────────────────────

    private fun sizeBytes(): Int {
        var s = _sizeBytes
        if (s == -1) {
            s = computeSizeBytes(witness)
            _sizeBytes = s
        }
        return s
    }

    private fun sizeCp(): Int {
        var s = _sizeCp
        if (s == -1) {
            s = computeCodepoints(witness)
            _sizeCp = s
        }
        return s
    }

    private fun xxh3(): Long {
        var h = _xxh3
        if (h == Long.MIN_VALUE) {
            h = computeXXH3(witness)
            _xxh3 = h
        }
        return h
    }

    // ── equality: canonical NFC witness + XXH3 hash ─────────────

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CharStrCached) return false
        // Fast path: hash compare
        if (xxh3() != other.xxh3()) return false
        // Slow path: NFC-normalized content compare
        return normalizedContent() == other.normalizedContent()
    }

    override fun hashCode(): Int = xxh3().toInt()

    private fun normalizedContent(): CharSequence {
        val norm = resolve(TextK.NormK.NFC)
        return if (norm is CharStrCached) norm.witness else witness
    }

    override fun toString(): String = witness.toString()
}

// ── Platform-hookable compute functions ─────────────────────────
// These are expect/actual candidates for KMP. Stubs for now.

internal fun computeSizeBytes(seq: CharSequence): Int =
    seq.toString().encodeToByteArray().size

internal fun computeCodepoints(seq: CharSequence): Int {
    var count = 0
    var i = 0
    val s = seq.toString()
    while (i < s.length) {
        val c = s[i]
        count++
        i += if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
    }
    return count
}

internal fun computeGraphemes(): Int {
    // Full grapheme cluster segmentation requires ICU or platform-specific impl.
    // Placeholder — returns codepoint count.
    return -1
}

internal fun computeXXH3(seq: CharSequence): Long {
    // XXH3 stub — proper impl is a platform expect/actual.
    // Uses FNV1a-64 as interim stand-in.
    var hash = -3750763034362895579L // FNV offset basis
    val bytes = seq.toString().encodeToByteArray()
    for (byte in bytes) {
        hash = hash xor byte.toLong()
        hash *= 1099511628211L // FNV prime
    }
    return hash
}

internal fun computeHash(op: TextK.HashK): Long {
    // Dispatch to the right hash family — platform expect/actual.
    return 0L
}

internal fun CharStrCached.computeNorm(op: TextK.NormK): CharStr {
    // Unicode normalization — platform expect/actual.
    // Returns self as identity for now.
    return this
}

internal fun CharStrCached.computeCaseFold(): CharStr {
    // Case fold depends on NFC — platform expect/actual.
    return CharStr(this.b(TextK.Raw).toString().lowercase())
}

internal fun computeRope(op: TextK.RopeK): RopeView {
    return object : RopeView {
        override val chunkCount: Int get() = 1
        override fun get(chunkIndex: Int): CharSequence = TODO("rope chunking")
        override fun iterator(): Iterator<CharSequence> = TODO("rope iteration")
    }
}

internal fun computeNgram(op: TextK.NgramK<*>): Any? {
    // N-gram computation — cold path, no cache.
    return null
}

internal fun computeFingerprint(op: TextK.FingerprintK): Long {
    // SimHash / MinHash — cold path, no cache.
    return 0L
}
