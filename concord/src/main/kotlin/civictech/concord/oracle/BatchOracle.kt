package civictech.concord.oracle

import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.DisconnectStep
import civictech.concord.schema.Expect
import civictech.concord.schema.LinkSpec
import civictech.concord.schema.Scenario
import civictech.concord.value.Value

/** Raised when a scenario's topology is outside the batch oracle's remit (e.g. a feedback cycle). */
class OracleUnsupported(message: String) : RuntimeException(message)

/**
 * The **batch oracle** (CONCORD-PLAN §1.4, W1-B): a pure-Kotlin implementation of
 * the cell-catalog + function-catalog semantics that folds a scenario's
 * *accepted-op multiset* (from `script` + effective `graph`) into the expected
 * final view value for each view cell. This is what `incremental-equals-batch`
 * compares a driver's `readView` against.
 *
 * **Order-independence is the point (Concord P2).** A source's fold is over the
 * multiset of its accepted ops; the schema fixes same-cell op order (file order),
 * so the sequential per-source fold is well-defined, and every operator is a pure
 * function of its inlets' folds — so the whole computation is independent of
 * delivery interleaving.
 *
 * The oracle is neutral: it imports `civictech.concord.{schema,value}` and the
 * sibling [Functions]/[Values] only — never `civictech.cell.*`.
 *
 * ## Documented semantic assumptions (catalog gaps at v1, flagged for the spec)
 * - **`count` vs `presence-count`.** cell-catalog.md contrasts "distinct-element
 *   count of a set stream" with "currently-present count", but v1 pins no scenario
 *   to disambiguate history-vs-presence; the oracle folds both to the **current
 *   membership cardinality**. Needs a dedicated scenario + a stream-history model
 *   to separate them.
 * - **`group-by` aggregator.** The catalog lists `fn (key-of), agg`, but [CellSpec]
 *   carries a single `fn` field. The oracle reads `fn` as the key extractor and
 *   defaults the per-group aggregator to **`count`** (cardinality). Selecting a
 *   different aggregator needs a second descriptor param (schema-change ticket).
 * - **`quorum-set` k-of-n.** The witnessing shape is not frozen (cell-catalog.md
 *   note 4) and `k` is not expressible on [CellSpec] v1, so the oracle folds a
 *   quorum-set as a plain set (every `add` admits). k-of-n admission needs the
 *   descriptor param before the oracle can honour it.
 * - **`window` / `partition`.** The window descriptor is deferred (note 5);
 *   `partition` is specified to equal its unpartitioned twin. The oracle folds both
 *   as **pass-through** of the upstream set.
 * - **`join` family element shape.** With no pilot pinning the joined element, the
 *   oracle treats elements as pairs `[k, v]` (`key-of` = first component): `join`
 *   emits `[k, leftVal, rightVal]`, `lookup-join` emits `[leftElem, rightVal]`,
 *   `semi-join` keeps left elements whose key is present on the right.
 * - **`map-source` op payload.** `put` accepts either a `[key, value]` pair or a
 *   `{key:, value:}` object; `remove` takes the bare key.
 */
class BatchOracle(private val scenario: Scenario) {

    private val graph = scenario.graph
        ?: throw OracleUnsupported("scenario ${scenario.id} has no graph (generative scenarios are W4-C)")

    private val cellsById: Map<String, CellSpec> = graph.cells.associateBy { it.id }

    /** Base topology plus accepted `connect` steps, minus accepted `disconnect` steps. */
    private val effectiveLinks: List<LinkSpec> = buildList {
        addAll(graph.links)
        for (step in scenario.script) when (step) {
            is ConnectStep -> if (step.expect != Expect.REJECTED) {
                add(LinkSpec(step.from, step.to, step.inlet, step.outlet, step.role))
            }
            is DisconnectStep -> if (step.expect != Expect.REJECTED) {
                removeAll { it.from == step.from && it.to == step.to && it.inlet == step.inlet }
            }
            else -> {}
        }
    }

