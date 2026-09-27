package civictech.concord.driver.kernel

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.data.Aggregator
import civictech.cell.data.op.CoalescingCombineCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.CounterCell
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.ListCell
import civictech.cell.data.MapCell
import civictech.cell.partition.PartitionedCell
import civictech.cell.data.PnCounterCell
import civictech.cell.data.op.PresenceCountCell
import civictech.cell.data.OrMapCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.data.SetCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.WaterlineCell
import civictech.cell.data.Windows
import civictech.cell.observe.AlignedCompositeCell
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.View
import civictech.concord.value.Value
import java.io.Serializable
import java.util.Collections

/**
 * The neutral cell-catalog → kernel-cell binding (W1-A/W3-0, CONCORD-PLAN §1.4
 * "catalog → cell via ContractRegistry descriptors"). In practice a direct
 * typed constructor per catalog id is clearer and equally faithful — the
 * generated `ContractRegistry` descriptors are still consulted by
 * `ManagedHost.spawn` at admission (port/manifest validation), so nothing is
 * lost by not reflecting over them here.
 *
 * Only [civictech.concord.driver.kernel] imports `civictech.cell.*`; this object
 * is the single place the neutral vocabulary (catalog id, op verb, `fn`, `agg`,
 * `k`, `of`) is translated to kernel types. Elements flow **unwrapped** — a
 * scenario [Value] is lowered to `String`/`Long`/… by [unwrap] before an op or a
 * link carries it — so the [KernelFunctions] the operators consume agree element
 * for element with the harness batch oracle.
 */
internal object KernelCatalog {

    /**
     * How a view cell's materialized value is folded back into a [Value].
     * [COMPOSITE] is an `aligned-view`: its sink ([RecordedComposite]) has
     * already rendered each member with the member's own kind, so the read is a
     * `MapVal` of those renderings.
     */
    enum class ViewKind { NONE, SET, VALUE, MAP, COUNT, LIST, COMPOSITE }

    /** The outcome of building one catalog cell: the kernel [Cell] plus (for views) the sink to read. */
    data class Built(
        val cell: Cell,
        val sink: ObservationSink<*>? = null,
        val viewKind: ViewKind = ViewKind.NONE,
        /**
         * `glitch-free: true` was requested: the driver spawns a downstream
         * [civictech.cell.consistency.GlitchFreeCell] and routes [cell]'s output
         * through it, so downstream links read the wave-aligned outlet.
         */
        val glitchFree: Boolean = false,
        /**
         * The built cell coalesces **at the operator** — it emits one delta per
         * *completed* input wave, so the requested `glitch-free` semantics are
         * already its own (`CoalescingCombineCell`, `[22-GF-01]`). Two
         * consequences the driver reads off this flag: no downstream
         * [civictech.cell.consistency.GlitchFreeCell] wrapper is spawned (there is
         * no torn stream left to buffer — see [build]), and the cell's fan-in is a
         * single unrestricted `inlet` rather than the plain form's `left`/`right`
         * port pair (see [inletName]).
         */
        val waveAligned: Boolean = false,
        /**
         * For a view cell: the live observation stream, appended to
         * **synchronously on the folding thread** — see [RecordedView] for why the
         * driver records here rather than through [ObservationSink.onChange], whose
         * listener dispatch is asynchronous and left the log racily truncated (and,
         * under starvation, empty) at check time.
         *
         * The list is the driver's to read; the catalog hands over the same
         * instance it wired into the fold, so entries keep arriving as the run
         * proceeds. `null` for a non-view cell.
         */
        val observations: MutableList<Value>? = null,
    )

