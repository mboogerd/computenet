package civictech.concord.oracle

import civictech.concord.schema.ApplyStep
import civictech.concord.schema.CellSpec
import civictech.concord.schema.ConnectStep
import civictech.concord.schema.DisconnectStep
import civictech.concord.schema.Expect
import civictech.concord.schema.LinkSpec
import civictech.concord.schema.QuiesceStep
import civictech.concord.schema.ReadStateStep
import civictech.concord.schema.RestartStep
import civictech.concord.schema.Scenario
import civictech.concord.schema.SnapshotStep
import civictech.concord.schema.Step
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
 *   `WindowSlidingCell`). Without `lateness`, windows never close
 *   (`[24-WL-11]`): the fold is over the whole accepted-op multiset, so a late
 *   add is just another member. `partition` is a sharded group-by
 *   (PartitionedCell) whose union of shard aggregates equals the
 *   unpartitioned group-by twin, so it folds identically to `group-by`.
 * - **`join` family element shape.** With no pilot pinning the joined element, the
 *   oracle treats elements as pairs `[k, v]` (`key-of` = first component): `join`
 *   emits `[k, leftVal, rightVal]`, `lookup-join` emits `[leftElem, rightVal]`,
 *   `semi-join` keeps left elements whose key is present on the right.
 * - **`map-source` op payload.** `put` accepts either a `[key, value]` pair or a
 *   `{key:, value:}` object; `remove` takes the bare key.
 * - **Lateness** (spec 24 §Lateness and waterlines, t4od7-D7) is the one
 *   exception to order-independence: a `waterline` cell and a cell declaring
 *   `lateness` are folded in script order by [Lateness]. The floor is `[24-WL-02]`'s
 *   running max of min-over-sources (max event time − lateness); an add is late
 *   iff its event time is strictly below the floor as of the **last `quiesce`
 *   preceding it**; an add between that floor and the highest floor that can
 *   have reached the cell first (on one host, the floor the adds applied before
 *   it in its quiesce block raise) is refused ([OracleUnsupported], "insert a
 *   `quiesce`") unless both outcomes fold alike. `[24-WL-10]`'s batch side then follows: a window
 *   folds its late-filtered input and drops every window whose end the final
 *   floor has passed; the join family drops every row strictly below the final
 *   floor. See [Lateness] for the whole model and what it refuses.
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
        // Lateness (spec 24 §Lateness and waterlines) is folded by the script-order
        // model in [Lateness] below, not by the multiset folds (t4od7-D7).
        val result = when {
            cell.type == WATERLINE -> Fold.ScalarF(Value.IntVal(latenessModel.floorTimeline(cell).lastOrNull() ?: 0L))
            cell.lateness != null -> latenessModel.gate(cell).mainFold()
            inputsByCell[id].isNullOrEmpty() -> sourceFold(cell)
            else -> operatorFold(cell)
        }
        visiting.remove(id)
        memo[id] = result
        return result
    }

    /**
     * The fold a [link] carries: its source cell's ordinary fold, except a link
     * from a lateness-declaring cell's late outlet (`late`, `lateLeft`,
     * `lateRight`), which carries the adds that cell late-dropped ([24-WL-07]).
     */
    private fun foldOfLink(link: LinkSpec): Fold {
        val outlet = link.outlet
        val from = cellsById[link.from]
        return if (outlet != null && outlet in LATE_OUTLETS && from?.lateness != null) {
            latenessModel.gate(from).lateFold(outlet)
        } else {
            foldOf(link.from)
        }
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

            "ormap-source" -> {
                // Observed-remove per-key map (kernel OrMapCell), folded over ONE
                // stream. A `put` mints a fresh dot at the key and covers every dot the
                // writer currently observes live there; a `remove` covers exactly the
                // dots it observes live. On a single replica the writer observes
                // everything it ever wrote, so at most one dot per key is ever live and
                // the fold collapses to file-order last-writer-wins with `remove`
                // dropping the key. That is the *single-stream* projection of the dot
                // algebra, not a restatement of it: what makes the two coincide is that
                // one replica's dot counter increases monotonically in script order, so
                // dot order IS file order. A concurrent second writer — where a
                // remove's cover set and a concurrent put's dot genuinely diverge — is
                // not expressible in a batch-oracle scenario, which has one stream by
                // construction; the dist/replica halves are pinned by the replication
                // scenarios instead.
                val entries = LinkedHashMap<Value, Value>()
                for (op in ops) {
                    when (op.op) {
                        "put" -> {
                            val (k, v) = keyValue(op.value ?: error("${cell.id}: put needs a value"))
                            entries[k] = v
                        }
                        "remove", "remove-key" -> op.value?.let { entries.remove(it) }
                        else -> error("${cell.id} (ormap-source): unsupported op '${op.op}' (put/remove(key))")
                    }
                }
                Fold.MapF(entries)
            }

            else -> throw OracleUnsupported("source type '${cell.type}' has no oracle fold")
        }
    }

    private fun operatorFold(cell: CellSpec): Fold {
        val ins = inputsByCell[cell.id].orEmpty()
        fun single(): Fold = foldOfLink(ins.singleOrNull() ?: error("${cell.id}: expected one inlet, got ${ins.size}"))
        return when (cell.type) {
            "filter" -> Fold.SetF(asSet(single()).filterTo(LinkedHashSet(), Functions.predicate(fn(cell))))
            "map" -> mapFold(single(), fn(cell))
            "flatmap" -> flatMapFold(asSet(single()), fn(cell))
            "union" -> Fold.SetF(LinkedHashSet(asSet(inlet(ins, "left", 0)) + asSet(inlet(ins, "right", 1))))
            "intersect" -> intersectOf(asSet(inlet(ins, "left", 0)), asSet(inlet(ins, "right", 1)))
            "join" -> joinOf(asSet(inlet(ins, "left", 0)), asSet(inlet(ins, "right", 1)))
            "semi-join" -> semiJoinOf(asSet(inlet(ins, "left", 0)), asSet(inlet(ins, "right", 1)))
            "lookup-join" -> lookupJoinFold(cell, ins)
            "group-by" -> groupByFold(cell, single())
            // partition is a sharded group-by (kernel PartitionedCell); its union of
            // shard aggregates equals the unpartitioned group-by twin (spec 24).
            "partition" -> groupByFold(cell, single())
            "quorum-set" -> quorumFold(cell, ins)
            "combine-latest" -> Fold.ScalarF(Functions.aggregate(fn(cell), ins.map { asScalar(foldOf(it.from)) }))
            "count" -> Fold.ScalarF(Value.IntVal(asSet(single()).size.toLong()))
            "presence-count" -> presenceCountFold(ins)
            "window" -> windowFold(cell, asSet(single()))
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

    private fun intersectOf(left: Set<Value>, right: Set<Value>): Fold =
        Fold.SetF(left.filterTo(LinkedHashSet()) { it in right })

    private fun joinOf(left: Set<Value>, right: Set<Value>): Fold {
        val out = LinkedHashSet<Value>()
        for (l in left) for (r in right) {
            if (Values.compare(Functions.keyOf(l), Functions.keyOf(r)) == 0) {
                out.add(Value.ListVal(listOf(Functions.keyOf(l), Functions.valueOf(l), Functions.valueOf(r))))
            }
        }
        return Fold.SetF(out)
    }

    private fun semiJoinOf(left: Set<Value>, right: Set<Value>): Fold {
        val rightKeys = right.map { Functions.keyOf(it) }.toSet()
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
     * `kernel/.../data/Windows.kt`, proven by `WindowingTest`). Without
     * `lateness` windows never close: this is a whole-multiset fold, so a late
     * add is simply another member of its window(s). A window that declares
     * `lateness` is folded here too, but over the late-filtered input
     * [Lateness.Gate] hands it, which then drops every passed window.
     */
    private fun windowFold(cell: CellSpec, members: Set<Value>): Fold.MapF {
        val spec = cell.window ?: error("${cell.id} (window): needs a `window:` descriptor")
        val aggId = cell.agg ?: "count"
        val groups = LinkedHashMap<Value, MutableList<Value>>()
        for (el in members) {
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

    // --- lateness (spec 24 §Lateness and waterlines, t4od7-D7) ---------------

    private val latenessModel = Lateness()

    /**
     * The oracle's lateness model: the one part of this oracle that is **not** a
     * multiset fold, because a late drop and a window eviction depend on which
     * adds a waterline floor has passed, and so on script order (t4od7-D7).
     *
     * - **Floor** ([floorTimeline]): a `waterline` cell's floor after every script
     *   step, per `[24-WL-02]`. Each upstream source cell is one `sourceId`
     *   (`[22-SRC-01]`), reached directly or through `map fn: identity` relays
     *   (transparent flow — any other cell on the way is refused). Only an
     *   *emitted* add contributes its head `at` to that source's maximum (a
     *   `set-source` emits an add only for an element it does not hold; a
     *   remove never contributes). Candidate = min over contributing sources of
     *   (max − lateness); floor = running max of the candidate, absent (the
     *   identity) until a source contributes; a source joining below the floor
     *   leaves it unchanged (`[24-WL-20]`). A `disconnect` of an edge into the
     *   waterline retires every source that edge carried unless another still-open
     *   edge carried it too (`[24-WL-12]`'s diamond); a `restart` of a
     *   `rebaseline-source` retires its epoch at the restart step (`[24-WL-13]`:
     *   retired when the notice supersedes it), and its later adds contribute
     *   from scratch. A `value-view` of a waterline reads 0 before any floor,
     *   the driver's `scalarView` convention.
     * - **Floor at an evicting cell**: the maximum over the waterline cells linked
     *   into its `waterline` inlet (`WaterlineDelta` merges by maximum, so several
     *   waterlines are a max). The operator's own `lateness` number is never read:
     *   the kernel reads only its `timeFn`, and the threshold comes entirely from
     *   the waterline cells (t4od7.1's review).
     * - **Late filter and the quiesce rule** ([Gate]): an add reaching a
     *   lateness-declaring inlet is late iff its event time is strictly below
     *   the floor **as of the last `quiesce` preceding it** (`[24-WL-07]`; that
     *   floor has certainly been delivered by then). An add at or above the
     *   highest floor that can have reached the cell before it is certainly
     *   admitted: on one host (FIFO scheduling, [singleHost]) that is the floor
     *   the adds applied **before** it in its quiesce block raise — a later add's
     *   floor is queued behind this add's delivery, and so is its own; on several
     *   hosts it is the floor at the end of its quiesce block (t4od7-D7's literal
     *   rule). An add between the two depends on delivery order the corpus does
     *   not control, and is
     *   refused with [OracleUnsupported] naming the step — *unless* both outcomes
     *   fold the same: a window whose end the final floor has passed, or any
     *   join-family row (its time is below that block's floor, so below the final
     *   floor, so evicted either way). A late outlet's fold is refused on any such
     *   add, since there the two outcomes always differ. Dels are never
     *   time-filtered (`[24-WL-08]`): a remove deletes the element from its link's
     *   live set when it is there and is a no-op otherwise.
     * - **Eviction restriction** (`[24-WL-10]`): a tumbling `window` folds the
     *   late-filtered input and then drops every window `k` with
     *   `k + size <= finalFloor` (`keyTime` is the exclusive end, `[24-WL-06]`);
     *   a `join`/`semi-join`/`intersect` removes every row with event time
     *   strictly below the final floor from both sides, then folds as usual.
     * - **Event time**, the catalog conventions (`concord/schema/scenario.md`
     *   §lateness; the driver's `EventTimeOfPair`/`EventTimeOfRow`): `window`,
     *   `intersect` and `waterline` elements are `[at, value]` → `at`; `join`/
     *   `semi-join` rows are `[k, at]` → `at` or `[k, [at, payload]]` → `at`.
     *   This is an independent copy — the two agreeing is part of what
     *   `incremental-equals-batch` checks.
     *
     * Out of the model, refused rather than guessed: `sliding` + lateness (the
     * catalog refuses it too), a data input that is not a set source (a
     * `rebaseline-source`'s re-asserted state included), a `connect`/`disconnect`
     * into the evicting cell or into a relay, and any step verb other than
     * apply, quiesce, connect, disconnect, restart (of a `rebaseline-source`),
     * snapshot and read-state.
     */
    private inner class Lateness {
        private val steps = scenario.script

        /**
         * One host, so one FIFO scheduler: every delivery an add causes is queued
         * behind the deliveries of every add applied before it (`SimulationController`
         * randomises only *across* hosts — "per-host FIFO holds under every seed").
         */
        private val singleHost = graph.hosts.isNullOrEmpty() && graph.cells.none { it.host != null }
        private val timelines = HashMap<String, List<Long?>>()
        private val gates = HashMap<String, Gate>()
        private val emissions = HashMap<String, List<Emission>>()

        /** What a source emitted downstream at one script step. */
        private inner class Emission(val adds: List<Value>, val removes: List<Value>)

        private val none = Emission(emptyList(), emptyList())

        /** Source [id]'s emission at every script step (index-aligned with the script). */
        private fun emissionsOf(id: String): List<Emission> = emissions.getOrPut(id) {
            val cell = cellsById[id] ?: throw OracleUnsupported("unknown cell '$id'")
            when (cell.type) {
                "set-source", DURABLE_SET_SOURCE -> {
                    val members = HashSet<Value>()
                    steps.map { step ->
                        if (step !is ApplyStep || step.on != id) return@map none
                        val adds = ArrayList<Value>()
                        val removes = ArrayList<Value>()
                        repeat(step.times ?: 1) {
                            val v = step.value ?: error("$id: ${step.op} needs a value")
                            when (step.op) {
                                "add" -> if (members.add(v)) adds += v
                                "remove" -> if (members.remove(v)) removes += v
                                else -> error("$id (${cell.type}): unsupported op '${step.op}'")
                            }
                        }
                        Emission(adds, removes)
                    }
                }
                // Add-only; every add mints a fresh tag, so every add emits.
                "rebaseline-source" -> steps.map { step ->
                    if (step !is ApplyStep || step.on != id) return@map none
                    if (step.op != "add") error("$id (rebaseline-source): unsupported op '${step.op}'")
                    Emission(List(step.times ?: 1) { step.value ?: error("$id: add needs a value") }, emptyList())
                }
                else -> throw OracleUnsupported(
                    "'$id' (${cell.type}) feeds a waterline or a lateness-declaring cell; the lateness model " +
                        "folds set-source / $DURABLE_SET_SOURCE there (and rebaseline-source into a waterline) only",
                )
            }
        }

        private fun topologyStepsInto(id: String): List<Step> = steps.filter {
            (it is ConnectStep && it.to == id && it.expect != Expect.REJECTED) ||
                (it is DisconnectStep && it.to == id && it.expect != Expect.REJECTED)
        }

        /** The source cell whose adds arrive over a link from [id], resolving identity relays. */
        private fun originOf(id: String, consumer: String): String {
            val cell = cellsById[id] ?: throw OracleUnsupported("unknown cell '$id'")
            val ins = inputsByCell[id].orEmpty()
            if (ins.isEmpty()) {
                emissionsOf(id) // refuses a source type the model cannot fold
                return id
            }
            val relay = cell.type == "map" && (cell.fn == null || cell.fn == "identity") && ins.size == 1
            if (relay && topologyStepsInto(id).isEmpty()) return originOf(ins.single().from, consumer)
            throw OracleUnsupported(
                "'$consumer' is fed by '$id' (${cell.type}); the lateness model follows only sources and " +
                    "`map fn: identity` relays with a fixed topology (a relay is transparent flow, [22-SRC-01])",
            )
        }

        private fun requireModelledSteps(consumer: String) {
            steps.forEachIndexed { i, step ->
                val ok = when (step) {
                    is ApplyStep, is QuiesceStep, is ConnectStep, is DisconnectStep, is SnapshotStep, is ReadStateStep -> true
                    is RestartStep -> cellsById[step.on]?.type == "rebaseline-source"
                    else -> false
                }
                if (!ok) {
                    throw OracleUnsupported(
                        "script step ${i + 1} (${step::class.simpleName}) is outside the oracle's lateness model " +
                            "(the cone of '$consumer' reaches lateness)",
                    )
                }
            }
        }

        /** Waterline [wl]'s floor after each script step (`null` = no source has contributed yet). */
        fun floorTimeline(wl: CellSpec): List<Long?> = timelines.getOrPut(wl.id) {
            requireModelledSteps(wl.id)
            val lateness = wl.lateness ?: throw OracleUnsupported("waterline '${wl.id}' declares no lateness")
            val open = graph.links.filter { it.to == wl.id }.mapTo(LinkedHashSet()) { it.from }
            val maxima = LinkedHashMap<String, Long>()
            val carried = HashMap<String, MutableSet<String>>() // edge (by from) -> sources it has carried
            var floor: Long? = null
            fun raise() {
                val candidate = (maxima.values.minOrNull() ?: return) - lateness
                val current = floor
                if (current == null || candidate > current) floor = candidate
            }
            fun retire(sources: Collection<String>) {
                sources.forEach { s -> maxima.remove(s); carried.values.forEach { it -= s } }
                raise()
            }
            steps.mapIndexed { i, step ->
                when (step) {
                    is ApplyStep -> {
                        for (from in open) {
                            val src = originOf(from, wl.id)
                            if (src != step.on) continue
                            val adds = emissionsOf(src)[i].adds
                            if (adds.isEmpty()) continue
                            for (e in adds) {
                                val t = headTime(e, "waterline '${wl.id}'")
                                maxima[src] = maxOf(maxima[src] ?: t, t)
                            }
                            carried.getOrPut(from) { mutableSetOf() } += src
                        }
                        raise()
                    }
                    is ConnectStep -> if (step.to == wl.id && step.expect != Expect.REJECTED) open += step.from
                    is DisconnectStep -> if (step.to == wl.id && step.expect != Expect.REJECTED) {
                        open -= step.from
                        val gone = carried.remove(step.from).orEmpty()
                        retire(gone.filter { s -> carried.values.none { s in it } })
                    }
                    is RestartStep -> if (open.any { originOf(it, wl.id) == step.on }) retire(listOf(step.on))
                    else -> {}
                }
                floor
            }
        }

        fun gate(cell: CellSpec): Gate = gates.getOrPut(cell.id) { Gate(cell) }

        /** One add whose lateness the script's quiesces do not settle. */
        private inner class Ambiguous(val step: Int, val on: String, val element: Value, val time: Long, val lo: Long?, val hi: Long?) {
            fun refuse(cellId: String): Nothing = throw OracleUnsupported(
                "script step ${step + 1} (apply on '$on', add ${Values.render(element)}) has event time $time, at or " +
                    "above the floor as of the preceding quiesce (${lo ?: "none"}) but below the floor " +
                    "${if (singleHost) "the adds applied before it in its quiesce block raise" else "at the end of its quiesce block"} " +
                    "($hi): whether '$cellId' late-drops it depends on delivery order the corpus does not control — " +
                    "insert a `quiesce` before this add",
            )
        }

        /**
         * A lateness-declaring cell's late filter, replayed over the script: per
         * data link, the live elements (certainly admitted, or [Ambiguous]); per
         * inlet side, the late-dropped adds.
         */
        inner class Gate(private val cell: CellSpec) {
            private val window: WindowSpec?
            private val rowTimed: Boolean
            private val finalFloor: Long?
            private val live = LinkedHashMap<String, MutableList<LinkedHashMap<Value, Ambiguous?>>>()
            private val late = LinkedHashMap<String, LinkedHashMap<Value, Ambiguous?>>()

            init {
                requireModelledSteps(cell.id)
                when (cell.type) {
                    "window" -> {
                        window = cell.window ?: error("${cell.id} (window): needs a `window:` descriptor")
                        if (window.kind != WindowKind.TUMBLING) {
                            throw OracleUnsupported("'${cell.id}': a sliding window with lateness is unbound (t4od7-D4)")
                        }
                        rowTimed = false
                    }
                    "join", "semi-join" -> { window = null; rowTimed = true }
                    "intersect" -> { window = null; rowTimed = false }
                    else -> throw OracleUnsupported(
                        "'${cell.id}' (${cell.type}) declares lateness; the oracle models it on window (tumbling), " +
                            "join, semi-join and intersect only",
                    )
                }
                if (topologyStepsInto(cell.id).isNotEmpty()) {
                    throw OracleUnsupported("a connect/disconnect into lateness-declaring '${cell.id}' is outside the lateness model")
                }
                val ins = inputsByCell[cell.id].orEmpty()
                val waterlines = ins.filter { it.inlet == WATERLINE_INLET }.map { link ->
                    cellsById[link.from]?.takeIf { it.type == WATERLINE }
                        ?: throw OracleUnsupported("'${cell.id}''s waterline inlet is fed by '${link.from}', not a waterline cell")
                }.map { floorTimeline(it) }
                val floorAfter: List<Long?> = steps.indices.map { i -> waterlines.mapNotNull { it[i] }.maxOrNull() }
                finalFloor = floorAfter.lastOrNull()
                val quiesces = steps.indices.filter { steps[it] is QuiesceStep }

                val data = ins.filter { it.inlet != WATERLINE_INLET }
                val sides = data.mapIndexed { idx, link ->
                    when {
                        window != null -> IN
                        link.inlet == LEFT || link.inlet == RIGHT -> link.inlet
                        link.inlet == null && idx < 2 -> if (idx == 0) LEFT else RIGHT
                        else -> throw OracleUnsupported("'${cell.id}': data link from '${link.from}' names no left/right inlet")
                    }
                }
                val origins = data.map { link ->
                    originOf(link.from, cell.id).also { o ->
                        if (cellsById.getValue(o).type == "rebaseline-source") {
                            throw OracleUnsupported(
                                "'${cell.id}' is fed data by rebaseline-source '$o'; its re-asserted state is not modelled",
                            )
                        }
                    }
                }
                val perLink = data.map { LinkedHashMap<Value, Ambiguous?>() }
                sides.forEachIndexed { k, side -> live.getOrPut(side) { mutableListOf() } += perLink[k] }

                for ((i, step) in steps.withIndex()) {
                    if (step !is ApplyStep) continue
                    val lo = quiesces.lastOrNull { it < i }?.let { floorAfter[it] }
                    val hi = if (singleHost) {
                        if (i == 0) null else floorAfter[i - 1]
                    } else {
                        floorAfter[(quiesces.firstOrNull { it > i } ?: steps.size) - 1]
                    }
                    data.indices.filter { origins[it] == step.on }.forEach { k ->
                        val emission = emissionsOf(step.on)[i]
                        emission.removes.forEach { perLink[k].remove(it) } // [24-WL-08]: liveness, not time
                        for (e in emission.adds) {
                            val t = timeOf(e)
                            val lateSide = late.getOrPut(sides[k]) { LinkedHashMap() }
                            when {
                                lo != null && t < lo -> lateSide[e] = null
                                hi == null || t >= hi -> perLink[k][e] = null
                                else -> Ambiguous(i, step.on, e, t, lo, hi).let {
                                    perLink[k][e] = it
                                    if (e !in lateSide) lateSide[e] = it
                                }
                            }
                        }
                    }
                }
            }

            private fun timeOf(e: Value): Long =
                if (rowTimed) rowTime(e, "'${cell.id}'") else headTime(e, "'${cell.id}'")

            /** A side's live elements after the final eviction; an ambiguous add that survives it is refused. */
            private fun survivors(side: String): Set<Value> {
                val out = LinkedHashSet<Value>()
                val links = live[side].orEmpty()
                for (link in links) for ((e, ambiguous) in link) {
                    if (e in out || evicted(e)) continue
                    if (ambiguous != null && links.none { it.containsKey(e) && it[e] == null }) ambiguous.refuse(cell.id)
                    out += e
                }
                return out
            }

            /** `[24-WL-10]`'s restriction: a row strictly below the final floor, or an element of a passed window. */
            private fun evicted(e: Value): Boolean {
                val floor = finalFloor ?: return false
                val t = timeOf(e)
                return if (window != null) Math.floorDiv(t, window.size) * window.size + window.size <= floor else t < floor
            }

            fun mainFold(): Fold = when (cell.type) {
                "window" -> windowFold(cell, survivors(IN))
                "join" -> joinOf(survivors(LEFT), survivors(RIGHT))
                "semi-join" -> semiJoinOf(survivors(LEFT), survivors(RIGHT))
                else -> intersectOf(survivors(LEFT), survivors(RIGHT))
            }

            fun lateFold(outlet: String): Fold {
                val side = when (outlet) {
                    "late" -> IN
                    "lateLeft" -> LEFT
                    else -> RIGHT
                }
                if ((side == IN) != (window != null)) {
                    throw OracleUnsupported("'${cell.id}' (${cell.type}) has no '$outlet' outlet")
                }
                val entries = late[side].orEmpty()
                entries.values.firstOrNull { it != null }?.refuse(cell.id)
                return Fold.SetF(LinkedHashSet(entries.keys))
            }
        }

        private fun headTime(e: Value, where: String): Long = Values.asLong(Functions.keyOf(e))
            ?: throw OracleUnsupported("$where: element ${Values.render(e)} has no integer event time at its head ([at, value])")

        private fun rowTime(e: Value, where: String): Long {
            val v = Functions.valueOf(e)
            return Values.asLong(v)
                ?: (if (v is Value.ListVal) Values.asLong(Functions.keyOf(v)) else null)
                ?: throw OracleUnsupported("$where: row ${Values.render(e)} has no integer event time ([k, at] or [k, [at, payload]])")
        }
    }

    // --- rendering ----------------------------------------------------------

    private fun renderView(type: String, fold: Fold): Value = when (if (type == DURABLE_SET_VIEW) "set-view" else type) {
        "set-view" -> Value.ListVal(Values.sortedList(asSet(fold)))
        "list-view" -> when (fold) {
            is Fold.ListF -> Value.ListVal(fold.items)
            else -> Value.ListVal(Values.sortedList(asSet(fold)))
        }
        // The tagged twin renders identically: the driver's TaggedMapView materializes
        // the same `{key -> exposed value}` Map that a plain map-view does.
        "map-view", TAGGED_MAP_VIEW -> mapToValue(asMap(fold))
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
        return foldOfLink(link)
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
         * Since computenet-yh6.1.10 put [DURABLE_SET_VIEW] into [Values.VIEW_TYPES] that
         * half of the union is idempotent; [TAGGED_MAP_VIEW] is **not** idempotent, and
         * this set is deliberately the wider of the two (see its KDoc for the residual
         * that leaves). The `+` is kept because the two answer different questions
         * ("what does the check layer call a terminal view?" versus "what can this
         * oracle fold and render?"), and this one must stay true of the durable binding
         * even if the check layer's catalog were ever narrowed again. Nothing here
         * changed behaviour when the durable halves converged:
         * [operatorFold]'s view pass-through already matched `journal-set-view` through
         * this set.
         */
        /**
         * The tagged twin of `map-view` — the only terminal that folds an
         * `ormap-source`'s TaggedMapDelta stream (KE1-F4). It renders exactly as a
         * `map-view` ([renderView]), because the driver's `TaggedMapView` materializes
         * the same `{key -> exposed value}` Map.
         *
         * **It is in [Values.VIEW_TYPES] since computenet-j2x.4.3 — the residual this
         * KDoc used to record is closed.** computenet-j2x.4.1 could only widen the
         * oracle's own set (`oracle/Values.kt` was outside its file claim), the same
         * split computenet-yh6.1.9 hit with [DURABLE_SET_VIEW] and computenet-yh6.1.10
         * later closed. The consequence while it stood was precise: `incremental-
         * equals-batch view: '*'` ([allViewValues]) and `Checks.viewCells` (from which
         * `late-join-equals-early` infers an early/late pair) both enumerate
         * `Values.VIEW_TYPES`, so they **skipped** a `tagged-map-view`, and a scenario
         * whose only view was tagged quantified over nothing and passed having read
         * nothing. computenet-j2x.4.3 authored the first scenarios that name the id
         * (`24-TMAP-MERGE-01`/`-PRESENCE-01`/`-LWW-01`/`-RESET-01`), measured that
         * vacuous pass on a probe, and widened `Values.VIEW_TYPES` — see its KDoc for
         * the evidence and the blast-radius argument. Those four name their view in
         * every check as well, so neither generalising form is load-bearing for them.
         */
        const val TAGGED_MAP_VIEW = "tagged-map-view"

        val VIEW_TYPES: Set<String> = Values.VIEW_TYPES + DURABLE_SET_VIEW + TAGGED_MAP_VIEW

        /** The catalog id of the cell that computes a waterline floor (`[24-WL-03]`). */
        const val WATERLINE = "waterline"

        /** The inlet a lateness-declaring cell reads its floor on. */
        const val WATERLINE_INLET = "waterline"

        /** Lateness-model inlet sides: a window's one data inlet, a join family's two. */
        const val IN = "in"
        const val LEFT = "left"
        const val RIGHT = "right"

        /** A lateness-declaring cell's late outlets (`[24-WL-07]`): window `late`, join family `lateLeft`/`lateRight`. */
        val LATE_OUTLETS = setOf("late", "lateLeft", "lateRight")
    }

    /** The oracle's intermediate stream state — a pure fold, one per cell. */
    private sealed interface Fold {
        data class SetF(val members: Set<Value>) : Fold
        data class MapF(val entries: Map<Value, Value>) : Fold
        data class ListF(val items: List<Value>) : Fold
        data class ScalarF(val value: Value) : Fold
    }
}
