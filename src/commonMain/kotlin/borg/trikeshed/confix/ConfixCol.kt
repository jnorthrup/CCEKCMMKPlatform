@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

/**
 * ConfixCol — taxonomic key family for Confix document fragments.
 *
 * Sibling of TextK, ColK, ManifoldK, LifeK under OpK.
 * Every scanned element is a FacetedRow<ConfixCol<*>> —
 * a row keyed by these four facets, with ColumnMeta describing
 * the shape of each column within the RowVec.
 *
 * The Children facet carries a nested Cursor (recursive descent).
 * The child Cursor is itself MetaSeries<FacetedRow<ConfixCol<*>>, RowVec>
 * — the same root shape at depth+1.
 */
sealed class ConfixCol<out R> : OpK<R>() {

    /** Element start offset in source. */
    data object Open : ConfixCol<Int>()

    /** Element end offset in source (inclusive of last content char). */
    data object Close : ConfixCol<Int>()

    /** Type discriminator — collapsed 8-case Tag enum into IOMemento. */
    data object Tag : ConfixCol<TypeMemento>()

    /** Nested child elements — lazy Cursor produced at scan time.
     *  When Tag is IoObject, children hold key-value pairs (alternating rows).
     *  When Tag is IoArray, children hold indexed elements.
     *  When Tag is a scalar, children is empty (size=0). */
    data object Children : ConfixCol<Cursor>()
}

/** ColumnMeta for a Confix fragment RowVec — the four standard columns. */
val CONFiX_COL_META: Series<ColumnMeta> = 4 j { i: Int ->
    when (i) {
        0 -> ColumnMeta("open", IOMemento.IoInt)
        1 -> ColumnMeta("close", IOMemento.IoInt)
        2 -> ColumnMeta("tag", IOMemento.IoObject)
        3 -> ColumnMeta("children", IOMemento.IoObject)
        else -> error("4 columns")
    }
}

/** Convenience: construct a FacetedRow for one Confix element. */
fun confixRow(open: Int, close: Int, tag: TypeMemento, children: Cursor): FacetedRow<ConfixCol<*>> =
    ConfixCol.Open j { op: ConfixCol<*> ->
        @Suppress("UNCHECKED_CAST")
        when (op) {
            ConfixCol.Open     -> open      as Any?
            ConfixCol.Close    -> close     as Any?
            ConfixCol.Tag      -> tag       as Any?
            ConfixCol.Children -> children  as Any?
        }
    }

/** Typed accessors for a Confix faceted row. */
@Suppress("UNCHECKED_CAST")
val FacetedRow<ConfixCol<*>>.open: Int                  get() = b(ConfixCol.Open) as Int
@Suppress("UNCHECKED_CAST")
val FacetedRow<ConfixCol<*>>.close: Int                 get() = b(ConfixCol.Close) as Int
@Suppress("UNCHECKED_CAST")
val FacetedRow<ConfixCol<*>>.confTag: TypeMemento        get() = b(ConfixCol.Tag) as TypeMemento
@Suppress("UNCHECKED_CAST")
val FacetedRow<ConfixCol<*>>.children: Cursor            get() = b(ConfixCol.Children) as Cursor

/** A Confix fragment = one node in the document DAG. */
typealias ConfixNode = FacetedRow<ConfixCol<*>>

// ═══════════════════════════════════════════════════════════════════
//  ConfixIndexK — scanner output domain (sibling of ConfixCol)
// ═══════════════════════════════════════════════════════════════════

/**
 * ConfixIndexK — facet keys for the scanned document index.
 *
 * The scanner produces a single ConfixIndex (= FacetedRow<ConfixIndexK<*>>).
 * Consumers project facets lazily — no consumer is forced through tree build
 * or reification. Like CharStr's TextK, ONE domain, MANY facets.
 *
 * Spans + Tags are filled eagerly by the scanner (one pass, cheap).
 * All other facets are lazy projections computed on first access and memoized.
 * The index IS the source of truth — Cursor, path table, reified values are all
 * derived views over the same flat positional data.
 */
sealed class ConfixIndexK<out R> : OpK<R>() {

    // ── Flat positional data (eager — one pass over source) ──

    /** All element (open j close) spans as a Series<Twin<Int>>. */
    data object Spans : ConfixIndexK<Series<Twin<Int>>>()

    /** All element type tags as a Series<IOMemento>. Aligned 1:1 with Spans. */
    data object Tags : ConfixIndexK<Series<IOMemento>>()

    // ── Single-element accessors ──────────────────────────────

    data class Open(val idx: Int)  : ConfixIndexK<Int>()
    data class Close(val idx: Int) : ConfixIndexK<Int>()
    data class Tag(val idx: Int)   : ConfixIndexK<IOMemento>()

    // ── Derived structural data (lazy — computed on first access) ──

    /** Nesting depth per element (0 = root). */
    data object Depths : ConfixIndexK<Series<Int>>()

    /**
     * Direct child indices for element at [idx].
     * Each child is an element whose span is strictly inside [idx]'s span
     * and not contained by any intermediate element.
     */
    data class DirectChildren(val idx: Int) : ConfixIndexK<Series<Int>>()

    // ── Tree projections (lazy, memoized) ─────────────────────

    /** Full tree as a Cursor. Built via nestChildren α over Spans+Tags. */
    data object TreeCursor : ConfixIndexK<Cursor>()

    // ── Path / navigation ─────────────────────────────────────

    /**
     * Name→child-index mapping for object elements.
     * For element [idx] (must be IoObject), returns MetaSeries<String, Int>
     * mapping key names to child element indices.
     */
    data class KeyToChild(val idx: Int) : ConfixIndexK<Series<Int>>()

    // ── Cursor interop ────────────────────────────────────────

    /**
     * Project element [idx] as a RowVec with the standard 4 columns
     * (open, close, tag, children). Children column is lazy TreeCursor
     * for this element's direct children only.
     */
    data class AsRowVec(val idx: Int) : ConfixIndexK<RowVec>()
}

/** The scanner output: MetaSeries over ConfixIndexK facets.
 *  FacetedRow<ConfixIndexK<*>> — each facet is a lazy projection
 *  over the flat positional data. */
typealias ConfixIndex = FacetedRow<ConfixIndexK<*>>

/** The Confix document root: Series<RowVec> — same underlying type as Cursor.
 *  Each row is a Confix element stored column-major with ColumnMeta↻ suppliers.
 *  A ConfixNode is a faceted view over a single RowVec. */
typealias ConfixCursor = Cursor

/** Lift a RowVec into a ConfixNode (faceted access over columnar storage).
 *  Row columns map: 0=open, 1=close, 2=tag, 3=children. */
fun RowVec.asConfixNode(): ConfixNode = ConfixCol.Open j { op: ConfixCol<*> ->
    @Suppress("UNCHECKED_CAST")
    when (op) {
        ConfixCol.Open     -> this[0].a as Any?
        ConfixCol.Close    -> this[1].a as Any?
        ConfixCol.Tag      -> this[2].a as Any?
        ConfixCol.Children -> this[3].a as Any?
    }
}
