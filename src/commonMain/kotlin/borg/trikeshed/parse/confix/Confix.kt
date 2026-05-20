@file:Suppress("NonAsciiCharacters")
package borg.trikeshed.parse.confix
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

object Confix {
    enum class Syntax {
        JSON { override fun scan(src: Series<Byte>): Cursor = Element(src).scan0().a
               override fun recognize(first: Byte): Boolean = first.toInt().toChar() in setOf('{', '[', '"') },
        CBOR { override fun scan(src: Series<Byte>): Cursor = Element(src).scanCbor().a
               override fun recognize(first: Byte): Boolean = true },
        YAML { override fun scan(src: Series<Byte>): Cursor = Element(src).scan0().a
               override fun recognize(first: Byte): Boolean = first.toInt().toChar() !in setOf('{', '[') };

        abstract fun scan(src: Series<Byte>): Cursor
        abstract fun recognize(first: Byte): Boolean
    }

    fun dispatch(bytes: ByteArray): Cursor {
        val n = bytes.size
        val src: Series<Byte> = n j { i: Int -> bytes[i] }
        val first = src[0]
        val syntax = Syntax.entries.first { it.recognize(first) }
        return syntax.scan(src)
    }
}
