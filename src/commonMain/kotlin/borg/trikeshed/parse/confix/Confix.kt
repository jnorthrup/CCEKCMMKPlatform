@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

enum class Syntax {
    JSON {
        override fun scan(src: Series<Byte>): Cursor = scan0(src).a
        override fun recognize(first: Byte): Boolean = first.toInt().toChar() in setOf('{', '[', '"')
    },
    CBOR {
        override fun scan(src: Series<Byte>): Cursor = scanCbor(src).a
        override fun recognize(first: Byte): Boolean = true
    },
    YAML {
        override fun scan(src: Series<Byte>): Cursor = scan0(src).a
        override fun recognize(first: Byte): Boolean = first.toInt().toChar() !in setOf('{', '[')
    };

    abstract fun scan(src: Series<Byte>): Cursor
    abstract fun recognize(first: Byte): Boolean

    companion object {
        fun dispatch(bytes: ByteArray): Cursor {
            val n = bytes.size
            val src: Series<Byte> = n j { i: Int -> bytes[i] }
            val first = src[0]
            return entries.first { it.recognize(first) }.scan(src)
        }
    }
}