    private val inputsByCell: Map<String, List<LinkSpec>> = effectiveLinks.groupBy { it.to }

    private val memo = HashMap<String, Fold>()
    private val visiting = HashSet<String>()

    /** The expected final value of view cell [viewId]. */
    fun view(viewId: String): Value {
        val cell = cellsById[viewId] ?: throw OracleUnsupported("no cell '$viewId' in scenario ${scenario.id}")
        return renderView(cell.type, foldOf(viewId))
    }

    /** Every view cell's expected final value (drives `incremental-equals-batch view: '*'`). */
    fun allViewValues(): Map<String, Value> =
        graph.cells.filter { it.type in Values.VIEW_TYPES }.associate { it.id to view(it.id) }

    // --- fold computation ---------------------------------------------------

    private fun foldOf(id: String): Fold {
        memo[id]?.let { return it }
        if (!visiting.add(id)) throw OracleUnsupported("feedback cycle at cell '$id' — not a batch-oracle topology")
        val cell = cellsById[id] ?: throw OracleUnsupported("unknown cell '$id'")
        val result = if (inputsByCell[id].isNullOrEmpty()) sourceFold(cell) else operatorFold(cell)
        visiting.remove(id)
        memo[id] = result
        return result
    }

    private fun sourceFold(cell: CellSpec): Fold {
        val ops = scenario.script.filterIsInstance<ApplyStep>().filter { it.on == cell.id }
        return when (cell.type) {
            "set-source", "quorum-set" -> {
                val members = LinkedHashSet<Value>()
                for (op in ops) repeat(op.times ?: 1) {
                    when (op.op) {
                        "add" -> members.add(op.value ?: error("${cell.id}: add needs a value"))
                        "remove" -> members.remove(op.value)
                        else -> error("${cell.id} (${cell.type}): unsupported op '${op.op}'")
                    }
                }
                Fold.SetF(members)
            }

            "counter-source", "pn-counter" -> {
                var c = 0L
                for (op in ops) {
                    val n = (op.times ?: 1).toLong()
                    when (op.op) {
                        "increment" -> c += n
                        "decrement" -> c -= n
                        else -> error("${cell.id} (${cell.type}): unsupported op '${op.op}'")
                    }
                }
                Fold.ScalarF(Value.IntVal(c))
            }

            "list-source" -> {
                val items = ArrayList<Value>()
                for (op in ops) repeat(op.times ?: 1) {
                    when (op.op) {
                        "append" -> items.add(op.value ?: error("${cell.id}: append needs a value"))
                        "insert" -> {
                            // value is [index, elem]
                            val v = op.value
                            require(v is Value.ListVal && v.items.size == 2) { "${cell.id}: insert needs [index, elem]" }
                            val idx = (Values.asLong(v.items[0]) ?: 0L).toInt().coerceIn(0, items.size)
                            items.add(idx, v.items[1])
                        }
                        "remove" -> items.remove(op.value)
                        else -> error("${cell.id} (list-source): unsupported op '${op.op}'")
                    }
                }
                Fold.ListF(items)
            }

            "map-source" -> {
                val entries = LinkedHashMap<Value, Value>()
                for (op in ops) {
                    when (op.op) {
                        "put" -> {
                            val (k, v) = keyValue(op.value ?: error("${cell.id}: put needs a value"))
                            entries[k] = v // last-writer-wins per key
                        }
                        "remove", "remove-key" -> entries.remove(op.value)
                        else -> error("${cell.id} (map-source): unsupported op '${op.op}'")
                    }
                }
                Fold.MapF(entries)
            }

            "keyed-set" -> {
                // Set partitioned by an extracted key -> map key -> its member set.
                val partitions = LinkedHashMap<Value, LinkedHashSet<Value>>()
                for (op in ops) repeat(op.times ?: 1) {
                    val el = op.value ?: error("${cell.id}: ${op.op} needs a value")
                    val k = Functions.keyOf(el)
                    val bucket = partitions.getOrPut(k) { LinkedHashSet() }
                    when (op.op) {
                        "add" -> bucket.add(el)
                        "remove" -> bucket.remove(el)
                        else -> error("${cell.id} (keyed-set): unsupported op '${op.op}'")
                    }
                }
                Fold.MapF(partitions.mapValues { (_, s) -> Value.ListVal(Values.sortedList(s)) })
            }

            else -> throw OracleUnsupported("source type '${cell.type}' has no oracle fold")
        }
    }

