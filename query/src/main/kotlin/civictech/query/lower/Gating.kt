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
 * The gate is set iff ALL hold:
 * 1. **Shared provenance** — the arms' [PlanNode.provenance] sets intersect. With no shared
 *    source there is no common wave frontier: the gate would wait on an edge that never
 *    arrives, so the operator runs ungated and is reported [LoweringDiagnostic.EventuallyConsistent].
 * 2. **Equal provenance** — every source feeds BOTH arms. The gate's frontier is a static link
 *    set with no upstream traversal, so an inlet whose arm structurally never carries a source
 *    is still an expected edge for that source's waves (`WaveGate` "The phantom expected edge
 *    (G-13)"): a final wave from a one-arm-only source is held at rest. `q(X, Z) :- e(X, Y),
 *    f(Y, Z), not e(X, Z).` — arms `{e,f}` and `{e}` — withheld `(5,-1)` after `e.add(5,1)`,
 *    `f.add(1,-1)` while the rule only required (1) (computenet-cab.4.8; `GatingEvidenceTest`
 *    pins it for the antijoin, `Difference` and `OuterJoin`). Unequal arms stay ungated and are
 *    reported [LoweringDiagnostic.GateNotProvable].
 * 3. **Carry precondition** — each arm is at most ONE operator deep from the sources: either a
 *    `Scan` (the `src:*` cell links straight into the gated inlet) or a node whose children are
 *    all `Scan`s. `SemiJoinCell`'s KDoc and `WaveGate`'s "One root is NOT sufficient" section
 *    (computenet-23bf, `doc/demo-findings.md` F-15) establish that an absorbing operator's
 *    absorb-ack rescues a wave only when it links directly into the gated inlet; a further hop
 *    swallows the ack and the gate withholds output at rest. Deeper arms stay ungated and are
 *    reported [LoweringDiagnostic.GateNotProvable].
 *
 * Limit of the claim: (2) and (3) together are a sufficient condition read from the kernel's
 * documented mechanism, measured only for the shapes computenet-23bf, computenet-cab.4.5,
 * computenet-cab.4.8 and computenet-cab.4.9 ran (`GatingEvidenceTest`) — it is not a proof over every plan shape. An
 * earlier version of this paragraph claimed the intersect-plus-depth rule "can withhold a gate
 * that would have been safe, never grant one that is not"; the unequal-provenance case above
 * falsified that, so treat a newly found withholding shape as a gap in this rule, not in the
 * kernel. The depth check is conservative: what withholds is an absorbing operator with a
 * further hop below it, not the arm's depth as such, and depth is the maximum over every
 * child, not only the children carrying the shared relation. `GatingEvidenceTest` measures
 * both sides on one equal-provenance two-`Filter` arm (computenet-cab.4.9): with the gate
 * forced on, a wave the inner filter drops is held at rest and a wave the outer filter drops
 * settles. No over-refusal by the depth check is measured: every two-`Filter` arm has an inner
 * filter, and the swapped shape still holds an inner-dropped final wave at rest (its answers
 * happen to stay equal to the batch fold on the scripts run). The equality check claims no
 * such over-refusal: per `WaveGate` G-13 a one-arm-only source's waves never reach the other
 * inlet, so neither an ack nor a later wave of that source can release them there.
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
        if (left.provenance != right.provenance) {
            val leftOnly = (left.provenance - right.provenance).sorted()
            val rightOnly = (right.provenance - left.provenance).sorted()
            return GateDecision(
                emitOnFrontier = false,
                diagnostic = LoweringDiagnostic.GateNotProvable(
                    locus,
                    handle,
                    "arms share a source but not every source: $leftOnly feed only the left arm and " +
                        "$rightOnly only the right, so the other inlet is a phantom expected edge for " +
                        "those sources' waves and a final wave from one is withheld at rest " +
                        "(WaveGate \"The phantom expected edge (G-13)\")",
                ),
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
