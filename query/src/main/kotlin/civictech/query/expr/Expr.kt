package civictech.query.expr

import civictech.query.ast.ComparisonOp
import civictech.query.schema.AttrType
import java.io.Serializable

/**
 * Closed row-expression algebra (cab.4-D1): the language `ExprTyping` types and the
 * interpreters in `Interpreters.kt` evaluate over a `civictech.query.schema.Row`. Sealed to
 * exactly [Attr], [Const], [Cmp], [And], [Or], [Not] — no arithmetic node. Nothing in the
 * supported aggregates or comparisons needs one: the aggregate selectors
 * (`RowSelector`/`RowLongSelector`) are column reads, and comparisons are binary on values as
 * they stand, not on a computed sum or product. Do not add an `Add`/`Mul`/etc. node
 * speculatively — a future feature that actually needs arithmetic extends this hierarchy (and
 * every completeness guard that closes over it) deliberately, not by accretion here.
 *
 * Pure data, `Serializable` per `[QRY1-LANG-06]` — this is a value the parser/typer/lowering
 * pipeline passes around, never itself an evaluator (the interpreters in `Interpreters.kt`
 * hold `Expr` values but are the things that implement Kotlin function types).
 */
sealed class Expr : Serializable {

    /** A column reference by [name], resolved against a `Row`'s producing column list. */
    data class Attr(val name: String) : Expr()

    /**
     * A typed literal constant — same constructor validation as
     * `civictech.query.ast.Term.Const`, deliberately duplicated rather than imported: `expr`
     * does not depend on `civictech.query.ast`.
     */
    data class Const(val value: Any, val type: AttrType) : Expr() {
        init {
            require(type.runtimeType.isInstance(value)) {
                "Const value $value (${value::class}) does not match declared type $type " +
                    "(expects ${type.runtimeType})"
            }
        }
    }

    /** A binary comparison of [left] and [right] under [op]. */
    data class Cmp(val op: ComparisonOp, val left: Expr, val right: Expr) : Expr()

    /** Boolean conjunction of [left] and [right]. */
    data class And(val left: Expr, val right: Expr) : Expr()

    /** Boolean disjunction of [left] and [right]. */
    data class Or(val left: Expr, val right: Expr) : Expr()

    /** Boolean negation of [expr]. */
    data class Not(val expr: Expr) : Expr()
}
