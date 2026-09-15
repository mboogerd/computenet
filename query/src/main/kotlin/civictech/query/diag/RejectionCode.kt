package civictech.query.diag

/**
 * A closed enum naming the reason a query was rejected during compilation.
 *
 * Closed under [QRY1-REJECT-05]: every variant is added by the feature that realizes the
 * refusal it names, together with a producing test, and a variant with no registered
 * producer fails `civictech.query.diag.RejectionExhaustivenessTest` (BS-13, cab.5-D11) —
 * the enum is never pre-seeded ahead of its refusal (cab.1-D1). Provenance, one line each:
 *
 * - [SYNTAX_ERROR] — the text parser (computenet-cab.2.2).
 * - [UNSAFE_RULE], [EDB_REDEFINED], [RECURSION_UNSUPPORTED] — the safety/EDB/recursion
 *   analysis (computenet-cab.2.3), `civictech.query.parse.SafetyAnalysis`.
 * - [NO_LOWERING] — `civictech.query.QueryCompiler`'s mapping of every
 *   `civictech.query.lower.LoweringRefusal` (computenet-cab.5.1, cab.5-D10).
 * - [UNKNOWN_PREDICATE], [ARITY_MISMATCH], [PREDICATE_REDEFINED], [UNPLANNABLE_STATEMENT] —
 *   `civictech.query.parse.WellFormednessAnalysis`, the fence in front of
 *   `civictech.query.plan.Planner`'s preconditions (computenet-cab.5.1, cab.5-D6).
 * - [ORDER_DEPENDENT_AGGREGATE] — the text parser, re-attributed from [SYNTAX_ERROR]
 *   (computenet-cab.5.3, [QRY1-SEM-05]).
 * - [BAG_SEMANTICS_REQUIRED] — `civictech.query.plan.BagSemantics`, run by
 *   `civictech.query.QueryCompiler` before and after planning (computenet-cab.5.2,
 *   [QRY1-SEM-02], [QRY1-SEM-04]).
 *
 * [Rejection] pairs a [RejectionCode] with a [Locus] (offending source span, statement, or
 * plan node) and a `specId` naming the spec text, gap marker, or roadmap item that forbids
 * the construct — the data shape [QRY1-REJECT-02] requires.
 *
 * [QRY1-REJECT-02], [QRY1-REJECT-05]
 *
 * **Five codes deliberately absent (cab.5-D5).** [NON_TOTAL_ORDER] ([QRY1-SEM-06]),
 * [MULTIWRITER_NONCONVERGENT] ([QRY1-SEM-07]), [WINDOW_CLOSE_UNSUPPORTED] ([QRY1-REJECT-07]),
 * [GLOBAL_ORDER_UNSUPPORTED] ([QRY1-REJECT-08]) and [EXCLUSIVE_PAYLOAD] ([QRY1-REJECT-09]) are
 * NOT variants of this enum: every antecedent they would name is unexpressible in today's
 * admissible vocabulary (no tie-ambiguous selector — every `AttrType.runtimeType` is a
 * totally-ordered `Comparable`; no declared multi-writer relation; no window or global-order
 * construct in the AST or grammar; no `Owned`/`Leased`/`Frozen` `AttrType`), so adding one now
 * would be a variant with no producing test, forbidden by [QRY1-REJECT-05]/cab.1-D1. Each
 * unexpressibility is pinned by a named test in
 * `civictech.query.diag.AdmissibleVocabularyTest` whose failure — the antecedent becoming
 * constructible — names the code to add.
 */
enum class RejectionCode {

    /**
     * The surface text is not in the `[QRY1-LANG-01]`/`[QRY1-LANG-04]` grammar — a missing
     * terminator, an unbalanced parenthesis, an unexpected token, an unterminated string.
     *
     * Strictly *syntactic*. A construct that parses but is semantically inadmissible —
     * an unsafe rule, a redefined EDB relation, a recursive rule graph, bag-semantics `ALL`
     * (cab.2-D3) — is **not** this code: it gets its own variant from the feature that
     * refuses it, so a rejection's code never mis-attributes a semantic exclusion to a typo.
     *
     * [QRY1-REJECT-03]
     */
    SYNTAX_ERROR,

    /**
     * A rule is unsafe: a head variable, a negated-atom variable, or a comparison variable
     * does not occur in any positive body atom of the same rule. The [Rejection] names the
     * unbound variable(s).
     *
     * [QRY1-LANG-07]
     */
    UNSAFE_RULE,

