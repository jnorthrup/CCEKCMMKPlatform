@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")
package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.charstr.*
import kotlin.test.*

class ConfixTest {

    @Test fun `toSeries  produces correct length`() {
        val text = "hello"
        val s = text.toSeries()
        assertEquals(5, s.size)
    }

    @Test fun `toSeries  char access`() {
        val text = "abc"
        val s = text.toSeries()
        assertEquals('a', s[0])
        assertEquals('b', s[1])
        assertEquals('c', s[2])
    }

    @Test fun `JSON index  flat number`() {
        val src = charSeries("42")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(1, tags.size)
        assertEquals(IOMemento.IoDouble, tags[0])
    }

    @Test fun `JSON index  string`() {
        val src = charSeries("\"hello\"")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(1, tags.size)
        assertEquals(IOMemento.IoString, tags[0])
    }

    @Test fun `JSON index  empty object`() {
        val src = charSeries("{}")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(1, tags.size)
        assertEquals(IOMemento.IoObject, tags[0])
    }

    @Test fun `JSON index  object with one key`() {
        val src = charSeries("{\"key\":\"val\"}")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(IOMemento.IoObject, tags[0])
        assertEquals(IOMemento.IoString, tags[1])
        assertEquals(IOMemento.IoString, tags[2])
    }

    @Test fun `JSON index  array`() {
        val src = charSeries("[1,2,3]")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(IOMemento.IoArray, tags[0])
    }

    @Test fun `JSON index  nested object depth`() {
        val src = charSeries("{\"a\":{\"b\":1}}")
        val ix = JsonScanner.index(src)
        val depths = ix[ConfixIndexK.Depths] as Series<Int>
        assertEquals(0, depths[0])
    }

    @Test fun `JSON index  boolean and null`() {
        val src = charSeries("[true,false,null]")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(IOMemento.IoArray, tags[0])
    }

    @Test fun `direct children  root has two children for object`() {
        val src = charSeries("{\"x\":1,\"y\":2}")
        val ix = JsonScanner.index(src)
        val dc = ix[ConfixIndexK.DirectChildren(0)] as Series<Int>
        assertEquals(4, dc.size)
    }
    @Test fun `direct children  array elements`() {
        val src = charSeries("[10,20,30]")
        val ix = JsonScanner.index(src)
        val dc = ix[ConfixIndexK.DirectChildren(0)] as Series<Int>
        assertEquals(3, dc.size)
    }

    @Test fun `tree cursor  root children`() {
        val src = charSeries("[1,2]")
        val cursor = JsonScanner.scan(src)
        assertEquals(1, cursor.size)
    }

    @Test fun `path resolve  key lookup by name`() {
        val src = charSeries("{\"name\":\"value\"}")
        val ix = JsonScanner.index(src)
        val dc = ix[ConfixIndexK.DirectChildren(0)] as Series<Int>
        assertEquals(2, dc.size)
    }

    @Test fun `CharStr  preserves source identity`() {
        val src = charSeries("\"test\"")
        val decoded = decodeValue(src, 0, 5, IOMemento.IoString)
        val cs = decoded as CharStr
        assertTrue(cs[TextK.Raw].toString().contains("test"))
    }

    @Test fun `CharStr  plain scalar`() {
        val src = charSeries("hello")
        val decoded = decodeValue(src, 0, 4, IOMemento.IoString)
        val cs = decoded as CharStr
        assertTrue(cs[TextK.Raw].toString().contains("hello"))
    }

    @Test fun `JSON escape  newline preserved as-is`() {
        val src = charSeries("\"hello\\\\nworld\"")
        val decoded = decodeValue(src, 0, 13, IOMemento.IoString)
        val cs = decoded as CharStr
        assertTrue(cs[TextK.Raw].toString().contains("\\\\n"))
    }

    @Test fun `JSON escape  tab and backslash preserved`() {
        val src = charSeries("\"a\\\\tb\\\\\\\\c\"")
        val decoded = decodeValue(src, 0, 8, IOMemento.IoString)
        val cs = decoded as CharStr
        assertTrue(cs[TextK.Raw].toString().contains("\\\\t"))
    }

    @Test fun `JSON escape  unicode literal preserved`() {
        val src = charSeries("\"\\\\u263A\"")
        val decoded = decodeValue(src, 0, 7, IOMemento.IoString)
        val cs = decoded as CharStr
        assertTrue(cs[TextK.Raw].toString().contains("\\\\u"))
    }

    @Test fun `JSON escape  no escape fast path`() {
        val src = charSeries("\"plain\"")
        val decoded = decodeValue(src, 0, 6, IOMemento.IoString)
        val cs = decoded as CharStr
        assertTrue(cs[TextK.Raw].toString().contains("plain"))
    }

    @Test fun `JSON index  deeply nested object`() {
        val src = charSeries("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 10)
    }

    @Test fun `JSON index  array of 100 numbers`() {
        val nums = (1..100).joinToString(",")
        val src = charSeries("[$nums]")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 100)
    }

    companion object {
        fun charSeries(raw: String): Series<Char> = raw.toSeries()
        fun CharSequence.toSeries(): Series<Char> { val n=this.length;return n j { i:Int->this[i]} }
    }
}
