package civictech.query.schema

import java.io.Serializable

/**
 * One row of a relation at runtime (cab.4-D1, epic §2.2/§2.3's `SetApi<Row>`): [values] are
 * positional against the *producing plan node's* `outputColumns`, not any fixed schema — a
 * `Row` carries no column names of its own. Nullable slots exist only so outer-join null
 * padding (`civictech.query.expr.RowCombinePadded`) is representable; every other producer
 * fills every slot. Pure data, `Serializable` per `[QRY1-LANG-06]`, and it declares no
 * function-typed field: it is a plain value, never an evaluator.
 */
data class Row(val values: List<Any?>) : Serializable
