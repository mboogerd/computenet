package civictech.oracle.model

/**
 * One operator's batch reference: the independent answer a differential run compares the
 * kernel's incremental answer against.
 *
 * [ReferenceOp] itself stays **memberless**, on purpose. It is the catalog's currency —
 * [civictech.oracle.bind.OperatorCatalog] requires a model beside every kernel binding
 * (`[ORA1-API-02]`) and must be able to hold any of them — while the two sub-interfaces below
 * carry the actual evaluation contract. A registered model is a [SourceModel] or an
 * [OperatorModel]; nothing else is evaluable, and [ReferenceModel] says so by name when it
 * meets something that is neither.
 *
 * ## The evaluation contract (decided by computenet-4ru.5.1; 4ru.5.2 and 4ru.5.3 extend it)
 *
 * Evaluation is **pure, batch, membership-only, and executes no kernel cell**
 * (`[ORA1-MODEL-01]`, `[ORA1-MODEL-03]`, `[ORA1-MODEL-11]`):
 *
 * - A **source** ([SourceModel]) folds its own [SourceScript] into a [ModelState]. It sees
 *   its slice and nothing else — no other source, no clock, no delivery order across
 *   sources — because a kernel source cell is a single serialization point and its slice is
 *   exactly its arrival order.
 * - An **operator** ([OperatorModel]) is a pure function from its input [ModelState]s, in
 *   [civictech.oracle.bind.ShapeRule.inputs] port order, to its output [ModelState]. It
 *   never sees the script: everything an operator can depend on has already been folded into
 *   its inputs, which is what makes the model a *recomputation* rather than a second copy of
 *   the kernel's incremental machinery (epic design D2).
 *
 * A `List<ModelState>` rather than a fixed arity keeps the n-ary fan-in family
 * (`UnionSetCell`, `PresenceCountCell`, `QuorumSetCell`) and any consumer's ternary operator
 * expressible without a `when` over an arity — the same reason [civictech.oracle.bind.ShapeRule]
 * carries an ordered, unbounded `inputs` list (`[ORA1-API-03]`).
 *
 * Neither interface is generic, and that is deliberate: `OperatorCatalog.register` takes a
 * plain `ReferenceOp?`, and a type parameter here would either infect that signature or be
 * erased at the catalog boundary anyway. Element domains are untyped throughout the model
 * (see [ElementShape] and [ScriptEvent] for the full reasoning).
 *
 * `[ORA1-MODEL-10]` binds this file too: an implementation may reference value, key and
 * delta types, never a `civictech.cell.data.op` type or a concrete data-cell class. A
 * reference op that reached for `FilterCell` would be checking the implementation against
 * itself.
 */
interface ReferenceOp

/**
 * A source's batch reference: its slice of the script, folded to a state.
 *
 * Implementations must be pure and must not mutate [SourceScript] (`[ORA1-MODEL-11]`). A
 * slice an implementation cannot honestly interpret — the `MapCell` multi-writer case
 * (`[ORA1-MODEL-08]`) is the known one — must fail loudly and by name rather than return a
 * plausible-looking value: an undefined expected value has to be unrepresentable, or a sweep
 * silently checks the kernel against a guess.
 */
interface SourceModel : ReferenceOp {
    /** [slice] folded to this source's observable state. */
    fun evaluate(slice: SourceScript): ModelState
}

/**
 * An operator's batch reference: a pure function of its inputs' states.
 *
 * [inputs] arrives in [civictech.oracle.bind.ShapeRule.inputs] port order, and has that
 * rule's arity for a fixed-arity operator. The fan-in family accepts any length — the
 * registered rule pins one canonical arity for the generator's benefit, while the model
 * itself is n-ary; each such registration says so in its own KDoc.
 */
interface OperatorModel : ReferenceOp {
    /** The state this operator produces from [inputs]. */
    fun evaluate(inputs: List<ModelState>): ModelState
}