    /**
     * Construct a kernel cell for catalog [type] with [params] (the descriptor
     * fields `of`/`fn`/`agg`/`k`/…). Throws [UnsupportedCatalogBinding] for a
     * catalog id with no honest kernel binding — a real gap for the corpus
     * authors (§5), surfaced loudly rather than silently mis-bound.
     */
    fun build(type: String, params: Map<String, Value>): Built {
        val fn = (params["fn"] as? Value.StrVal)?.value
        val agg = (params["agg"] as? Value.StrVal)?.value ?: "count"
        val k = (params["k"] as? Value.IntVal)?.value?.toInt()
        // FU-6: a view/cell declaring `inlet-mode: single-writer` binds a strict
        // point-to-point (single-writer) FanInlet, so a second writer's connect is Rejected.
        val singleWriter = (params["inlet-mode"] as? Value.StrVal)?.value == "single-writer"
        // `glitch-free: true` (scenario.md: "request wave-aligned semantics on a
        // fan-in cell"). Read here as well as at the bottom of this function
        // because one binding — the scalar `combine-latest` — has two honest
        // kernel forms and the param selects between them.
        val glitchFreeRequested = (params["glitch-free"] as? Value.BoolVal)?.value == true
        // `lateness: L` (scenario.md §lateness, spec 24 §Lateness and waterlines,
        // computenet-t4od7.1): legal only on the catalog ids whose kernel cell
        // takes a `Windows.Lateness` — anywhere else it would be silently
        // meaningless, so it is refused by name.
        val lateness = lateness(type, params)
        val built = when (type) {
            // ---- sources ----------------------------------------------------
            "set-source" -> Built(SetCell<Any?>())
            "counter-source" -> Built(CounterCell())
            "map-source" -> Built(MapCell<Any?, Any?>())
            "list-source" -> Built(ListCell<Any?>())
            "pn-counter" -> Built(PnCounterCell())
            "keyed-set" -> Built(KeyedSetCell<Any?, Any?>())
            // `ormap-source` (96 §E1.3): the observed-remove per-key map. `put` mints a
            // fresh dot and covers the dots the writer sees live at that key
            // (reset-remove); `remove` covers exactly the dots observed live here and
            // now, so a concurrent put's dot survives the merge. Its outlet carries a
            // TaggedMapDelta, which only `tagged-map-view` folds.
            "ormap-source" -> Built(OrMapCell<Any?, Any?>())
            // `rebaseline-source` (D-C12): a tagged set source whose merge tags are
            // minted under its outlet's CURRENT emission epoch, and which re-announces
            // its recovered state on a RESTART (21-REBASE-01). `set-source`'s tag
            // source is deliberately replay-stable, so it cannot witness epoch
            // succession — see ReBaselineSourceCell.
            "rebaseline-source" -> Built(ReBaselineSourceCell())
            // quorum-set is a fan-in OPERATOR over set streams (one link per source):
            // an element is emitted when its live-source count meets the threshold.
            // `k` fixes a k-of-n quorum; absent ⇒ all live sources (intersection).
            "quorum-set" -> Built(
                if (k != null) QuorumSetCell<Any?>(threshold = { k }) else QuorumSetCell.intersection(),
            )

            // ---- operators --------------------------------------------------
            "union" -> Built(UnionSetCell<Any?>())
            "intersect" -> Built(
                // `lateness` declares the same bound on both inlets (t4od7-D3); an
                // intersect element is `[at, value]` like a window's (t4od7-D2).
                lateness?.let { l ->
                    val lat = Windows.Lateness<Any?>(EventTimeOfPair, l)
                    IntersectSetCell<Any?>(leftLateness = lat, rightLateness = lat)
                } ?: IntersectSetCell<Any?>(),
            )
            "count" -> Built(CountCell<Any?>())
            "presence-count" -> Built(PresenceCountCell<Any?>())
            "filter" -> Built(FilterCell<Any?>(predicate = KernelFunctions.predicate(requireFn(type, fn))))
            "map" -> when (fn) {
                "identity", null -> Built(IdentityCell()) // type-agnostic pass-through (set or scalar arm)
                else -> {
                    val t = KernelFunctions.transform(fn)
                    Built(FlatMapSetCell<Any?, Any?>(f = { listOf(t(it)) })) // element-wise map = singleton flatMap
                }
            }
            "flatmap" -> {
                val t = KernelFunctions.transform(requireFn(type, fn))
                Built(FlatMapSetCell<Any?, Any?>(f = { x -> flatten(t(x)) }))
            }
            "join" -> Built(
                JoinSetCell<Any?, Any?, Any?, Any?>(
                    leftKey = { KernelFunctions.keyOf(it) },
                    rightKey = { KernelFunctions.keyOf(it) },
                    // `lateness` declares both inlets (t4od7-D3); a row's event time is
                    // its value (`[k, at]`) or the value's head (`[k, [at, payload]]`).
                    leftLateness = rowLateness(lateness),
                    rightLateness = rowLateness(lateness),
                    // inner equi-join → [key, leftValue, rightValue] (matches the oracle joinFold)
                    combine = { a, b -> listOf(KernelFunctions.keyOf(a), KernelFunctions.valueOf(a), KernelFunctions.valueOf(b)) },
                ),
            )
            "semi-join" -> Built(
                SemiJoinCell<Any?, Any?, Any?>(
                    leftKey = { KernelFunctions.keyOf(it) },
                    rightKey = { KernelFunctions.keyOf(it) },
                    negated = false,
                    leftLateness = rowLateness(lateness),
                    rightLateness = rowLateness(lateness),
                ),
            )
            "lookup-join" -> Built(
                // referential lookup over set streams: enrich each left element with
                // the matched right value → [leftElem, rightValue] (matches lookupJoinFold).
                JoinSetCell<Any?, Any?, Any?, Any?>(
                    leftKey = { KernelFunctions.keyOf(it) },
                    rightKey = { KernelFunctions.keyOf(it) },
                    combine = { a, b -> listOf(a, KernelFunctions.valueOf(b)) },
                ),
            )
            "group-by" -> Built(groupBy(KernelFunctions.aggregator(agg)))
            "partition" -> Built(partitioned(KernelFunctions.aggregator(agg)))
            // The scalar `combine-latest fn: sum` has TWO bound forms, selected by
            // the existing `glitch-free` descriptor param (no new catalog id, no new
            // field):
            //  - plain (`glitch-free` absent/false) → the driver adapter
            //    [ScalarSumCombineCell]: it folds each arm's arrival straight into
            //    the running sum, so it is order-independent at quiescence
            //    (`final-view` holds) but deliberately NOT wave-aligned — a torn
            //    intermediate sum is observable mid-wave (`CTL-GF-01`). It combines
            //    two *independent* inlets, the shape `24-OP-COMBINE-01` drives.
            //  - `glitch-free: true` → the kernel's [CoalescingCombineCell]
            //    (D-COMBINE): version-buffered, emitting exactly one delta per
            //    *completed* input wave, so no torn sum is ever observable
            //    (`24-OP-COMBINE-02`, `[22-GF-01]`). Wave-alignment is a property of
            //    a fork-join: its completeness set is every open Consume inlink, so
            //    this form belongs on arms of one forked source (as any wave-aligned
            //    fan-in does), not on two independent sources.
            "combine-latest" -> when (fn) {
                "sum" -> {
                    if (glitchFreeRequested) Built(CoalescingCombineCell(), waveAligned = true)
                    else Built(ScalarSumCombineCell())
                }
                else -> throw UnsupportedCatalogBinding(
                    "combine-latest with fn='$fn' has no honest kernel binding — only the scalar fn=sum " +
                        "arm is bound (plain → ScalarSumCombineCell, order-independent at quiescence but not " +
                        "wave-aligned; `glitch-free: true` → the kernel's wave-coalescing CoalescingCombineCell). " +
                        "Combining with any other pure function is unbound.",
                )
            }
            // `window` (M11.6 "windowing = key derivation", spec 24 §Grouped aggregation,
            // 24-OP-WINDOW-01/02): NOT a kernel-gap — `Windows.tumbling`/`sliding` are pure
            // event-time → key-derivation functions the frozen `window:` descriptor
            // (CellSpec, W3-0-followup) feeds straight into the real `GroupByCell`
            // aggregation, exactly the composition kernel `WindowingTest` exercises.
            "window" -> Built(window(params, KernelFunctions.aggregator(agg), lateness))
            // `waterline` (spec 24 §Lateness and waterlines, `[24-WL-02]`/`[24-WL-03]`):
            // the event-time floor as an ordinary data cell. The scenario wires it
            // explicitly (t4od7-D1) — `{from: src, to: wl}`, then `{from: wl, to: op,
            // inlet: waterline}` into an evicting operator — and observes the floor
            // through a `value-view` (`scalarView` folds `WaterlineDelta`).
            "waterline" -> Built(
                WaterlineCell<Any?>(
                    lateness = Windows.Lateness(
                        EventTimeOfPair,
                        lateness ?: throw UnsupportedCatalogBinding(
                            "waterline requires a `lateness: <non-negative int>` param (spec 24 [24-WL-01])",
                        ),
                    ),
                ),
            )

            // ---- views ------------------------------------------------------
            "set-view" -> observeCell(View.set<Any?>(), ViewKind.SET, singleWriter)
            "value-view" -> observeCell(scalarView(), ViewKind.VALUE, singleWriter)
            "map-view" -> observeCell(View.map<Any?, Any?>(), ViewKind.MAP, singleWriter)
            // The tagged twin of `map-view`: it folds an `OrMapCell`'s TaggedMapDelta
            // stream (dots + covers) rather than a plain MapDelta, and materializes the
            // same `{key -> exposed value}` shape — so ViewKind.MAP's existing Value
            // rendering applies unchanged.
            "tagged-map-view" -> observeCell(View.taggedMap<Any?, Any?>(), ViewKind.MAP, singleWriter)
            "count-view" -> observeCell(View.count<Any?>(), ViewKind.COUNT, singleWriter)
            "list-view" -> observeCell(listView(), ViewKind.LIST, singleWriter)
            // `aligned-view` (KE2 §5.8, [22-OBS-01]/[22-OBS-02]): ONE kernel
            // AlignedCompositeCell over the named member folds of `views:` (the
            // runner lowers it to `params["views"] = MapVal(name -> StrVal(id))`).
            // Each member name is also the cell's inlet port for that member, so a
            // link into it must name `inlet:` (see [inletName]). Members bind the
            // same folds their standalone ids do; the composite is published once
            // per completed wave, so no read of it ever mixes waves. The
            // observation stream is captured by [RecordedComposite] (listener +
            // drain barrier), not at a fold — see its KDoc for why.
            "aligned-view" -> alignedView(params)

            // ---- cycles -----------------------------------------------------
            // A CycleHead: its `feedbackInput` (a FeedbackInlet) is the only inlet a
            // cycle-closing edge may land on (spec 21 §Cycles). Two catalog ids
            // distinguish the damping outcome (a `damped` descriptor param would be a
            // third schema field; two ids keep the frozen CellSpec at agg/k):
            //  - `feedback` carries a Magnitude lap payload (the damping witness) ⇒ a
            //    self-loop through it is ADMITTED and decays to a fixpoint (34-CYCLE-01).
            //  - `feedback-undamped` carries a plain CounterDelta with no witness ⇒ the
            //    same loop is REJECTED at connect (CycleWithoutDamping, FU-8; 34-CYCLE-REJECT-01).
            "feedback" -> Built(FeedbackCell(damped = true))
            "feedback-undamped" -> Built(FeedbackCell(damped = false))

            // ---- nature/ownership disputes (12-NEGOTIATE-01 / 23-SPSC-01) ---
            // `nature-gate`: an inlet DECLARING a required nature (CP-F2), so a
            // plain default-nature producer's connect is refused by the kernel's
            // real NatureNegotiation (CP-F3) — see KernelAdapters.NatureGatedSinkCell.
            "nature-gate" -> Built(NatureGatedSinkCell())
            // `exclusive-source`/`exclusive-sink`: an Owned-carrying SPSC outlet
            // (M5.6) — a second Consume link is refused by the kernel's own
            // FanOutlet exclusivity check, not a driver-side fake.
            "exclusive-source" -> Built(ExclusiveSourceCell())
            "exclusive-sink" -> exclusiveSink()

            else -> throw UnsupportedCatalogBinding("no kernel binding for catalog cell type '$type'")
        }
        // `glitch-free: true` (spec 20/22): flag the built cell so the driver spawns
        // a downstream kernel GlitchFreeCell wrapper and routes this operator's
        // output through it — the wave-completeness gate the kernel packages
        // (GlitchFreeOperatorSuiteTest construction). The wrap lives in the driver
        // (it needs a real host link so the frontier sees EdgeOpen/Progress), not
        // here — build only records the request via [Built.glitchFree]. A cell that
        // already coalesces at the operator ([Built.waveAligned]) is exempt: its
        // outlet carries one delta per completed wave, so a wrapper would re-buffer
        // an already wave-aligned stream rather than align anything.
        return if (glitchFreeRequested && !built.waveAligned) built.copy(glitchFree = true) else built
    }

