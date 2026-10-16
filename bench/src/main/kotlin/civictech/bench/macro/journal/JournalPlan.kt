package civictech.bench.macro.journal

import civictech.bench.Drive
import civictech.bench.cost.Coordinate
import civictech.bench.macro.PointBudget
import civictech.bench.macro.SweepPlan

/**
 * The one-file journal study's declared SIM and REAL workloads (`[BEN2-37]`,
 * BS-14/BS-15, 9f7x5-D6..D9/D16).
 *
 * D6 fixes the structural unit at one durable host: a point measures one host's
 * file rather than mixing local ingress with cross-host frames. D13's verdict
 * rule is emitted by [describe] before any measurement is available so later
 * findings cannot tune the rule to the observed numbers.
 */
object JournalPlan {
    val CARDINALITIES: List<Long> = listOf(1_000L, 10_000L, 100_000L, 1_000_000L)
    val BATCH_SIZES: List<Int> = listOf(1, 10, 100)
    val REAL_CARDINALITIES: List<Long> = listOf(1_000L, 10_000L, 100_000L)

    const val CALIBRATION_CARDINALITY: Long = 10_000L
    const val TIMEOUT_MINUTES: Long = 30L
    const val DETERMINISM_CHECK: Boolean = true
    const val RETAINED_FRACTION_FIRES: Double = 0.25
    const val SIM_OMITTED_CARDINALITY: Long = 1_000_000L
    const val SIM_OMISSION_REASON =
        "9f7x5-D19: not attempted in-process: safety under the test executor's heap not established"

    /**
     * One declared omission or explicitly excluded edge of the `[BEN2-37]`,
     * BS-14/BS-15 study envelope (9f7x5-D6..D9/D16).
     */
    data class Omission(
        val what: String,
        val count: Int,
        val reason: String,
    )

    /** The D6 coordinate: durable DIAMOND, SIM drive, and exactly one host. */
    fun coordinate(cardinality: Long, batchSize: Int): Coordinate =
        SweepPlan.BASELINE.copy(
            cardinality = cardinality,
            batchSize = batchSize,
            hostCount = 1,
            durability = true,
        )

    /** The 12 SIM points in cardinality-major, then batch-size order. */
    fun points(): List<Coordinate> = CARDINALITIES.flatMap { cardinality ->
        BATCH_SIZES.map { batchSize -> coordinate(cardinality, batchSize) }
    }

    /** The macro runner's calibrated allowance for this coordinate. */
    fun budgetFor(at: Coordinate): PointBudget = SweepPlan.budgetFor(at)

    /** The declared a-priori omission covering the three C=1e6 SIM points. */
    fun omissionFor(at: Coordinate): Omission? =
        omissions().firstOrNull {
            it.what == "SIM C=1e6" &&
                at.drive == Drive.SIM &&
                at.cardinality == SIM_OMITTED_CARDINALITY
        }

    /** D14/D19's four declared omissions/exclusion cases, with reasons preserved. */
    fun omissions(): List<Omission> = listOf(
        Omission(
            what = "REAL C=1e6",
            count = 1,
            reason = "measured on Apple M3 Max (Darwin arm64, JDK 21.0.11): corrected C=1e5 file/memory invocations were 3.65 s/0.62 s; linear C=1e6 extrapolation is ~36 s/~6 s per invocation and at least ~32 min of drive/setup for the four-method setting",
        ),
        Omission(
            what = "REAL batch ∈ {1, 100}",
            count = 2,
            reason = "batch is a SIM-structural dimension here",
        ),
        Omission(
            what = "H ∈ {2, 3, 5, 8}",
            count = 4,
            reason = "9f7x5-D6: G-25's unit is one file per host",
        ),
        Omission(
            what = "SIM C=1e6",
            count = BATCH_SIZES.size,
            reason = SIM_OMISSION_REASON,
        ),
    )

    /** Markdown copied into the SIM findings entry by the owning macro suite. */
    fun describe(): String = buildString {
        appendLine("### Plan")
        appendLine()
        appendLine("- Cardinalities: ${CARDINALITIES.joinToString()}")
        appendLine("- Batch sizes: ${BATCH_SIZES.joinToString()}")
        appendLine("- Executed SIM combinations: ${CARDINALITIES.size} × ${BATCH_SIZES.size} = ${points().size}")
        appendLine("- Nesting: cardinality -> batch size")
        appendLine(
            "- Coordinate: ${SweepPlan.BASELINE.shape}, seed=${SweepPlan.SHAPE_SEED}, " +
                "fanOut=${SweepPlan.BASELINE.fanOut}, H=1, replication=false, durability=true, drive=${Drive.SIM}",
        )
        appendLine("- D6: H=1 because G-25's measured unit is one journal file per host; multi-host ingress is not mixed into that file.")
        appendLine("- Topology: ${SweepPlan.TOPOLOGY}")
        appendLine("- Determinism check: $DETERMINISM_CHECK for every SIM point")
        appendLine("- Per-invocation timeout: $TIMEOUT_MINUTES minutes")
        appendLine("- REAL cardinalities at batch 10: ${REAL_CARDINALITIES.joinToString()}")
        appendLine("- Calibration cardinality: $CALIBRATION_CARDINALITY")
        appendLine(
            "- D12 REAL idle decision: remove the 5 ms polling floor; each batch waits " +
                "on scheduler completion notification, with no after-the-fact subtraction.",
        )
        appendLine(
            "- D13 FIRES iff (i) replay or recover raises OutOfMemoryError at any SIM point; " +
                "(ii) retainedBytes at the largest Ok SIM point is at least " +
                "${RETAINED_FRACTION_FIRES * 100}% of that measuring JVM's maxHeap; or " +
                "(iii) REAL realReplay(1e5) exceeds realFileJournalPoint(1e5) beyond combined error.",
        )
        appendLine(
            "- D13 RETIRES iff none of (i)-(iii) holds, all 12 SIM points are Ok, and all " +
                "three REAL cardinalities have rows; the claim is limited to ≤ 1e6 elements on one host.",
        )
        appendLine("- D13 is INCONCLUSIVE otherwise and names the arm that could not be evaluated.")
        appendLine(
            "- D13 reports by how much: retained-bytes fraction, replay/ingest ratio, and checkpoint reduction at each cardinality.",
        )
    }
}
