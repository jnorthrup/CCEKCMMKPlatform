package borg.trikeshed.cursor

import borg.trikeshed.lib.*

// ── Type Memento ────────────────────────────────────────────────

/**
 * TypeMemento — the type evidence carried by column metadata.
 * IOMemento enum dispatch is the sealed hierarchy for wire format.
 */
interface TypeMemento {
    val networkSize: Int?
}

/** Standard IO mementos — fixed-width types enable O(1) random access. */
enum class IOMemento(override val networkSize: Int?) : TypeMemento {
    IoBoolean(1),
    IoInt(4),
    IoLong(8),
    IoFloat(4),
    IoDouble(8),
    IoString(null),     // variable width — requires offset index
    IoLocalDate(10),
    IoInstant(null),
    IoNothing(0),
}

// ── Column Metadata ─────────────────────────────────────────────

/** ColumnMeta = Join<CharSequence, TypeMemento> — name × type, nothing else. */
typealias ColumnMeta = Join<CharSequence, TypeMemento>

/** Lazy column metadata supplier — metadata is part of the algebra, not an afterthought. */
typealias `ColumnMeta↻` = () -> ColumnMeta

/** Construct column metadata inline. */
fun ColumnMeta(name: CharSequence, type: TypeMemento): ColumnMeta = name j type

val ColumnMeta.name: CharSequence get() = a
val ColumnMeta.type: TypeMemento get() = b

// ── RowVec ──────────────────────────────────────────────────────

/**
 * RowVec = Series2<Any?, ColumnMeta↻>
 *
 * Row-shaped value view plus metadata supplier.
 * The cursor's row is a split series: values separated from metadata.
 * This is the columnar storage format.
 */
typealias RowVec = Series2<Any?, `ColumnMeta↻`>

// ── Cursor ──────────────────────────────────────────────────────

/**
 * Cursor = Series<RowVec>
 *
 * Indexed composition of RowVec.
 * The dataframe-shaped specialization of the same Join/Series algebra.
 */
typealias Cursor = Series<RowVec>
