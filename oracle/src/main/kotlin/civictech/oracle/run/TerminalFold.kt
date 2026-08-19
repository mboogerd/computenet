package civictech.oracle.run

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.oracle.model.ModelState
import java.util.UUID

/**
 * One fold per terminal delta family, each a hosted collector cell plus a `civictech.cell.data
 * .view` fold, producing a [ModelState] so a differential run compares by structural
 * [ModelState] equality rather than delta-stream equality.
 *
 * `:oracle` authors no `@Contract`/`@CellBase` cells (`oracle/build.gradle.kts`); each fold
 * registers its inlet port directly, as `GenerativeGraphTest.CounterCollectorCell`
 * (`kernel/src/test/kotlin/civictech/cell/verify/GenerativeGraphTest.kt`) does. How a fold gets
 * linked to a case's terminal ref is the execution/check task's judgment — this file only
 * defines the three shapes.
 */

/**
 * What every terminal fold is, from the differential runner's side: a hosted [Cell] whose
 * [current] reading is a [ModelState], so the runner compares a terminal to the reference
 * model by structural [ModelState] equality without knowing which delta family produced it.
 *
 * The three implementations below are the three families; a case's terminal map
 * ([civictech.oracle.run.CaseGraph.terminals]) is keyed by the terminal's name and valued by
 * one of them.
 */
interface TerminalFold : Cell {
    /** This terminal's folded state as of the last delta it received. */
    fun current(): ModelState
}

/**
 * The set family (`SetDelta`): folds through [SetView] into a [ModelState.SetState].
 *
 * `Set<E>` is covariant, so [current] needs no unchecked cast to widen into
 * `ModelState.SetState`'s `Set<Any?>`.
 */
class SetTerminalFold<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) : TerminalFold {
    private val view = SetView<E>()

    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<E>>>())

    init {
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) {
                view.apply(value)
            }
        })
    }

    /** The terminal's current folded state, as a [ModelState.SetState]. */
    override fun current(): ModelState.SetState = ModelState.SetState(view.current())
}

/**
 * The map family (`MapDelta`: the groupBy* ids and presenceCount): folds through [MapView]
 * into a [ModelState.MapState]. `presenceCount`'s values stay `Int` — no widening to `Long`
 * happens here, which is the width [TerminalFoldTest] pins.
 *
 * `Map<K, V>` is invariant in `K`, so widening into `ModelState.MapState`'s
 * `Map<Any?, Any?>` needs an unchecked cast; it is sound because the widening only relaxes the
 * static key/value types, never touches the underlying map at runtime.
 */
class MapTerminalFold<K, V>(override val ref: CellRef = CellRef(UUID.randomUUID())) : TerminalFold {
    private val view = MapView<K, V>()

    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<K, V>>>())

    init {
        inlet.serve(object : Propagate<MapDelta<K, V>> {
            override fun propagate(value: MapDelta<K, V>) {
                view.apply(value)
            }
        })
    }

    /** The terminal's current folded state, as a [ModelState.MapState]. */
    @Suppress("UNCHECKED_CAST")
    override fun current(): ModelState.MapState = ModelState.MapState(view.current() as Map<Any?, Any?>)
}

/**
 * The scalar family (`CounterDelta`: `count`, `counter`, `pnCounter`): sums arriving amounts
 * as a `Long` into a [ModelState.ScalarState]. `CounterDelta.amount` is a `Long` because
 * `CountCell` (and `CounterCell`/`PnCounterCell`) emit it as one — this is the width
 * [TerminalFoldTest] pins against `ModelState.ScalarState(2)` (an `Int`), which is a different
 * value under [ModelState.ScalarState]'s structural equality.
 */
class ScalarTerminalFold(override val ref: CellRef = CellRef(UUID.randomUUID())) : TerminalFold {
    private var total: Long = 0L

    val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

    init {
        inlet.serve(object : Propagate<CounterDelta> {
            override fun propagate(value: CounterDelta) {
                total += value.amount
            }
        })
    }

    /** The terminal's current folded state, as a [ModelState.ScalarState] holding a `Long`. */
    override fun current(): ModelState.ScalarState = ModelState.ScalarState(total)
}
