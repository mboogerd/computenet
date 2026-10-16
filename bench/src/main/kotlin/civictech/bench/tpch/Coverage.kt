package civictech.bench.tpch

/** One query's explicit row in the 22-query coverage table. */
data class CoverageRow(
    val query: TpchQuery,
    val tier: TpchTier,
    val route: String,
    val status: Status,
    val cause: String? = null,
) {
    init {
        require(tier == query.tier) {
            "coverage tier $tier does not match ${query.name}'s tier ${query.tier}"
        }
        require(route.isNotBlank()) { "coverage route must not be blank" }
    }
}

/** A coverage table cannot omit, duplicate, or silently explain away a query. */
class CoverageTable(val rows: List<CoverageRow>) {
    init {
        require(rows.size == TpchQuery.entries.size) {
            "CoverageTable requires exactly ${TpchQuery.entries.size} rows, found ${rows.size}"
        }
        require(rows.map { it.query }.toSet() == TpchQuery.entries.toSet()) {
            "CoverageTable requires exactly one row per TpchQuery entry"
        }
        rows.filter { it.status != Status.verified }.forEach { row ->
            require(!row.cause.isNullOrBlank()) {
                "CoverageTable requires a cause for ${row.query} with status ${row.status}"
            }
        }
    }

    /** Render the rows without collapsing failure, cost, or absence into one state. */
    fun render(): String = buildString {
        appendLine("| Query | Tier | Route | Status | Cause |")
        appendLine("| --- | --- | --- | --- | --- |")
        rows.forEach { row ->
            append("| ")
            append(row.query.name)
            append(" | ")
            append(row.tier.name)
            append(" | ")
            append(row.route)
            append(" | ")
            append(row.status.name)
            append(" | ")
            append(row.cause.orEmpty())
            appendLine(" |")
        }
    }
}

/**
 * Collapse repeated drives and seeds to one row per query.
 *
 * A query is verified only when every observed record is verified. A missing
 * hand-route run is explicitly unverified; a missing BLOCKED_2 run uses the
 * query's settled cause so the table remains complete even before execution.
 */
fun coverageFor(records: List<TpchRecord>): CoverageTable = CoverageTable(
    TpchQuery.entries.map { query ->
        val matching = records.filter { it.query == query }
        when {
            matching.isEmpty() && query.tier == TpchTier.BLOCKED_2 -> CoverageRow(
                query = query,
                tier = query.tier,
                route = query.route,
                status = Status.blocked,
                cause = requireNotNull(query.blockedCause),
            )

            matching.isEmpty() -> CoverageRow(
                query = query,
                tier = query.tier,
                route = query.route,
                status = Status.unverified,
                cause = "no run recorded",
            )

            matching.all { it.status == Status.verified } -> CoverageRow(
                query = query,
                tier = query.tier,
                route = matching.map { (it as TpchRecord.Verified).header.route }.first(),
                status = Status.verified,
            )

            else -> {
                val worst = matching.maxWithOrNull(
                    compareBy<TpchRecord> { statusRank(it.status) },
                ) ?: error("a non-empty matching record set has no worst record")
                CoverageRow(
                    query = query,
                    tier = query.tier,
                    route = routeOf(worst),
                    status = worst.status,
                    cause = causeOf(worst),
                )
            }
        }
    },
)

private fun routeOf(record: TpchRecord): String = when (record) {
    is TpchRecord.Blocked -> record.query.route
    is TpchRecord.NotExpressible -> record.query.route
    is TpchRecord.Verified -> record.header.route
    is TpchRecord.Failed -> record.header.route
    is TpchRecord.Unverified -> record.header.route
    is TpchRecord.Unreportable -> record.header.route
}

private fun causeOf(record: TpchRecord): String = when (record) {
    is TpchRecord.Blocked -> record.cause
    is TpchRecord.NotExpressible -> record.cause
    is TpchRecord.Unverified -> record.reason
    is TpchRecord.Unreportable ->
        "variance ${record.variance} exceeds the reportability threshold ${record.threshold}"
    is TpchRecord.Failed ->
        "batch ${record.batchIndex} failed; differing rows=${record.differingRows}"
    is TpchRecord.Verified -> error("verified records do not have a coverage cause")
}

private fun statusRank(status: Status): Int = when (status) {
    Status.verified -> 0
    Status.blocked -> 1
    Status.not_expressible -> 2
    Status.unverified -> 3
    Status.unreportable -> 4
    Status.failed -> 5
}

