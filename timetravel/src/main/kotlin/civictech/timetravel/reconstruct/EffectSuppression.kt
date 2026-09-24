package civictech.timetravel.reconstruct

import civictech.cell.CellRef
import civictech.cell.evolve.Effectful
import civictech.cell.evolve.Shadow
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRegistry
import civictech.nature.ContractRegistry
import civictech.timetravel.fidelity.Reason

/**
 * Effect suppression for an offline reconstruction (TTD1 F5, computenet-yhvlz D1, yhvlz-D5/D6;
 * `[TTD1-26]`, spec 52 "NoOp-served sinks").
 *
 * [apply] runs `Shadow.spawn`'s post-spawn half over every built cell — `Effectful` cells have
 * every fan-in inlet NoOp-served ([Shadow.suppress]); any other cell has exactly its
 * `@Contract(effect = true)` inlets NoOp-served ([Shadow.suppressEffectContracts]) — so no
 * replayed frame reaches an effect inlet's real handler. The NoOp-serving itself, and its
 * discharge-vs-noop choice for exclusive contracts, is `Shadow`'s: this object serves nothing,
 * it only names what `Shadow` served, by the same selection `Shadow` applies.
 */
internal object EffectSuppression {

    /**
     * Suppresses [build]'s effect inlets through [Shadow] and returns, per cell, the names of the
     * inlets it NoOp-served. An `Effectful` cell always has an entry (possibly empty: it is
     * `Effectful` whether or not it has inlets); any other cell has one only when at least one of
     * its inlets carries an effect contract.
     */
    fun apply(build: GraphBuild): Map<CellRef, Set<String>> {
        val suppressed = LinkedHashMap<CellRef, Set<String>>()
        for (cell in build.cells) {
            val ports = PortRegistry.of(cell)
            val fanInlets = ports.names().filter { ports[it] is FanInlet<*> }
            if (cell is Effectful) {
                suppressed[cell.ref] = fanInlets.toSet()
                Shadow.suppress(cell)
            } else {
                val effectInlets = fanInlets.filter { name ->
                    ContractRegistry.descriptor((ports[name] as FanInlet<*>).clazz)?.effect == true
                }
                if (effectInlets.isNotEmpty()) suppressed[cell.ref] = effectInlets.toSet()
                Shadow.suppressEffectContracts(cell)
            }
        }
        return suppressed
    }
}

/**
 * The [Reason.EFFECTFUL_CELL] detail (yhvlz-D7): [ref]'s effect [inlets] were NoOp-served during
 * the reconstruction, so its state is the checkpoint's (if any), not the run's (`[TTD1-27]`).
 * One per cell [EffectSuppression.apply] reported, including an `Effectful` cell with no inlets.
 */
data class EffectInletsSuppressed(val ref: CellRef, val inlets: Set<String>) : ReconstructionDetail {
    override val reason: Reason = Reason.EFFECTFUL_CELL
}
