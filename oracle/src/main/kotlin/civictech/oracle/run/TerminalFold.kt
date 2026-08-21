package civictech.oracle.run

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.PnCounterDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
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

/**
 * The tagged-map family (`TaggedMapDelta`): merges arriving deltas by the dot algebra and reads
 * `[24-TMAP-02]` membership plus `[24-TMAP-03]` per-key value out of the merge — ORA2's
 * `[ORA2-CONV-04]` fold.
 *
 * **Why this is not [MapTerminalFold] with a different delta type.** A `MapDelta` fold is an
 * arrival-order fold: the last write the stream carried wins. A tagged map's value is decided by
 * `(counter, sourceId)` over the key's *live dots*, which is a property of the merged dot set and
 * not of the order the deltas arrived in. Folding a `TaggedMapDelta` stream through [MapView]
 * would therefore produce a replica-dependent answer for exactly the concurrent-put case ORA2
 * exists to check — that substitution is `[ORA2-CTL-01]`'s control, and it must not be reachable
 * by accident here.
 *
 * **What "reachable through the outlet stream" buys.** The state this fold exposes is
 * reconstructed *only* from the deltas the cell emitted; nothing reads `OrMapCell.state()`,
 * `membership()` or `value()`. A replica whose gossip is incomplete therefore folds to an
 * incomplete answer rather than to the cell's internal truth, which is what makes a convergence
 * check over these folds able to fail at all (`[ORA2-CONV-04]`, `[ORA2-CTL-04]`).
 */
class TaggedMapTerminalFold<K, V>(override val ref: CellRef = CellRef(UUID.randomUUID())) : TerminalFold {
    private var merged: TaggedMapDelta<K, V> = TaggedMapDelta()

    val inlet = registerPort("inlet", FanInlet.create<Propagate<TaggedMapDelta<K, V>>>())

    init {
        inlet.serve(object : Propagate<TaggedMapDelta<K, V>> {
            override fun propagate(value: TaggedMapDelta<K, V>) {
                merged = merged.merge(value)
            }
        })
    }

    /** Every delta this fold has received, merged — the dot state the reading is taken from. */
    fun merged(): TaggedMapDelta<K, V> = merged

    override fun current(): ModelState.MapState = stateOf(merged)

    companion object {
        /**
         * [delta]'s `(membership, value-per-key)` reading as a [ModelState.MapState].
         *
         * Exposed as a pure function of a merged delta, not only as a cell method, because the
         * *same* reading has to be taken over a
         * [civictech.cell.verify.ReplicaConvergence] fold — which folds a replica's outlet
         * stream with a merge function of the caller's choosing and hands back the merged
         * value, not a cell. One function, two callers, so a convergence verdict and a terminal
         * comparison can never be reading the delta two different ways.
         */
        @Suppress("UNCHECKED_CAST")
        fun <K, V> stateOf(delta: TaggedMapDelta<K, V>): ModelState.MapState =
            ModelState.MapState(delta.membership().associateWith { delta.value(it) } as Map<Any?, Any?>)

        /** The merge `[24-TMAP-01]` states, as the fold function `ReplicaConvergence` takes. */
        fun <K, V> merge(acc: TaggedMapDelta<K, V>, delta: TaggedMapDelta<K, V>): TaggedMapDelta<K, V> =
            acc.merge(delta)
    }
}

/**
 * The PN-counter family (`PnCounterDelta`): merges per-source cumulative totals by pointwise max
 * and reads their difference as a scalar — the second member of the replicable class beside the
 * tagged family (`[ORA2-MODEL-10]`).
 *
 * Distinct from [ScalarTerminalFold] for the same reason [TaggedMapTerminalFold] is distinct from
 * [MapTerminalFold]: [ScalarTerminalFold] *sums* arriving `CounterDelta.amount`s, which is not
 * idempotent, so a gossip echo double-counts. `PnCounterDelta`'s merge is pointwise max and
 * therefore idempotent, which is the whole reason `PnCounterCell` replicates and `CounterCell`
 * does not.
 *
 * The reading is a `Long` (`incs` total minus `decs` total), matching the width
 * `PnCounterDelta` carries; [ModelState.ScalarState]'s equality is structural, so a `Long` and an
 * `Int` of the same magnitude are *not* equal — the width [TerminalFoldTest] pins.
 */
class PnCounterTerminalFold(override val ref: CellRef = CellRef(UUID.randomUUID())) : TerminalFold {
    private var merged: PnCounterDelta = PnCounterDelta()

    val inlet = registerPort("inlet", FanInlet.create<Propagate<PnCounterDelta>>())

    init {
        inlet.serve(object : Propagate<PnCounterDelta> {
            override fun propagate(value: PnCounterDelta) {
                merged = merged.merge(value)
            }
        })
    }

    /** Every delta this fold has received, merged by pointwise max. */
    fun merged(): PnCounterDelta = merged

    override fun current(): ModelState.ScalarState = stateOf(merged)

    companion object {
        /** [delta]'s net total as a [ModelState.ScalarState] holding a `Long`. See [TaggedMapTerminalFold.stateOf] for why this is a function. */
        fun stateOf(delta: PnCounterDelta): ModelState.ScalarState =
            ModelState.ScalarState(delta.incs.values.sum() - delta.decs.values.sum())

        /** The pointwise-max merge, as the fold function `ReplicaConvergence` takes. */
        fun merge(acc: PnCounterDelta, delta: PnCounterDelta): PnCounterDelta = acc.merge(delta)
    }
}
