package civictech.query.lower

import civictech.query.diag.Locus
import civictech.query.plan.AntiJoin
import civictech.query.plan.Difference
import civictech.query.plan.GroupAggregate
import civictech.query.plan.Intersect
import civictech.query.plan.Join
import civictech.query.plan.OuterJoin
import civictech.query.plan.PlanNode
import civictech.query.plan.Project
import civictech.query.plan.Scan
import civictech.query.plan.Select
import civictech.query.plan.SemiJoin
import civictech.query.plan.Union

/** Whether an absence-based operator gets `emitOnFrontier`, and the diagnostic when it does not. */
data class GateDecision(val emitOnFrontier: Boolean, val diagnostic: LoweringDiagnostic?)

/**
 * The single `emitOnFrontier` decision for absence-based operators (`[QRY1-LOWER-08]`,
 * `[QRY1-LOWER-09]`, cab.4-D3, cab.4-D6). This task applies it to `AntiJoin`; the completion
 * task reuses it for `Difference` and `OuterJoin`, so the rule has one statement.
 *
 * The gate is set iff BOTH hold:
 * 1. **Shared provenance** — the arms' [PlanNode.provenance] sets intersect. With no shared
 *    source there is no common wave frontier: the gate would wait on an edge that never
 *    arrives, so the operator runs ungated and is reported [LoweringDiagnostic.EventuallyConsistent].
 * 2. **Carry precondition** — each arm is at most ONE operator deep from the sources: either a
 *    `Scan` (the `src:*` cell links straight into the gated inlet) or a node whose children are
 *    all `Scan`s. `SemiJoinCell`'s KDoc and `WaveGate`'s "One root is NOT sufficient" section
 *    (computenet-23bf, `doc/demo-findings.md` F-15) establish that an absorbing operator's
 *    absorb-ack rescues a wave only when it links directly into the gated inlet; a further hop
 *    swallows the ack and the gate withholds output at rest. Deeper arms stay ungated and are
 *    reported [LoweringDiagnostic.GateNotProvable].
 *
 * Limit of the claim: the depth rule is a sufficient condition read from the kernel's
 * documented mechanism, measured only for the shapes computenet-23bf ran. It is conservative —
 * depth is the maximum over every child, not only the children carrying the shared relation —
 * so it can withhold a gate that would have been safe, never grant one that is not.
 */
object Gating {

    private const val EVENTUALLY_CONSISTENT_SPEC = "[24-OP-SEMIJOIN-04]/[24-OP-OUTERJOIN-02]"

    fun decide(left: PlanNode, right: PlanNode, locus: Locus.PlanNode, handle: String): GateDecision {
        if (left.provenance.intersect(right.provenance).isEmpty()) {
            return GateDecision(
                emitOnFrontier = false,
                diagnostic = LoweringDiagnostic.EventuallyConsistent(locus, handle, EVENTUALLY_CONSISTENT_SPEC),
            )
        }
        val leftDepth = operatorDepth(left)
        val rightDepth = operatorDepth(right)
        if (leftDepth > 1 || rightDepth > 1) {
            return GateDecision(
                emitOnFrontier = false,
                diagnostic = LoweringDiagnostic.GateNotProvable(
                    locus,
                    handle,
                    "arms share a source but are $leftDepth and $rightDepth operators deep; the " +
                        "gate is only carried at most one operator from the source (F-15, " +
                        "WaveGate \"One root is NOT sufficient\")",
                ),
            )
        }
        return GateDecision(emitOnFrontier = true, diagnostic = null)
    }

    /** Operator cells between the sources and [node]'s output: 0 for a `Scan`, else 1 + the deepest child. */
    fun operatorDepth(node: PlanNode): Int = when (node) {
        is Scan -> 0
        else -> 1 + (children(node).maxOfOrNull { operatorDepth(it) } ?: 0)
    }

    private fun children(node: PlanNode): List<PlanNode> = when (node) {
        is Scan -> emptyList()
        is Select -> listOf(node.input)
        is Project -> listOf(node.input)
        is Join -> listOf(node.left, node.right)
        is SemiJoin -> listOf(node.input, node.witness)
        is AntiJoin -> listOf(node.input, node.witness)
        is Union -> node.inputs
        is Intersect -> listOf(node.left, node.right)
        is Difference -> listOf(node.left, node.right)
        is GroupAggregate -> listOf(node.input)
        is OuterJoin -> listOf(node.left, node.right)
    }
}
