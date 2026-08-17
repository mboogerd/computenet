package civictech.oracle.bind

import civictech.oracle.model.ElementShape

/**
 * The machine-readable input/output signature of one catalog entry: what shapes an operator
 * consumes, in port order, and what shape it produces.
 *
 * This is `[ORA1-API-03]`'s carrier. The requirement is that a consumer registering a new
 * operator is picked up by the generator's shape rules *without edits to the generator
 * itself*, so the rule has to be **data the generator reads**, not a branch the generator
 * has. Hence a plain product of [ElementShape]s rather than a sealed `Source | Unary |
 * Binary` hierarchy: a `when` over a sealed arity is precisely the generator edit a
 * consumer's ternary operator would force. [inputs] is ordered and unbounded, so the
 * generator's linkability question — "is the shape at this frontier equal to the shape this
 * entry wants at port *i*" — is answerable uniformly at every arity.
 *
 * [ElementShape] equality is therefore load-bearing, which is why its variants are data
 * classes and objects.
 */
data class ShapeRule(
    /** The shape each input port consumes, in port order. Empty for a source. */
    val inputs: List<ElementShape>,
    /** The shape the operator emits. */
    val output: ElementShape,
) {
    /** Number of input ports — 0 for a source, 1 for a unary operator, 2 for a binary one. */
    val arity: Int get() = inputs.size

    companion object {
        /** A source: it consumes nothing and emits [output]. */
        fun source(output: ElementShape): ShapeRule = ShapeRule(emptyList(), output)

        /** A unary operator over [input]. */
        fun unary(input: ElementShape, output: ElementShape): ShapeRule = ShapeRule(listOf(input), output)

        /** A binary operator; [left] is port 0, [right] is port 1. */
        fun binary(left: ElementShape, right: ElementShape, output: ElementShape): ShapeRule =
            ShapeRule(listOf(left, right), output)
    }
}
