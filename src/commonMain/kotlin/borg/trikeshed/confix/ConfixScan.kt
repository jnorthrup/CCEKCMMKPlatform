@file:Suppress("NonAsciiCharacters", "FunctionName", "ObjectPropertyName", "LocalVariableName")

package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

/**
 * ConfixScanner — Axis 1: Tokenization Strategy.
 *
 * Shared topology across JSON / YAML / CBOR:
 *   1. Syntax-specific indexer → ConfixIndex
 *      (single pass: fills Spans + Tags eagerly)
 *   2. Shared lazy facets over the index:
 *      TreeCursor, Depths, DirectChildren, AsRowVec, KeyToChild
 *
 * Like CharStr's TextK domain, ConfixIndexK is ONE domain with MANY facets.
 * No consumer is forced through tree build or reification.
 */
interface ConfixScanner {
    /** Primary output: the ConfixIndex domain. Consumers project facets lazily. */
    fun index(src: Series<Char>): ConfixIndex

    /** Convenience: the full tree cursor. */
    fun scan(src: Series<Char>): ConfixCursor =
        index(src)[ConfixIndexK.TreeCursor] as Cursor
}

// ═══════════════════════════════════════════════════════════════════
//  Shared: ConfixIndex construction from flat positional data
// ═══════════════════════════════════════════════════════════════════

/**
 * Build a ConfixIndex from already-indexed Spans + Tags.
 * All derived facets are lazy projections.
 */