    /** The catalog ids whose kernel cell takes a `Windows.Lateness` (scenario.md §lateness). */
    private val LATENESS_TYPES = setOf("window", "join", "semi-join", "intersect", "waterline")

    /**
     * The validated `lateness` param, or `null` when absent. Refuses it on a
     * catalog id outside [LATENESS_TYPES], and refuses a non-integer or negative
     * value (`Windows.Lateness` requires `lateness >= 0`).
     */
    private fun lateness(type: String, params: Map<String, Value>): Long? {
        val raw = params["lateness"] ?: return null
        if (type !in LATENESS_TYPES) {
            throw UnsupportedCatalogBinding(
                "catalog '$type' takes no `lateness` param — only ${LATENESS_TYPES.joinToString()} bind one " +
                    "(spec 24 §Lateness and waterlines; scenario.md §lateness)",
            )
        }
        val l = (raw as? Value.IntVal)?.value
            ?: throw UnsupportedCatalogBinding("`lateness` on '$type' must be an integer; got $raw")
        if (l < 0) throw UnsupportedCatalogBinding("`lateness` on '$type' must be non-negative; got $l")
        return l
    }

    /** A join/semi-join inlet's lateness over `[k, at]` / `[k, [at, payload]]` rows (t4od7-D2), or `null`. */
    private fun rowLateness(lateness: Long?): Windows.Lateness<Any?>? =
        lateness?.let { Windows.Lateness(EventTimeOfRow, it) }

