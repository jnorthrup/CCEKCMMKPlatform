@file:Suppress("NonAsciiCharacters", "FunctionName", "PropertyName")

package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

/**
 * Confix — the compose point.
 *
 * All six axes are imported from sibling files:
 *   ConfixCol    — Axis 2: token shape invariant (ConfixCol, ConfixNode, ConfixCursor)
 *   ConfixScan   — Axis 1: scanners (JsonScanner, YamlScanner, CborScanner)
 *   ConfixDecode — Axis 3: decoders (JsonDecoder, YamlDecoder, CborDecoder)
 *   ConfixReify  — Axis 4: reifier (Scanner × Decoder → Cursor)
 *   ConfixPath   — Axis 5: navigation (JsPath, resolve, step)
 *   ConfixScope  — Axis 6: lifecycle + fanout (ConfixScope, ConfixFanout)
 *
 * This file: Syntax discriminator, source constructors, convenience compose.
 */

/** Syntax discriminator — unchanged. */
enum class Syntax { JSON, CBOR, YAML }

// ── Source constructors ──────────────────────────────────────────

fun jsonSource(text: CharSequence): ConfixSource =
    ConfixSource(Syntax.JSON, text.toSeries())

fun yamlSource(text: CharSequence): ConfixSource =
    ConfixSource(Syntax.YAML, text.toSeries())

fun cborSource(bytes: ByteArray): ConfixSource {
    val n = bytes.size
    val ca = CharArray(n)
    var i = 0
    while (i < n) { ca[i] = (bytes[i].toInt() and 0xFF).toChar(); i++ }
    val series: Series<Char> = n j { k: Int -> ca[k] }
    return ConfixSource(Syntax.CBOR, series)
}

/** Construct a Series<ConfixSource> from varargs. */
fun sources(vararg src: ConfixSource): Series<ConfixSource> {
    val n = src.size
    return n j { i: Int -> src[i] }
}

// ── Convenience compose: scan + reify in one call ────────────────

/** Parse a CharSequence into a reified Cursor. */
fun parse(text: CharSequence, syntax: Syntax = Syntax.JSON): Cursor {
    val series: Series<Char> = text.toSeries()
    return ConfixReify.parse(series, syntax)
}

/** CharSequence → Series<Char> without stdlib. */
private fun CharSequence.toSeries(): Series<Char> {
    val n = this.length
    return n j { i: Int -> this[i] }
}
