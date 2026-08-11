package civictech.concord.driver.kernel

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Owned
import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.data.Aggregator
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.ListDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.control.Magnitude
import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.Propagate
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.View
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.FeedbackInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.Use
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.concord.value.Value
import civictech.nature.CellColor
import civictech.nature.CellDescriptor
import civictech.nature.ContractDescriptor
import civictech.nature.ContractModule
import civictech.nature.ContractRegistry
import civictech.nature.MergeClass
import civictech.nature.MethodDescriptor
import civictech.nature.NatureVector
import civictech.nature.PortDescriptor
import civictech.nature.PortDirection
import java.io.Serializable
import java.util.UUID

/**
 * Driver-internal adapter cells and view folds (W1-A/W3-0). These live **only**
 * in `civictech.concord.driver.kernel` — the one package permitted to import
 * `civictech.cell.*` — and exist to bind catalog ids that have no clean 1:1
 * kernel cell as of the pinned commit (see `cell-catalog.md` "Kernel-binding
 * notes and gaps"). They compose the kernel's own port primitives
 * ([FanInlet]/[FanOutlet]/[FeedbackInlet]/[Propagate]); the kernel is **not**
 * modified.
 */

/**
 * Binds the catalog `map` operator with `fn: identity` — a pass-through arm.
 * The kernel ships no element-map cell (only [civictech.cell.data.FlatMapSetCell]
 * over set streams), and the diamond exemplar applies `map, fn: identity` to a
 * *counter* stream, so no set-shaped cell fits either. This trivial pass-through
 * re-propagates every delta unchanged and is type-agnostic (erased `Propagate`),
 * so it binds identity arms over both `SetDelta` (21-PIPE-01) and `CounterDelta`
 * (22-GF-DIAMOND-01) streams. A *non-identity* element map binds to
 * [civictech.cell.data.FlatMapSetCell] with a singleton transform (see
 * [KernelCatalog]).
 */
class IdentityCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Propagate<Any>>())
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<Any>>())

    init {
        inlet.serve(Propagate<Any> { value -> outlet.call.propagate(value) })
    }
}

/**
 * Binds the **plain** (non-`glitch-free`) catalog `combine-latest` operator with
 * `fn: sum` over two **scalar counter** arms — two independent inlets summed into
 * one value (`24-OP-COMBINE-01`).
 *
 * The kernel's [civictech.cell.data.CombineLatestCell] is a *keyed-map* outer
 * combine (`MapDelta<K, …>`), so it cannot combine two `CounterDelta` streams;
 * this adapter holds each arm's running total and emits the *change in the sum*
 * as a `CounterDelta`, which a `value-view` folds back to the scalar sum. Sum of
 * all emitted deltas equals `left + right` regardless of arrival order, so the
 * final value is order-independent — `final-view` holds under any schedule.
 *
 * Deliberately **not** wave-aligned, and no longer a gap: it folds each arm's
 * arrival straight into the running sum, so the two arms of one *forked* source
 * wave arrive as distinct waves and a scalar observer can fold the torn
 * intermediate sum (`CTL-GF-01`, the control that pins exactly this). Wrapping it
 * in a downstream `GlitchFreeCell` cannot rescue that — the per-arm emissions are
 * two *complete* one-edge waves, which the wrapper faithfully replays. A scenario
 * that wants wave-aligned scalar semantics asks for them with `glitch-free: true`,
 * which binds the kernel's `CoalescingCombineCell` instead (version-buffered, one
 * delta per completed wave — D-COMBINE, `24-OP-COMBINE-02`, `[22-GF-01]`). This
 * form is retained because that one is a *fork-join* operator: its completeness
 * set is every open Consume inlink, so it aligns arms of one source, whereas this
 * one combines genuinely independent inlets.
 */
class ScalarSumCombineCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
    val left = registerPort("left", FanInlet.create<Propagate<CounterDelta>>())
    val right = registerPort("right", FanInlet.create<Propagate<CounterDelta>>())
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

    private var leftTotal = 0L
    private var rightTotal = 0L
    private var lastSum = 0L

    init {
        left.serve(Propagate<CounterDelta> { d -> leftTotal += d.amount; emitDelta() })
        right.serve(Propagate<CounterDelta> { d -> rightTotal += d.amount; emitDelta() })
    }

    private fun emitDelta() {
        val sum = leftTotal + rightTotal
        val diff = sum - lastSum
        lastSum = sum
        if (diff != 0L) outlet.call.propagate(CounterDelta(diff))
    }

    override fun snapshot(): Serializable = longArrayOf(leftTotal, rightTotal, lastSum)

    override fun restore(state: Serializable) {
        val s = state as LongArray
        leftTotal = s[0]; rightTotal = s[1]; lastSum = s[2]
    }
}

/**
 * A [View] decorator that records the observation stream **synchronously, at the
 * fold** — the sequence of settled materialized values the corpus's
 * `observations-*` checks read.
 *
 * Why not [ObservationSink.onChange], which the driver used to register for this?
 * Because the kernel's [civictech.cell.observe.ObserveCell] deliberately invokes
 * its listeners on a per-sink single-thread executor (Observe.kt, T08 finding 4:
 * a blocking app listener must not pin the host's dispatch). That is the right
 * contract for an *app* listener and the wrong one for the harness: the runner
 * quiesces the deterministic [civictech.cell.host.SimulationController] on its own
 * thread and then reads the log, with no happens-before edge to the dispatcher
 * thread, so the log it reads is whatever that thread happened to have appended.
 * An oversubscribed machine can starve the dispatcher for the whole run and leave
 * the log **empty**, which silently turns every `observations-*` check vacuous —
 * observed as `CTL-GF-01` passing its declared check when a control must fail it
 * (computenet-dqy.18).
 *
 * Recording here instead removes the thread hop entirely: [apply] runs on the
 * host scheduler thread, inside `ObserveCell`'s own fold lock, in exactly the
 * total order the kernel applies deltas. In the simulation that thread *is* the
 * controller thread, so the log is complete and identical on every run — the
 * determinism the whole schedule sweep depends on. [log] is a plain list for
 * that reason: it is written and read on one thread.
 *
 * The constructor appends the fold's initial value, which is what `onChange`'s
 * late-join catch-up used to contribute as the stream's first element. A caller
 * that rebuilds a cell over a surviving log (the durable driver's crash/recover
 * cycle) therefore gets one catch-up entry per rebuild, as before.
 */
internal class RecordedView<D : Any, S>(
    private val inner: View<D, S>,
    private val kind: KernelCatalog.ViewKind,
    private val log: MutableList<Value>,
) : View<D, S> {

    init {
        log += KernelCatalog.readView(kind, inner.current())
    }

    override fun apply(delta: D): Boolean {
        val changed = inner.apply(delta)
        if (changed) log += KernelCatalog.readView(kind, inner.current())
        return changed
    }

    override fun current(): S = inner.current()

    override fun snapshot(): Serializable = inner.snapshot()

    override fun restore(state: Serializable) = inner.restore(state)
}

/**
 * The scalar `value-view` fold the kernel's [View] companion is missing (it
 * ships only `set` / `map` / `count`). Folds a scalar delta stream into a single
 * running `Long`, dispatching on the payload type so **one** `value-view` binding
 * serves both `counter-source`/`combine-latest`/`feedback` ([CounterDelta], raw
 * addition) and `pn-counter` ([PnCounterDelta], per-source pointwise-max union).
 * The inlet is erased ([civictech.cell.host.ObserveCell] serves `Propagate<Any>`),
 * so the same cell folds whichever scalar delta its producer emits.
 */
