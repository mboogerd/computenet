package civictech.query.ast

import java.io.Serializable

/**
 * Closed set-operation vocabulary (`[QRY1-LANG-04]`, citing `[24-OP-UNION-01]`,
 * `[24-OP-INTERSECT-01]`, `[24-OP-SEMIJOIN-01]`). Union, intersection and difference exist
 * as *first-class* constructs precisely so an author never has to encode them as negation.
 */
enum class SetOpKind {
    UNION, INTERSECTION, DIFFERENCE
}

/**
 * Which side of an outer join is preserved (`[QRY1-LANG-04]`, citing
 * `[24-OP-OUTERJOIN-01]`): `LEFT` keeps every left row, `RIGHT` every right row, `FULL` both.
 */
enum class OuterJoinSide {
    LEFT, RIGHT, FULL
}

/**
 * One equality of an outer join's `ON` clause: the [left] expression's variable joined to
 * the [right] expression's variable. Variables, not positions — the surrounding [Atom]s
 * already name their columns positionally, so a key is stated in the same `Term.Var`
 * vocabulary a rule body uses rather than in a second, parallel one.
 */
data class JoinKey(val left: Term.Var, val right: Term.Var) : Serializable

/**
 * A relational expression: the operand vocabulary of the first-class set operations and
 * outer joins `[QRY1-LANG-04]` requires. It sits *alongside* [Rule], not inside it — a rule
 * body remains exactly the `[QRY1-LANG-01]` literal list it was, and a [Definition] is the
 * statement form that names an expression's result.
 *
 * Pure `Serializable` data with no function value (`[QRY1-LANG-06]`) and — deliberately —
 * no source span and no producer marker of any kind: cab.2-D1 asserts parser/builder
 * equivalence by structural AST equality, which any such field would break. Spans travel in
 * a parser-side table, owned by the parser task.
 *
 * Sealed, so a planner's `when` over an expression is exhaustive and a later construct
 * cannot be silently skipped by an existing walker.
 */
sealed class RelationalExpr : Serializable {

    /** A leaf: the rows of [atom]'s predicate, bound as [atom]'s terms name them. */
    data class Relation(val atom: Atom) : RelationalExpr()

    /**
     * [kind] applied to [left] and [right].
     *
     * [all] is the `UNION ALL` / `INTERSECT ALL` / `EXCEPT ALL` flag. It is **stored and
     * never judged here** (cab.2-D3): representing it is what lets the rejection feature
     * refuse it as `BAG_SEMANTICS_REQUIRED` (`[QRY1-SEM-04]`) rather than mis-attribute a
     * semantic exclusion to a syntax error. Neither this type nor the builder passes any
     * judgment on it — `all = true` is a perfectly constructible AST value.
     */
    data class SetOp(
        val kind: SetOpKind,
        val left: RelationalExpr,
        val right: RelationalExpr,
        val all: Boolean = false,
    ) : RelationalExpr()

    /**
     * An outer join of [left] and [right] on the [on] key equalities, preserving [side].
     *
     * [on] must be non-empty: an outer join with no key equality is a cross product with a
     * preserved side, which is not the `[24-OP-OUTERJOIN-01]` construct this node names, and
     * admitting it would give the planner a shape with no operator to lower it to. The check
     * is structural, not semantic — it is the same constructor-validation discipline
     * [Aggregate] and [Term.Const] already use, not the safety analysis (a sibling's).
     */
    data class OuterJoin(
        val side: OuterJoinSide,
        val left: RelationalExpr,
        val right: RelationalExpr,
        val on: List<JoinKey>,
    ) : RelationalExpr() {
        init {
            require(on.isNotEmpty()) {
                "OuterJoin($side) requires at least one JoinKey; an outer join with no key " +
                    "equality is a cross product, not [24-OP-OUTERJOIN-01]'s construct"
            }
        }
    }
}
