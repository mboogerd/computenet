package civictech.query.lower

import civictech.query.schema.AttrType
import civictech.query.schema.Catalog

/**
 * Column typing for lowering. No plan node carries an [AttrType] — a plan node's columns are
 * variable names (`Planner`'s "Column naming") — so types are derived bottom-up from each
 * `Scan` through the [Catalog]. A node's types are a list positional with its
 * `outputColumns`. Every derivation returns either the types or a refusal reason; none throws.
 */
internal object ColumnTypes {

    /** A derivation's outcome: the column types, or why they cannot be derived. */
    sealed interface Derived {
        data class Types(val types: List<AttrType>) : Derived
        data class Failure(val reason: String) : Derived
    }

    /** A `Scan`: the relation's attribute types, positionally against [columns]. */
    fun scan(relation: String, columns: List<String>, catalog: Catalog): Derived {
        val schema = catalog.relations[relation]
            ?: return Derived.Failure("relation '$relation' is not declared in the catalog")
        if (schema.attributes.size != columns.size) {
            return Derived.Failure(
                "scan of '$relation' exposes ${columns.size} columns $columns but the relation " +
                    "declares ${schema.attributes.size} attributes",
            )
        }
        return Derived.Types(schema.attributes.map { it.type })
    }

    /**
     * [columns] read by name, from [sides] in order — the first side declaring a name wins,
     * matching `RowCombine`'s left-first rule. Serves `Project` (one side) and `Join` (left,
     * right).
     */
    fun byName(columns: List<String>, sides: List<Pair<List<String>, List<AttrType>>>): Derived {
        val missing = columns.filter { name -> sides.none { (names, _) -> name in names } }
        if (missing.isNotEmpty()) {
            return Derived.Failure("columns $missing are produced by no input")
        }
        return Derived.Types(
            columns.map { name ->
                val (names, types) = sides.first { (names, _) -> name in names }
                types[names.indexOf(name)]
            },
        )
    }

    /** [name]-to-type lookup, the shape `ExprTyping.typeOf` reads. */
    fun asMap(columns: List<String>, types: List<AttrType>): Map<String, AttrType> = columns.zip(types).toMap()
}
