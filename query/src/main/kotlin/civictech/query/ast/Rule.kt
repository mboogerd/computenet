package civictech.query.ast

import java.io.Serializable

/**
 * One Datalog-style rule: a [head] atom derived from a [body] of literals, with an optional
 * [aggregate] annotation on the head (`[QRY1-LANG-01]`, `[QRY1-LANG-03]`). Surface
 * vocabulary only — safety analysis and evaluation are sibling features' concern, not this
 * type's.
 */
data class Rule(
    val head: Atom,
    val body: List<Literal>,
    val aggregate: Aggregate? = null,
) : Serializable