    private fun operatorFold(cell: CellSpec): Fold {
        val ins = inputsByCell[cell.id].orEmpty()
        fun single(): Fold = foldOf(ins.singleOrNull()?.from ?: error("${cell.id}: expected one inlet, got ${ins.size}"))
        return when (cell.type) {
            "filter" -> Fold.SetF(asSet(single()).filterTo(LinkedHashSet(), Functions.predicate(fn(cell))))
            "map" -> mapFold(single(), fn(cell))
            "flatmap" -> flatMapFold(asSet(single()), fn(cell))
            "union" -> Fold.SetF(LinkedHashSet(asSet(inlet(ins, "left", 0)) + asSet(inlet(ins, "right", 1))))
            "intersect" -> Fold.SetF(asSet(inlet(ins, "left", 0)).filterTo(LinkedHashSet()) { it in asSet(inlet(ins, "right", 1)) })
            "join" -> joinFold(cell, ins)
            "semi-join" -> semiJoinFold(cell, ins)
            "lookup-join" -> lookupJoinFold(cell, ins)
            "group-by" -> groupByFold(cell, single())
            "combine-latest" -> Fold.ScalarF(Functions.aggregate(fn(cell), ins.map { asScalar(foldOf(it.from)) }))
            "count", "presence-count" -> Fold.ScalarF(Value.IntVal(asSet(single()).size.toLong()))
            "window", "partition" -> single() // pass-through (documented v1 semantics)
            // Views are pass-throughs of their upstream fold; renderView converts to Value.
            in Values.VIEW_TYPES -> single()
            else -> throw OracleUnsupported("operator type '${cell.type}' has no oracle fold")
        }
    }

    private fun mapFold(input: Fold, fnId: String): Fold {
        val t = Functions.transform(fnId)
        return when (input) {
            is Fold.SetF -> Fold.SetF(input.members.mapTo(LinkedHashSet(), t))
            is Fold.ListF -> Fold.ListF(input.items.map(t))
            is Fold.ScalarF -> Fold.ScalarF(t(input.value))
            is Fold.MapF -> Fold.MapF(input.entries.mapValues { t(it.value) })
        }
    }

    private fun flatMapFold(input: Set<Value>, fnId: String): Fold {
        val t = Functions.transform(fnId)
        val out = LinkedHashSet<Value>()
        for (x in input) {
            when (val mapped = t(x)) {
                is Value.ListVal -> out.addAll(mapped.items)
                else -> out.add(mapped)
            }
        }
        return Fold.SetF(out)
    }

    private fun joinFold(cell: CellSpec, ins: List<LinkSpec>): Fold {
        val left = asSet(inlet(ins, "left", 0))
        val right = asSet(inlet(ins, "right", 1))
        val out = LinkedHashSet<Value>()
        for (l in left) for (r in right) {
            if (Values.compare(Functions.keyOf(l), Functions.keyOf(r)) == 0) {
                out.add(Value.ListVal(listOf(Functions.keyOf(l), Functions.valueOf(l), Functions.valueOf(r))))
            }
        }
        return Fold.SetF(out)
    }

    private fun semiJoinFold(cell: CellSpec, ins: List<LinkSpec>): Fold {
        val left = asSet(inlet(ins, "left", 0))
        val rightKeys = asSet(inlet(ins, "right", 1)).map { Functions.keyOf(it) }.toSet()
        return Fold.SetF(left.filterTo(LinkedHashSet()) { Functions.keyOf(it) in rightKeys })
    }

