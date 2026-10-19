package civictech.bench.nexmark

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsRefusalException
import civictech.bench.FindingsTable
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim

/** The comparison deliberately excluded from the BB-NEX entry. */
const val NO_PUBLISHED_COMPARISON: String =
    "No comparison against Beam, Flink, DBSP/Feldera or any published Nexmark figure is " +
        "drawn: set semantics over value-equal rows, Q7 one-bid tie-break, 1 000 events/s, " +
        "SIM collector-applied counts, no latency, no WAL comparison in this entry."

/** Renders the append-only BB-NEX coverage entry from already-verified run records. */
object NexmarkFindings {

    /**
     * Render one BB-NEX entry.
     *
     * Only verified SIM records contribute numeric rows. Other records remain useful to
     * [coverageFor], but an unverified or failed record makes the whole finding
     * unreportable: a partial entry would make the coverage table look more certain than
     * the run set is.
     */
    fun render(
        records: List<NexmarkRecord>,
        env: RunEnvironment,
        date: String,
        growth: GrowthFinding,
    ): String {
        if (records.isEmpty()) {
            refuse("findings render requires at least one record")
        }
        if (date.isBlank()) {
            refuse("findings date must not be blank")
        }

        records.filterIsInstance<NexmarkRecord.Unverified>().firstOrNull()?.let { record ->
            refuse(
                "unverified run for ${record.query.name} seed ${record.header.seed}: " +
                    record.reason,
            )
        }
        records.filterIsInstance<NexmarkRecord.Failed>().firstOrNull()?.let { record ->
            refuse(
                "failed run for ${record.query.name} seed ${record.header.seed} at batch " +
                    "${record.batchIndex}; differing rows=${record.differingRows}",
            )
        }

        val verified = records.filterIsInstance<NexmarkRecord.Verified>()
        if (verified.isEmpty()) {
            refuse("findings render requires at least one verified SIM record")
        }
        if (verified.any { it.header.drive != Drive.SIM }) {
            refuse("BB-NEX findings rows must come from SIM records")
        }
        if (verified.any { it.header.env != env }) {
            refuse("BB-NEX findings environment must match every verified record")
        }

        val rows = verified
            .groupBy { it.query }
            .toSortedMap(compareBy<NexmarkQuery> { it.ordinal })
            .flatMap { (query, queryRecords) ->
                listOfNotNull(
                    measuredRow(query, Phase.INSERT, queryRecords),
                    measuredRow(query, Phase.RETRACT, queryRecords),
                )
            }
        if (rows.isEmpty()) {
            refuse("verified records contain no insert or retract SIM work figures")
        }

        val entry = Findings.entry(
            date = date,
            subject = "BB-NEX — Nexmark Q1–Q8 coverage on kernel operator cells",
            results = FindingsTable(
                results = rows.map { it.result },
                labels = rows.map { it.label },
                dispersionObserved = rows.map { it.dispersionObserved },
            ),
            trigger = TriggerClaim.Cited(
                "G-42",
                "${growth.verdict.name} for Q7 window-keyed state under event-time eviction: " +
                    growth.note.removePrefix("${growth.verdict.name}:").trimStart(),
            ),
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
            NexmarkGenerator.deviations.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Query deviations:")
            NexmarkQuery.entries.forEach { query ->
                appendLine("${query.name}:")
                query.deviations.forEach { appendLine("- $it") }
            }
            appendLine()
            append(coverageFor(records).render())
            appendLine()
            appendLine("Q7 state growth")
            appendLine(
                "LatenessPolicy bounds live windows, not retained bytes; the 100k series " +
                    "is the growth evidence, while the 10k pair spans one 10 s window and " +
                    "is a classifier sanity check.",
            )
            appendLine("Q7 unbounded-state series (batches, liveWindows, floor):")
            appendSeries(growth.unboundedSeries)
            appendLine(
                "Q7 evicting series (lateness=5_000 ms; live-window bound=${growth.bound ?: "n/a"}; " +
                    "batches, liveWindows, floor):",
            )
            appendSeries(growth.evictingSeries)
            appendLine(
                "Each policy-series floor is the final floor stamped on every sample; " +
                    "it is not a per-batch floor observation.",
            )
            appendLine("Growth verdict: ${growth.note}")
            appendLine()
            appendLine("Candidate G-item")
            appendLine("| Gap | Missing surface | Trigger | Tier |")
            appendLine("| --- | --- | --- | --- |")
            appendLine(
                "| G-NN | aggregate output as a relation (Q4, Q5, Q6; same wall as " +
                    "BB-TPCH 2026-10-16) | first consumer needing a second-level aggregate " +
                    "or scalar-subquery threshold | 24 |",
            )
            appendLine()
            appendLine(NO_PUBLISHED_COMPARISON)
        }
    }

    private fun StringBuilder.appendSeries(series: List<WindowGrowthSample>) {
        if (series.isEmpty()) {
            appendLine("- no samples")
            return
        }
        series.forEach { sample ->
            appendLine(
                "- batches=${sample.batches}, liveWindows=${sample.liveWindows}, " +
                    "floor=${sample.floor ?: "n/a"}",
            )
        }
    }

    private fun measuredRow(
        query: NexmarkQuery,
        phase: Phase,
        records: List<NexmarkRecord.Verified>,
    ): LabelledResult? {
        val perRun = records.mapNotNull { record ->
            val figures = when (phase) {
                Phase.INSERT -> record.insert
                Phase.RETRACT -> record.retract
            }
            if (figures.isEmpty()) return@mapNotNull null
            val steps = figures.map { batch ->
                val work = batch.figure as? Figure.Work
                    ?: refuse(
                        "${query.name} ${phase.name.lowercase()} findings require SIM work " +
                            "figures, found ${batch.figure::class.simpleName}",
                    )
                if (work.unit != WorkUnit.COLLECTOR_APPLIED_DELTA_ELEMENTS) {
                    refuse(
                        "${query.name} ${phase.name.lowercase()} findings require " +
                            "collector-applied delta work, found ${work.unit}",
                    )
                }
                work.steps.toDouble()
            }
            steps.average()
        }
        if (perRun.isEmpty()) return null
        return LabelledResult(
            label = "${query.name} ${phase.name.lowercase()}",
            result = BenchResult(
                value = perRun.average(),
                unit = "applied delta elements/batch",
                dispersion = perRun.maxOrNull()!! - perRun.minOrNull()!!,
                drive = Drive.SIM,
                env = records.first().header.env,
            ),
            dispersionObserved = perRun.size >= 2,
        )
    }

    private fun refuse(reason: String): Nothing = throw FindingsRefusalException(
        "BB-NEX findings render refused: $reason",
    )

    private data class LabelledResult(
        val label: String,
        val result: BenchResult,
        val dispersionObserved: Boolean,
    )

    private const val SIM_MAINTENANCE_WORK_CAVEAT =
        "SIM maintenance-work caveat (all insert/retract rows above): " +
            "`applied delta elements/batch` sums adds+dels applied by every materialized " +
            "query-outlet collector between quiescence points; the same logical change " +
            "counts once at each observed outlet it reaches. It is query-specific " +
            "propagation volume, not elapsed time, internal operator operations, or " +
            "deliveries on unmaterialized edges."
}
