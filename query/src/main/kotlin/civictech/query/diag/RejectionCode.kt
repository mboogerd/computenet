package civictech.query.diag

/**
 * A closed enum naming the reason a query was rejected during compilation.
 *
 * Landed EMPTY by cab.1 (feature decision cab.1-D1, computenet-cab.1): each variant is
 * added by the feature that realizes the refusal it names, together with that refusal's own
 * named behaviour test ([QRY1-REJECT-05]). Pre-seeding a variant here — before any feature
 * exercises the rejection it stands for — would violate that discipline from day one: a
 * variant with no behaviour test is exactly the gap [QRY1-REJECT-05] exists to forbid.
 *
 * [SYNTAX_ERROR] is the first variant to be added under that discipline, by the text-parser
 * feature (computenet-cab.2.2) together with the named tests that produce it in
 * `civictech.query.parse.QueryParserTest`.
 *
 * [Rejection] pairs a [RejectionCode] with a [Locus] (offending source span or plan node)
 * and a `specId` naming the spec text, gap marker, or roadmap item that forbids the
 * construct — the data shape [QRY1-REJECT-02] requires. `RejectionCode` being empty does not
 * block that shape from existing; it blocks a [Rejection] from being *instantiated* until a
 * rejection-realizing feature adds a variant, which is intentional (see
 * `civictech.query.diag.DiagSerializationTest`'s KDoc for what is proven in the meantime).
 *
 * [QRY1-REJECT-02], [QRY1-REJECT-05]
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
}