    private fun requireFn(type: String, fn: String?): String =
        fn ?: throw UnsupportedCatalogBinding("catalog '$type' requires an `fn` param")

    /** Element-wise map result → set expansion: a list flattens, a scalar is a singleton (matches flatMapFold). */
    private fun flatten(mapped: Any?): Iterable<Any?> = if (mapped is List<*>) mapped else listOf(mapped)

    private fun <ACC : Serializable> groupBy(a: Aggregator<Any?, Long, ACC>): GroupByCell<Any?, Any?, Long, ACC> =
        GroupByCell(keyFn = { KernelFunctions.keyOf(it) }, aggregator = a)

    private fun <ACC : Serializable> partitioned(a: Aggregator<Any?, Long, ACC>): PartitionedCell<Any?, Any?, Long, ACC> =
        PartitionedCell(initialShardCount = 4, keyFn = { KernelFunctions.keyOf(it) }, aggregator = a)

    /**
     * Binds catalog `window` (M11.6 "windowing = key derivation"): every
     * element is a `[at, value]` pair — `at` the event-time/sequence
     * attribute (`KernelFunctions.keyOf`), `value` the payload
     * (`KernelFunctions.valueOf`) — matching the `[k, v]` convention `join`/
     * `group-by` already use. Without [lateness] windows never close
     * (`24-OP-WINDOW-02`, `[24-WL-11]`): neither arm evicts, so a late element
     * is an ordinary add and a retraction flows exactly as any other view.
     * - **tumbling**: a 1:1 function of the event time, so it needs no
     *   fan-out stage — the window start folds straight into `GroupByCell`'s
     *   `keyFn` (`24-OP-WINDOW-01`: "tumbling as a composite key"). With
     *   [lateness] the same cell is built with `Windows.Lateness(EventTimeOfPair,
     *   L)` and `keyTime(start) = start + size` (the window's exclusive end, as
     *   `GroupByCell`'s KDoc requires), so a floor delivered on its `waterline`
     *   inlet evicts passed windows (`[24-WL-06]`) and a below-floor add leaves
     *   on its `late` outlet (`[24-WL-07]`).
     * - **sliding**: one element can belong to several windows, so it binds
     *   to `WindowSlidingCell` — the real kernel `FlatMapSetCell` (per-element
     *   window-start expansion over `Windows.sliding`) linked into a real
     *   `GroupByCell` (24-OP-WINDOW-01: "sliding as per-element expansion
     *   then group"), the same two-cell composition kernel `WindowingTest`
     *   proves incremental-equals-batch on directly. It has no `waterline` inlet
     *   and no `late` outlet, so sliding + [lateness] is refused (t4od7-D4).
     */
    private fun <ACC : Serializable> window(
        params: Map<String, Value>,
        a: Aggregator<Any?, Long, ACC>,
        lateness: Long?,
    ): Cell {
        val descriptor = (params["window"] as? Value.MapVal)?.entries
            ?: throw UnsupportedCatalogBinding("window requires a `window: {kind, size, slide?}` descriptor")
        val kind = (descriptor["kind"] as? Value.StrVal)?.value
            ?: throw UnsupportedCatalogBinding("window descriptor needs a string `kind` (tumbling|sliding)")
        val size = (descriptor["size"] as? Value.IntVal)?.value
            ?: throw UnsupportedCatalogBinding("window descriptor needs an integer `size`")
        return when (kind) {
            "tumbling" -> {
                val bucket = Windows.tumbling(size)
                if (lateness == null) {
                    GroupByCell(keyFn = { e: Any? -> bucket(eventTime(e)) }, aggregator = a)
                } else {
                    GroupByCell(
                        keyFn = { e: Any? -> bucket(eventTime(e)) },
                        aggregator = a,
                        lateness = Windows.Lateness(EventTimeOfPair, lateness),
                        keyTime = WindowEnd(size),
                    )
                }
            }
            "sliding" -> {
                if (lateness != null) {
                    throw UnsupportedCatalogBinding(
                        "window kind 'sliding' with `lateness` is unbound — it binds to WindowSlidingCell, which " +
                            "has no `waterline` inlet and no `late` outlet (t4od7-D4); only kind 'tumbling' " +
                            "declares lateness",
                    )
                }
                val slide = (descriptor["slide"] as? Value.IntVal)?.value
                    ?: throw UnsupportedCatalogBinding("sliding window descriptor needs an integer `slide`")
                WindowSlidingCell(Windows.sliding(size, slide), a)
            }
            else -> throw UnsupportedCatalogBinding("window kind '$kind' unbound (tumbling|sliding)")
        }
    }

