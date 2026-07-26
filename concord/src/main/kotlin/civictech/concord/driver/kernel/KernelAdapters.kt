package civictech.concord.driver.kernel

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Stateful
import civictech.cell.data.CounterDelta
import civictech.cell.data.ListDelta
import civictech.cell.data.Magnitude
import civictech.cell.data.PnCounterDelta
import civictech.cell.Propagate
import civictech.cell.observe.View
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.FeedbackInlet
import civictech.cell.port.registerPort
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
 * Binds the catalog `combine-latest` operator with `fn: sum` over two **scalar
 * counter** arms — the shape of the glitch-free diamond (22-GF-DIAMOND-01).
 *
 * The kernel's [civictech.cell.data.CombineLatestCell] is a *keyed-map* outer
 * combine (`MapDelta<K, …>`), so it cannot combine two `CounterDelta` streams;
 * this adapter holds each arm's running total and emits the *change in the sum*
 * as a `CounterDelta`, which a `value-view` folds back to the scalar sum. Sum of
 * all emitted deltas equals `left + right` regardless of arrival order, so the
 * final value is order-independent (final-view only — see the report).
 *
 * NOTE / gap: this is **not** wave-aligned. `glitch-free: true` in the descriptor
 * is accepted but not honoured — intermediate (odd) sums may be observed mid-wave,
 * so `observations-all-satisfy(even)` is NOT guaranteed. Only `final-view` is.
 * A genuine glitch-free scalar combine is a §5 kernel gap.
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
