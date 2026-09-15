package civictech.demo.tiering

import civictech.query.QueryCompiler
import civictech.query.diag.CompileResult
import civictech.query.run.CompiledQuery
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema

/**
 * Tiering's relational core, expressed as a query (feature computenet-cab.7, task .8;
 * `[QRY1-ORA-10]`; decision cab.7-D11).
 *
 * [TierPipeline] ([TieringApp.kt]) folds two independent signals into one fused tier:
 * `vals` (a [KeyedSetCell][civictech.cell.data.KeyedSetCell] of [Valuation]s, one live
 * valuation per `(agent, item)`) averaged per item into `tierAvg`, and `prefs` (a set of
 * [Pref]s) fanned out into `+1`/`-1` [Contribution]s and averaged per item into `prefAvg`.
 * `[Tiering.fuse]` blends the two into the board.
 *
 * Only the two averages' RELATIONAL shape is expressible here — everything below is why the
 * rest of the pipeline is boundary arithmetic, not a language gap this task can close:
 *
 * - `contribs` puts a CONSTANT `±1` in a rule's head (`FlatMapSetCell`, TieringApp.kt:195-201);
 *   [QRY1-LANG-01] terms are variables or constants bound by the body, and
 *   `Planner.headVariables` refuses a head constant outright (Planner.kt:192).
 * - Folding `prefs` into one `prefAvg` the way `contribs` does — winner gets `+1`, loser
 *   gets `-1`, then one GroupBy — would need a two-rule UNION (`wins ∪ losses` with opposite
 *   signs), and `PlanAnalyses.unionKey` is never key-preserving (PlanAnalyses.kt:166): an
 *   `@avg`/`@sum` over it is `BAG_SEMANTICS_REQUIRED` (computenet-afnwl; that relaxation is
 *   parked, not worked around here — see this task's bead for the decision).
 * - `Tiering.fuse` (the 0.7/0.3 weighted blend, normalization, fixed thresholds) is ordinary
 *   arithmetic over two doubles, not a relational operator.
 *
 * So the query below states the two aggregates the language CAN express — `tierAvg`
 * (identical to the kernel's), and `wins`/`losses` (the two one-sided counts `contribs`
 * would otherwise fold with a sign) — and [TieringQueryAgreementTest] applies the same
 * `(wins − losses) / (wins + losses)` and `Tiering.fuse` arithmetic to the query's answers
 * that the kernel pipeline applies to its own, at the pre-fusion boundary (cab.7-D10/D11).
 * `contribs`, `fused`'s kernel cell, the manual re-tier lane, `board`, `items` and `manual`
 * have no relational twin and are not compared here — [TieringPipelineTest] already pins the
 * manual lane.
 *
 * Row keys: `vals(agent, item, score)` keyed `{agent, item}` — exactly the KeyedSetCell's own
 * key, so the row key is honest, not a convenience fiction. `prefs(agent, winner, loser)`
 * keyed on all three attributes — a preference is already unique per triple in the demo (no
 * cell dedupes it further). Both aggregates apply directly to a keyed `Scan`, with no
 * projection in front of it (Planner.kt's aggregate-lowering KDoc), so both compile
 * independently of computenet-afnwl's parked relaxation.
 */
object TieringQuery {

    val catalog: Catalog = Catalog(
        mapOf(
            "vals" to RelationSchema(
                attributes = listOf(
                    Attribute("agent", AttrType.STRING),
                    Attribute("item", AttrType.STRING),
                    Attribute("score", AttrType.LONG),
                ),
                rowKey = setOf("agent", "item"),
            ),
            "prefs" to RelationSchema(
                attributes = listOf(
                    Attribute("agent", AttrType.STRING),
                    Attribute("winner", AttrType.STRING),
                    Attribute("loser", AttrType.STRING),
                ),
                rowKey = setOf("agent", "winner", "loser"),
            ),
        ),
    )

    /**
     * `tierAvg`: group by item, average score (LONG → DOUBLE). `wins`/`losses`: group by
     * winner / loser, count preferences — the two one-sided tallies `prefAvg`'s sign-mean is
     * built from at the boundary, in [TieringQueryAgreementTest].
     */
    const val SOURCE = """
        @avg tierAvg(I, S) :- vals(A, I, S).
        @count wins(W, L) :- prefs(A, W, L).
        @count losses(L, W) :- prefs(A, W, L).
    """

    /** Compiled once, through the compiler front door — failing loudly on a rejection. */
    val compiled: CompiledQuery = when (val result = QueryCompiler.compile(SOURCE, catalog)) {
        is CompileResult.Compiled -> result.query
        is CompileResult.Rejected -> error(
            "TieringQuery source did not compile: ${result.rejections}\n$SOURCE",
        )
    }
}