    /** A window element's event-time/sequence attribute (the `at` of a `[at, value]` pair). */
    private fun eventTime(e: Any?): Long =
        KernelFunctions.asLong(KernelFunctions.keyOf(e)) ?: error("window element's event-time key is not an integer: $e")

    /** `exclusive-sink`: a running-count view over `ExclusivePush` deliveries (23-SPSC-01). */
    private fun exclusiveSink(): Built {
        val cell = ExclusiveSinkCell()
        val log = mutableListOf<Value>()
        // This adapter fires its listeners inline, under its own lock, on the
        // delivering thread — no dispatcher hop — so `onChange` here *is* the
        // synchronous settled stream (and its immediate catch-up seeds the log,
        // exactly as [RecordedView]'s constructor does for the folded views).
        cell.onChange { log += readView(ViewKind.COUNT, it) }
        return Built(cell, cell, ViewKind.COUNT, observations = log)
    }

    /**
     * Build one view cell over [view], with its observation stream recorded at the
     * fold ([RecordedView]) rather than through the sink's asynchronous listener
     * dispatch — the log the driver publishes must be complete and identical on
     * every run of the schedule sweep, on a loaded machine as much as an idle one.
     */
    private fun <D : Any, S> observeCell(view: View<D, S>, kind: ViewKind, singleWriter: Boolean = false): Built {
        val log = mutableListOf<Value>()
        val recorded = RecordedView(view, kind, log)
        return if (singleWriter) {
            val cell = SingleWriterObserveCell(recorded)
            Built(cell, cell, kind, observations = log)
        } else {
            val cell = ObserveCell(recorded)
            Built(cell, cell, kind, observations = log)
        }
    }