    private fun lookupJoinFold(cell: CellSpec, ins: List<LinkSpec>): Fold {
        val left = asSet(inlet(ins, "left", 0))
        val rightByKey = asSet(inlet(ins, "right", 1)).associate { Functions.keyOf(it) to Functions.valueOf(it) }
        val out = LinkedHashSet<Value>()
        for (l in left) {
            val v = rightByKey[Functions.keyOf(l)] ?: continue
            out.add(Value.ListVal(listOf(l, v)))
        }
        return Fold.SetF(out)
    }

    private fun groupByFold(cell: CellSpec, input: Fold): Fold {
        val members = asSet(input)
        val groups = LinkedHashMap<Value, MutableList<Value>>()
        for (el in members) groups.getOrPut(Functions.keyOf(el)) { ArrayList() }.add(el)
        // Aggregator param is not expressible on CellSpec v1 -> default `count` (documented gap).
        return Fold.MapF(groups.mapValues { (_, g) -> Functions.aggregate("count", g) })
    }

    // --- rendering ----------------------------------------------------------

    private fun renderView(type: String, fold: Fold): Value = when (type) {
        "set-view" -> Value.ListVal(Values.sortedList(asSet(fold)))
        "map-view" -> mapToValue(asMap(fold))
        "count-view" -> when (fold) {
            is Fold.MapF -> mapToValue(fold.entries)
            is Fold.ScalarF -> fold.value
            else -> Value.IntVal(asSet(fold).size.toLong())
        }
        "value-view" -> when (fold) {
            is Fold.ScalarF -> fold.value
            is Fold.SetF -> Value.ListVal(Values.sortedList(fold.members))
            is Fold.ListF -> Value.ListVal(fold.items)
            is Fold.MapF -> mapToValue(fold.entries)
        }
        else -> throw OracleUnsupported("cell '$type' is not a view; nothing to render")
    }

    private fun mapToValue(entries: Map<Value, Value>): Value =
        Value.MapVal(entries.entries.associate { Values.render(it.key) to it.value })

    // --- helpers ------------------------------------------------------------

    private fun fn(cell: CellSpec): String = cell.fn ?: error("${cell.id} (${cell.type}): needs an fn")

    private fun inlet(ins: List<LinkSpec>, name: String, index: Int): Fold {
        val link = ins.firstOrNull { it.inlet == name } ?: ins.getOrNull(index)
        ?: error("missing inlet '$name'/[$index] among ${ins.map { it.inlet }}")
        return foldOf(link.from)
    }

    private fun keyValue(v: Value): Pair<Value, Value> = when {
        v is Value.ListVal && v.items.size == 2 -> v.items[0] to v.items[1]
        v is Value.MapVal && v.entries.containsKey("key") && v.entries.containsKey("value") ->
            v.entries.getValue("key") to v.entries.getValue("value")
        else -> error("map-source put needs [key, value] or {key:, value:}, got $v")
    }

    private fun asSet(f: Fold): Set<Value> = when (f) {
        is Fold.SetF -> f.members
        is Fold.ListF -> LinkedHashSet(f.items)
        else -> throw OracleUnsupported("expected a set fold, got ${f::class.simpleName}")
    }

    private fun asMap(f: Fold): Map<Value, Value> = when (f) {
        is Fold.MapF -> f.entries
        else -> throw OracleUnsupported("expected a map fold, got ${f::class.simpleName}")
    }

    private fun asScalar(f: Fold): Value = when (f) {
        is Fold.ScalarF -> f.value
        else -> throw OracleUnsupported("expected a scalar fold, got ${f::class.simpleName}")
    }

    /** The oracle's intermediate stream state — a pure fold, one per cell. */
    private sealed interface Fold {
        data class SetF(val members: Set<Value>) : Fold
        data class MapF(val entries: Map<Value, Value>) : Fold
        data class ListF(val items: List<Value>) : Fold
        data class ScalarF(val value: Value) : Fold
    }
}
