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

    /**
     * A rule or definition statement identified positionally rather than by source span —
     * the locus a builder-produced [civictech.query.ast.Query] uses, since it carries no
     * source text and therefore no [SourceSpan] (computenet-cab.2.3). [ruleIndex] is the
     * statement's index into [civictech.query.ast.Query.rules]; [headPredicate] is its head
     * atom's predicate name, carried alongside the index so a message naming the offending
     * rule need not re-resolve the index back into the query to be readable.
     */
    data class RuleStatement(val ruleIndex: Int, val headPredicate: String) : Locus
}
