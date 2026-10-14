package civictech.bench.graphalytics

/**
 * Build the explicit six-algorithm coverage table for the KE5-independent slice.
 *
 * The input normally contains one or more LCC records (one per validation dataset).
 * Missing iterative records are deliberate: the pre-E5 cycle head cannot express
 * their iteration identity, so their causes name the missing E5 constructs instead
 * of inventing a benchmark-side loop.
 */
fun coverageFor(records: List<GraRecord>): CoverageTable {
    val byAlgorithm = records.groupBy(::algorithmOf)
    return CoverageTable(
        Algorithm.entries.map { algorithm ->
            val algorithmRecords = byAlgorithm[algorithm].orEmpty()
            when (algorithm) {
                Algorithm.LCC -> lccRow(algorithmRecords)
                else -> algorithmRecords.lastOrNull()?.toCoverageRow()
                    ?: CoverageRow(
                        algorithm = algorithm,
                        status = Status.blocked,
                        cause = blockedCause(algorithm),
                    )
            }
        },
    )
}

private fun algorithmOf(record: GraRecord): Algorithm = when (record) {
    is GraRecord.Validated -> record.header.algorithm
    is GraRecord.Failed -> record.header.algorithm
    is GraRecord.MeasurementOnly -> record.header.algorithm
    is GraRecord.Unreportable -> record.header.algorithm
    is GraRecord.Blocked -> record.algorithm
    is GraRecord.NotExpressible -> record.algorithm
}

private fun lccRow(records: List<GraRecord>): CoverageRow {
    val failed = records.filterIsInstance<GraRecord.Failed>()
    if (failed.isNotEmpty()) {
        return CoverageRow(
            algorithm = Algorithm.LCC,
            status = Status.failed,
            cause = failed.joinToString(separator = " ") { record ->
                failedCause(record)
            },
        )
    }

    if (records.isNotEmpty() && records.all { it is GraRecord.Validated }) {
        return CoverageRow(Algorithm.LCC, Status.validated, cause = null)
    }

    return records.lastOrNull()?.toCoverageRow()
        ?: CoverageRow(
            algorithm = Algorithm.LCC,
            status = Status.blocked,
            cause = "LCC has no run record; [BB-GRA-13] validation cannot be claimed.",
        )
}

private fun GraRecord.toCoverageRow(): CoverageRow = when (this) {
    is GraRecord.Validated -> CoverageRow(header.algorithm, Status.validated, null)
    is GraRecord.Failed -> CoverageRow(header.algorithm, Status.failed, failedCause(this))
    is GraRecord.MeasurementOnly -> CoverageRow(
        algorithm = header.algorithm,
        status = Status.measurement_only,
        cause = "measurement-only record has no validation result",
    )
    is GraRecord.Unreportable -> CoverageRow(
        algorithm = header.algorithm,
        status = Status.unreportable,
        cause = "run variance $variance exceeds the reportability threshold $threshold",
    )
    is GraRecord.Blocked -> CoverageRow(algorithm, Status.blocked, cause)
    is GraRecord.NotExpressible -> CoverageRow(algorithm, Status.not_expressible, cause)
}

private fun failedCause(record: GraRecord.Failed): String {
    val validation = record.validation
    return "LCC validation failed for ${record.header.dataset.name}: " +
        "differing vertices=${validation.differing.map { it.vertex }}; " +
        "missing vertices=${validation.missing.sorted()}; " +
        "extra vertices=${validation.extra.sorted()} [BB-GRA-13]."
}

private fun blockedCause(algorithm: Algorithm): String {
    val iterativeDetection = when (algorithm) {
        Algorithm.BFS, Algorithm.SSSP, Algorithm.WCC ->
            " It also needs E5.4 iteration-cut detection."
        Algorithm.CDLP, Algorithm.PR -> ""
        Algorithm.LCC -> error("LCC is not an iterative blocked algorithm")
    }
    return "blocked — [BB-GRA-16]: needs E5.2 LoopContext (iteration identity on " +
        "MessageContext) and E5.3 cycle-region ingress/egress; the pre-E5 " +
        "CycleHead/FeedbackInlet offers bounded-lap termination but no iteration " +
        "identity, so a fixed-iteration run cannot be expressed in bench/. " +
        "[BB-GRA-30] gate not landed (computenet-dud DEFERRED).$iterativeDetection"
}
