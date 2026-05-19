@file:Suppress("UNCHECKED_CAST")
package borg.trikeshed.parse.confix
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.charstr.*

fun reifyConfix(ix: ConfixIndex, src: Series<Char>): Cursor {
    val depths = ix[ConfixIndexK.Depths] as Series<Int>
    val n = depths.size
    val rc = (0 until n).count { k: Int -> depths[k] == 0 }
    val ri = IntArray(rc); var rx = 0
    for (i in 0 until n) if (depths[i] == 0) ri[rx++] = i
    return rc j { k: Int -> reifyElement(ri[k], ix, src) }
}

private val metaVal: `ColumnMeta↻` = { ColumnMeta("value", IOMemento.IoObject) }

private fun reifyElement(idx: Int, ix: ConfixIndex, src: Series<Char>): RowVec {
    val open = ix[ConfixIndexK.Open(idx)] as Int
    val close = ix[ConfixIndexK.Close(idx)] as Int
    val tag = ix[ConfixIndexK.Tag(idx)] as IOMemento
    val value = decodeValue(src, open, close, tag)
    return (value j metaVal) as RowVec
}
