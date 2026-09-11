package civictech.query.ast

import java.io.Serializable

/**
 * A statement that names a [RelationalExpr]'s result: `head` is derived from [expr]
 * (`[QRY1-LANG-04]`). The statement-level sibling of [Rule] — a [Rule] derives its head from
 * a literal body, a [Definition] derives it from the set-operation / outer-join algebra —
 * which is what makes those constructs *first class* rather than something the author has to
 * encode as negation.
 *
 * [head]'s terms name the derived relation's columns, in the same `Term` vocabulary a rule
 * head uses. Pure `Serializable` data, no function value, no source span (`[QRY1-LANG-06]`,
 * cab.2-D1).
 */
data class Definition(val head: Atom, val expr: RelationalExpr) : Serializable
