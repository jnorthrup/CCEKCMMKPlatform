@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.charstr.*

interface ConfixDecoder {
    fun decode(open: Int, close: Int, tag: IOMemento, src: Series<Char>): Any?
}

// ═══════════════════════════════════════════════════════════════════
//  JSON Decoder
// ═══════════════════════════════════════════════════════════════════

object JsonDecoder : ConfixDecoder {
    override fun decode(open: Int, close: Int, tag: IOMemento, src: Series<Char>): Any? =
        when (tag) {
            IOMemento.IoString  -> decodeJsonString(src, open, close)
            IOMemento.IoDouble  -> decodeJsonNumber(src, open, close)
            IOMemento.IoBoolean -> src[open] == 't'
            IOMemento.IoNothing -> null
            else -> null
        }
}

private fun decodeJsonString(src: Series<Char>, open: Int, close: Int): CharStr {
    val first = src[open]; val last = src[close]
    // Strip quotes if present; content includes escape sequences as-is.
    // Escape conversion is the consumer's concern — the parser contract
    // is span accuracy, not string transformation.
    if (first == '"' && last == '"' && close > open + 1)
        return CharStr(src, open + 1, close - 1)
    return CharStr(src, open, close)
}

private fun decodeJsonNumber(src: Series<Char>, open: Int, close: Int): Any? {
    val len = close - open + 1
    val ca = CharArray(len); var k = 0
    while (k < len) { ca[k] = src[open + k]; k++ }
    val s = ca.concatToString()
    return s.toLongOrNull() ?: s.toIntOrNull() ?: s.toDoubleOrNull() ?: s
}

// ═══════════════════════════════════════════════════════════════════
//  YAML Decoder
// ═══════════════════════════════════════════════════════════════════

object YamlDecoder : ConfixDecoder {
    override fun decode(open: Int, close: Int, tag: IOMemento, src: Series<Char>): Any? =
        when (tag) {
            IOMemento.IoString  -> CharStr(src, open, close)
            IOMemento.IoDouble  -> decodeJsonNumber(src, open, close)
            IOMemento.IoBoolean -> decodeYamlBool(src, open, close)
            IOMemento.IoNothing -> null
            else -> null
        }
}

private fun decodeYamlBool(src: Series<Char>, open: Int, close: Int): Boolean {
    val len = close - open + 1
    val ca = CharArray(len); var i = 0
    while (i < len) { ca[i] = src[open + i]; i++ }
    val low = ca.concatToString().trim().lowercase()
    return low == "true" || low == "yes" || low == "on"
}

// ═══════════════════════════════════════════════════════════════════
//  CBOR Decoder
// ═══════════════════════════════════════════════════════════════════

object CborDecoder : ConfixDecoder {
    override fun decode(open: Int, close: Int, tag: IOMemento, src: Series<Char>): Any? =
        when (tag) {
            IOMemento.IoString -> CharStr(src, cborPayloadStart(src, open), close)
            IOMemento.IoDouble -> decodeCborNum(src, open)
            IOMemento.IoBoolean -> (src[open].code and 0x1F) == 21
            IOMemento.IoBytes  -> decodeCborBytes(src, open, close)
            IOMemento.IoNothing -> null
            else -> null
        }
}

private fun cborPayloadStart(src: Series<Char>, open: Int): Int {
    val ai = src[open].code and 0x1F
    return open + 1 + when (ai) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> 0 }
}

private fun decodeCborNum(src: Series<Char>, open: Int): Number {
    val ib = src[open].code and 0xFF
    val mt = ib ushr 5; val ai = ib and 0x1F
    if (mt == 0 || mt == 1) {
        val v = readCborUint(src, open + 1, ai)
        return if (mt == 0) v else -(v.toDouble()) - 1.0
    }
    return 0.0
}

private fun readCborUint(src: Series<Char>, p: Int, ai: Int): Long = when (ai) {
    in 0..23 -> ai.toLong()
    24 -> src[p].code.toLong() and 0xFF
    25 -> (((src[p].code and 0xFF) shl 8) or (src[p+1].code and 0xFF)).toLong()
    26 -> (((src[p].code and 0xFF).toLong() shl 24) or ((src[p+1].code and 0xFF).toLong() shl 16) or
           ((src[p+2].code and 0xFF).toLong() shl 8) or (src[p+3].code and 0xFF).toLong())
    27 -> { var v = 0L; var k = 0; while (k < 8) { v = (v shl 8) or (src[p+k].code and 0xFF).toLong(); k++ }; v }
    else -> -1L
}

private fun decodeCborBytes(src: Series<Char>, open: Int, close: Int): ByteArray {
    val p = cborPayloadStart(src, open)
    val len = close - p + 1
    if (len <= 0) return ByteArray(0)
    return ByteArray(len) { i -> src[p + i].code.toByte() }
}

fun decoderFor(syntax: Syntax): ConfixDecoder = when (syntax) {
    Syntax.JSON -> JsonDecoder
    Syntax.CBOR -> CborDecoder
    Syntax.YAML -> YamlDecoder
}
