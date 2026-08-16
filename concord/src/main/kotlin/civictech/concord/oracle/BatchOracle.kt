package civictech.concord.oracle

import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.DisconnectStep
import civictech.concord.schema.Expect
import civictech.concord.schema.LinkSpec
import civictech.concord.schema.Scenario
import civictech.concord.schema.WindowKind
import civictech.concord.schema.WindowSpec
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
 * - **`presence-count`** is a **fan-in** operator, not a `count`-shaped scalar
 *   (resolves the `24-OP-PRESENCE-01` oracle-gap, DISPUTES.md): the kernel's
 *   `PresenceCountCell` shares its `PresenceLanes` substrate with
 *   `QuorumSetCell` — one `TagState` per *open source link*, emitting
 *   `MapDelta<E, Int>` keyed by element, value = the number of distinct live
 *   source links currently asserting that element (group-death when a count
 *   drops to 0). [presenceCountFold] folds each inbound link's source to its
 *   *current* membership set (same per-source fold every other operator
 *   uses) and counts, per element, how many of those sets currently hold it —
 *   an element whose count reaches 0 is simply absent from the resulting map,
 *   matching the kernel's group-death.
 * - **`group-by` aggregator.** The catalog lists `fn (key-of), agg`. `fn` is the
 *   key extractor; the aggregator is the additive `agg` [CellSpec] field (W3-0,
 *   `count`|`sum`|`min`|`max`, default `count`). Non-count aggregators fold the
 *   group's element VALUE components (`valueOf`), matching the kernel binding.
 * - **`quorum-set` k-of-n (operator, not source).** The kernel QuorumSetCell is a
 *   **fan-in operator**, not an add/remove source: an element is admitted once `k`
 *   of the `n` live source links assert it. `k` is the additive [CellSpec] field
 *   (W3-0); absent ⇒ `n` (intersection). The catalog's source framing was refined
 *   to match the kernel (§5).
 * - **`keyed-set` (keyed upsert, not partitioned set).** The kernel KeyedSetCell is
 *   a keyed upsert (`put(key, element)` last-writer-wins per key, `remove(key)`),
 *   whose output is the flat set of currently-held elements (a set-view) — NOT the
 *   per-key partitions the v1 catalog implied. Refined to match the kernel (§5).
 * - **`window`.** M11.6 "windowing = key derivation" (`24-data-cells.md` §Grouped
 *   aggregation, `24-OP-WINDOW-01`/`-02`): a `window` cell's frozen `window:`
 *   descriptor ([civictech.concord.schema.WindowSpec]) assigns each element (a
 *   `[at, value]` pair) to one or more window-start keys — tumbling: one
 *   composite key per element; sliding: every window of `size` the element
 *   falls in, `slide` apart — then group-by's own `agg` fold runs over the
 *   value components, exactly mirroring the kernel binding
 *   (`Windows.tumbling`/`sliding` + `GroupByCell`, `KernelCatalog`/
 *   `WindowSlidingCell`). Windows never close: the fold is over the whole
 *   accepted-op multiset, so a late add is just another member — there is no
 *   separate eviction step to model. `partition` is a sharded group-by
 *   (PartitionedCell) whose union of shard aggregates equals the
 *   unpartitioned group-by twin, so it folds identically to `group-by`.
 * - **`join` family element shape.** With no pilot pinning the joined element, the
 *   oracle treats elements as pairs `[k, v]` (`key-of` = first component): `join`
 *   emits `[k, leftVal, rightVal]`, `lookup-join` emits `[leftElem, rightVal]`,
 *   `semi-join` keeps left elements whose key is present on the right.
 * - **`map-source` op payload.** `put` accepts either a `[key, value]` pair or a
 *   `{key:, value:}` object; `remove` takes the bare key.
 * - **Durable set bindings** (`journal-set-source`, `journal-set-view`) fold
 *   exactly as their volatile twins — see [DURABLE_SET_SOURCE]/[DURABLE_SET_VIEW]
 *   for the adjudication and its one recorded residual.
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

    /**
     * Every view cell's expected final value (drives `incremental-equals-batch view: '*'`).
     *
     * Enumerated over [Values.VIEW_TYPES] — the check layer's own catalog of terminal
     * views, which since computenet-yh6.1.10 includes [DURABLE_SET_VIEW], so `'*'`
     * quantifies over a journaled view like any other. The alternative considered when
     * computenet-yh6.1.9 taught this oracle the durable bindings was to leave the
     * check-layer constant narrow, on the reasoning that widening a `'*'` quantifier
     * changes the meaning of every existing `'*'` check; that was revisited against the
     * corpus and decided the other way, because the narrow form's failure mode on a
     * scenario whose only view is journaled is a *vacuous pass* over an empty target
     * set. The reasoning and the measured blast radius are in `Values.VIEW_TYPES`'
     * KDoc, which is where the decision lives.
     */
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
            // `journal-set-source` is the DURABLE BINDING of this same cell
            // (`KernelDriverDur.build`: both lower to `SetCell`), and it folds
            // identically — see [DURABLE_SET_SOURCE] for why the journal changes
            // nothing the oracle models.
            "set-source", DURABLE_SET_SOURCE -> {
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
                // A step is `value` (the increment amount, default 1) repeated `times`,
                // matching the driver: `increment value:50` is +50, `increment times:50`
                // is fifty unit steps — both fold to +50.
                var c = 0L
                for (op in ops) {
                    val amount = op.value?.let { Values.asLong(it) } ?: 1L
                    val step = amount * (op.times ?: 1).toLong()
                    when (op.op) {
                        "increment" -> c += step
                        "decrement" -> c -= step
                        else -> error("${cell.id} (${cell.type}): unsupported op '${op.op}'")
                    }
                }
                Fold.ScalarF(Value.IntVal(c))
            }

            "list-source" -> {
                // Index-addressed ops mirroring the kernel ListCell (append / insert[i,e]
                // / set[i,e] / remove-at[i]); there is no remove-by-value.
                val items = ArrayList<Value>()
                for (op in ops) repeat(op.times ?: 1) {
                    when (op.op) {
                        "append" -> items.add(op.value ?: error("${cell.id}: append needs a value"))
                        "insert" -> {
                            val (idx, elem) = indexElem(cell.id, op.value)
                            items.add(idx.coerceIn(0, items.size), elem)
                        }
                        "set" -> {
                            val (idx, elem) = indexElem(cell.id, op.value)
                            if (idx in items.indices) items[idx] = elem
                        }
                        "remove-at" -> {
                            val idx = (op.value?.let { Values.asLong(it) } ?: -1L).toInt()
                            if (idx in items.indices) items.removeAt(idx)
                        }
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
                // Keyed upsert bridge (kernel KeyedSetCell): `put(key, element)` sets the
                // element under a key (last-writer-wins per key), `remove(key)` drops it;
                // membership is the set of currently-held elements (observed through a
                // set-view), NOT per-key partitions.
                val current = LinkedHashMap<Value, Value>()
                for (op in ops) {
                    when (op.op) {
                        "put" -> {
                            val (k, e) = keyValue(op.value ?: error("${cell.id}: put needs a value"))
                            current[k] = e
                        }
                        "remove", "remove-key" -> op.value?.let { current.remove(it) }
                        else -> error("${cell.id} (keyed-set): unsupported op '${op.op}' (put/remove(key))")
                    }
                }
                Fold.SetF(LinkedHashSet(current.values))
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
            // partition is a sharded group-by (kernel PartitionedCell); its union of
            // shard aggregates equals the unpartitioned group-by twin (spec 24).
            "partition" -> groupByFold(cell, single())
            "quorum-set" -> quorumFold(cell, ins)
            "combine-latest" -> Fold.ScalarF(Functions.aggregate(fn(cell), ins.map { asScalar(foldOf(it.from)) }))
            "count" -> Fold.ScalarF(Value.IntVal(asSet(single()).size.toLong()))
            "presence-count" -> presenceCountFold(ins)
            "window" -> windowFold(cell, single())
            // Views are pass-throughs of their upstream fold; renderView converts to Value.
            in VIEW_TYPES -> single()
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
        // Aggregator id from the additive `agg` CellSpec field (W3-0); absent ⇒ `count`.
        // Non-count aggregators fold the group's element VALUE components (`valueOf`),
        // matching the kernel binding's `sumOf/minOf/maxOf { valueOf(it) }`.
        val aggId = cell.agg ?: "count"
        return Fold.MapF(groups.mapValues { (_, g) -> Functions.aggregate(aggId, g.map { Functions.valueOf(it) }) })
    }

    /**
     * `window` (M11.6 "windowing = key derivation", `24-OP-WINDOW-01`/`-02`):
     * every element is a `[at, value]` pair; [windowsOf] assigns `at` to one
     * (tumbling) or several (sliding) window-start keys, and every group's
     * `agg` fold runs over the value components — the same shape
     * `groupByFold` uses, just keyed by window instead of `Functions.keyOf`.
     * Mirrors the kernel `Windows.tumbling`/`sliding` formulas exactly (see
     * `kernel/.../data/Windows.kt`, proven by `WindowingTest`). Windows never
     * close: this is a whole-multiset fold, so a late add is simply another
     * member of its window(s) — there is no separate eviction step.
     */
    private fun windowFold(cell: CellSpec, input: Fold): Fold {
        val spec = cell.window ?: error("${cell.id} (window): needs a `window:` descriptor")
        val aggId = cell.agg ?: "count"
        val groups = LinkedHashMap<Value, MutableList<Value>>()
        for (el in asSet(input)) {
            val at = Values.asLong(Functions.keyOf(el))
                ?: error("${cell.id}: window element's event-time key is not an integer: $el")
            windowsOf(spec, at).forEach { w -> groups.getOrPut(Value.IntVal(w)) { ArrayList() }.add(el) }
        }
        return Fold.MapF(groups.mapValues { (_, g) -> Functions.aggregate(aggId, g.map { Functions.valueOf(it) }) })
    }

    /** Every window start [at] falls in, ascending — tumbling: exactly one; sliding: `Windows.sliding`'s formula. */
    private fun windowsOf(spec: WindowSpec, at: Long): List<Long> = when (spec.kind) {
        WindowKind.TUMBLING -> listOf(Math.floorDiv(at, spec.size) * spec.size)
        WindowKind.SLIDING -> {
            val slide = spec.slide ?: error("sliding window needs a `slide`")
            val starts = mutableListOf<Long>()
            var start = Math.floorDiv(at, slide) * slide
            while (start + spec.size > at) {
                starts += start
                start -= slide
            }
            starts.reversed()
        }
    }

    /**
     * Quorum over a fan-in of set sources (kernel QuorumSetCell): an element is
     * emitted once it is asserted by at least `k` of the `n` live source links.
     * `k` from the additive `k` CellSpec field (W3-0); absent ⇒ `n` (all sources,
     * an intersection).
     */
    private fun quorumFold(cell: CellSpec, ins: List<LinkSpec>): Fold {
        val sources = ins.map { asSet(foldOf(it.from)) }
        val target = cell.k ?: sources.size
        val counts = LinkedHashMap<Value, Int>()
        sources.forEach { s -> s.forEach { counts.merge(it, 1, Int::plus) } }
        return Fold.SetF(counts.filterValues { it >= target }.keys.toCollection(LinkedHashSet()))
    }

    /**
     * `presence-count` (kernel `PresenceCountCell`, a `PresenceLanes` fan-in
     * peer of `quorum-set`, not a `count`-shaped scalar — DISPUTES.md
     * `24-OP-PRESENCE-01`): folds each inbound source link to its own current
     * membership set, then for every element counts how many of those sets
     * currently hold it — one live source link asserting an element is one
     * count. An element no source currently holds has count 0 and is simply
     * absent from the map (the kernel's group-death), never emitted as a 0.
     */
    private fun presenceCountFold(ins: List<LinkSpec>): Fold {
        val counts = LinkedHashMap<Value, Int>()
        for (link in ins) {
            asSet(foldOf(link.from)).forEach { el -> counts.merge(el, 1, Int::plus) }
        }
        return Fold.MapF(counts.mapValues { (_, c) -> Value.IntVal(c.toLong()) })
    }

    // --- rendering ----------------------------------------------------------

    private fun renderView(type: String, fold: Fold): Value = when (if (type == DURABLE_SET_VIEW) "set-view" else type) {
        "set-view" -> Value.ListVal(Values.sortedList(asSet(fold)))
        "list-view" -> when (fold) {
            is Fold.ListF -> Value.ListVal(fold.items)
            else -> Value.ListVal(Values.sortedList(asSet(fold)))
        }
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
        else -> error("put needs [key, value] or {key:, value:}, got $v")
    }

    private fun indexElem(cellId: String, v: Value?): Pair<Int, Value> {
        require(v is Value.ListVal && v.items.size == 2) { "$cellId: insert/set needs [index, element], got $v" }
        return (Values.asLong(v.items[0]) ?: 0L).toInt() to v.items[1]
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

    private companion object {

        /**
         * The durable binding of `set-source` (`KernelDriverDur`), folded by
         * [sourceFold] exactly as its volatile twin.
         *
         * **Why that is sound, adjudicated rather than assumed** (computenet-yh6.1.9;
         * the question `computenet-yh6.1.5.2` left open was whether a journaled
         * source's *replayed* op history can diverge from the script's accepted-op
         * multiset this oracle folds — if it could, the omission would have been
         * deliberate and adding the case would make every `incremental-equals-batch`
         * over a journaled source quietly wrong):
         *
         * 1. Both catalog ids lower to the **same** `SetCell` (`KernelDriverDur.build`);
         *    the journal is a write-path tee, not a second semantics.
         * 2. The tee is **write-ahead and inside the staging lock**
         *    (`ManagedHost.enqueueHostedInvocation`, `[24-DUR-01]`): every accepted
         *    invocation is appended before it is staged, in the same
         *    `synchronized(dataLock)` block, so journal order **is** acceptance order.
         *    A coalesced invocation is appended too. Nothing accepted is missing from
         *    the replayed history, and nothing is reordered relative to it.
         * 3. Replay cannot **grow** the history either: both append sites are guarded
         *    by `!hostDurability.recovering`, so a replayed frame is never re-journaled.
         * 4. A checkpoint substitutes a `Stateful` snapshot for the prefix it compacts
         *    (`[24-DUR-02]`), so checkpoint + surviving tail folds to the same value as
         *    the whole history.
         * 5. And the set fold is idempotent under `add` anyway, which is the only op
         *    besides `remove` this binding admits (`KernelDriverDur.apply`).
         *
         * The corpus already asserts the conclusion from two directions, which is why
         * this is an observation and not an argument: `DUR-SNAPTAIL-01` pins a
         * journaled source→view against an **uninterrupted volatile twin driven with
         * the identical op history** (`views-converge`), and `DUR-ATOMIC-01`'s
         * `final-view` golden is literally the fold of its script's adds, across
         * checkpoint, compaction, tail replay and post-recovery live traffic.
         *
         * **The riskier case was already in the table.** Whether a driver's read
         * matches this whole-history fold across a crash is a property of the
         * *scenario's* recovery construction, not of the source's binding — and a
         * **volatile** `set-source` on `host: dur`, which this oracle has always
         * folded, loses its state outright on the crash (`[24-DUR-03]`). A journaled
         * source is the *safer* of the two: its replay reconstructs exactly the
         * accepted-op fold.
         */
        const val DURABLE_SET_SOURCE = "journal-set-source"

        /**
         * The durable binding of `set-view` — a view pass-through like every other
         * ([operatorFold]), rendered as a set ([renderView]).
         *
         * **The check layer knows it too, since computenet-yh6.1.10.** Two check-layer
         * facts in `oracle/Values.kt` were left behind by computenet-yh6.1.9 (outside
         * that item's file claim) and are now closed: `Values.VIEW_TYPES` — which
         * `Checks.viewCells` and [allViewValues] enumerate, so `view: '*'` no longer
         * skips a journaled view — and `Values.canonicalForView`, which now consults
         * `Values.SET_VIEW_TYPES` and so compares a journaled set **order-insensitively**
         * rather than structurally. That second one was a real divergence, not a
         * cosmetic one: the driver sorts a set by `Value.toString()`
         * (`KernelCatalog.readView`) and this layer by `Values.compare`, orders that
         * agree on homogeneous sets and need not agree on mixed-type ones. The decision
         * and its measured blast radius are recorded in `Values.VIEW_TYPES`' KDoc.
         */
        const val DURABLE_SET_VIEW = "journal-set-view"

        /**
         * View catalog ids this oracle can fold and render — the check layer's terminal
         * views plus the durable binding.
         *
         * Since computenet-yh6.1.10 put [DURABLE_SET_VIEW] into [Values.VIEW_TYPES] the
         * union is idempotent and the two sets are **equal**; the `+` is kept because
         * the two answer different questions ("what does the check layer call a
         * terminal view?" versus "what can this oracle fold and render?"), and this one
         * must stay true of the durable binding even if the check layer's catalog were
         * ever narrowed again. Nothing here changed behaviour when they converged:
         * [operatorFold]'s view pass-through already matched `journal-set-view` through
         * this set.
         */
        val VIEW_TYPES: Set<String> = Values.VIEW_TYPES + DURABLE_SET_VIEW
    }

    /** The oracle's intermediate stream state — a pure fold, one per cell. */
    private sealed interface Fold {
        data class SetF(val members: Set<Value>) : Fold
        data class MapF(val entries: Map<Value, Value>) : Fold
        data class ListF(val items: List<Value>) : Fold
        data class ScalarF(val value: Value) : Fold
    }
}