    /**
     * A rule's head predicate, or a `define` statement's head predicate (computenet-bmq7i),
     * is also a relation declared in the [Catalog][civictech.query.schema.Catalog] — neither a
     * rule nor a `define` statement can redefine an EDB (extensional database) relation.
     *
     * [QRY1-LANG-08]
     */
    EDB_REDEFINED,

    /**
     * A rule reaches its own head predicate through the head-predicate dependency graph of
     * the rule set — self- or mutual recursion. Recursive evaluation is QRY2's, not this
     * module's; the [Rejection] names every predicate on the offending cycle and cites the
     * unbuilt cycle machinery ([21-CYCLE-01], [21-CYCLE-03]) as the reason it is refused
     * rather than evaluated.
     *
     * [QRY1-LANG-09]
     */
    RECURSION_UNSUPPORTED,

    /**
     * A plan node has no lowering rule for the shape it was given — a non-root aggregate, an
     * ill-typed comparison, an aggregate over a column the kernel cannot sum. One rejection per
     * `civictech.query.lower.LoweringRefusal`, located at that plan node (`<root>/<n>:<kind>`),
     * its `specId` naming the node kind and the refusal reason; never collapsed, never a
     * nearest-match operator.
     *
     * [QRY1-REJECT-06], [QRY1-REJECT-01]
     */
    NO_LOWERING,

    /**
     * A body atom, negated atom, or definition leaf names a predicate that is neither a
     * relation declared in the [Catalog][civictech.query.schema.Catalog] nor the head of a
     * rule or `define` statement.
     *
     * [QRY1-LANG-05]
     */
    UNKNOWN_PREDICATE,

    /**
     * A predicate is used at an arity other than the one it has: an atom whose term count
     * differs from its catalog schema's attribute count or from its defining head's arity,
     * two rules for one head at different arities, a `define` head whose arity differs from
     * its expression's, or set-operation operands of different arity.
     *
     * [QRY1-LANG-05]
     */
    ARITY_MISMATCH,

    /**
     * A head predicate is defined by both a rule and a `define` statement, or by more than one
     * `define` statement — a head has exactly one kind of definition. (A head that redefines a
     * catalog relation is [EDB_REDEFINED], not this.)
     *
     * [QRY1-LANG-04], [QRY1-REJECT-03]
     */
    PREDICATE_REDEFINED,

    /**
     * A statement parses and is safe but has a shape the planner has no translation for: a
     * constant or repeated variable in a head, a rule with no positive body atom (a fact such
     * as `q(1).` included), an aggregate-annotated nullary head, or an outer-join key that is
     * not a column of its side or is used twice.
     *
     * [QRY1-REJECT-03]
     */
    UNPLANNABLE_STATEMENT,

    /**
     * A rule's aggregate annotation names something outside `[QRY1-LANG-03]`'s closed seven:
     * `@first`, `@last`, `@scan` — the arrival-order aggregates the set-semantic operator
     * algebra excludes by rule ([24-AGG-01]) — or any other name `[QRY1-LANG-03]` never
     * admitted. Located at the aggregate name's span. Strictly a re-attribution of what the
     * text parser used to report as [SYNTAX_ERROR]: the name is syntactically well-formed, so
     * [SYNTAX_ERROR] stays syntactic and this variant carries the semantic exclusion instead.
     *
     * [QRY1-SEM-05]
     */
    ORDER_DEPENDENT_AGGREGATE,

    /**
     * The query's answer would differ between bag and set semantics, and the operator algebra
     * is set-semantic (`[QRY1-SEM-01]`; weighted/bag semantics is owned by 96 §E6 / 95 R17).
     * Two producers, both in `civictech.query.plan.BagSemantics`:
     *
     * - an `EXCEPT ALL` / `UNION ALL` / `INTERSECT ALL` set operation in a `define` statement,
     *   located at that statement (`[QRY1-SEM-04]`); and
     * - a `COUNT`, `SUM` or `AVG` `GroupAggregate` whose input is not key-preserving per the
     *   planner's `[QRY1-PLAN-06]` annotation, located at that plan node (`<root>/<n>:<kind>`)
     *   and naming the lost row key or the relations with no declared row key
     *   (`[QRY1-SEM-02]`). `MIN`, `MAX`, `TOP_K` and `COLLECT_TO_SET` never produce it.
     *
     * Never a distinct-semantics approximation compiled in its place.
     *
     * [QRY1-SEM-02], [QRY1-SEM-04], [QRY1-REJECT-01]
     */
    BAG_SEMANTICS_REQUIRED,
}