fun scalarView(): View<Any, Long> = object : View<Any, Long> {
    private var counterTotal = 0L
    private val incs = HashMap<UUID, Long>()
    private val decs = HashMap<UUID, Long>()

    override fun apply(delta: Any): Boolean {
        val before = current()
        when (delta) {
            is CounterDelta -> counterTotal += delta.amount
            is PnCounterDelta -> {
                delta.incs.forEach { (s, t) -> incs[s] = maxOf(incs[s] ?: 0L, t) }
                delta.decs.forEach { (s, t) -> decs[s] = maxOf(decs[s] ?: 0L, t) }
            }
            else -> return false
        }
        return current() != before
    }

    override fun current(): Long = counterTotal + incs.values.sum() - decs.values.sum()

    override fun snapshot(): Serializable = arrayListOf<Serializable>(counterTotal, HashMap(incs), HashMap(decs))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val parts = state as ArrayList<Serializable>
        counterTotal = parts[0] as Long
        incs.clear(); incs.putAll(parts[1] as Map<UUID, Long>)
        decs.clear(); decs.putAll(parts[2] as Map<UUID, Long>)
    }
}

/**
 * The ordered `list-view` fold — the kernel [View] companion ships no list fold
 * (only set/map/count), so a `list-source` ([ListDelta] stream) had no terminal
 * sink. Folds positional deltas (append/insert/set/remove-at) into a live
 * `List`, so `readView` returns the ordered sequence.
 */
fun listView(): View<ListDelta<Any?>, List<Any?>> = object : View<ListDelta<Any?>, List<Any?>> {
    private val items = ArrayList<Any?>()

    override fun apply(delta: ListDelta<Any?>): Boolean {
        var changed = false
        delta.adds.forEach { iv -> items.add(iv.index.coerceIn(0, items.size), iv.value); changed = true }
        delta.updates.forEach { iv -> if (iv.index in items.indices) { items[iv.index] = iv.value; changed = true } }
        delta.removals.sortedDescending().forEach { i -> if (i in items.indices) { items.removeAt(i); changed = true } }
        return changed
    }

    override fun current(): List<Any?> = ArrayList(items)

    override fun snapshot(): Serializable = ArrayList(items)

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        items.clear(); items.addAll(state as List<Any?>)
    }
}

/**
 * A geometrically-decaying feedback lap payload (spec 21 §Cycles): [Magnitude]
 * so a [FeedbackInlet]'s weak tier absorbs it once the value halves to `0`
 * (`size() = 0` ≤ the head's quiescence), and — being `Magnitude`-typed — it is
 * itself the *damping witness* that admits the cycle at connect (FU-8).
 */
data class DecayDelta(val v: Long) : Magnitude, Serializable {
    override fun size(): Double = Math.abs(v).toDouble()
}

/**
 * Binds the catalog `feedback` cell — a [civictech.cell.port.CycleHead] making
 * the `34-CYCLE-*` scenarios drivable. A self-loop `loopOutlet → feedbackInput`
 * is the cycle-closing edge; [ManagedHost.connect][civictech.cell.host.ManagedHost]
 * admits it only because it lands on the [FeedbackInlet] head (headedness) AND
 * carries a damping witness (FU-8):
 *
 * - **[damped] = true** (default): laps carry a [Magnitude] [DecayDelta] that
 *   halves each iteration, so the loop decays to a fixpoint and the head's weak
 *   tier absorbs the terminal `0`. The Magnitude payload is the witness ⇒ the
 *   cycle is **admitted**. Seeding the cell with a single `CounterDelta(S)` (a
 *   `counter-source` increment of `S`) yields the running total
 *   `S + ⌊S/2⌋ + ⌊S/4⌋ + … + 1` — for `S = 2^n − k` a clean closed form
 *   (`S = 64 → 127`), read through a `value-view`.
 * - **[damped] = false**: laps carry a plain `CounterDelta` — not `Magnitude`,
 *   and `loopOutlet` declares neither MONOTONE nor IDEMPOTENT, and the head has
 *   no quiescence override — so the same self-loop is **rejected** at connect
 *   (`CycleWithoutDamping`), driving `34-CYCLE-REJECT-01`.
 *
 * Topology a scenario wires (all ports named explicitly on the cycle edge):
 * `counter-source.outlet → feedback.inlet`; `feedback.loopOutlet →
 * feedback.feedbackInput` (`expect: rejected` when `damped=false`);
 * `feedback.outlet → value-view`.
 */