    /**
     * Binds `aligned-view`: `params["views"]` must be a non-empty `MapVal` of
     * view name -> member view id. Only the member ids whose standalone fold is
     * a plain [View] bind here (`set-view`, `map-view`, `count-view`,
     * `value-view`); anything else — `list-view`, `tagged-map-view`, a nested
     * `aligned-view`, an unknown id — is refused by name rather than bound to a
     * fold that does not mean what the scenario says.
     *
     * The cell is constructed by the `views =` named argument only, and the
     * binding touches only `current()`, `onChange`, `inlets` and `close()`, so
     * additive constructor params on [AlignedCompositeCell] do not reach it.
     */
    private fun alignedView(params: Map<String, Value>): Built {
        val members = (params["views"] as? Value.MapVal)?.entries
            ?: throw UnsupportedCatalogBinding(
                "aligned-view requires a `views:` map of view name -> member view id " +
                    "(set-view | map-view | count-view | value-view); got ${params["views"]}",
            )
        if (members.isEmpty()) {
            throw UnsupportedCatalogBinding("aligned-view requires a non-empty `views:` map; got {}")
        }
        val folds = LinkedHashMap<String, View<*, *>>()
        val kinds = LinkedHashMap<String, ViewKind>()
        for ((name, idValue) in members) {
            val id = (idValue as? Value.StrVal)?.value
                ?: throw UnsupportedCatalogBinding(
                    "aligned-view member '$name' must name a member view id as a string; got $idValue",
                )
            val (fold, kind) = when (id) {
                "set-view" -> View.set<Any?>() to ViewKind.SET
                "map-view" -> View.map<Any?, Any?>() to ViewKind.MAP
                "count-view" -> View.count<Any?>() to ViewKind.COUNT
                "value-view" -> scalarView() to ViewKind.VALUE
                else -> throw UnsupportedCatalogBinding(
                    "aligned-view member '$name' names '$id', which has no aligned-view binding — only " +
                        "set-view, map-view, count-view and value-view fold into an aligned composite",
                )
            }
            folds[name] = fold
            kinds[name] = kind
        }
        val cell = AlignedCompositeCell(views = folds)
        // Appended on the sink's dispatcher thread, read on the runner thread
        // after [RecordedComposite.drain]: the drain latch's countDown/await is
        // the happens-before edge; the synchronized list is belt and braces.
        val log: MutableList<Value> = Collections.synchronizedList(mutableListOf())
        val sink = RecordedComposite(cell, kinds, log)
        return Built(cell, sink, ViewKind.COMPOSITE, observations = log)
    }

    /**
     * The kernel inlet port name a scenario link's [scenarioInlet] targets on a
     * cell of catalog [targetType]. The neutral `left`/`right` inlets of a
     * single-port fan-in (`union`/`intersect`/`quorum-set`) collapse to the
     * kernel's one `inlet`; two-input operators (`combine-latest`, the join
     * family) keep their distinct `left`/`right` ports. Everything else uses the
     * given name or defaults to `inlet` — which lets a cycle edge name
     * `feedbackInput` and a seed edge default to `inlet`.
     *
     * [waveAligned] is the target's [Built.waveAligned]: the wave-coalescing
     * `combine-latest` ([CoalescingCombineCell]) is a genuine single-port
     * unrestricted fan-in like `quorum-set`, so *both* neutral arms collapse onto
     * its one `inlet` — each still its own link, hence its own expected edge in
     * the cell's completeness set.
     *
     * An `aligned-view` has one inlet per member view, named after it, and no
     * port called `inlet`: a link into one must name its member, so a missing
     * [scenarioInlet] is refused here rather than defaulted to a port that does
     * not exist.
     */
    fun inletName(targetType: String, scenarioInlet: String?, waveAligned: Boolean = false): String = when {
        targetType == "aligned-view" -> scenarioInlet ?: throw UnsupportedCatalogBinding(
            "a link into an aligned-view names `inlet:` as one of its view names — the cell has one inlet " +
                "per member view and no port called `inlet`",
        )
        // union/quorum-set are single fan-in ports (one `inlet`, left/right merge on it);
        // intersect is NOT — IntersectSetCell exposes distinct `left`/`right` ports (its
        // contract has no `inlet` port), so it routes through the two-input branch.
        targetType == "union" || targetType == "quorum-set" -> "inlet"
        targetType == "combine-latest" -> if (waveAligned) "inlet" else scenarioInlet ?: "left"
        targetType == "intersect" || targetType == "join" ||
            targetType == "semi-join" || targetType == "lookup-join" -> scenarioInlet ?: "left"
        else -> scenarioInlet ?: "inlet"
    }

    /** The kernel outlet port name for a source of catalog [sourceType]; defaults to `outlet` (a cycle names `loopOutlet`). */
    fun outletName(sourceType: String, scenarioOutlet: String?): String = scenarioOutlet ?: "outlet"

