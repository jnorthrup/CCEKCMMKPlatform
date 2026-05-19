@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

enum class Syntax { JSON, CBOR, YAML }

fun parse(text: CharSequence, syntax: Syntax = Syntax.JSON): Cursor {
    val series: Series<Char> = text.toSeries()
    return scannerFor(syntax).scan(series)
}

fun CharSequence.toSeries(): Series<Char> {
    val n = this.length
    return n j { i: Int -> this[i] }
}
