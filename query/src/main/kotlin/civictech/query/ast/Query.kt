package civictech.query.ast

import civictech.query.schema.Catalog
import java.io.Serializable

/**
 * A full query: its [rules] against the [catalog] of relations they may reference (epic
 * computenet-cab §2.2, §4.1). Pure data — no evaluation or planning here; that is
 * `LogicalPlan` territory (cab.3).
 */
data class Query(val rules: List<Rule>, val catalog: Catalog) : Serializable
