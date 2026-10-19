package civictech.bench.tpch

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsTable
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim

/**
 * The append-only BB-TPCH findings renderer.
 *
 * This object deliberately accepts records rather than reading a file or running a
 * measurement itself. The caller owns the run and supplies the BS-6 heap-path verdict;
 * this keeps the G-42 claim tied to the instrument that produced it.
 */
object TpchFindings {

    /**
     * Render one BB-TPCH entry and its coverage/finding blocks.
     *
     * Verified records are folded by query and refresh kind. A row's value is the mean
     * per-batch SIM collector-applied delta elements across the supplied records; its
     * dispersion is the spread of those per-record means. With one seed the spread is
     * retained as zero internally, but the rendered row is marked `± n/a` because no
     * dispersion was observed.
     */
    fun render(
        records: List<TpchRecord>,
        env: RunEnvironment,
        date: String,
        bs6Verdict: GrowthVerdict,
        bs6Series: String,
    ): String {
        require(records.isNotEmpty()) { "findings render requires at least one record" }
        require(date.isNotBlank()) { "findings date must not be blank" }
        require(bs6Series.isNotBlank()) { "BS-6 series must not be blank" }

        val verified = records.filterIsInstance<TpchRecord.Verified>()
        require(verified.isNotEmpty()) { "findings render requires a verified SIM record" }
        require(verified.all { it.header.drive == Drive.SIM }) {
            "BB-TPCH findings rows must come from SIM records"
        }
        require(verified.all { it.header.env == env }) {
            "BB-TPCH findings environment must match every verified record"
        }

        val rows = verified
            .groupBy { it.query }
            .toSortedMap(compareBy<TpchQuery> { it.ordinal })
            .flatMap { (query, queryRecords) ->
                listOfNotNull(
                    measuredRow(query, RefreshKind.RF1, queryRecords),
                    measuredRow(query, RefreshKind.RF2, queryRecords),
                )
            }
        require(rows.isNotEmpty()) { "verified records contain no RF1/RF2 figures" }

        val coverage = coverageFor(records)
        val triggerStatement =
            "${bs6Verdict.name} for tag/tombstone accretion under a balanced RF1/RF2 stream: " +
                bs6Series
        val entry = Findings.entry(
            date = date,
            subject = "BB-TPCH — incremental TPC-H hand-assembly coverage",
            results = FindingsTable(
                results = rows.map { it.result },
                labels = rows.map { it.label },
                dispersionObserved = rows.map { it.dispersionObserved },
            ),
            trigger = TriggerClaim.Cited("G-42", triggerStatement),
        )

        return buildString {
            append(entry)
            appendLine()
            appendLine()
            appendLine(SIM_MAINTENANCE_WORK_CAVEAT)
            appendLine()
            appendLine("Specification: $SPEC_REVISION")
            appendLine("Framing: $FRAMING")
            appendLine("Generator deviations:")
            TpchGenerator.deviations.forEach { deviation -> appendLine("- $deviation") }
            appendLine("Route: hand (QRY1 route unbuildable in :bench — [BEN1-03]; see finding below)")
            appendLine()
            append(coverage.render())
            appendLine()
            appendLine("Candidate G-item")
            appendLine("| Gap | Missing surface | Trigger | Tier |")
            appendLine("| --- | --- | --- | --- |")
            appendLine(
                "| G-NN | aggregate output as a relation: GroupByCell emits MapDelta and no " +
                    "kernel relation operator (fold, filter, semijoin) consumes it; blocks " +
                    "TPC-H Q2, Q11, Q13, Q15, Q17, Q18, Q20, Q21, Q22; QRY1-side twin: " +
                    "Lowering.kt non-root GroupAggregate refusal | trigger: first consumer " +
                    "needing a second-level aggregate or scalar-subquery threshold | 24 |",
            )
            appendLine()
            appendLine(
                "Pulled trigger: [BEN1-03] fences `:query`/`:oracle` out of `:bench`, so " +
                    "BB-TPCH cannot be QRY1's acceptance corpus until BEN1 or the module " +
                    "layout decides otherwise; the evidence is `bench/build.gradle.kts` and " +
                    "`ProjectGraphTest`.",
            )
            appendLine()
            appendLine(
                "[BB-TPCH-30] No comparison against DBToaster or any published IVM figure " +
                    "is drawn: this is SF 0.001 hand coverage with set-semantics-over-keyed-rows, " +
                    "the comment-column deviation, and only 13/22 queries covered.",
            )
        }
    }

    private fun measuredRow(
        query: TpchQuery,
        kind: RefreshKind,
        records: List<TpchRecord.Verified>,
    ): LabelledResult? {
        val perRun = records.mapNotNull { record ->
            val figures = when (kind) {
                RefreshKind.RF1 -> record.rf1
                RefreshKind.RF2 -> record.rf2
            }
            if (figures.isEmpty()) return@mapNotNull null
            val appliedElements = figures.map { figure ->
                val work = figure.figure as? Figure.Work
                    ?: error("${query.name} ${kind.name} findings require SIM work figures")
                work.steps.toDouble()
            }
            appliedElements.average()
        }
        if (perRun.isEmpty()) return null
        val spread = perRun.maxOrNull()!! - perRun.minOrNull()!!
        return LabelledResult(
            label = "${query.name} ${kind.name}",
            result = BenchResult(
                value = perRun.average(),
                unit = "applied delta elements/batch",
                dispersion = spread,
                drive = Drive.SIM,
                env = records.first().header.env,
            ),
            dispersionObserved = perRun.size >= 2,
        )
    }

    private data class LabelledResult(
        val label: String,
        val result: BenchResult,
        val dispersionObserved: Boolean,
    )

    private const val SIM_MAINTENANCE_WORK_CAVEAT =
        "SIM maintenance-work caveat (all RF1/RF2 rows above): " +
            "`applied delta elements/batch` sums adds+dels or puts+removals applied " +
            "by every materialized query-outlet collector between quiescence points; " +
            "the same logical change counts once at each observed outlet it reaches. " +
            "It is query-specific propagation volume, not elapsed time, internal " +
            "operator operations, or deliveries on unmaterialized edges. RF1/RF2 " +
            "host-scheduler task counts are query-independent ingress instrumentation " +
            "and are not retained in these rows."
}
