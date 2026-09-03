package civictech.agora

import civictech.agora.cell.ClaimCell
import civictech.agora.cell.Polarity
import civictech.agora.semantics.DfQuad
import civictech.agora.semantics.GradualSemantics
import civictech.cell.CellRef

/**
 * The batch reference: a plain-Kotlin Gauss-Seidel fixpoint over the final
 * graph, using the SAME semantics functions and the SAME ref-sorted fold
 * order as the cells — incremental == batch must be a property of the
 * propagation machinery, not of parallel math.
 *
 * Lives in `:demo:agora`'s `testFixtures` source set (computenet-5swa) so any
 * differential test outside `:demo:agora` — `:demo:dialogue`'s
 * `ApplierSemanticsTest`, and any later AGO work — can `testImplementation
 * (testFixtures(project(":demo:agora")))` and import it directly instead of
 * reproducing the solver as a private copy that can silently drift from this
 * one. `:demo:agora`'s own test source set gets it automatically (the
 * `java-test-fixtures` plugin wires `test` to depend on `testFixtures` within
 * the same project), so `AgoraExitTest` and `CycleQuiescenceTest` keep
 * resolving `BatchReference` unqualified with no import change needed.
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
        val order = topology.keys.sortedWith(ClaimCell.REF_ORDER)
        val cred = order.associateWith { 0.5 }.toMutableMap()
        val incoming = order.associateWith { n ->
            topology.entries
                .filter { it.value.target == n }
                .sortedWith(compareBy(ClaimCell.REF_ORDER) { it.key })
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
