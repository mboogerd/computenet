package civictech.query.lower

import civictech.cell.graph.GraphSpec
import civictech.query.diag.Locus
import java.io.Serializable

/**
 * The outcome of [Lowering.lower] (cab.4-D5): a plan either lowers to a [GraphSpec] in full,
 * or it is [Refused] with every node that has no lowering rule named — never partially
 * lowered, never thrown, never nearest-matched onto a rule meant for another node kind.
 */
sealed interface LoweringResult : Serializable {

    /**
     * The plan lowered. [spec] is the ordered step list (sources first, then each root in
     * sorted root-name order, children before parents; a subtree value-equal to one already
     * lowered is emitted only there, under the earlier root, `[QRY1-LOWER-11]`);
     * [sourceHandles] maps each EDB relation scanned by the plan to its `src:<relation>` spawn
     * handle; [outputHandles] maps each root name to the handle of the cell producing that
     * root's rows, which for a shared subtree may lie under another root's prefix. [diagnostics] records
     * non-fatal facts about the lowering, such as a gate that could not be proven.
     */
    data class Lowered(
        val spec: GraphSpec,
        val sourceHandles: Map<String, String>,
        val outputHandles: Map<String, String>,
        val diagnostics: List<LoweringDiagnostic>,
    ) : LoweringResult

    /** The plan has no lowering; every reason is collected, not only the first. */
    data class Refused(val refusals: List<LoweringRefusal>) : LoweringResult
}

/**
 * One plan node that could not be lowered: [locus] names the node by its numbered path
 * (`<root>/<n>:<kind>`), [nodeKind] is the plan node's simple class name (`GroupAggregate`,
 * `Intersect`, ...), and [reason] says why. `civictech.query.QueryCompiler` maps each one
 * onto its own `NO_LOWERING` rejection, located at [locus] and naming [nodeKind] and [reason].
 */
data class LoweringRefusal(
    val locus: Locus.PlanNode,
    val nodeKind: String,
    val reason: String,
) : Serializable
