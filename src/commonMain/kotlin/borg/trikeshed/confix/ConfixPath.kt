@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

/**
 * ConfixPath — Axis 5: Navigation over ConfixIndex.
 */

typealias JsPathElement = Either<String, Int>
typealias JsPath = Series<JsPathElement>

fun path(vararg segs: Any): JsPath {
    val n = segs.size
    return n j { i ->
        when (val s = segs[i]) {
            is String -> Either.left(s)
            is Int    -> Either.right(s)
            else      -> error("path segment must be String or Int, was ${s::class}")
        }
    }
}

fun List<Any?>.toJsPath(): JsPath {
    val n = this.size
    if (n == 0) return 0 j { _: Int -> Either.left("") }
    return n j { i: Int ->
        val seg = this[i]
        when (seg) {
            is Int    -> Either.right(seg)
            is Number -> Either.right(seg.toInt())
            else      -> Either.left(seg?.toString() ?: "")
        }
    }
}

fun resolve(index: ConfixIndex, path: JsPath, src: Series<Char>): ConfixIndex? {
    if (path.size == 0) return index

    val tags  = index[ConfixIndexK.Tags] as Series<IOMemento>
    val n = tags.size
    if (n == 0) return null

    val depths = index[ConfixIndexK.Depths] as Series<Int>
    var currentIdx = -1
    for (i in 0 until n) if (depths[i] == 0) { currentIdx = i; break }
    if (currentIdx < 0) return null

    var i = 0
    while (i < path.size) {
        val seg = path[i]
        val children = index[ConfixIndexK.DirectChildren(currentIdx)] as Series<Int>
        @Suppress("UNCHECKED_CAST")
        when {
            seg is Either.Left<*> -> {
                val keyName = (seg as Either.Left<String>).value
                var found = false
                for (k in 0 until children.size / 2) {
                    val keyIdx = children[k * 2]
                    val keyOpen  = index[ConfixIndexK.Open(keyIdx)] as Int
                    val keyClose = index[ConfixIndexK.Close(keyIdx)] as Int
                    val len = keyClose - keyOpen + 1
                    val ca = CharArray(len)
                    for (x in 0 until len) ca[x] = src[keyOpen + x]
                    var keyText = ca.concatToString()
                    // Strip quotes if present (scanner spans include quotes for strings)
                    if (keyText.length >= 2 && keyText[0] == '"' && keyText[keyText.length - 1] == '"')
                        keyText = keyText.substring(1, keyText.length - 1)
                    if (keyText == keyName) {
                        currentIdx = children[k * 2 + 1]
                        found = true
                        break
                    }
                }
                if (!found) return null
            }
            seg is Either.Right<*> -> {
                val idx = (seg as Either.Right<Int>).value
                if (idx < 0 || idx >= children.size) return null
                currentIdx = children[idx]
            }
        }
        i++
    }
    return index
}
