package civictech.query.ast

import java.io.Serializable

/** A predicate application: [predicate] name applied to positional [terms] (`[QRY1-LANG-01]`). */
data class Atom(val predicate: String, val terms: List<Term>) : Serializable

/**
 * One rule-body literal (`[QRY1-LANG-01]`): a positive atom, a negated atom, or a comparison
 * between two terms. Sealed so a rule body is exhaustively a list of these three forms —
 * later features add semantics (safety analysis, evaluation) over this closed shape, they do
 * not extend it with a fourth case a body walker would silently miss.
 */
sealed class Literal : Serializable {

    /** `atom` holds in the body. */
    data class Positive(val atom: Atom) : Literal()

    /** `not atom` — [atom] must not hold (`[QRY1-LANG-01]` negation). */
    data class Negated(val atom: Atom) : Literal()

    /** `left <op> right` over two terms — a comparison, not a relation lookup. */
    data class Comparison(val left: Term, val op: ComparisonOp, val right: Term) : Literal()
}

/** Closed comparison-operator vocabulary for [Literal.Comparison]. */
enum class ComparisonOp {
    EQ, NE, LT, LE, GT, GE
}