    /**
     * Translate a neutral op verb + [Value] payload into a reflective
     * ([methodName], jvm-parameter-type-names, boxed-args) triple the driver
     * routes through the host router to the source cell's `inlet` API. Throws
     * [UnsupportedCatalogBinding] for an unbound (type, op) pair.
     */
    fun op(type: String, op: String, value: Value?): OpCall = when (type) {
        "set-source" -> when (op) {
            "add" -> OpCall("add", OBJECT, listOf(unwrap(value)))
            "remove" -> OpCall("remove", OBJECT, listOf(unwrap(value)))
            else -> throw UnsupportedCatalogBinding("set-source op '$op' unbound")
        }
        "counter-source", "pn-counter" -> when (op) {
            "increment" -> OpCall("increment", LONG, listOf(amount(value)))
            "decrement" -> OpCall("decrement", LONG, listOf(amount(value)))
            else -> throw UnsupportedCatalogBinding("$type op '$op' unbound")
        }
        "map-source" -> when (op) {
            "put" -> keyValue(value).let { (kk, vv) -> OpCall("put", OBJECT2, listOf(kk, vv)) }
            "remove", "remove-key" -> OpCall("remove", OBJECT, listOf(unwrap(value)))
            else -> throw UnsupportedCatalogBinding("map-source op '$op' unbound")
        }
        "keyed-set" -> when (op) {
            // keyed upsert: `put` sets the element under a key (last-writer-wins per key),
            // `remove` drops the key. Distinct from set-source add/remove-by-value.
            "put" -> keyValue(value).let { (kk, vv) -> OpCall("put", OBJECT2, listOf(kk, vv)) }
            "remove", "remove-key" -> OpCall("remove", OBJECT, listOf(unwrap(value)))
            else -> throw UnsupportedCatalogBinding(
                "keyed-set op '$op' unbound — the kernel KeyedSetCell is a keyed upsert " +
                    "(put(key,element)/remove(key)), NOT an add/remove-by-value set (§5 catalog refinement)",
            )
        }
        "ormap-source" -> when (op) {
            // OrMapCell's inlet is the same MapOps shape as map-source's: put(key,value)
            // / remove(key). What differs is the delta it emits (dots + covers), not the
            // op surface — so the lowering mirrors keyed-set/map-source exactly.
            "put" -> keyValue(value).let { (kk, vv) -> OpCall("put", OBJECT2, listOf(kk, vv)) }
            "remove", "remove-key" -> OpCall("remove", OBJECT, listOf(unwrap(value)))
            else -> throw UnsupportedCatalogBinding(
                "ormap-source op '$op' unbound — the kernel OrMapCell is an observed-remove " +
                    "per-key map (put(key,value)/remove(key)); there is no remove-by-value",
            )
        }
        "list-source" -> when (op) {
            "append" -> OpCall("add", OBJECT, listOf(unwrap(value)))
            "insert" -> indexElem(value).let { (i, e) -> OpCall("add", INT_OBJECT, listOf(i, e)) }
            "set" -> indexElem(value).let { (i, e) -> OpCall("set", INT_OBJECT, listOf(i, e)) }
            "remove-at" -> OpCall("removeAt", INT, listOf((unwrap(value) as Number).toInt()))
            else -> throw UnsupportedCatalogBinding(
                "list-source op '$op' unbound — the kernel ListCell is index-addressed " +
                    "(append / insert[i,e] / set[i,e] / remove-at[i]); there is no remove-by-value (§5)",
            )
        }
        "rebaseline-source" -> when (op) {
            "add" -> OpCall("add", OBJECT, listOf(unwrap(value)))
            else -> throw UnsupportedCatalogBinding(
                "rebaseline-source op '$op' unbound — it is an add-only tagged source; a restart is " +
                    "requested with the `restart` step verb, not with an op (see KernelDriver.restart)",
            )
        }
        "exclusive-source" -> when (op) {
            "push" -> OpCall("push", OBJECT, listOf(unwrap(value)))
            else -> throw UnsupportedCatalogBinding("exclusive-source op '$op' unbound")
        }
        else -> throw UnsupportedCatalogBinding("no op binding for '$op' on catalog type '$type'")
    }

    /**
     * The invocation the driver's `restart` verb delivers to a cell of catalog
     * [type] to induce a **real** supervision RESTART (D-C12).
     *
     * `ManagedHost` runs its RESTART branch — generation bump, per-outlet fresh
     * epoch, checkpoint restore, `ReBaseline` — from exactly one place: the
     * invocation-failure handler of a cell supervised `SupervisionPolicy.RESTART`.
     * So the honest trigger is a *failing invocation*, the same poison pattern
     * kernel `RestartReBaselineTest` uses; there is no synthetic rollback
     * anywhere on this path.
     *
     * Delivered through a [HostedCellProxy], **not** through the host's routing
     * inlet the way [op] payloads are: `route` resolves the target port and
     * invokes its served handler inside the router's own scheduler task, so a
     * throw there is caught by the host's generic task guard — dead-lettered,
     * but never attributed to the target cell and therefore never supervised. A
     * proxy send stages the invocation *as that cell's*, which is what puts it
     * under the cell's own supervision policy.
     *
     * Kept here rather than in [op] because it is this binding's way of inducing
     * a recovery, not a neutral op a scenario may name.
     */
    fun restartTrigger(type: String): (ManagedHost, CellRef) -> Unit = when (type) {
        "rebaseline-source" -> { host, ref ->
            (HostedCellProxy.create(ref, host, ReBaselineSourceProxy::class.java) as ReBaselineSourceProxy)
                .inlet.call.failInvocation()
        }
        else -> throw UnsupportedCatalogBinding(
            "catalog type '$type' has no restart trigger — only `rebaseline-source` is bound as a " +
                "restartable source (D-C12); restarting any other catalog cell is unbound rather than faked",
        )
    }

