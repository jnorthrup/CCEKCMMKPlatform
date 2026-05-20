@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

enum class Syntax(val scan: (Series<Char>) -> Cursor) {
    JSON({ scanJson(it).a }),
    CBOR({ scanJson(it).a }),
    YAML({ scanJson(it).a }),
}

fun parse(text: CharSequence, syntax: Syntax = Syntax.JSON): Cursor =
    syntax.scan(text.toSeries())

fun CharSequence.toSeries(): Series<Char> {
    val n = this.length
    return n j { i: Int -> this[i] }
}
