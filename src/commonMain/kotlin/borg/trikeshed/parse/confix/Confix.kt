@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

enum class Syntax {
    JSON {
        override fun scan(src: Series<Char>): Cursor = json(src)
        override fun recognize(first: Char): Boolean = first == '{' || first == '[' || first == '"'
    },
    CBOR {
        override fun scan(src: Series<Char>): Cursor = json(src)
        override fun recognize(first: Char): Boolean = true
    },
    YAML {
        override fun scan(src: Series<Char>): Cursor = json(src)
        override fun recognize(first: Char): Boolean = first != '{' && first != '['
    };

    abstract fun scan(src: Series<Char>): Cursor
    abstract fun recognize(first: Char): Boolean

    companion object {
        fun parse(text: CharSequence, syntax: Syntax = JSON): Cursor =
            syntax.scan(text.toSeries())

        fun CharSequence.toSeries(): Series<Char> {
            val n = this.length
            return n j { i: Int -> this[i] }
        }
    }
}
