package civictech.concord.oracle

import civictech.concord.value.Value

/**
 * Neutral [Value] utilities shared by the batch oracle and the check evaluators
 * (CONCORD-PLAN §1.4). Pure — imports `civictech.concord.value` only, never
 * `civictech.cell.*`.
 *
 * The load-bearing operations are:
 * - a **total order** over [Value] ([compare]) so a set can be rendered as a
 *   deterministic sorted [Value.ListVal] (sets are order-independent — Concord P2 —
 *   so the canonical wire form of a set is its sorted list);
 * - a **view-type-aware equivalence** ([equalForView]) so a `set-view` compares
 *   order-insensitively while an ordered `list`/`value` view compares structurally.
 */
object Values {

    /** The catalog ids of terminal view cells (CONCORD-PLAN cell-catalog §Views). */
    val VIEW_TYPES: Set<String> = setOf("set-view", "map-view", "count-view", "value-view", "list-view")

    /** A stable textual rendering — used for map keys (which must be strings) and as the total-order tiebreak. */
    fun render(v: Value): String = when (v) {
        is Value.StrVal -> v.value
        is Value.IntVal -> v.value.toString()
        is Value.RealVal -> v.value.toString()
        is Value.BoolVal -> v.value.toString()
        Value.NullVal -> "null"
        is Value.ListVal -> v.items.joinToString(prefix = "[", postfix = "]", separator = ",") { render(it) }
        is Value.MapVal -> v.entries.toSortedMap()
            .entries.joinToString(prefix = "{", postfix = "}", separator = ",") { "${it.key}=${render(it.value)}" }
    }

    /** Numeric projection (int/real/parseable-string), or null when the value is not a number. */
    fun asDouble(v: Value): Double? = when (v) {
        is Value.IntVal -> v.value.toDouble()
        is Value.RealVal -> v.value
        is Value.StrVal -> v.value.toDoubleOrNull()
        else -> null
    }

    /** Integer projection (int/whole-real/parseable-string), or null. */
    fun asLong(v: Value): Long? = when (v) {
        is Value.IntVal -> v.value
        is Value.RealVal -> v.value.toLong()
        is Value.StrVal -> v.value.toLongOrNull()
        else -> null
    }

    private fun rank(v: Value): Int = when (v) {
        Value.NullVal -> 0
        is Value.BoolVal -> 1
        is Value.IntVal, is Value.RealVal -> 2
        is Value.StrVal -> 3
        is Value.ListVal -> 4
        is Value.MapVal -> 5
    }

    /**
     * A total order over [Value]: numbers numerically, strings lexicographically,
     * everything else by rank then by [render]. Deterministic regardless of the
     * insertion order of a set (the order-independence the oracle relies on).
     */
    fun compare(a: Value, b: Value): Int {
        val ra = rank(a)
        val rb = rank(b)
        if (ra != rb) return ra.compareTo(rb)
        return when {
            ra == 2 -> asDouble(a)!!.compareTo(asDouble(b)!!)
            a is Value.StrVal && b is Value.StrVal -> a.value.compareTo(b.value)
            a is Value.BoolVal && b is Value.BoolVal -> a.value.compareTo(b.value)
            a is Value.ListVal && b is Value.ListVal -> {
                val n = minOf(a.items.size, b.items.size)
                for (i in 0 until n) {
                    val c = compare(a.items[i], b.items[i])
                    if (c != 0) return c
                }
                a.items.size.compareTo(b.items.size)
            }
            else -> render(a).compareTo(render(b))
        }
    }

    /** A set rendered as a deterministically sorted list of [Value]s. */
    fun sortedList(members: Collection<Value>): List<Value> = members.sortedWith(::compare)

    /**
     * Canonical form for comparison under a view's semantic: a `set-view` is
     * order-independent, so its list payload is sorted; every other view keeps its
     * structure (ordered lists, maps — [Value.MapVal] equality is already
     * key-order-insensitive).
     */
    fun canonicalForView(v: Value, viewType: String?): Value =
        if (viewType == "set-view" && v is Value.ListVal) Value.ListVal(sortedList(v.items)) else v

    /** View-type-aware equality: equal after [canonicalForView] on both sides. */
    fun equalForView(a: Value, b: Value, viewType: String?): Boolean =
        canonicalForView(a, viewType) == canonicalForView(b, viewType)
}
