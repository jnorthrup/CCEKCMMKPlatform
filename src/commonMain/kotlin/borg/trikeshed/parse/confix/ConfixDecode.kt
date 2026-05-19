@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.charstr.*

fun decodeText(src: Series<Char>, open: Int, close: Int): CharStr {
    val first = src[open]; val last = src[close]
    if (first == '"' && last == '"' && close > open + 1) return CharStr(src, open + 1, close - 1)
    return CharStr(src, open, close)
}

fun decodeValue(src: Series<Char>, open: Int, close: Int, tag: IOMemento): Any? = when (tag) {
    IOMemento.IoString  -> decodeText(src, open, close)
    IOMemento.IoBoolean -> src[open] == 't'
    IOMemento.IoNothing -> null
    IOMemento.IoDouble  -> decodeText(src, open, close)
    else -> null
}
