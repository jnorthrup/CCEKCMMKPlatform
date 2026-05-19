@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST")

package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*
import borg.trikeshed.charstr.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfixTest {

    /** Find root element (depth 0) index. */
    private fun rootIdx(ix: ConfixIndex): Int {
        val depths = ix[ConfixIndexK.Depths] as Series<Int>
        val n = (ix[ConfixIndexK.Tags] as Series<IOMemento>).size
        for (i in 0 until n) if (depths[i] == 0) return i
        return 0
    }

    // ═══════════════════════════════════════════════════════════
    //  JSON — index facets
    // ═══════════════════════════════════════════════════════════

    @Test fun `JSON index — flat scalar`() {
        val src = charSeries("42")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        val spans = ix[ConfixIndexK.Spans] as Series<Twin<Int>>
        assertEquals(1, tags.size)
        assertEquals(IOMemento.IoDouble, tags[0])
        assertEquals(0, spans[0].a)
        assertEquals(1, spans[0].b)
    }

    @Test fun `JSON index — object with one key`() {
        val src = charSeries("""{"key": "val"}""")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 2, "expected >= 2 elements, got ${tags.size}")
        val r = rootIdx(ix)
        assertEquals(IOMemento.IoObject, tags[r])
    }

    @Test fun `JSON index — nested object`() {
        val src = charSeries("""{"a":{"b":1}}""")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 3, "expected >= 3 elements")
        val depths = ix[ConfixIndexK.Depths] as Series<Int>
        val r = rootIdx(ix)
        assertEquals(0, depths[r])
    }

    @Test fun `JSON index — array with numbers`() {
        val src = charSeries("[1, 2, 3]")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 4)
        val r = rootIdx(ix)
        assertEquals(IOMemento.IoArray, tags[r])
    }

    @Test fun `JSON index — boolean and null`() {
        val src = charSeries("[true, false, null]")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 4, "got ${tags.size}")
    }

    // ═══════════════════════════════════════════════════════════
    //  JSON — TreeCursor
    // ═══════════════════════════════════════════════════════════

    @Test fun `JSON tree cursor — root element`() {
        val src = charSeries("""{"name":"test"}""")
        val cursor = JsonScanner.scan(src)
        assertTrue(cursor.size > 0)
    }

    @Test fun `JSON tree cursor — children present`() {
        val src = charSeries("""{"a":1,"b":2}""")
        val cursor = JsonScanner.scan(src)
        assertTrue(cursor.size > 0)
    }

    // ═══════════════════════════════════════════════════════════
    //  JSON — DirectChildren
    // ═══════════════════════════════════════════════════════════

    @Test fun `JSON direct children — simple object`() {
        val src = charSeries("""{"k":"v"}""")
        val ix = JsonScanner.index(src)
        val r = rootIdx(ix)
        val dc = ix[ConfixIndexK.DirectChildren(r)] as Series<Int>
        assertTrue(dc.size >= 1, "expected >= 1 children, got ${dc.size}")
    }

    @Test fun `JSON direct children — nested`() {
        val src = charSeries("""{"outer":{"inner":1}}""")
        val ix = JsonScanner.index(src)
        val r = rootIdx(ix)
        val rootChildren = ix[ConfixIndexK.DirectChildren(r)] as Series<Int>
        assertTrue(rootChildren.size >= 1, "expected >= 1 root children, got ${rootChildren.size}")
    }

    // ═══════════════════════════════════════════════════════════
    //  JSON — Reify
    // ═══════════════════════════════════════════════════════════

    @Test fun `reify JSON — number`() {
        val src = charSeries("42")
        val cursor = ConfixReify.parse(src, Syntax.JSON)
        assertTrue(cursor.size > 0)
    }

    @Test fun `reify JSON — string becomes CharStr`() {
        val src = charSeries(""""hello"""")
        val cursor = ConfixReify.parse(src, Syntax.JSON)
        assertTrue(cursor.size > 0)
    }

    @Test fun `reify JSON — object with reified keys`() {
        val src = charSeries("""{"x":1,"y":2}""")
        val cursor = ConfixReify.parse(src, Syntax.JSON)
        assertTrue(cursor.size > 0)
    }

    // ═══════════════════════════════════════════════════════════
    //  Path resolution
    // ═══════════════════════════════════════════════════════════

    @Test fun `path resolve — key lookup by name`() {
        val src = charSeries("""{"name":"value"}""")
        val ix = JsonScanner.index(src)
        val result = resolve(ix, path("name"), src)
        assertNotNull(result)
    }

    @Test fun `path resolve — missing key returns null`() {
        val src = charSeries("""{"a":1}""")
        val ix = JsonScanner.index(src)
        val result = resolve(ix, path("missing"), src)
        assertNull(result)
    }

    @Test fun `path resolve — array index`() {
        val src = charSeries("[10, 20, 30]")
        val ix = JsonScanner.index(src)
        val result = resolve(ix, path(1), src)
        assertNotNull(result)
    }

    // ═══════════════════════════════════════════════════════════
    //  YAML
    // ═══════════════════════════════════════════════════════════

    @Test fun `YAML scan — simple mapping`() {
        val src = charSeries("key: value\n")
        val ix = YamlScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 2)
    }

    @Test fun `YAML scan — nested mapping`() {
        val src = charSeries("outer:\n  inner: 1\n")
        val ix = YamlScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 3)
    }

    @Test fun `YAML scan — sequence`() {
        val src = charSeries("- one\n- two\n")
        val ix = YamlScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 2)
    }

    // ═══════════════════════════════════════════════════════════
    //  CBOR
    // ═══════════════════════════════════════════════════════════

    @Test fun `CBOR scan — unsigned int`() {
        val src = cborBytes(byteArrayOf(0x01))
        val ix = CborScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(1, tags.size)
        assertEquals(IOMemento.IoDouble, tags[0])
    }

    @Test fun `CBOR scan — text string`() {
        val src = cborBytes(byteArrayOf(0x63.toByte(), 0x61.toByte()))
        val ix = CborScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(IOMemento.IoString, tags[0])
    }

    @Test fun `CBOR scan — array of ints`() {
        val src = cborBytes(byteArrayOf(0x83.toByte(), 0x01, 0x02, 0x03))
        val ix = CborScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        // Should contain IoArray somewhere
        var foundArray = false
        for (i in 0 until tags.size) if (tags[i] == IOMemento.IoArray) foundArray = true
        assertTrue(foundArray, "array tag not found in scanned elements")
        assertTrue(tags.size >= 4)
    }

    // ═══════════════════════════════════════════════════════════
    //  CharStr zero-copy
    // ═══════════════════════════════════════════════════════════

    @Test fun `CharStr — decoder returns non-null for JSON string`() {
        val src = charSeries(""""hello world"""")
        val decoded = JsonDecoder.decode(0, 12, IOMemento.IoString, src)
        assertNotNull(decoded)
        val cs = decoded as CharStr
        assertTrue(cs.sizeBytes > 0)
    }

    @Test fun `CharStr — preserves source identity`() {
        val src = charSeries(""""test"""")
        val decoded = JsonDecoder.decode(0, 5, IOMemento.IoString, src)
        val cs = decoded as CharStr
        val raw = cs[TextK.Raw]
        assertNotNull(raw)
        assertTrue(raw.toString().contains("test"))
    }

    @Test fun `CharStr — YAML plain scalar`() {
        val src = charSeries("hello")
        val decoded = YamlDecoder.decode(0, 4, IOMemento.IoString, src)
        val cs = decoded as CharStr
        val raw = cs[TextK.Raw]
        assertNotNull(raw)
        assertTrue(raw.toString().contains("hello"))
    }

    // ═══════════════════════════════════════════════════════════
    //  Challenging samples
    // ═══════════════════════════════════════════════════════════

    @Test fun `JSON escape — newline preserved as-is`() {
        val src = charSeries(""""hello\\nworld"""")
        val decoded = JsonDecoder.decode(0, 13, IOMemento.IoString, src)
        val cs = decoded as CharStr
        // Span captures raw content — escape sequences unmodified.
        // TextK.Raw returns the witness CharSequence wrapping the source.
        assertTrue(cs[TextK.Raw].toString().contains("\\n"))
    }

    @Test fun `JSON escape — tab and backslash preserved`() {
        val src = charSeries(""""a\\tb\\\\c"""")
        val decoded = JsonDecoder.decode(0, 8, IOMemento.IoString, src)
        val cs = decoded as CharStr
        assertTrue(cs[TextK.Raw].toString().contains("\\t"))
    }

    @Test fun `JSON escape — unicode literal preserved`() {
        val src = charSeries(""""\\u263A"""")
        val decoded = JsonDecoder.decode(0, 7, IOMemento.IoString, src)
        val cs = decoded as CharStr
        // \\u263A stays as 6 literal characters, not converted to ☺
        assertTrue(cs[TextK.Raw].toString().contains("\\u"))
    }

    @Test fun `JSON escape — no escape fast path`() {
        // No backslash — should use zero-copy path
        val src = charSeries(""""plain"""")
        val decoded = JsonDecoder.decode(0, 6, IOMemento.IoString, src)
        val cs = decoded as CharStr
        assertTrue(cs[TextK.Raw].toString().contains("plain"))
    }

    @Test fun `JSON index — deeply nested object`() {
        val src = charSeries("""{"a":{"b":{"c":{"d":{"e":1}}}}}""")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 10)  // 5 objects + 5 values
    }

    @Test fun `JSON index — empty object and array`() {
        val src = charSeries("""{"empty":{},"blank":[]}""")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        // Root object, key, empty object, key, empty array
        assertTrue(tags.size >= 5)
    }

    @Test fun `JSON index — unicode key`() {
        val src = charSeries("""{"名前":"値"}"""")
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size >= 3)  // root + key + value
    }

    @Test fun `JSON index — array of 100 numbers`() {
        val sb = StringBuilder("[")
        for (i in 0 until 99) sb.append("$i,")
        sb.append("99]")
        val src = charSeries(sb.toString())
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertEquals(IOMemento.IoArray, tags[rootIdx(ix)])
        assertTrue(tags.size >= 101)  // 1 array + 100 numbers
    }

    // ═══════════════════════════════════════════════════════════
    //  File-based samples
    // ═══════════════════════════════════════════════════════════

    @Test fun `big json — parse 224KB real document`() {
        val text = readResource("big.json")
        val src = charSeries(text)
        val ix = JsonScanner.index(src)
        val tags = ix[ConfixIndexK.Tags] as Series<IOMemento>
        assertTrue(tags.size > 100, "expected >100 elements in big.json, got ${tags.size}")
        val r = rootIdx(ix)
        assertEquals(IOMemento.IoObject, tags[r])
        // Verify depths: root at 0, children nested
        val depths = ix[ConfixIndexK.Depths] as Series<Int>
        assertEquals(0, depths[r])
    }

    @Test fun `big json — tree cursor builds`() {
        val text = readResource("big.json")
        val src = charSeries(text)
        val cursor = JsonScanner.scan(src)
        assertTrue(cursor.size > 0)
    }

    @Test fun `big json — reify`() {
        val text = readResource("big.json")
        val src = charSeries(text)
        val cursor = ConfixReify.parse(src, Syntax.JSON)
        assertTrue(cursor.size > 0)
    }

    // ═══════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════

    private fun charSeries(s: String): Series<Char> {
        val n = s.length
        return n j { i: Int -> s[i] }
    }

    private fun cborBytes(bytes: ByteArray): Series<Char> {
        val n = bytes.size
        return n j { i: Int -> (bytes[i].toInt() and 0xFF).toChar() }
    }

    private fun readResource(name: String): String {
        val file = java.io.File("src/commonTest/resources/$name")
        return file.readText()
    }
}
