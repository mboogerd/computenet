package civictech.bench.nexmark

/** One query's explicit row in the eight-query coverage table. */
data class CoverageRow(
    val query: NexmarkQuery,
    val tier: Tier,
    val status: Status,
    val cause: String? = null,
) {
    init {
        require(tier == query.tier) {
            "coverage tier $tier does not match ${query.name}'s tier ${query.tier}"
        }
    }
}

/** A coverage table cannot omit, duplicate, or silently explain away a query. */
class CoverageTable(val rows: List<CoverageRow>) {
    init {
        require(rows.size == NexmarkQuery.entries.size) {
            "CoverageTable requires exactly ${NexmarkQuery.entries.size} rows, found ${rows.size}"
        }
        require(rows.map { it.query }.toSet() == NexmarkQuery.entries.toSet()) {
            "CoverageTable requires exactly one row per NexmarkQuery entry"
        }
        rows.filter { it.status != Status.verified }.forEach { row ->
            require(!row.cause.isNullOrBlank()) {
                "CoverageTable requires a cause for ${row.query} with status ${row.status}"
            }
        }
    }

    fun render(): String = buildString {
        appendLine("| Query | Tier | Status | Cause |")
        appendLine("| --- | --- | --- | --- |")
        rows.forEach { row ->
            append("| ")
            append(row.query.name)
            append(" | ")
            append(row.tier.name)
            append(" | ")
            append(row.status.name)
            append(" | ")
            append(row.cause.orEmpty())
            appendLine(" |")
        }
    }
}

/** Collapse the record set to one explicit coverage row per query. */
fun coverageFor(records: List<NexmarkRecord>): CoverageTable = CoverageTable(
    NexmarkQuery.entries.map { query ->
        val matching = records.filter { it.query == query }
        when {
            matching.isEmpty() && query.tier == Tier.NOT_EXPRESSIBLE -> CoverageRow(
                query = query,
                tier = query.tier,
                status = Status.not_expressible,
                cause = requireNotNull(query.blockedCause),
            )

            matching.isEmpty() -> CoverageRow(
                query = query,
                tier = query.tier,
                status = Status.unverified,
                cause = "no run recorded",
            )

            matching.all { it.status == Status.verified } -> CoverageRow(
                query = query,
                tier = query.tier,
                status = Status.verified,
            )

            else -> {
                val worst = matching.maxWithOrNull(compareBy<NexmarkRecord> { statusRank(it.status) })
                    ?: error("a non-empty matching record set has no worst record")
                CoverageRow(
                    query = query,
                    tier = query.tier,
                    status = worst.status,
                    cause = causeOf(worst),
                )
            }
        }
    },
)

private fun causeOf(record: NexmarkRecord): String = when (record) {
    is NexmarkRecord.Failed ->
        "batch ${record.batchIndex} failed; differing rows=${record.differingRows}"
    is NexmarkRecord.Unverified -> record.reason
    is NexmarkRecord.Unreportable -> record.cause.coverageCause()
    is NexmarkRecord.NotExpressible -> record.cause
    is NexmarkRecord.StateBound ->
        "outlet ${record.outlet} exceeded ceiling ${record.ceiling} at batch ${record.batchIndex}"
    is NexmarkRecord.Verified -> error("verified records do not have a coverage cause")
}

private fun UnreportableCause.coverageCause(): String = when (this) {
    is NexmarkRecord.UnreportableCause.Variance ->
        "variance $variance exceeds threshold $threshold"
    is NexmarkRecord.UnreportableCause.GenerationOnMeasuredPath ->
        "generation on measured path (share=$share)"
}

private fun statusRank(status: Status): Int = when (status) {
    Status.verified -> 0
    Status.not_expressible -> 1
    Status.unverified -> 2
    Status.state_bound -> 3
    Status.unreportable -> 4
    Status.failed -> 5
}
