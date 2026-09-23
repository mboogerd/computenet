package civictech.cell.data

import java.io.Serializable

/**
 * A declared, total, serializable ordering over rows `R` (KAGG-R-11): an
 * ordered non-empty list of [SortKey] columns, each with its own [Direction],
 * followed by a **mandatory** ascending tie-break that is unique per distinct
 * row (typically the row's id).
 *
 * Why a value type and not a raw `Comparator` lambda: the spec is carried
 * *inside* the accumulator (`TreeMap<R, Int>` with this as its comparator,
 * [Aggregators.topKBy]), so it rides through `GroupByCell` snapshot/restore
 * and must serialize; `[24-AGG-01]`/`[24-OP-GROUPBY-05]` require the order to
 * be total; and a declared column list is what a page or a query frontend can
 * describe and generate.
 *
 * **Totality is enforced twice** (KAGG-R-12). Construction refuses an empty
 * column list and a missing tie-break. A tie-break that is declared but not
 * actually unique cannot be detected here — it surfaces at
 * [Aggregators.topKBy]'s `insert`, which throws rather than merging two
 * distinct rows that compare equal.
 *
 * **Selectors must be serializable functions.** A Kotlin 2.x lambda is compiled
 * via `invokedynamic` and is *not* `Serializable` unless annotated
 * `@JvmSerializableLambda`; a property or function reference (`Row::salary`)
 * is. [SortKey] refuses a non-serializable selector at construction, because
 * the failure would otherwise surface only at the first snapshot.
 *
 * Rows are compared for identity with `equals` (the insert-time totality check
 * distinguishes "the same row again" from "a different row the spec cannot
 * tell apart"), so `R` should have value equality — a data class.
 */
class SortSpec<R>(
    columns: List<SortKey<R, *>>,
    tieBreak: SortKey<R, *>?,
) : Comparator<R>, Serializable {

    enum class Direction { ASC, DESC }

    /** One sort column: a serializable selector plus its direction. */
    class SortKey<R, V : Comparable<V>>(
        val selector: (R) -> V,
        val direction: Direction = Direction.ASC,
    ) : Serializable {
        init {
            require(selector is Serializable) {
                "SortKey selector must be Serializable (a property/function reference, or a " +
                    "@JvmSerializableLambda lambda): SortSpec travels inside snapshotted accumulators"
            }
        }

        internal fun compare(a: R, b: R): Int {
            val c = selector(a).compareTo(selector(b))
            return if (direction == Direction.DESC) -c else c
        }

        companion object {
            fun <R, V : Comparable<V>> asc(selector: (R) -> V): SortKey<R, V> = SortKey(selector, Direction.ASC)
            fun <R, V : Comparable<V>> desc(selector: (R) -> V): SortKey<R, V> = SortKey(selector, Direction.DESC)
        }
    }

    val columns: List<SortKey<R, *>> = columns.toList()
    val tieBreak: SortKey<R, *>

    init {
        require(this.columns.isNotEmpty()) {
            "SortSpec needs at least one column to define a total order"
        }
        requireNotNull(tieBreak) {
            "SortSpec is not total without a tie-break: supply a final ascending key unique per distinct row (e.g. its id)"
        }
        require(tieBreak.direction == Direction.ASC) {
            "SortSpec tie-break must be ascending (it makes the order total, it does not rank)"
        }
        this.tieBreak = tieBreak
    }

    override fun compare(a: R, b: R): Int {
        for (column in columns) {
            val c = column.compare(a, b)
            if (c != 0) return c
        }
        return tieBreak.compare(a, b)
    }

    companion object {
        private const val serialVersionUID = 1L

        /** `SortSpec.by(desc(Row::salary), asc(Row::name), tieBreak = Row::id)`. */
        fun <R, T : Comparable<T>> by(vararg columns: SortKey<R, *>, tieBreak: (R) -> T): SortSpec<R> =
            SortSpec(columns.toList(), SortKey.asc(tieBreak))
    }
}
