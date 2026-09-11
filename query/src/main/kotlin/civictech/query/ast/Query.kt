package civictech.query.ast

import civictech.query.schema.Catalog
import java.io.Serializable

/**
 * A full query: its [rules] and [definitions] against the [catalog] of relations they may
 * reference (epic computenet-cab §2.2, §4.1). Pure data — no evaluation or planning here;
 * that is `LogicalPlan` territory (cab.3).
 *
 * Two statement kinds, deliberately parallel lists rather than one heterogeneous list:
 * [rules] are `[QRY1-LANG-01]`'s Datalog rules (head from a literal body) and [definitions]
 * are `[QRY1-LANG-04]`'s first-class set-operation / outer-join statements (head from a
 * [RelationalExpr]). They share the [Atom] head vocabulary and nothing else, so a consumer
 * that only walks rules keeps compiling — [definitions] defaults to empty — while one that
 * walks the algebra asks for it by name instead of filtering a sealed statement type.
 */
data class Query(
    val rules: List<Rule>,
    val catalog: Catalog,
    val definitions: List<Definition> = emptyList(),
) : Serializable
