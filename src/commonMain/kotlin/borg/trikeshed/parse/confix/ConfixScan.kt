@file:Suppress("NonAsciiCharacters", "LocalVariableName", "UNCHECKED_CAST")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

private val COL_META: Series<`ColumnMeta↻`> = 4 j { c: Int ->
    when (c) {
        0 -> ColumnMeta("open", IOMemento.IoInt)
        1 -> ColumnMeta("close", IOMemento.IoInt)
        2 -> ColumnMeta("tag", IOMemento.IoObject)
        3 -> ColumnMeta("children", IOMemento.IoObject)
        else -> error("4 columns")
    } as `ColumnMeta↻`
}

data class FlatIndex(
    val spans: Series<Twin<Int>>,
    val tags: Series<IOMemento>,
    val depths: Series<Int>,
    val childOf: (Int) -> Series<Int>,
)

fun scanJson(src: Series<Char>): Pair<Cursor, FlatIndex> {
    val n = src.size
    data class P(val open: Int, val tag: IOMemento)
    val sOpen = ChunkedMutableSeries<Int>()
    val sClose = ChunkedMutableSeries<Int>()
    val sTag = ChunkedMutableSeries<IOMemento>()
    val stack = ChunkedMutableSeries<P>()
    var inQ = false; var esc = false

    fun push(o: Int, t: IOMemento) { stack.add(P(o, t)) }
    fun pop(c: Int) { if (stack.size == 0) return; val p=stack.removeAt(stack.size-1); sOpen.add(p.open); sClose.add(c); sTag.add(p.tag) }
    fun add(o: Int, c: Int, t: IOMemento) { sOpen.add(o); sClose.add(c); sTag.add(t) }

    var i = 0
    while (i < n) { val c=src[i]; when { inQ->when{esc->esc=false;c=='"'->{inQ=false;pop(i)};else->{}} else->when(c){ '{'->push(i,IOMemento.IoObject);'['->push(i,IOMemento.IoArray);'}'->pop(i);']'->pop(i);'"'->{push(i,IOMemento.IoString);inQ=true};'t'->{if(i+3<n&&src[i+1]=='r'&&src[i+2]=='u'&&src[i+3]=='e'){add(i,i+3,IOMemento.IoBoolean);i+=3}};'f'->{if(i+4<n&&src[i+1]=='a'&&src[i+2]=='l'&&src[i+3]=='s'&&src[i+4]=='e'){add(i,i+4,IOMemento.IoBoolean);i+=4}};'n'->{if(i+3<n&&src[i+1]=='u'&&src[i+2]=='l'&&src[i+3]=='l'){add(i,i+3,IOMemento.IoNothing);i+=3}};'-','+',in '0'..'9'->{val s=i;while(i<n){val ch=src[i];if(ch !in '0'..'9'&&ch!='.'&&ch!='e'&&ch!='E'&&ch!='+'&&ch!='-')break;i++};add(s,i-1,IOMemento.IoDouble);continue} } }; i++ }
    while (stack.size > 0) { val p = stack.removeAt(stack.size - 1); add(p.open, n-1, p.tag) }

    val total = sOpen.size
    val spans = total j { k: Int -> sOpen[total-1-k] j sClose[total-1-k] }
    val tags = total j { k: Int -> sTag[total-1-k] }
    val depths = total j { idx: Int -> val si=spans[idx];(0 until total).count{k:Int->k!=idx&&spans[k].a<si.a&&spans[k].b>si.b} }

    val childOf = { idx: Int -> val si=spans[idx];val o=si.a;val cl=si.b;val td=depths[idx]+1;val b=IntArray(total);var ct=0;for(k in 0 until total){if(k==idx)continue;val sk=spans[k];if(sk.a>o&&sk.b<cl&&depths[k]==td)b[ct++]=k};val c=ct;val a=b;c j { out:Int->a[out] } }

    val rvC = arrayOfNulls<RowVec>(total)
    fun row(i: Int): RowVec { rvC[i]?.let{return it};val sp=spans[i];val tg=tags[i];val dc=childOf(i);val cc:Cursor=dc.size j { k:Int->row(dc[k]) };val rv=(4 j { c:Int->when(c){0->(sp.a as Any?)j COL_META[0];1->(sp.b as Any?)j COL_META[1];2->(tg as Any?)j COL_META[2];3->(cc as Any?)j COL_META[3];else->error("4")}})as RowVec;rvC[i]=rv;return rv }

    val rc=(0 until total).count{k:Int->depths[k]==0};val ri=IntArray(rc);var rx=0
    for(i in 0 until total)if(depths[i]==0)ri[rx++]=i
    val tree = rc j { k:Int->row(ri[k]) }
    return tree to FlatIndex(spans, tags, depths, childOf)
}

fun scan(src: Series<Char>): Cursor = scanJson(src).first

object JsonScanner {
    fun scan(src: Series<Char>) = scanJson(src).first
    fun index(src: Series<Char>) = scanJson(src)
}

fun scannerFor(syntax: Syntax) = JsonScanner
