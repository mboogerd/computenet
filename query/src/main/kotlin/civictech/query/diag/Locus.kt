package civictech.query.diag

import java.io.Serializable

/**
 * Where a [Rejection] points, per [QRY1-REJECT-02]'s "offending source span or plan node":
 * pure data, landed before `LogicalPlan` exists so the [Rejection] shape can be expressed
 * ahead of the lowering/planning features that will produce real [PlanNode] values.
 */
sealed interface Locus : Serializable {

    /** A line/column extent of the offending source text. */
    data class SourceSpan(
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val endColumn: Int,
    ) : Locus

    /** A stable string path/id naming a plan node, owned by the planning/lowering features. */
    data class PlanNode(val id: String) : Locus
}
