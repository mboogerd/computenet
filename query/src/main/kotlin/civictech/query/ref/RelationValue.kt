package civictech.query.ref

import civictech.query.schema.Row
import java.io.Serializable

/**
 * One root's answer as [BatchEvaluator] computes it (cab.6-D5). The three cases are the three
 * shapes a compiled root's outlet folds to (`civictech.query.run.OutputShape`), so the
 * differential adapter can map each to the runner's comparison currency without re-deriving
 * the root's kind:
 *
 * - [Rows]: every non-aggregate root — the set of distinct rows, positional against the root's
 *   `outputColumns`.
 * - [Groups]: a grouped `GroupAggregate` root, keyed by `Row(<group-by values in groupByColumns
 *   order>)`; and a scalar non-COUNT aggregate root, under the single String key `"global"`.
 *   A group with no live row is ABSENT, never present with an identity value
 *   (`[24-OP-GROUPBY-02]`).
 * - [Count]: a scalar (no group-by) COUNT root.
 *
 * This package keeps its own result type rather than `civictech.oracle.model.ModelState`
 * because `civictech.query.ref` is main-scope and `:oracle` is test-scope only
 * (`[QRY1-API-01]`).
 */
sealed interface RelationValue : Serializable {

    /** A non-aggregate root's distinct rows. */
    data class Rows(val rows: Set<Row>) : RelationValue

    /** A grouped or scalar non-COUNT aggregate root: group key to aggregate value. */
    data class Groups(val entries: Map<Any?, Any?>) : RelationValue

    /** A scalar COUNT root. */
    data class Count(val count: Long) : RelationValue
}