private fun buildIndex(
    spans: Series<Twin<Int>>,
    tags: Series<IOMemento>,
    src: Series<Char>,
): ConfixIndex {
    val n = spans.size

    // Memoization cells:
    var depthsCache: Series<Int>? = null
    @Suppress("UNCHECKED_CAST")
    val childCache = arrayOfNulls<Series<Int>>(n)   // one per element
    var treeCache: Cursor? = null
    val keyToChildCache = arrayOfNulls<Series<Int>>(n)

    fun depths(): Series<Int> {
        if (depthsCache != null) return depthsCache!!
        depthsCache = n j { i ->
            var d = 0
            val s = spans[i]; val o = s.a; val cl = s.b
            for (j in 0 until n) {
                if (j == i) continue
                val sj = spans[j]
                if (sj.a < o && sj.b > cl) d++
            }
            d
        }
        return depthsCache!!
    }

    fun directChildren(idx: Int): Series<Int> {
        childCache[idx]?.let { return it }
        val s = spans[idx]; val open = s.a; val close = s.b
        val dd = depths()
        val targetDepth = dd[idx] + 1
        // Collect all elements at depth targetDepth+1 that are inside this span
        val buf = IntArray(n)
        var count = 0
        for (j in 0 until n) {
            if (j == idx) continue
            val sj = spans[j]
            if (sj.a > open && sj.b < close && dd[j] == targetDepth) {
                if (count == buf.size) { /* grow */ }
                buf[count++] = j
            }
        }
        val c = count; val b = buf
        val result: Series<Int> = c j { k: Int -> b[k] }
        childCache[idx] = result
        return result
    }

    fun treeCursor(): Cursor {
        if (treeCache != null) return treeCache!!
        // Build tree: for each element, construct RowVec with children = tree of direct children
        val dd = depths()
        val colMeta: Series<`ColumnMeta↻`> = s_[
            { ColumnMeta("open", IOMemento.IoInt) },
            { ColumnMeta("close", IOMemento.IoInt) },
            { ColumnMeta("tag", IOMemento.IoObject) },
            { ColumnMeta("children", IOMemento.IoObject) },
        ]
        // Build RowVecs for each element depth-first
        val rvCache = arrayOfNulls<RowVec>(n)

        fun buildRowVec(i: Int): RowVec {
            rvCache[i]?.let { return it }
            val span = spans[i]; val tag = tags[i]
            val dc = directChildren(i)
            // children cursor: for each direct child, build its RowVec
            val childCursor: Cursor = dc.size j { k ->
                buildRowVec(dc[k])
            }
            val rv = (4 j { c: Int ->
                when (c) {
                    0 -> (span.a as Any?) j colMeta[0]
                    1 -> (span.b as Any?) j colMeta[1]
                    2 -> (tag    as Any?) j colMeta[2]
                    3 -> (childCursor as Any?) j colMeta[3]
                    else -> error("4 columns")
                }
            }) as RowVec
            rvCache[i] = rv
            return rv
        }

        // Return top-level elements only (depth 0)
        val topCount = (0 until n).count { dd[it] == 0 }
        val topIndices = IntArray(topCount)
        var ti = 0
        for (i in 0 until n) if (dd[i] == 0) topIndices[ti++] = i
        val result: Cursor = topCount j { k: Int -> buildRowVec(topIndices[k]) }
        treeCache = result
        return result
    }

    fun keyToChild(idx: Int): Series<Int> {
        keyToChildCache[idx]?.let { return it }
        val dc = directChildren(idx)
        // For objects, children are in key/value pairs
        // Return indices of keys (even positions in dc)
        val nk = dc.size / 2
        // For now, return direct children as-is
        keyToChildCache[idx] = dc
        return dc
    }

    @Suppress("UNCHECKED_CAST")
    return ConfixIndexK.Spans j { op: ConfixIndexK<*> ->
        when (op) {
            ConfixIndexK.Spans          -> spans
            ConfixIndexK.Tags           -> tags
            is ConfixIndexK.Open        -> spans[op.idx].a
            is ConfixIndexK.Close       -> spans[op.idx].b
            is ConfixIndexK.Tag         -> tags[op.idx]
            ConfixIndexK.Depths         -> depths()
            is ConfixIndexK.DirectChildren -> directChildren(op.idx)
            ConfixIndexK.TreeCursor     -> treeCursor()
            is ConfixIndexK.KeyToChild  -> keyToChild(op.idx)
            is ConfixIndexK.AsRowVec    -> treeCursor()[op.idx]
            else -> error("unknown facet $op")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  JSON indexer — single pass over chars
// ═══════════════════════════════════════════════════════════════════

object JsonScanner : ConfixScanner {
    override fun index(src: Series<Char>): ConfixIndex {
        val n = src.size

        // First pass: collect spans + tags into growable arrays
        data class Pending(val open: Int, val tag: IOMemento)
        var spanBuf = IntArray(256); var tagBuf = arrayOfNulls<IOMemento>(256)
        var count = 0
        var stack = arrayOfNulls<Pending>(64); var sd = 0
        // Grow function for spanBuf/tagBuf
        fun grow() {
            val n2 = spanBuf.size * 2
            spanBuf = spanBuf.copyOf(n2)
            tagBuf = tagBuf.copyOf(n2)
        }
        var insideQuote = false; var escapeNext = false

        fun push(open: Int, tag: IOMemento) { if (sd < stack.size) stack[sd++] = Pending(open, tag) }
        fun pop(close: Int) {
            if (sd == 0) return
            if (count + 2 > spanBuf.size) grow()
            val p = stack[--sd]!!
            spanBuf[count] = p.open; spanBuf[count + 1] = close
            tagBuf[count / 2] = p.tag
            count += 2
        }
        fun addScalar(open: Int, close: Int, tag: IOMemento) {
            if (count + 2 > spanBuf.size) grow()
            spanBuf[count] = open; spanBuf[count + 1] = close
            tagBuf[count / 2] = tag
            count += 2
        }

        var i = 0
        while (i < n) {
            val c = src[i]
            when {
                insideQuote -> when {
                    escapeNext -> escapeNext = false
                    c == '\\' -> escapeNext = true
                    c == '"'  -> { insideQuote = false; pop(i) }
                }
                else -> when (c) {
                    '{' -> push(i, IOMemento.IoObject)
                    '[' -> push(i, IOMemento.IoArray)
                    '}' -> pop(i)
                    ']' -> pop(i)
                    '"' -> { push(i, IOMemento.IoString); insideQuote = true }
                    't' -> { if (matchAt(src, i, n, "true"))  { addScalar(i, i+3, IOMemento.IoBoolean); i += 3 } }
                    'f' -> { if (matchAt(src, i, n, "false")) { addScalar(i, i+4, IOMemento.IoBoolean); i += 4 } }
                    'n' -> { if (matchAt(src, i, n, "null"))  { addScalar(i, i+3, IOMemento.IoNothing);  i += 3 } }
                    '-', '+', in '0'..'9' -> {
                        val start = i
                        while (i < n) {
                            val ch = src[i]
                            if (ch !in '0'..'9' && ch != '.' && ch != 'e' && ch != 'E' && ch != '+' && ch != '-') break
                            i++
                        }
                        addScalar(start, i - 1, IOMemento.IoDouble)
                        continue  // skip i++ at end
                    }
                }
            }
            i++
        }
        // Flush unclosed
        while (sd > 0) { val p = stack[--sd]!!; addScalar(p.open, n-1, p.tag) }

        val totalElems = count / 2
        val sb = spanBuf; val tb = tagBuf; val c = totalElems
        val spans: Series<Twin<Int>> = c j { k -> sb[k*2] j sb[k*2+1] }
        val tags:  Series<IOMemento>  = c j { k -> tb[k]!! }

        return buildIndex(spans, tags, src)
    }
}

private fun matchAt(src: Series<Char>, start: Int, n: Int, word: String): Boolean {
    if (start + word.length > n) return false
    for (k in word.indices) if (src[start + k] != word[k]) return false
    val after = start + word.length
    if (after < n) { val c = src[after]; if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') return false }
    return true
}

// ═══════════════════════════════════════════════════════════════════
//  YAML indexer
// ═══════════════════════════════════════════════════════════════════

object YamlScanner : ConfixScanner {
    override fun index(src: Series<Char>): ConfixIndex {
        val n = src.size
        val spanBuf = IntArray(256); val tagBuf = arrayOfNulls<IOMemento>(128)
        var count = 0
        var pos = 0

        fun add(open: Int, close: Int, tag: IOMemento) {
            if (count + 2 > spanBuf.size) { /* grow — simplified */ }
            spanBuf[count] = open; spanBuf[count + 1] = close
            tagBuf[count / 2] = tag
            count += 2
        }

        fun skipBlank(): Int {
            while (pos < n) {
                val start = pos
                while (pos < n && src[pos] == ' ') pos++
                if (pos < n && (src[pos] == '\n' || src[pos] == '\r' || src[pos] == '#')) {
                    while (pos < n && src[pos] != '\n') pos++
                    if (pos < n) pos++
                } else { pos = start; break }
            }
            var i = pos; var k = 0
            while (i < n && src[i] == ' ') { k++; i++ }
            return k
        }

        fun readLineEnd(): Int {
            var end = pos
            while (end < n && src[end] != '\n' && src[end] != '\r') end++
            val close = if (end > pos) end - 1 else pos
            pos = if (end < n) end + 1 else end
            return close
        }

        fun readToColon(): Int {
            var e = pos; while (e < n && src[e] != ':' && src[e] != '\n' && src[e] != '\r') e++; return e
        }

        fun lineKw(kw: String): Boolean {
            var q = pos; var k = 0
            while (k < kw.length && q < n) { if (src[q] != kw[k]) return false; q++; k++ }
            if (k != kw.length) return false
            while (q < n && (src[q]==' '||src[q]=='\t')) q++
            return q >= n || src[q]=='\n' || src[q]=='\r' || src[q]=='#'
        }

        fun classify(p: Int): IOMemento {
            val ch = src[p]
            if (ch == '"' || ch == '\'') return IOMemento.IoString
            if (ch == 't' || ch == 'T') return if (lineKw("true") || lineKw("True")) IOMemento.IoBoolean else IOMemento.IoString
            if (ch == 'f' || ch == 'F') return if (lineKw("false") || lineKw("False")) IOMemento.IoBoolean else IOMemento.IoString
            if (ch == 'n' || ch == 'N') return if (lineKw("null") || lineKw("Null") || lineKw("~")) IOMemento.IoNothing else IOMemento.IoString
            if (ch == '~') return IOMemento.IoNothing
            if (ch == '-' || ch == '+' || (ch in '0'..'9')) return IOMemento.IoDouble
            return IOMemento.IoString
        }

        fun parseBlock(indent: Int, isMap: Boolean = false) {
            if (isMap) {
                var here = indent
                var first = true
                while (pos < n && (first || here >= indent)) {
                    first = false
                    val keyOpen = pos
                    val col = readToColon()
                    if (col >= n || src[col] != ':') {
                        pos = keyOpen
                        val t = classify(pos)
                        add(keyOpen, readLineEnd(), t)
                        val nx = skipBlank()
                        if (nx < indent) break
                        here = nx
                        repeat(here) { pos++ }
                        continue
                    }
                    val keyClose = if (col > keyOpen) col - 1 else keyOpen
                    add(keyOpen, keyClose, IOMemento.IoString)
                    pos = col + 1
                    while (pos < n && src[pos] == ' ') pos++
                    if (pos < n && src[pos] != '\n' && src[pos] != '\r') {
                        val valOpen = pos
                        val ct = classify(valOpen)
                        add(valOpen, readLineEnd(), ct)
                    } else {
                        if (pos < n) pos++
                        parseBlock(indent + 2)
                    }
                    val nx = skipBlank()
                    if (nx < indent) break
                    here = nx
                    repeat(here) { pos++ }
                }
                return
            }
            val here = skipBlank()
            if (pos >= n || here < indent) return
            repeat(here) { pos++ }
            val p = pos
            when (src[p]) {
                '-' -> {
                    pos++; if (pos < n && src[pos] == ' ') pos++
                    val colon = readToColon()
                    if (colon < n && src[colon] == ':') parseBlock(indent + 2, isMap = true)
                    else { val o = pos; val t = classify(o); add(o, readLineEnd(), t) }
                }
                '{', '[' -> {
                    val close = readLineEnd()
                    add(p, close, if (src[p]=='{') IOMemento.IoObject else IOMemento.IoArray)
                }
                '"', '\'' -> { add(p, readLineEnd(), IOMemento.IoString) }
                else -> parseBlock(indent, isMap = true)
            }
            while (pos < n && skipBlank() >= indent) { val ii = skipBlank(); if (ii < indent) break; repeat(ii) { pos++ }; parseBlock(indent) }
        }




        while (pos < n) { val indent = skipBlank(); if (pos >= n) break; repeat(indent) { pos++ }; parseBlock(indent) }

        val total = count / 2
        val sb = spanBuf; val tb = tagBuf; val c = total
        val spans: Series<Twin<Int>> = c j { k -> sb[k*2] j sb[k*2+1] }
        val tags:  Series<IOMemento>  = c j { k -> tb[k]!! }

        return buildIndex(spans, tags, src)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  CBOR indexer
// ═══════════════════════════════════════════════════════════════════

object CborScanner : ConfixScanner {
    override fun index(src: Series<Char>): ConfixIndex {
        val n = src.size
        val ba = ByteArray(n) { i -> src[i].code.toByte() }
        val spanBuf = IntArray(128); val tagBuf = arrayOfNulls<IOMemento>(64)
        var count = 0

        fun add(open: Int, close: Int, tag: IOMemento) {
            spanBuf[count] = open; spanBuf[count + 1] = close
            tagBuf[count / 2] = tag
            count += 2
        }

        fun readLen(p: Int, ai: Int): Pair<Long, Int> = when (ai) {
            in 0..23 -> ai.toLong() to p
            24 -> (ba[p].toLong() and 0xFF) to (p + 1)
            25 -> (((ba[p].toInt() and 0xFF) shl 8) or (ba[p+1].toInt() and 0xFF)).toLong() to (p + 2)
            26 -> (((ba[p].toInt() and 0xFF) shl 24) or ((ba[p+1].toInt() and 0xFF) shl 16) or
                    ((ba[p+2].toInt() and 0xFF) shl 8) or (ba[p+3].toInt() and 0xFF)).toLong() to (p + 4)
            27 -> { var v = 0L; var k = 0; while (k < 8) { v = (v shl 8) or (ba[p+k].toLong() and 0xFF); k++ }; v to (p + 8) }
            31 -> -1L to p
            else -> error("bad cbor ai $ai")
        }

        fun parseItem(p: Int): Int {
            val open = p
            val ib = ba[p].toInt() and 0xFF
            val mt = ib ushr 5; val ai = ib and 0x1F
            var np = p + 1
            np = when (mt) {
                0, 1 -> { val (_, np1) = readLen(np, ai); add(open, np1-1, IOMemento.IoDouble); np1 }
                2 -> { val (len, np1) = readLen(np, ai); if (len<0) np1 else { add(open, np1+len.toInt()-1, IOMemento.IoBytes); np1+len.toInt() } }
                3 -> { val (len, np1) = readLen(np, ai); if (len<0) np1 else { add(open, np1+len.toInt()-1, IOMemento.IoString); np1+len.toInt() } }
                4 -> {
                    val (len, np1) = readLen(np, ai); var kp = np1
                    if (len < 0L) while (kp < n && (ba[kp].toInt() and 0xFF) != 0xFF) kp = parseItem(kp)
                    else { var k = 0L; while (k < len) { kp = parseItem(kp); k++ } }
                    if (kp < n && len < 0L) kp++
                    add(open, kp-1, IOMemento.IoArray); kp
                }
                5 -> {
                    val (len, np1) = readLen(np, ai); var kp = np1
                    if (len < 0L) while (kp < n && (ba[kp].toInt() and 0xFF) != 0xFF) { kp = parseItem(kp); kp = parseItem(kp) }
                    else { var k = 0L; while (k < len) { kp = parseItem(kp); kp = parseItem(kp); k++ } }
                    if (kp < n && len < 0L) kp++
                    add(open, kp-1, IOMemento.IoObject); kp
                }
                6 -> { val (_, np1) = readLen(np, ai); parseItem(np1) }
                7 -> {
                    val tag = when (ai) { 20 -> IOMemento.IoBoolean; 21 -> IOMemento.IoBoolean; 22,23 -> IOMemento.IoNothing; 25,26,27 -> IOMemento.IoDouble; else -> IOMemento.IoNothing }
                    val sz = when (ai) { 25 -> 2; 26 -> 4; 27 -> 8; 24 -> 1; else -> 0 }
                    add(open, open+sz, tag); np + sz
                }
                else -> { add(open, open, IOMemento.IoNothing); np }
            }
            return np
        }

        var pos = 0
        while (pos < n) pos = parseItem(pos)

        val total = count / 2
        val sb = spanBuf; val tb = tagBuf; val c = total
        val spans: Series<Twin<Int>> = c j { k -> sb[k*2] j sb[k*2+1] }
        val tags:  Series<IOMemento>  = c j { k -> tb[k]!! }

        return buildIndex(spans, tags, src)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Dispatcher
// ═══════════════════════════════════════════════════════════════════

fun scannerFor(syntax: Syntax): ConfixScanner = when (syntax) {
    Syntax.JSON -> JsonScanner
    Syntax.CBOR -> CborScanner
    Syntax.YAML -> YamlScanner
}

fun scan(src: Series<Char>, syntax: Syntax): ConfixCursor = scannerFor(syntax).scan(src)