class FeedbackCell(
    private val damped: Boolean,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, Stateful {

    /** The running total, emitted incrementally for a `value-view`. */
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<CounterDelta>>())

    /**
     * The lap emitter: its payload closes the cycle back onto [feedbackInput]. A
     * cycle edge feeds a [FeedbackInlet] (a `Consumer`), so the loop outlet is a
     * `Consumer`-typed fan-out (the proven kernel cycle-wiring shape) — emit via
     * `provide`, not `propagate`.
     */
    val loopOutlet = registerPort("loopOutlet", FanOutlet.create<Consumer<Any>>())

    /** The cycle-closing terminus (the CycleHead). Payload type gates the damping witness. */
    val feedbackInput = registerPort(
        "feedbackInput",
        FeedbackInlet<Any>(
            quiescence = 0.0,
            payloadType = if (damped) DecayDelta::class.java else CounterDelta::class.java,
            onLap = { lap -> onLap(lap) },
        ),
    )

    /** The seed inlet: a `counter-source` outlet kicks the loop off from here. */
    val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

    private var total = 0L

    init {
        inlet.serve(Propagate<CounterDelta> { d -> absorb(d.amount) })
    }

    private fun onLap(lap: Any) {
        val x = when (lap) {
            is DecayDelta -> lap.v
            is CounterDelta -> lap.amount
            else -> 0L
        }
        absorb(x)
    }

    private fun absorb(x: Long) {
        if (x == 0L) return
        total += x
        outlet.call.propagate(CounterDelta(x))
        val next = x / 2
        loopOutlet.call.provide(if (damped) DecayDelta(next) else CounterDelta(next))
    }

    override fun snapshot(): Serializable = total

    override fun restore(state: Serializable) {
        total = state as Long
    }
}

/**
 * Binds catalog `window` with `kind: sliding` (M11.6 "windowing = key
 * derivation", spec 24 §Grouped aggregation, `24-OP-WINDOW-01`): one element
 * can belong to several sliding windows, so — as the spec names the
 * composition verbatim — this wraps a real kernel [FlatMapSetCell] (the
 * per-element window-start expansion over [starts], a
 * [civictech.cell.data.Windows.sliding] assigner) linked into a real kernel
 * [GroupByCell] (the aggregation), via the same negotiated kernel link
 * kernel `WindowingTest`'s `sliding windows - flatMap expansion plus groupBy
 * equals batch` case exercises directly — packaged as one [Cell] so the
 * driver's one-cell-per-catalog-id model (`KernelCatalog.build`) can spawn it.
 * (`window kind: tumbling` needs no such wrapper: the window start is a 1:1
 * function of the event time, so it binds straight to a bare [GroupByCell]
 * whose `keyFn` composes the tumbling assigner — see `KernelCatalog.window`.)
 *
 * Each inbound element is a `[at, value]` pair; [starts] maps `at` to every
 * window start it falls in, and each expands to `[windowStart, value]` —
 * `GroupByCell.keyFn` groups on the first component, `aggregator` folds the
 * second (`KernelFunctions.keyOf`/`valueOf`, the same `[k, v]` convention
 * `join`/`group-by` use). Windows never close (`24-OP-WINDOW-02`): neither
 * [FlatMapSetCell] nor [GroupByCell] evicts on a timer, so a late element is
 * an ordinary add through the same expand-then-group path and a retraction
 * flows exactly as any other view.
 */
