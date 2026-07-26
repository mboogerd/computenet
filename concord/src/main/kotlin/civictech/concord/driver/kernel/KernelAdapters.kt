package civictech.concord.driver.kernel

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.data.CounterDelta
import civictech.cell.Propagate
import civictech.cell.host.View
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.UUID

/**
 * Driver-internal adapter cells (W1-A). These live **only** in
 * `civictech.concord.driver.kernel` — the one package permitted to import
 * `civictech.cell.*` — and exist to bind catalog ids that have no clean 1:1
 * kernel cell as of the pinned commit (see `cell-catalog.md` "Kernel-binding
 * notes and gaps"). They compose the kernel's own port primitives
 * ([FanInlet]/[FanOutlet]/[Propagate]); the kernel is **not** modified.
 */

/**
 * Binds the catalog `map` operator with `fn: identity` — a pass-through arm.
 * The kernel ships no element-map cell (only [civictech.cell.data.FlatMapSetCell]
 * over set streams), and the diamond exemplar applies `map, fn: identity` to a
 * *counter* stream, so no set-shaped cell fits either. This trivial pass-through
 * re-propagates every delta unchanged and is type-agnostic (erased `Propagate`),
 * so it binds identity arms over both `SetDelta` (21-PIPE-01) and `CounterDelta`
 * (22-GF-DIAMOND-01) streams.
 *
 * Binding decision (reported to the wave lead): `map fn: identity` → this cell.
 * A *non-identity* element map over a set stream (e.g. `24-OP-MAPFN`, W3-2)
 * should bind to `FlatMapSetCell` with a singleton transform — not implemented
 * here because no pilot needs it.
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
 * A genuine glitch-free scalar combine is a gap for W1-B / W3.
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
 * ships only `set` / `map` / `count`). Rather than edit kernel `Observe.kt` — a
 * concurrent restructure run is touching kernel — this synthesises the scalar
 * view as a [View] adapter *inside the driver package*, folding a `CounterDelta`
 * stream into a single running `Long`. A driver [civictech.cell.host.ObserveCell]
 * over it exposes the scalar through the ordinary [civictech.cell.host.ObservationSink].
 */
fun scalarCounterView(): View<CounterDelta, Long> = object : View<CounterDelta, Long> {
    private var total = 0L
    override fun apply(delta: CounterDelta): Boolean {
        if (delta.amount == 0L) return false
        total += delta.amount
        return true
    }

    override fun current(): Long = total
    override fun snapshot(): Serializable = total
    override fun restore(state: Serializable) {
        total = state as Long
    }
}
