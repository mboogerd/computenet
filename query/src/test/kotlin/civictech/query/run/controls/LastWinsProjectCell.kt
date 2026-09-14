package civictech.query.run.controls

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.graph.TypedCellFactory
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.query.expr.RowProjection
import civictech.query.schema.Row
import java.util.UUID

/**
 * A deliberately WRONG projection — the divergence control of `[QRY1-ORA-07]` (BS-3, cab.6-D7):
 * "projection lowered as last-wins remapping rather than preimage-tag union", the
 * `[24-OP-FLATMAP-01]` failure class that `FlatMapSetCell.remap` guards against and that kernel
 * `OperatorTest."control - last-wins remap diverges where fold-with-union converges"` reproduces
 * at the delta level.
 *
 * It computes the same column permutation the lowering's `FlatMapFactory` does ([RowProjection]
 * is shared on purpose: this is a control on the LOWERING's tag handling, not on the projection
 * function). What differs is the tags: each output row is owned by the LAST live preimage that
 * projected onto it. A new colliding preimage displaces the previous owner — the owner's tags
 * are retracted downstream and the newcomer's asserted — and when the owner dies the output row
 * dies with it, even while displaced preimages are still live. The correct lowering unions every
 * preimage's tags, so the row survives until its last preimage dies.
 *
 * **Why stateful, not `adds.mapKeys { … }` per delta (the kernel control's literal shape).** A
 * stateless per-delta `mapKeys` loses tags only when two preimages collide inside ONE delta. In
 * `q(X) :- r(X, Y).` the projection's input is the `SetCell` source, whose deltas carry exactly
 * one row each, so a stateless `mapKeys` agreed with the correct lowering on every seed of
 * `0 until 50` — measured at base 46d4e795 for computenet-cab.6.4 (0 failures over the hand
 * script and a 40-step `QueryScripts` sweep). Owning the output row across deltas is the same
 * last-wins-instead-of-union error, applied to the output's accumulated tag set rather than to
 * one delta's map.
 *
 * Test scope only: `NoCellClassArchitectureTest` gates `query/src/main` (`[QRY1-LOWER-03]`).
 * It emits on every delta (never absorbs a wave), because `absorbAck` is kernel-internal.
 */
class LastWinsProjectCell(
    private val transform: RowProjection,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Row>>>())
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Row>>>())

    /** Live input rows and their tags. */
    private val live = HashMap<Row, MutableSet<Timestamp>>()

    /** The one preimage whose tags each output row currently carries downstream. */
    private val owner = HashMap<Row, Row>()

    private fun project(row: Row): Row = transform(row).single()

    init {
        inlet.serve(object : Propagate<SetDelta<Row>> {
            override fun propagate(value: SetDelta<Row>) {
                val out = Pending()
                value.adds.forEach { (input, tags) ->
                    val target = project(input)
                    live.getOrPut(input) { HashSet() } += tags
                    val previous = owner[target]
                    if (previous != null && previous != input) {
                        out.retract(target, live[previous].orEmpty()) // last wins: the old owner's tags go
                        out.assert(target, live.getValue(input))
                    } else {
                        out.assert(target, tags)
                    }
                    owner[target] = input
                }
                value.dels.forEach { (input, tags) ->
                    val held = live[input] ?: return@forEach
                    val removed = held.intersect(tags)
                    held -= removed
                    if (held.isEmpty()) live.remove(input)
                    val target = project(input)
                    if (owner[target] != input) return@forEach // a displaced preimage's tags are already gone
                    out.retract(target, removed)
                    if (held.isEmpty()) owner.remove(target) // no fallback to another live preimage
                }
                outlet.call.propagate(SetDelta(adds = out.adds, dels = out.dels))
            }
        })
    }

    /** One outgoing delta, with an assert and a retract of the same tag cancelling out. */
    private class Pending {
        val adds = HashMap<Row, MutableSet<Timestamp>>()
        val dels = HashMap<Row, MutableSet<Timestamp>>()

        fun assert(row: Row, tags: Set<Timestamp>) = move(row, tags, from = dels, to = adds)

        fun retract(row: Row, tags: Set<Timestamp>) = move(row, tags, from = adds, to = dels)

        private fun move(
            row: Row,
            tags: Set<Timestamp>,
            from: HashMap<Row, MutableSet<Timestamp>>,
            to: HashMap<Row, MutableSet<Timestamp>>,
        ) {
            val cancelled = from[row]
            tags.forEach { tag ->
                if (cancelled != null && cancelled.remove(tag)) return@forEach
                to.getOrPut(row) { HashSet() } += tag
            }
            if (cancelled != null && cancelled.isEmpty()) from.remove(row)
        }
    }
}

/** The data-class factory `SpecMutations.lastWinsProjection` substitutes for a root's `FlatMapFactory`. */
data class LastWinsProjectFactory(val transform: RowProjection) : TypedCellFactory<LastWinsProjectCell> {
    override fun create(ref: CellRef): LastWinsProjectCell = LastWinsProjectCell(transform, ref)
}
