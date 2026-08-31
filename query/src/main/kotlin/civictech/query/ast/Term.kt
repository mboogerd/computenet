package civictech.query.ast

import civictech.query.schema.AttrType
import java.io.Serializable

/**
 * Surface term vocabulary (`[QRY1-LANG-01]`): a query body element references a bound
 * variable or a literal constant. Pure data, `Serializable` per `[QRY1-LANG-06]` — no
 * evaluation logic here, only the shape a parser produces and a planner consumes.
 */
sealed class Term : Serializable {

    /** A variable, identified by [name]; two [Var]s with the same [name] are the same binding. */
    data class Var(val name: String) : Term()

    /**
     * A typed literal constant. [type] is the declared [AttrType] and [value] its runtime
     * representation — [AttrType.INT] as [Int], [AttrType.LONG] as [Long], [AttrType.DOUBLE]
     * as [Double], [AttrType.STRING] as [String], [AttrType.BOOL] as [Boolean]. The
     * constructor validates the pairing so a [Const] can never carry a value whose runtime
     * type disagrees with its own [type].
     */
    data class Const(val value: Any, val type: AttrType) : Term() {
        init {
            require(type.runtimeType.isInstance(value)) {
                "Const value $value (${value::class}) does not match declared type $type " +
                    "(expects ${type.runtimeType})"
            }
        }
    }
}