class WindowSlidingCell<ACC : Serializable>(
    starts: (Long) -> List<Long>,
    aggregator: Aggregator<Any?, Long, ACC>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, Stateful {

    private val expand = FlatMapSetCell<Any?, Any?>(f = { e ->
        val at = KernelFunctions.asLong(KernelFunctions.keyOf(e))
            ?: error("window element's event-time key is not an integer: $e")
        val value = KernelFunctions.valueOf(e)
        starts(at).map { windowStart -> listOf(windowStart, value) }
    })

    private val grouped = GroupByCell(
        keyFn = { e: Any? -> KernelFunctions.keyOf(e) },
        aggregator = aggregator,
    )

    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Any?>>>())
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<MapDelta<Any?, Long>>>())

    init {
        @Suppress("UNCHECKED_CAST")
        expand.outlet.linkTo(grouped.inlet as LinkFrom<Propagate<SetDelta<Any?>>>)
        inlet.serve(Propagate<SetDelta<Any?>> { d -> expand.inlet.call.propagate(d) })
        grouped.outlet.subscribe(
            Use.fixed(Propagate<MapDelta<Any?, Long>> { d -> outlet.call.propagate(d) }, PortRef.generate()),
        )
    }

    override fun snapshot(): Serializable = arrayListOf(expand.snapshot(), grouped.snapshot())

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val parts = state as ArrayList<Serializable>
        expand.restore(parts[0])
        grouped.restore(parts[1])
    }
}

/**
 * A [civictech.cell.observe.ObserveCell] whose inlet is a **single-writer** (strict
 * point-to-point) [FanInlet] (FU-6). Binds a view/cell declaring
 * `inlet-mode: single-writer`: the kernel `ObserveCell` always constructs a plain
 * multi-writer inlet, so a scenario asserting "a second writer's `connect` is
 * Rejected" (13-LINK-05, exemplar (d)) needs this driver variant — the kernel is
 * not modified. Identical fold/observation/state behaviour to `ObserveCell`; the
 * only difference is `FanInlet.create(singleWriter = true)`, so `linkFrom` refuses
 * a second [civictech.cell.link.LinkRole.Consume] producer while Observe taps stay
 * unrestricted.
 */
