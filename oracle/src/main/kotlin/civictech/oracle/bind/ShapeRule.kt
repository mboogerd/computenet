package civictech.oracle.bind

import civictech.oracle.model.ElementShape

/**
 * The machine-readable input/output signature of one catalog entry: what shapes an operator
 * consumes, in port order, and what shape it produces.
 *
 * This is `ORA1 §API-03`'s carrier. The requirement is that a consumer registering a new
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
 *
 * [inputPorts] and [outputPort] carry the same requirement one level down: a generator
 * cannot write a kernel `ConnectStep(from, outlet, to, inlet)`
 * ([civictech.cell.graph.GraphDsl]) from shapes alone — it needs the actual port name the
 * registered `CellFactory` exposes on its `@CellBase` interface. Kernel cells do not agree on
 * a single convention (`left`/`right` for the binary family, `fact`/`dimension` for
 * `LookupJoinCell`, `inlet` for unary cells and for every fan-in cell's single dynamic
 * inlet), so the port names are registration data, exactly like the shapes they sit beside.
 */
data class ShapeRule(
    /** The shape each input port consumes, in port order. Empty for a source. */
    val inputs: List<ElementShape>,
    /** The shape the operator emits. */
    val output: ElementShape,
    /**
     * The port name for each entry in [inputs], in the same order and the same size. A fan-in
     * cell that exposes one dynamic inlet for every arm (`UnionSetCell`, `PresenceCountCell`,
     * `QuorumSetCell`) repeats that one port name once per advertised arm.
     */
    val inputPorts: List<String>,
    /** The port name the operator emits on. Every registered kernel cell today names it `outlet`. */
    val outputPort: String = "outlet",
) {
    init {
        require(inputPorts.size == inputs.size) {
            "inputPorts must have one entry per input: inputs.size=${inputs.size}, inputPorts.size=${inputPorts.size}"
        }
    }

    /** Number of input ports — 0 for a source, 1 for a unary operator, 2 for a binary one. */
    val arity: Int get() = inputs.size

    companion object {
        /** A source: it consumes nothing and emits [output] on [outputPort]. */
        fun source(output: ElementShape, outputPort: String = "outlet"): ShapeRule =
            ShapeRule(emptyList(), output, emptyList(), outputPort)

        /** A unary operator over [input], consumed on [inputPort] (default `inlet`). */
        fun unary(
            input: ElementShape,
            output: ElementShape,
            inputPort: String = "inlet",
            outputPort: String = "outlet",
        ): ShapeRule = ShapeRule(listOf(input), output, listOf(inputPort), outputPort)

        /**
         * A binary operator; [left] is port 0 (default port name `left`), [right] is port 1
         * (default port name `right`).
         */
        fun binary(
            left: ElementShape,
            right: ElementShape,
            output: ElementShape,
            leftPort: String = "left",
            rightPort: String = "right",
            outputPort: String = "outlet",
        ): ShapeRule = ShapeRule(listOf(left, right), output, listOf(leftPort, rightPort), outputPort)
    }
}
