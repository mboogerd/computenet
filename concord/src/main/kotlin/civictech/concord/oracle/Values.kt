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
 * - a **view-type-aware equivalence** ([equalForView]) so a set view ([SET_VIEW_TYPES])
 *   compares order-insensitively while an ordered `list`/`value` view compares
 *   structurally.
 */
object Values {

    /**
     * The subset of [VIEW_TYPES] whose payload is a SET, and which therefore compares
     * order-independently (Concord P2) — the volatile `set-view` and its durable
     * binding `journal-set-view` alike. Consulted by [canonicalForView].
     *
     * A journaled set view left out of this set compares **order-sensitively**, which
     * is a real divergence and not a cosmetic one: the driver sorts a set by
     * `Value.toString()` (`KernelCatalog.readView`) and this layer by [compare],
     * orders that agree on homogeneous sets and need not agree on mixed-type ones.
     */
    val SET_VIEW_TYPES: Set<String> = setOf("set-view", "journal-set-view")

    /**
     * The catalog ids of terminal view cells (CONCORD-PLAN cell-catalog §Views) —
     * every binding of a view, volatile or journaled.
     *
     * **`journal-set-view` is in this set on purpose (computenet-yh6.1.10).**
     * `computenet-yh6.1.9` taught [BatchOracle] the durable set bindings but
     * deliberately left this constant alone, reasoning that widening what a `'*'`
     * quantifier ranges over changes the meaning of every existing `'*'` check rather
     * than only making a new one expressible. That call was revisited against the
     * corpus and decided the other way, for three reasons:
     *
     * - **The blast radius is zero, measured rather than assumed.** Only two consumers
     *   generalise over this set: `Checks.viewCells` (from which
     *   `late-join-equals-early` infers its early/late pair when the scenario names
     *   neither) and [BatchOracle.allViewValues] (over which
     *   `incremental-equals-batch view: '*'` quantifies). The one corpus scenario that
     *   uses either form is the generative `24-GEN-01`, whose `ScenarioGenerator` draws
     *   terminals from `set-view`/`count-view`/`value-view` alone and its operator
     *   vocabulary from the volatile set-algebra core — no generated instance can
     *   contain a journaled view. The two scenarios that do carry one
     *   (`DUR-ATOMIC-01`, `DUR-SNAPTAIL-01`) name their views explicitly in every check.
     * - **The narrow form fails silently; the wide one fails loudly.** With
     *   `journal-set-view` outside the set, `incremental-equals-batch view: '*'` on a
     *   durable scenario skips the journaled view, and on a scenario whose *only* view
     *   is journaled — `DUR-ATOMIC-01`'s shape exactly — it quantifies over nothing and
     *   passes having read nothing. "The check had nothing to look at" reading as "the
     *   property held" is the one outcome this corpus does not accept (compare
     *   `Checks.observationsWholeWaves`, which fails rather than passes on an empty
     *   observation stream). The widened form's failure mode is an ordinary mismatch.
     * - **It is a catalog fact, not a policy knob.** This constant answers "which cells
     *   are terminal views?", and a `journal-set-view` is one. Since
     *   `computenet-yh6.1.9` the oracle can fold and render it
     *   ([BatchOracle.VIEW_TYPES]), so the honest answer no longer costs anything.
     *
     * The residual this closes was recorded in `BatchOracle.DURABLE_SET_VIEW`'s KDoc,
     * which now describes both halves as closed. `concord/corpus/DISPUTES.md`'s
     * G-59/C-9 entry still narrates the pre-widening state as computenet-yh6.1.9's
     * scope; it is a historical record of that item and was left alone.
     */
    val VIEW_TYPES: Set<String> =
        SET_VIEW_TYPES + setOf("map-view", "count-view", "value-view", "list-view")

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
     * Canonical form for comparison under a view's semantic: a set view (any member of
     * [SET_VIEW_TYPES], journaled or not) is order-independent, so its list payload is
     * sorted; every other view keeps its structure (ordered lists, maps —
     * [Value.MapVal] equality is already key-order-insensitive).
     */
    fun canonicalForView(v: Value, viewType: String?): Value =
        if (viewType != null && viewType in SET_VIEW_TYPES && v is Value.ListVal) {
            Value.ListVal(sortedList(v.items))
        } else {
            v
        }

    /** View-type-aware equality: equal after [canonicalForView] on both sides. */
    fun equalForView(a: Value, b: Value, viewType: String?): Boolean =
        canonicalForView(a, viewType) == canonicalForView(b, viewType)
}