class SingleWriterObserveCell<D : Any, S>(
    private val view: View<D, S>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, Stateful, ObservationSink<S> {

    val inlet = registerPort("inlet", FanInlet.create<Propagate<D>>(singleWriter = true))

    private val lock = Any()
    private val listeners = mutableListOf<(S) -> Unit>()

    @Volatile
    private var latest: S = view.current()

    init {
        inlet.serve(object : Propagate<D> {
            override fun propagate(value: D) {
                synchronized(lock) {
                    if (view.apply(value)) {
                        latest = view.current()
                        listeners.forEach { it(latest) }
                    }
                }
            }
        })
    }

    override fun current(): S = latest

    override fun onChange(listener: (S) -> Unit) {
        synchronized(lock) {
            listeners += listener
            listener(latest)
        }
    }

    override fun snapshot(): Serializable = synchronized(lock) { view.snapshot() }

    override fun restore(state: Serializable) {
        synchronized(lock) {
            view.restore(state)
            latest = view.current()
        }
    }
}

/**
 * The command port `apply(..., op: add, value:)` routes to on a
 * [ReBaselineSourceCell], plus the failing invocation the driver's `restart`
 * verb routes to induce a real supervision RESTART ([failInvocation]).
 *
 * [failInvocation] is deliberately **not** in the neutral op table
 * (`KernelCatalog.op`): a scenario asks for a restart with the `restart` step,
 * and *how* this binding induces one — the kernel's RESTART branch only runs
 * off a failed invocation — is the driver's business, not the scenario's
 * vocabulary (see [civictech.concord.driver.Driver.restart]).
 */
interface ReBaselineSourceOps {
    fun add(element: Any?)
    fun failInvocation()
}

/**
 * The structural-navigation shape a [civictech.cell.host.HostedCellProxy] needs
 * to send [ReBaselineSourceOps.failInvocation] *as the target cell's own
 * invocation* — the dispatch path that puts it under that cell's supervision
 * policy (see `KernelCatalog.restartTrigger`). Mirrors kernel
 * `RestartReBaselineTest`'s `ProducerProxy`.
 */
interface ReBaselineSourceProxy {
    val inlet: Use<ReBaselineSourceOps>
}

/**
 * Binds the catalog `rebaseline-source` id (D-C12, resolving the
 * `21-REBASE-01` driver-surface gap): a single-writer tagged set source whose
 * merge tags are minted under its outlet's **current emission epoch**, and
 * which re-announces its recovered state as a re-baseline when its host
 * restarts it.
 *
 * Why not `set-source`. [civictech.cell.data.SetCell]'s tag source is
 * deliberately **replay-stable** — it mints tags under a fixed identity so a
 * journal replay reproduces the same tags — which is exactly the property that
 * makes it unable to witness epoch succession. The re-baseline rules are about
 * a source whose epoch *changes* across recovery (spec 20/22 §Source identity,
 * 93 I-14 Rule S1), so the catalog needs a source of that second kind. This is
 * the same distinction `RestartReBaselineTest`'s own `TaggedProducerCell`
 * draws, and this cell is its catalog-side twin: tags minted as
 * `Timestamp(outlet.waveState().sourceId, ++counter)` inside
 * [FanOutlet.originate], a [Stateful] snapshot/restore over the tag map, and a
 * [ReBaselineEmitting.reBaseline] that re-emits the restored state through
 * [FanOutlet.reBaseline] — the ordinary catch-up path, flagged with the dead
 * epochs' source ids.
 *
 * The kernel is **not** modified: [Stateful], [ReBaselineEmitting] and
 * `FanOutlet.originate`/`waveState`/`reBaseline` are all public kernel API, and
 * every step of the recovery — the epoch succession, the checkpoint restore,
 * the [reBaseline] call itself — is performed by
 * [civictech.cell.host.ManagedHost]'s own supervision path, not staged here.
 * Downstream, the convergent-consumer half is the catalog `union`
 * ([civictech.cell.data.op.UnionSetCell], which folds an inbound re-baseline
 * through its tag algebra rather than as an ordinary delta).
 */
class ReBaselineSourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) :
    Cell, Stateful, ReBaselineEmitting {

    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Any?>>>())
    val inlet = registerPort("inlet", FanInlet.create<ReBaselineSourceOps>())

    private val adds = mutableMapOf<Any?, MutableSet<Timestamp>>()
    private var counter = 0L

    init {
        inlet.serve(object : ReBaselineSourceOps {
            override fun add(element: Any?) {
                // A local add is a fresh origination point: mint the tag under
                // THIS outlet's current epoch, so a post-restart tag cannot
                // alias a pre-restart one (spec 20/24 §Tag continuity).
                outlet.originate {
                    val tag = Timestamp(outlet.waveState().sourceId, ++counter)
                    adds.getOrPut(element) { mutableSetOf() } += tag
                    propagate(SetDelta(adds = mapOf(element to setOf(tag))))
                }
            }

            override fun failInvocation(): Unit =
                throw IllegalStateException("rebaseline-source: restart trigger (driver `restart` verb)")
        })
    }

    override fun snapshot(): Serializable = HashMap(adds.mapValues { HashSet(it.value) })

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        adds.clear()
        (state as Map<Any?, Set<Timestamp>>).forEach { (e, tags) -> adds[e] = tags.toMutableSet() }
    }

    /**
     * The re-baseline the host invokes after recovering this cell: re-emit the
     * restored state as an ordinary catch-up delta-from-empty, flagged with the
     * superseded epochs. Emitted unconditionally — an *empty* restored state is
     * the case that carries the whole retraction, so guarding on non-emptiness
     * would silently drop exactly the reconciliation `[21-REBASE-01]` asks for.
     */
    override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
        val delta = SetDelta(adds = adds.mapValues { it.value.toSet() })
        outlet.reBaseline(supersedes, supersede) { propagate(delta) }
    }
}