    /** A routable op: the served-handler method and its erased signature. */
    data class OpCall(val methodName: String, val parameterTypes: List<String>, val args: List<Any?>)

    private val OBJECT = listOf("java.lang.Object")
    private val OBJECT2 = listOf("java.lang.Object", "java.lang.Object")
    private val LONG = listOf("long")
    private val INT = listOf("int")
    private val INT_OBJECT = listOf("int", "java.lang.Object")

    /** A value-less counter op defaults to a unit step. */
    private fun amount(value: Value?): Long = (value as? Value.IntVal)?.value ?: 1L

    /** A `put`'s `[key, value]` (or `{key:, value:}`) payload, unwrapped. */
    private fun keyValue(value: Value?): Pair<Any?, Any?> = when (value) {
        is Value.ListVal -> if (value.items.size == 2) unwrap(value.items[0]) to unwrap(value.items[1])
            else error("put needs [key, value], got $value")
        is Value.MapVal -> unwrap(value.entries["key"]) to unwrap(value.entries["value"])
        else -> error("put needs [key, value] or {key:, value:}, got $value")
    }

    /** An `insert`/`set`'s `[index, element]` payload, unwrapped. */
    private fun indexElem(value: Value?): Pair<Int, Any?> = when (value) {
        is Value.ListVal -> if (value.items.size == 2) (unwrap(value.items[0]) as Number).toInt() to unwrap(value.items[1])
            else error("insert/set needs [index, element], got $value")
        else -> error("insert/set needs [index, element], got $value")
    }

    /** Unwrap a scalar [Value] to the Kotlin value a source cell holds. */
    fun unwrap(value: Value?): Any? = when (value) {
        null, Value.NullVal -> null
        is Value.StrVal -> value.value
        is Value.IntVal -> value.value
        is Value.RealVal -> value.value
        is Value.BoolVal -> value.value
        is Value.ListVal -> value.items.map { unwrap(it) }
        is Value.MapVal -> value.entries.mapValues { unwrap(it.value) }
    }

    /** Fold a view cell's current materialized value into the neutral [Value] model. */
    fun readView(kind: ViewKind, current: Any?): Value = when (kind) {
        ViewKind.VALUE -> Value.of(current)
        ViewKind.SET -> Value.ListVal((current as Set<*>).map { Value.of(it) }.sortedBy { it.toString() })
        ViewKind.LIST -> Value.ListVal((current as List<*>).map { Value.of(it) })
        ViewKind.MAP, ViewKind.COUNT -> Value.of(current)
        // Already rendered member by member by RecordedComposite.current().
        ViewKind.COMPOSITE -> {
            @Suppress("UNCHECKED_CAST")
            Value.MapVal(current as Map<String, Value>)
        }
        ViewKind.NONE -> error("readView on a non-view cell")
    }
}

/**
 * The event time of a `[at, value]` element — the head (`window`, `intersect`,
 * `waterline`; t4od7-D2). A named `Serializable` object, because
 * `Windows.Lateness.timeFn` must be one (`[24-WL-01]`: it survives graph-spec
 * capture); a lambda would not.
 */
internal object EventTimeOfPair : (Any?) -> Long, Serializable {
    override fun invoke(e: Any?): Long =
        KernelFunctions.asLong(KernelFunctions.keyOf(e))
            ?: throw IllegalArgumentException("element's event time (the head of [at, value]) is not an integer: $e")

    private fun readResolve(): Any = EventTimeOfPair
}

/**
 * The event time of a `join`/`semi-join` row `[k, v]` (t4od7-D2): `v` itself
 * when it is an integer (`[k, at]`), else the head of `v` when `v` is a list
 * (`[k, [at, payload]]`). Named and `Serializable` for the same reason as
 * [EventTimeOfPair].
 */
internal object EventTimeOfRow : (Any?) -> Long, Serializable {
    override fun invoke(e: Any?): Long {
        val v = KernelFunctions.valueOf(e)
        return KernelFunctions.asLong(v)
            ?: (if (v is List<*>) KernelFunctions.asLong(KernelFunctions.keyOf(v)) else null)
            ?: throw IllegalArgumentException(
                "row's event time is neither an integer value ([k, at]) nor the head of a list value " +
                    "([k, [at, payload]]): $e",
            )
    }

    private fun readResolve(): Any = EventTimeOfRow
}

/** A tumbling window key's exclusive end (`keyTime` of a lateness-declaring `window`): `start + size`. */
internal data class WindowEnd(val size: Long) : (Long) -> Long, Serializable {
    override fun invoke(start: Long): Long = start + size
}

/** A catalog id / op the kernel driver cannot honestly bind — a real gap, not a soft failure. */
class UnsupportedCatalogBinding(message: String) : RuntimeException(message)
