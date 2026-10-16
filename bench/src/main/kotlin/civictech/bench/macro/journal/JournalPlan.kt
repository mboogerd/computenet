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
    const val FORKED_CARDINALITY: Long = 1_000_000L
    const val FORKED_XMX: String = "-Xmx8g"
    const val IN_PROCESS_DEFERRAL_REASON =
        "computenet-9f7x5.5: executed separately in a bounded fork at -Xmx8g; " +
            "the 2 GiB Gradle executor does not repeat that measurement"

    /**
     * One declared omission or explicitly excluded edge of the `[BEN2-37]`,
     * BS-14/BS-15 study envelope (9f7x5-D6..D9/D16).
     */
    data class Omission(
        val what: String,
        val count: Int,
        val reason: String,
    )

    /** The observed classification from the bounded C=1e6 forked run. */
    data class ForkedObservation(
        val batchSize: Int,
        val kind: String,
        val stage: String,
        val replayCompleted: Boolean,
        val recoverCompleted: Boolean,
        val retainedBytes: Long,
        val maxHeapBytes: Long,
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

    /** Every declared SIM point, across the in-process and isolated-fork lanes. */
    fun executedPoints(): List<Coordinate> = points()

    /** The nine points safe to repeat inside the Gradle test executor. */
    fun inProcessPoints(): List<Coordinate> = points().filterNot { inProcessDeferralFor(it) != null }

    /** The three C=1e6 outcomes observed by computenet-9f7x5.5. */
    fun forkedObservations(): List<ForkedObservation> = BATCH_SIZES.map { batchSize ->
        ForkedObservation(
            batchSize = batchSize,
            kind = "Ok",
            stage = "complete",
            replayCompleted = true,
            recoverCompleted = true,
            retainedBytes = 216_486_328L,
            maxHeapBytes = 8_589_934_592L,
        )
    }

    /** The macro runner's calibrated allowance for this coordinate. */
    fun budgetFor(at: Coordinate): PointBudget = SweepPlan.budgetFor(at)

    /**
     * Keeps the isolated C=1e6 measurement out of the 2 GiB Gradle executor.
     * This is an execution-lane deferral, not an omission from the study.
     */
    fun inProcessDeferralFor(at: Coordinate): Omission? =
        if (at.drive == Drive.SIM && at.cardinality == FORKED_CARDINALITY) {
            Omission("SIM C=1e6 in-process repeat", 1, IN_PROCESS_DEFERRAL_REASON)
        } else {
            null
        }

    /** D14's remaining REAL and host-count omissions, with reasons preserved. */
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
    )

    /** Markdown copied into the SIM findings entry by the owning macro suite. */
    fun describe(): String = buildString {
        val declaredPoints = points()
        val executedPoints = executedPoints()
        appendLine("### Plan")
        appendLine()
        appendLine("- Cardinalities: ${CARDINALITIES.joinToString()}")
        appendLine("- Batch sizes: ${BATCH_SIZES.joinToString()}")
        appendLine("- Declared SIM combinations: ${CARDINALITIES.size} × ${BATCH_SIZES.size} = ${declaredPoints.size}")
        appendLine(
            "- Executed SIM combinations: ${CARDINALITIES.size} × ${BATCH_SIZES.size} = " +
                "${executedPoints.size} (9 in the 2 GiB test executor; 3 C=1e6 points in " +
                "bounded $FORKED_XMX forks)",
        )
        appendLine("- Nesting: cardinality -> batch size")
        appendLine(
            "- Coordinate: ${SweepPlan.BASELINE.shape}, seed=${SweepPlan.SHAPE_SEED}, " +
                "fanOut=${SweepPlan.BASELINE.fanOut}, H=1, replication=false, durability=true, drive=${Drive.SIM}",
        )
        appendLine("- D6: H=1 because G-25's measured unit is one journal file per host; multi-host ingress is not mixed into that file.")
        appendLine("- Topology: ${SweepPlan.TOPOLOGY}")
        appendLine("- Determinism check: $DETERMINISM_CHECK for every SIM point")
        appendLine("- Per-invocation timeout: $TIMEOUT_MINUTES minutes")
        appendLine(
            "- Forked C=1e6 outcomes: " + forkedObservations().joinToString { observation ->
                "b=${observation.batchSize} ${observation.kind} stage=${observation.stage}"
            },
        )
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