/**
 * Binds the catalog `nature-gate` sink (12-NEGOTIATE-01, resolving the
 * `12-NEGOTIATE-01` schema-gap): an inlet that DECLARES a required nature (CP-F2),
 * so a plain default-nature producer's `connect` is refused at link time by the
 * kernel's own [civictech.cell.nature.NatureNegotiation] (CP-F3) — the same real
 * mechanism `TypedRefusalTest`/`NegotiatedAttachmentTest` exercise.
 *
 * The dispute's suggested binding — the driver calling the kernel-internal
 * `civictech.cell.port.PortNatures.stamp(...)` directly — is not reachable:
 * `PortNatures` is `internal` to the `:kernel` Gradle module, invisible from
 * `:concord` (a different module; `internal` is module-scoped, not
 * package-scoped, and there is no friend-path between the two). The **honest**
 * substitute below drives the identical kernel code path through a different,
 * equally-real *public* seam: [civictech.nature.ContractRegistry.register] — the
 * same registry a KSP-generated `@Contract` module populates at real-cell
 * compile time — is public API (the `:nature` module is `api`-exposed through
 * `:kernel`). Registering a [CellDescriptor] for this class's own fqn, once, at
 * class-load (the `companion object` `init` block, which the JVM runs before any
 * instance is constructed) makes `registerPort`'s existing, unconditional
 * `PortNatures.project(this, name, port)` call (see `PortRegistry.kt`) pick it up
 * and project the declared [NatureVector] onto the live port — *exactly* the
 * projection a KSP-generated descriptor would drive, just registered at runtime
 * instead of compile time (`:concord` cannot depend on the kernel's `:gen` KSP
 * processor without adopting kernel-internal build machinery, which the scope
 * fence forbids). From there the real `handshake`/`NatureNegotiation.reconcile`
 * path runs unmodified: no fake, no driver-side rejection — the refusal is the
 * kernel's.
 *
 * Requires [MergeClass.IDEMPOTENT] on `MERGE_IDEMPOTENCE` (the same axis
 * `TypedRefusalTest`'s first case exercises); a plain source's outlet (e.g.
 * `counter-source` → `CounterCell`, which does not implement `Replicable`) stays
 * at the axis default `NON_IDEMPOTENT`, so the mismatch is real, not staged.
 */
class NatureGatedSinkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    companion object {
        private const val FQN = "civictech.concord.driver.kernel.NatureGatedSinkCell"

        init {
            ContractRegistry.register(object : ContractModule {
                override val contracts: List<ContractDescriptor> = emptyList()
                override val cells: List<CellDescriptor> = listOf(
                    CellDescriptor(
                        fqn = FQN,
                        color = CellColor.PURE,
                        ports = listOf(
                            PortDescriptor(
                                name = "inlet",
                                direction = PortDirection.IN,
                                contractFqn = "civictech.cell.Propagate",
                                contractId = civictech.nature.StableHash.of("civictech.cell.Propagate"),
                                natures = NatureVector.of(MergeClass.IDEMPOTENT),
                            ),
                        ),
                    ),
                )
            })
        }
    }

    val inlet = registerPort("inlet", FanInlet.create<Propagate<Any>>())

    init {
        // A gate, not a view: the scenario only asserts the connect's admission
        // (Rejected) — nothing needs to be observed downstream of it.
        inlet.serve(Propagate<Any> { })
    }
}

