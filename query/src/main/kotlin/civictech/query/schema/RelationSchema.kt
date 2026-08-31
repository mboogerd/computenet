package civictech.query.schema

import java.io.Serializable

/**
 * A relation's schema: its [attributes] plus an optional row key (cab.1-D3,
 * `[QRY1-LANG-05]`).
 *
 * [rowKey] is the subset of [attributes] names that is unique per row. `null` means
 * "no declared row key" — a deliberately representable state, not an error
 * (`[QRY1-API-05]`): a relation without a row key is a *valid* schema that simply narrows
 * the admissible query language later (enforced by sibling features, not this type). An
 * empty set is rejected rather than treated as a second spelling of "no key" — every
 * non-null [rowKey] must be non-empty and every name in it must be a declared attribute, so
 * "no key" is always `null` and never `emptySet()`, keeping the two representations from
 * being conflated by callers that construct one where they meant the other.
 */
data class RelationSchema(
    val attributes: List<Attribute>,
    val rowKey: Set<String>? = null,
) : Serializable {

    init {
        val names = attributes.map { it.name }
        require(names.size == names.toSet().size) {
            "RelationSchema declares duplicate attribute names: $names"
        }
        rowKey?.let { key ->
            require(key.isNotEmpty()) {
                "RelationSchema.rowKey must be null (no declared key) or a non-empty set, " +
                    "never empty — use null to represent \"no declared row key\""
            }
            val unknown = key - names.toSet()
            require(unknown.isEmpty()) {
                "RelationSchema.rowKey names attributes not declared on this relation: $unknown"
            }
        }
    }

    /** Attribute names, in declaration order — a convenience over `attributes.map { it.name }`. */
    val attributeNames: List<String> get() = attributes.map { it.name }

    /** `true` iff this relation has a declared row key ([rowKey] is non-null). */
    val hasRowKey: Boolean get() = rowKey != null
}
