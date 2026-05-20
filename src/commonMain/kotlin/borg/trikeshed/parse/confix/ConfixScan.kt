@file:Suppress("NonAsciiCharacters", "LocalVariableName", "UNCHECKED_CAST")

package borg.trikeshed.parse.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

val COL_META: Series<`ColumnMeta↻`> = 4 j { c: Int ->
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

fun scan0(src: Series<Byte>): Join<Cursor, FlatIndex> {
    val n = src.size
    val chars: Series<Char> = n j { i: Int -> src[i].toInt().toChar() }
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
    while (i < n) { val c=chars[i]; when { inQ->when{esc->esc=false;c=='"'->{inQ=false;pop(i)};else->{}} else->when(c){ '{'->push(i,IOMemento.IoObject);'['->push(i,IOMemento.IoArray);'}'->pop(i);']'->pop(i);'"'->{push(i,IOMemento.IoString);inQ=true};'t'->{if(i+3<n&&chars[i+1]=='r'&&chars[i+2]=='u'&&chars[i+3]=='e'){add(i,i+3,IOMemento.IoBoolean);i+=3}};'f'->{if(i+4<n&&chars[i+1]=='a'&&chars[i+2]=='l'&&chars[i+3]=='s'&&chars[i+4]=='e'){add(i,i+4,IOMemento.IoBoolean);i+=4}};'n'->{if(i+3<n&&chars[i+1]=='u'&&chars[i+2]=='l'&&chars[i+3]=='l'){add(i,i+3,IOMemento.IoNothing);i+=3}};'-','+',in '0'..'9'->{val s=i;while(i<n){val ch=chars[i];if(ch !in '0'..'9'&&ch!='.'&&ch!='e'&&ch!='E'&&ch!='+'&&ch!='-')break;i++};add(s,i-1,IOMemento.IoDouble);continue} } }; i++ }
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
    return tree j FlatIndex(spans, tags, depths, childOf)
}

fun scanCbor(src: Series<Byte>): Join<Cursor, FlatIndex> {
    val n = src.size
    val sOpen = ChunkedMutableSeries<Int>()
    val sClose = ChunkedMutableSeries<Int>()
    val sTag = ChunkedMutableSeries<IOMemento>()

    fun add(o: Int, c: Int, t: IOMemento) { sOpen.add(o); sClose.add(c); sTag.add(t) }

    fun readLen(p: Int, ai: Int): Pair<Long, Int> = when (ai) {
        in 0..23 -> ai.toLong() to p
        24 -> (src[p].toLong() and 0xFF) to (p + 1)
        25 -> (((src[p].toInt() and 0xFF) shl 8) or (src[p+1].toInt() and 0xFF)).toLong() to (p + 2)
        26 -> (((src[p].toInt() and 0xFF) shl 24) or ((src[p+1].toInt() and 0xFF) shl 16) or ((src[p+2].toInt() and 0xFF) shl 8) or (src[p+3].toInt() and 0xFF)).toLong() to (p + 4)
        27 -> { var v=0L; var k=0; while(k<8){ v=(v shl 8) or (src[p+k].toLong() and 0xFF); k++ }; v to (p+8) }
        31 -> -1L to p
        else -> error("bad cbor ai $ai")
    }

    fun parseItem(p: Int): Int {
        val open = p; val ib = src[p].toInt() and 0xFF; val mt = ib ushr 5; val ai = ib and 0x1F; var np = p + 1
        np = when (mt) {
            0,1 -> { val (_, np1) = readLen(np, ai); add(open, np1-1, IOMemento.IoDouble); np1 }
            2 -> { val (len, np1) = readLen(np, ai); if(len<0) np1 else { add(open, np1+len.toInt()-1, IOMemento.IoBytes); np1+len.toInt() } }
            3 -> { val (len, np1) = readLen(np, ai); if(len<0) np1 else { add(open, np1+len.toInt()-1, IOMemento.IoString); np1+len.toInt() } }
            4 -> { val (len,np1)=readLen(np,ai); var kp=np1; if(len<0L)while(kp<n&&(src[kp].toInt()and 0xFF)!=0xFF)kp=parseItem(kp);else{var k=0L;while(k<len){kp=parseItem(kp);k++}};if(kp<n&&len<0L)kp++;add(open,kp-1,IOMemento.IoArray);kp }
            5 -> { val (len,np1)=readLen(np,ai); var kp=np1; if(len<0L)while(kp<n&&(src[kp].toInt()and 0xFF)!=0xFF){kp=parseItem(kp);kp=parseItem(kp)}else{var k=0L;while(k<len){kp=parseItem(kp);kp=parseItem(kp);k++}};if(kp<n&&len<0L)kp++;add(open,kp-1,IOMemento.IoObject);kp }
            6 -> { val (_,np1)=readLen(np,ai); parseItem(np1) }
            7 -> { val tag=when(ai){20->IOMemento.IoBoolean;21->IOMemento.IoBoolean;22,23->IOMemento.IoNothing;25,26,27->IOMemento.IoDouble;else->IOMemento.IoNothing};val sz=when(ai){25->2;26->4;27->8;24->1;else->0};add(open,open+sz,tag);np+sz }
            else -> { add(open, open, IOMemento.IoNothing); np }
        }
        return np
    }

    var pos = 0
    while (pos < n) pos = parseItem(pos)

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
    return tree j FlatIndex(spans, tags, depths, childOf)
}