/**
 * The SPSC-exclusive outlet contract used by [ExclusiveSourceCell]/
 * [ExclusiveSinkCell] (23-SPSC-01, resolving the `23-SPSC-01` schema-gap): a
 * method carrying an [Owned] parameter, which is exactly the shape
 * `OwnershipTest`'s `OwnedPush` exercises — the KSP `@Contract` processor would
 * mark this method's [MethodDescriptor.exclusive] `true` from that parameter
 * shape alone (see `carriesExclusive` in `ContractProcessor.kt`). `:concord` has
 * no KSP step, so [ExclusiveSourceCell]'s companion registers the identical
 * descriptor by hand (same public [ContractRegistry.register] seam as
 * [NatureGatedSinkCell]) — the `exclusive` flag [FanOutlet] reads at
 * construction (`FanOutlet.kt`: `ContractRegistry.descriptor(clazz)?.methods?.any
 * { it.exclusive }`) is the kernel's own field, not a driver-side simulation.
 * From there the real SPSC rule runs unmodified: [FanOutlet.linkTo] refuses a
 * second Consume subscriber with a genuine `LinkResult.Rejected` — the same path
 * `OwnershipTest`'s `the handshake path returns Rejected for the second link`
 * exercises.
 */
interface ExclusivePush {
    fun push(payload: Owned<Any>)
}

/** The command port `apply(..., op: push, value:)` routes to (source-side, not exclusive itself). */
interface ExclusiveSourceOps {
    fun push(value: Any?)
}

/**
 * Binds the catalog `exclusive-source` id: a source whose `outlet` carries
 * [ExclusivePush] — an `Owned`-payload, single-consumer (SPSC) contract — fed by
 * the plain `push` command on its `inlet`. Each `apply … op: push` wraps the
 * value in a fresh [Owned] (a `take()`-once handle), mirroring the kernel's own
 * `Owned`-carrying producers (`OwnershipTest`).
 */
class ExclusiveSourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    companion object {
        private const val FQN = "civictech.concord.driver.kernel.ExclusivePush"

        init {
            ContractRegistry.register(object : ContractModule {
                override val contracts: List<ContractDescriptor> = listOf(
                    ContractDescriptor(
                        contractId = civictech.nature.StableHash.of(FQN),
                        fqn = FQN,
                        management = false,
                        methods = listOf(
                            MethodDescriptor(
                                methodId = civictech.nature.StableHash.of("$FQN#push(Lcivictech/cell/Owned;)V"),
                                name = "push",
                                jvmDescriptor = "(Lcivictech/cell/Owned;)V",
                                exclusive = true,
                            ),
                        ),
                    ),
                )
            })
        }
    }

    val outlet = registerPort("outlet", FanOutlet.create<ExclusivePush>())
    val inlet = registerPort("inlet", FanInlet.create<ExclusiveSourceOps>())

    init {
        inlet.serve(object : ExclusiveSourceOps {
            override fun push(value: Any?) {
                requireNotNull(value) { "exclusive-source push requires a non-null value" }
                outlet.call.push(Owned(value))
            }
        })
    }
}

/**
 * Binds the catalog `exclusive-sink` id: a consumer of [ExclusivePush] that
 * `take()`s each payload and folds a running count, observed through a
 * `count-view`-shaped [ObservationSink] (so `final-view`/`readView` can assert
 * "still exactly one delivery" after the rejected second connect).
 */
class ExclusiveSinkCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful, ObservationSink<Long> {
    val inlet = registerPort("inlet", FanInlet.create<ExclusivePush>())

    private val lock = Any()
    private var count = 0L
    private val listeners = mutableListOf<(Long) -> Unit>()

    init {
        inlet.serve(object : ExclusivePush {
            override fun push(payload: Owned<Any>) {
                payload.take()
                synchronized(lock) {
                    count++
                    listeners.forEach { it(count) }
                }
            }
        })
    }

    override fun current(): Long = count

    override fun onChange(listener: (Long) -> Unit) {
        synchronized(lock) {
            listeners += listener
            listener(count)
        }
    }

    override fun snapshot(): Serializable = count

    override fun restore(state: Serializable) {
        count = state as Long
    }
}
