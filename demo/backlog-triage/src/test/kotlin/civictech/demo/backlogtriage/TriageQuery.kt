package civictech.demo.backlogtriage

import civictech.query.QueryCompiler
import civictech.query.diag.CompileResult
import civictech.query.run.CompiledQuery
import civictech.query.schema.AttrType
import civictech.query.schema.Attribute
import civictech.query.schema.Catalog
import civictech.query.schema.RelationSchema

/**
 * Backlog-triage's relational core, expressed as a query (feature computenet-cab.7, task .9;
 * `[QRY1-ORA-10]`; decision cab.7-D12).
 *
 * [TriagePipeline] ([TriageApp.kt]) folds `prefs` (a [Pref] set) into `contribs` (`FlatMapSetCell`
 * projecting each preference into a `+1` winner [Contribution] and a `-1` loser one), then
 * `score` (`GroupByCell` avg of sign per item) and `votes` (`GroupByCell` count per item) — the
 * pipeline's own KDoc names this "the kernel-operator pipeline", i.e. the mean lane. Everything
 * else — `elo`/`bt`/`trueskill`/`glicko`/`wenglin` (cross-key/global-fixpoint `RatingCell`s,
 * `RankingCells.kt`'s own KDoc), `wilson` (a plain per-key `Aggregator` but not compared here —
 * out of this task's scope), and `meta` (Borda fusion of all of the above) — is non-relational by
 * design and outside the comparison (see [TriagePipeline]'s own doc comment).
 *
 * Only the mean lane's shape is expressible here, for the same reason tiering's `prefAvg` is
 * boundary arithmetic rather than a query (`TieringQuery`'s KDoc, cab.7-D11):
 *
 * - `contribs` puts a CONSTANT `±1` in a rule's head (`FlatMapSetCell`, TriageApp.kt:80-86);
 *   [QRY1-LANG-01] terms are variables or constants bound by the body, and
 *   `Planner.headVariables` refuses a head constant outright (Planner.kt:192).
 * - Folding `prefs` into `score`/`votes` the way `contribs` does — winner `+1`, loser `-1`, one
 *   GroupBy avg / GroupBy count — would need a two-rule UNION (`wins ∪ losses`), and
 *   `PlanAnalyses.unionKey` is never key-preserving (PlanAnalyses.kt:166): an `@avg`/`@count`
 *   over it is `BAG_SEMANTICS_REQUIRED` (computenet-afnwl; that relaxation is parked, not worked
 *   around here).
 *
 * So the query below states the two one-sided tallies the language CAN express — `wins`
 * (grouped by winner) and `losses` (grouped by loser) — and [TriageQueryAgreementTest] applies
 * the same `score = (wins − losses) / (wins + losses)`, `votes = wins + losses` boundary
 * arithmetic to the query's answers that the kernel pipeline computes with its own cells.
 *
 * Row key: `prefs(agent, winner, loser)` keyed on all three attributes — a preference is already
 * unique per triple in the demo (no cell dedupes it further, and `TriageApp`'s reverse-vote
 * retraction is app logic layered on top, not a pipeline invariant — see [TriageQuery]'s test
 * for why the churn generator doesn't emulate it). Both aggregates apply directly to a keyed
 * `Scan`, with no projection in front of it, so both compile independently of
 * computenet-afnwl's parked relaxation.
 */
object TriageQuery {

    val catalog: Catalog = Catalog(
        mapOf(
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
     * `wins`: group by winner, count preferences. `losses`: group by loser, count preferences —
     * the two one-sided tallies `score`/`votes`' boundary arithmetic is built from, in
     * [TriageQueryAgreementTest].
     */
    const val SOURCE = """
        @count wins(W, L) :- prefs(A, W, L).
        @count losses(L, W) :- prefs(A, W, L).
    """

    /** Compiled once, through the compiler front door — failing loudly on a rejection. */
    val compiled: CompiledQuery = when (val result = QueryCompiler.compile(SOURCE, catalog)) {
        is CompileResult.Compiled -> result.query
        is CompileResult.Rejected -> error(
            "TriageQuery source did not compile: ${result.rejections}\n$SOURCE",
        )
    }
}
