@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")

package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.charstr.*

object ConfixReify {

    fun reify(index: ConfixIndex, src: Series<Char>, decoder: ConfixDecoder): Cursor {
        val n = (index[ConfixIndexK.Tags] as Series<IOMemento>).size
        if (n == 0) return 0 j { _ -> error("empty") as RowVec }
        val depths = index[ConfixIndexK.Depths] as Series<Int>
        val ri = IntArray(n); var rc = 0
        for (i in 0 until n) if (depths[i] == 0) ri[rc++] = i
        return rc j { k: Int -> reifyElement(ri[k], index, src, decoder) }
    }

    private fun reifyElement(idx: Int, index: ConfixIndex, src: Series<Char>, decoder: ConfixDecoder): RowVec {
        val tag = index[ConfixIndexK.Tag(idx)] as IOMemento
        return when (tag.kind) {
            0 -> reifyContainer(idx, index, src, decoder, IOMemento.IoObject)
            1 -> reifyContainer(idx, index, src, decoder, IOMemento.IoArray)
            else -> {
                val open  = index[ConfixIndexK.Open(idx)] as Int
                val close = index[ConfixIndexK.Close(idx)] as Int
                reifyScalar(open, close, tag, src, decoder)
            }
        }
    }

    private fun reifyContainer(idx: Int, index: ConfixIndex, src: Series<Char>, decoder: ConfixDecoder, containerTag: IOMemento): RowVec {
        val children = index[ConfixIndexK.DirectChildren(idx)] as Series<Int>
        val subCursor: Cursor = children.size j { k: Int -> reifyElement(children[k], index, src, decoder) }
        val sc = subCursor
        val metaTag: `ColumnMeta↻` = { ColumnMeta("tag", containerTag) }
        val metaChildren: `ColumnMeta↻` = { ColumnMeta("children", IOMemento.IoObject) }
        val metaCount: `ColumnMeta↻` = { ColumnMeta("childCount", IOMemento.IoInt) }
        val metaIdx: `ColumnMeta↻` = { ColumnMeta("sourceIndex", IOMemento.IoInt) }
        return (4 j { c: Int ->
            when (c) {
                0 -> (null as Any?) j metaTag
                1 -> (sc as Any?)   j metaChildren
                2 -> (children.size as Any?) j metaCount
                3 -> (idx as Any?) j metaIdx
                else -> error("4 columns")
            }
        }) as RowVec
    }

    private fun reifyScalar(open: Int, close: Int, tag: IOMemento, src: Series<Char>, decoder: ConfixDecoder): RowVec {
        val value = decoder.decode(open, close, tag, src)
        val v = value
        val metaVal: `ColumnMeta↻` = { ColumnMeta("value", tag) }
        val metaTag2: `ColumnMeta↻` = { ColumnMeta("tag", tag) }
        val metaOpen: `ColumnMeta↻` = { ColumnMeta("open", IOMemento.IoInt) }
        val metaClose: `ColumnMeta↻` = { ColumnMeta("close", IOMemento.IoInt) }
        return (4 j { c: Int ->
            when (c) {
                0 -> (v as Any?) j metaVal
                1 -> (null as Any?) j metaTag2
                2 -> (open as Any?) j metaOpen
                3 -> (close as Any?) j metaClose
                else -> error("4 columns")
            }
        }) as RowVec
    }

    fun parse(src: Series<Char>, syntax: Syntax): Cursor {
        val ix = scannerFor(syntax).index(src)
        return reify(ix, src, decoderFor(syntax))
    }
}
