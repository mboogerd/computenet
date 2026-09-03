package civictech.query.schema

import java.io.Serializable

/**
 * The schema catalog: every known relation, keyed by name (`[QRY1-LANG-05]`). Pure data,
 * `Serializable` per `[QRY1-LANG-06]`; a [Query][civictech.query.ast.Query] carries one
 * alongside its rule set.
 */
data class Catalog(val relations: Map<String, RelationSchema>) : Serializable
