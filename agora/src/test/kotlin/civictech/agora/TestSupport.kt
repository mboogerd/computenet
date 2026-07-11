package civictech.agora

import civictech.agora.cell.Polarity
import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController

/** One deterministic single-host world (the repo's SimulationController idiom). */
class Harness(
    seed: Long? = null,
    quiescence: Double = 1e-3,
    magnitude: Boolean = true,
    onCredence: (CellRef, Double) -> Unit = { _, _ -> },
) {
    val controller = SimulationController(seed)
    val registry = LocationRegistry()
    val host = ManagedHost(
        scheduler = controller.scheduler(),
        registry = registry,
        attention = if (magnitude) {
            civictech.cell.host.AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS)
        } else null,
    )
    val service = AgoraService(host, registry, quiescence = quiescence, onCredence = onCredence)

    /** Drain to idle under a hard step budget: quiescence is asserted, not hoped for. */
    fun runToIdle(budget: Int = 200_000): Int {
        var steps = 0
        while (controller.step()) {
            check(++steps < budget) { "no quiescence within $budget steps" }
        }
        return steps
    }
}

/**
 * The batch reference: a plain-Kotlin Gauss-Seidel fixpoint over the final
 * graph, using the SAME semantics functions and the SAME ref-sorted fold
 * order as the cells — incremental == batch must be a property of the
 * propagation machinery, not of parallel math.
 */
object BatchReference {
    data class NodeSpec(
        val stances: Map<String, Double> = emptyMap(),
        val polarity: Polarity? = null, // non-null for edges
        val source: CellRef? = null,
        val target: CellRef? = null,
    )

    fun solve(
        topology: Map<CellRef, NodeSpec>,
        semantics: GradualSemantics = DfQuad,
        tol: Double = 1e-13,
        maxSweeps: Int = 100_000,
    ): Map<CellRef, Double> {
        val order = topology.keys.sortedWith(civictech.agora.cell.ClaimCell.REF_ORDER)
        val cred = order.associateWith { 0.5 }.toMutableMap()
        val incoming = order.associateWith { n ->
            topology.entries
                .filter { it.value.target == n }
                .sortedWith(compareBy(civictech.agora.cell.ClaimCell.REF_ORDER) { it.key })
        }
        repeat(maxSweeps) {
            var maxDelta = 0.0
            order.forEach { n ->
                val energies = incoming.getValue(n).map { (ref, spec) ->
                    val e = cred.getValue(ref) * cred.getValue(spec.source!!)
                    if (spec.polarity == Polarity.SUPPORT) e else -e
                }
                val attacks = energies.filter { it < 0 }.map { -it }
                val supports = energies.filter { it > 0 }
                val next = semantics.combine(semantics.base(topology.getValue(n).stances.values), attacks, supports)
                maxDelta = maxOf(maxDelta, kotlin.math.abs(next - cred.getValue(n)))
                cred[n] = next
            }
            if (maxDelta < tol) return cred
        }
        error("batch reference did not converge within $maxSweeps sweeps")
    }
}
