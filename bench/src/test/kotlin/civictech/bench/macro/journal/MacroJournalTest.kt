package civictech.bench.macro.journal

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsTable
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostModel
import civictech.bench.cost.CostSchema
import civictech.bench.cost.CostSubject
import civictech.bench.cost.CostWriter
import civictech.bench.cost.Dimensions
import civictech.bench.cost.FitResult
import civictech.bench.cost.GrowthForm
import civictech.bench.cost.PointOutcome
import civictech.bench.cost.SubjectMerge
import civictech.bench.micro.FootprintReport
import civictech.testkit.JvmPeer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.InvocationInterceptor
import org.junit.jupiter.api.extension.ReflectiveInvocationContext
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.extension.TestWatcher
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import java.nio.file.Files
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeoutException
import kotlin.math.round
import kotlin.system.exitProcess

/** Runs, merges, and renders the G-25 durable journal SIM grid (9f7x5-D6..D10/D13..D15). */
@Tag("bench")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroJournalTest {

    @JvmField
    @TempDir
    var temporaryRoot: File? = null

    private val outcomes = LinkedHashMap<Coordinate, JournalMeasurement>()
    private val invocationCoordinates = ConcurrentHashMap<String, Coordinate>()

    @JvmField
    @RegisterExtension
    val timeoutObserver: TestWatcher = TimeoutObserver()

    /** Kept as the single plan seam so reviewers can run a bounded slice. */
    fun points(): List<Coordinate> = JournalPlan.points()

    @ParameterizedTest(name = "{0}")
    @MethodSource("points")
    @Timeout(value = JournalPlan.TIMEOUT_MINUTES, unit = MINUTES)
    fun `runs every declared durable journal point with determinism enabled`(at: Coordinate) {
        val pointNumber = points().indexOf(at) + 1
        val journalDir = File(requireNotNull(temporaryRoot), "point-$pointNumber")
        val measured = try {
            JournalProbe.run(
                at = at,
                journalDir = journalDir,
                budget = JournalPlan.budgetFor(at),
                determinismCheck = JournalPlan.DETERMINISM_CHECK,
            )
        } finally {
            // The measurement has already read, recovered, and checkpointed the file. Keeping
            // only the classified figures prevents 12 points from filling the temp root.
            journalDir.deleteRecursively()
        }

        assertNull(outcomes.put(at, measured), "plan coordinate produced more than one outcome: $at")
        assertEquals(at, measured.outcome.at, "probe outcome must retain the coordinate it measured")
    }

    /** JUnit finalizes SAME_THREAD timeout outcomes after the body returns. */
    private inner class TimeoutObserver : InvocationInterceptor, TestWatcher {
        override fun interceptTestTemplateMethod(
            invocation: InvocationInterceptor.Invocation<Void>,
            invocationContext: ReflectiveInvocationContext<Method>,
            extensionContext: ExtensionContext,
        ) {
            val at = invocationContext.arguments.singleOrNull() as? Coordinate
                ?: error("journal invocation must carry exactly one Coordinate")
            check(invocationCoordinates.put(extensionContext.uniqueId, at) == null) {
                "duplicate JUnit invocation id: ${extensionContext.uniqueId}"
            }
            invocation.proceed()
        }

        override fun testSuccessful(context: ExtensionContext) {
            invocationCoordinates.remove(context.uniqueId)
        }

        override fun testAborted(context: ExtensionContext, cause: Throwable?) {
            invocationCoordinates.remove(context.uniqueId)
        }

        override fun testFailed(context: ExtensionContext, cause: Throwable?) {
            val at = invocationCoordinates.remove(context.uniqueId) ?: return
            if (cause?.hasTimeoutCause() == true) outcomes[at] = timeoutMeasurement(at)
        }
    }

    private fun Throwable.hasTimeoutCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is TimeoutException }

    private fun timeoutMeasurement(at: Coordinate): JournalMeasurement = JournalMeasurement(
        outcome = PointOutcome.NonConvergent(
            at = at,
            subject = CostSubject.JOURNAL,
            budget = "junitTimeout=${JournalPlan.TIMEOUT_MINUTES}m",
        ),
        stage = "timeout",
        replayCompleted = false,
        recoverCompleted = false,
        fileBytes = null,
        secondAttemptFileBytes = null,
        records = null,
        appended = null,
        bytesPerRecord = null,
        retainedBytes = null,
        maxHeapBytes = Runtime.getRuntime().maxMemory(),
        checkpointBytes = null,
        recordsAfterCheckpoint = null,
    )

    @AfterAll
    fun `merges the structural export and prints the SIM findings entry`() {
        val plan = points()
        assertTrue(
            invocationCoordinates.isEmpty(),
            "every parameterized invocation must receive its finalized JUnit result",
        )
        plan.filterNot(outcomes::containsKey).forEach { at -> outcomes[at] = timeoutMeasurement(at) }
        assertEquals(plan.size, outcomes.size, "every plan coordinate must have exactly one outcome")
        assertEquals(plan.toSet(), outcomes.keys.toSet(), "outcomes must cover the declared plan")

        val harnessSha = requiredHarnessSha()
        val orderedMeasurements = plan.map(outcomes::getValue)
        val writer = CostWriter(harnessSha)
        orderedMeasurements.map { it.outcome }.forEach(writer::admit)
        val admittedCoordinates = orderedMeasurements
            .filter { it.outcome is PointOutcome.Ok }
            .mapTo(linkedSetOf()) { it.outcome.at }
        val fits = fitGrowthLaws(writer, plan, admittedCoordinates)

        val replacementPath = File(requireNotNull(temporaryRoot), "journal-model.json").toPath()
        writer.write(replacementPath)
        val rawReplacement = CostSchema.parse(Files.readString(replacementPath))
        val replacement = rawReplacement.copy(
            exclusions = rawReplacement.exclusions.map { exclusion ->
                val measurement = orderedMeasurements.first { it.outcome.at == exclusion.at }
                if (measurement.declaredOmission) {
                    exclusion.copy(
                        kind = "Omitted",
                        reason = requireNotNull(JournalPlan.omissionFor(exclusion.at)).reason,
                    )
                } else {
                    exclusion
                }
            },
        )

        val export = File(requiredRepoRoot(), CostSchema.STABLE_PATH).toPath()
        val before = CostSchema.parse(Files.readString(export))
        val merged = SubjectMerge.replaceSubject(export, CostSubject.JOURNAL, replacement)
        val model = CostSchema.parse(Files.readString(export))
        assertEquals(merged, model, "the stable export must parse as the validated merge result")
        assertExport(model, before, harnessSha, orderedMeasurements, fits)

        val date = System.getProperty(DATE_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: LocalDate.now().toString()
        val rendered = renderFindings(
            date = date,
            harnessSha = harnessSha,
            before = before,
            model = model,
            orderedMeasurements = orderedMeasurements,
            fits = fits,
        )

        println("JOURNAL_OUTCOME_TALLY ${tally(orderedMeasurements)}")
        print(rendered)
    }

    private fun fitGrowthLaws(
        writer: CostWriter,
        plan: List<Coordinate>,
        admitted: Set<Coordinate>,
    ): List<FitRecord> = listOf(
        fit(
            writer = writer,
            admitted = admitted,
            dimension = Dimensions.CARDINALITY,
            metric = "journalRecords",
            slice = "batch=10",
            declaredPoints = plan.filter { it.batchSize == 10 },
        ),
        fit(
            writer = writer,
            admitted = admitted,
            dimension = Dimensions.BATCH_SIZE,
            metric = "journalRecords",
            slice = "C=1e4",
            declaredPoints = plan.filter { it.cardinality == 10_000L },
        ),
    )

    private fun fit(
        writer: CostWriter,
        admitted: Set<Coordinate>,
        dimension: String,
        metric: String,
        slice: String,
        declaredPoints: List<Coordinate>,
    ): FitRecord {
        val fittedOver = declaredPoints.filter(admitted::contains)
        return FitRecord(
            dimension = dimension,
            metric = metric,
            slice = slice,
            fittedOver = fittedOver,
            result = writer.fit(
                dimension = dimension,
                subject = CostSubject.JOURNAL,
                metric = metric,
                form = GrowthForm.LINEAR,
                slice = slice,
                points = fittedOver,
            ),
        )
    }

    private fun assertExport(
        model: CostModel,
        before: CostModel,
        harnessSha: String,
        orderedMeasurements: List<JournalMeasurement>,
        fits: List<FitRecord>,
    ) {
        assertEquals(CostSchema.VERSION, model.schemaVersion)
        assertEquals(before.schemaVersion, model.schemaVersion)
        assertEquals(before.harnessSha, model.harnessSha, "subject merge keeps the export harness SHA")
        assertEquals(
            before.structural.filter { it.subject != CostSubject.JOURNAL },
            model.structural.filter { it.subject != CostSubject.JOURNAL },
            "all non-JOURNAL structural entries and transport marks must remain unchanged",
        )
        assertEquals(before.calibration, model.calibration, "calibration must remain unchanged")
        assertEquals(
            before.laws.filter { it.subject != CostSubject.JOURNAL },
            model.laws.filter { it.subject != CostSubject.JOURNAL },
            "non-JOURNAL fitted laws must remain unchanged",
        )
        assertEquals(
            before.unfitted.filter { it.subject != CostSubject.JOURNAL },
            model.unfitted.filter { it.subject != CostSubject.JOURNAL },
            "non-JOURNAL unfitted laws must remain unchanged",
        )
        assertEquals(
            before.exclusions.filter { it.subject != CostSubject.JOURNAL },
            model.exclusions.filter { it.subject != CostSubject.JOURNAL },
            "non-JOURNAL exclusions must remain unchanged",
        )

        val admitted = orderedMeasurements.filter { it.outcome is PointOutcome.Ok }
        val excluded = orderedMeasurements.filterNot { it.outcome is PointOutcome.Ok }
        val structural = model.structural.filter { it.subject == CostSubject.JOURNAL }
        assertEquals(admitted.map { it.outcome.at }, structural.map { it.at })
        admitted.zip(structural).forEach { (measured, entry) ->
            val outcome = measured.outcome as PointOutcome.Ok
            assertEquals(outcome.counts, entry.counts)
            assertEquals(harnessSha, entry.harnessSha)
        }

        val actualExclusions = model.exclusions.filter { it.subject == CostSubject.JOURNAL }
        assertEquals(excluded.size, actualExclusions.size, "every non-Ok outcome must be exported")
        excluded.zip(actualExclusions).forEach { (measured, actual) ->
            assertEquals(measured.outcome.at, actual.at)
            assertEquals(exclusionKind(measured), actual.kind)
            assertTrue(actual.reason.isNotBlank(), "excluded point must retain a reason: ${actual.at}")
        }
        assertEquals(admitted.size, structural.size)
        assertEquals(excluded.size, actualExclusions.size)

        val expectedLaws = fits.mapNotNull { (it.result as? FitResult.Law)?.law }
        assertEquals(expectedLaws, model.laws.filter { it.subject == CostSubject.JOURNAL })
        val expectedUnfitted = fits.mapNotNull { (it.result as? FitResult.Unfitted)?.record }
        assertEquals(expectedUnfitted, model.unfitted.filter { it.subject == CostSubject.JOURNAL })
        assertEquals(orderedMeasurements.size, structural.size + actualExclusions.size)
    }

    private fun renderFindings(
        date: String,
        harnessSha: String,
        before: CostModel,
        model: CostModel,
        orderedMeasurements: List<JournalMeasurement>,
        fits: List<FitRecord>,
    ): String {
        val env = RunEnvironment.forRun(
            measuringJvm = FootprintReport.inProcessMeasuringJvm(),
            jmhMode = SIM_MODE,
            forkCount = 1,
            warmupIterations = 1,
            measurementIterations = 2,
            harnessCommitSha = harnessSha,
        )
        val byCoordinate = orderedMeasurements.associateBy { it.outcome.at }
        val rows = mutableListOf<BenchResult>()
        val labels = mutableListOf<String>()
        model.structural.filter { it.subject == CostSubject.JOURNAL }.forEach { entry ->
            val measurement = byCoordinate.getValue(entry.at)
            metrics(measurement).forEach { (metric, value) ->
                rows += BenchResult(
                    value = value,
                    unit = unit(metric),
                    dispersion = 0.0,
                    drive = Drive.SIM,
                    env = env,
                )
                labels += "n=${entry.at.cardinality} b=${entry.at.batchSize} $metric"
            }
        }
        val entry = Findings.entry(
            date = date,
            subject = SUBJECT,
            results = FindingsTable(
                results = rows,
                labels = labels,
                dispersionObserved = List(rows.size) { false },
            ),
            trigger = TriggerClaim.None,
        )

        val okMeasurements = orderedMeasurements.filter { it.outcome is PointOutcome.Ok }
        val largestOk = okMeasurements.maxWithOrNull(
            compareBy<JournalMeasurement> { it.outcome.at.cardinality }
                .thenBy { it.outcome.at.batchSize },
        )
        val armOne = orderedMeasurements.filter { measurement ->
            val outcome = measurement.outcome
            outcome is PointOutcome.Unschedulable &&
                measurement.stage in setOf("replay", "recover")
        }
        val replayRecoverCompleted = orderedMeasurements.filter {
            it.replayCompleted && it.recoverCompleted
        }
        val replayRecoverNotEvaluated = orderedMeasurements.filterNot {
            it.replayCompleted && it.recoverCompleted
        }

        return buildString {
            appendLine(entry)
            appendLine()
            append(JournalPlan.describe())
            appendLine()
            appendLine("### Omissions")
            appendLine()
            JournalPlan.omissions().forEach { omission ->
                appendLine("- ${omission.count} ${omission.what} — reason: ${omission.reason}")
            }
            model.exclusions.filter { it.subject == CostSubject.JOURNAL }.forEach { exclusion ->
                appendLine(
                    "- ${coordinateLabel(exclusion.at)}: ${exclusion.kind} — " +
                        exclusion.reason,
                )
            }
            appendLine()
            appendLine("### Growth laws")
            appendLine()
            fits.forEach { fit -> appendLine(fit.render()) }
            appendLine()
            appendLine("### Outcome tally")
            appendLine()
            appendLine(tally(orderedMeasurements))
            orderedMeasurements.filter { it.outcome !is PointOutcome.Ok }.forEach { measurement ->
                appendLine(
                    "- ${coordinateLabel(measurement.outcome.at)} " +
                        "kind=${displayKind(measurement)} " +
                        "stage=${measurement.stage} " +
                        if (measurement.declaredOmission) {
                            "heap=n/a reason=${reason(measurement)}"
                        } else {
                            "maxHeap=${measurement.maxHeapBytes} reason=${reason(measurement)}"
                        },
                )
            }
            appendLine()
            appendLine("### Determinism")
            appendLine()
            orderedMeasurements.forEach { measurement ->
                val first = measurement.fileBytes?.toString() ?: "n/a"
                val second = measurement.secondAttemptFileBytes?.toString() ?: "n/a"
                appendLine(
                    "- ${coordinateLabel(measurement.outcome.at)} " +
                        "kind=${displayKind(measurement)} attempt1=$first " +
                        "attempt2=$second equal=${first == second && first != "n/a"}",
                )
            }
            appendLine()
            appendLine("### Retained-bytes fraction")
            appendLine()
            okMeasurements.forEach { measurement ->
                val fraction = reportedRetainedBytes(measurement).toBytes() / measurement.maxHeapBytes
                appendLine(
                    "- ${coordinateLabel(measurement.outcome.at)} retainedBytes≈" +
                        "${reportedRetainedBytes(measurement)}MiB maxHeap=${measurement.maxHeapBytes} " +
                        "fraction=${fraction.formatFraction()}",
                )
            }
            if (largestOk == null) {
                appendLine("- arm (ii): not evaluated — no Ok SIM point")
            } else {
                val fraction = reportedRetainedBytes(largestOk).toBytes() / largestOk.maxHeapBytes
                appendLine(
                    "- arm (ii): largest Ok point in the declared grid is " +
                        "${coordinateLabel(largestOk.outcome.at)}; fraction=" +
                        "${fraction.formatFraction()} against ${JournalPlan.RETAINED_FRACTION_FIRES} " +
                        if (fraction >= JournalPlan.RETAINED_FRACTION_FIRES) "— holds" else "— does not hold",
                )
            }
            appendLine()
            appendLine("### D13 arm (i)")
            appendLine()
            appendLine(
                "- replay and recover completed at: " +
                    replayRecoverCompleted.joinToString { coordinateLabel(it.outcome.at) },
            )
            appendLine(
                "- not evaluated (replay/recover did not both complete) at: " +
                    replayRecoverNotEvaluated.joinToString { coordinateLabel(it.outcome.at) },
            )
            if (armOne.isEmpty()) {
                appendLine("- No replay/recover OutOfMemoryError was observed at evaluated points.")
            } else {
                appendLine("Replay/recover OutOfMemoryError: yes")
                armOne.forEach { measurement ->
                    appendLine(
                        "- ${coordinateLabel(measurement.outcome.at)}: " +
                            reason(measurement),
                    )
                }
            }
            appendLine()
            appendLine("### Export")
            appendLine()
            appendLine(
                "- Before: structural=${before.structural.count { it.subject != CostSubject.JOURNAL }} " +
                    "exclusions=${before.exclusions.count { it.subject != CostSubject.JOURNAL }} " +
                    "laws=${before.laws.count { it.subject != CostSubject.JOURNAL }} " +
                    "unfitted=${before.unfitted.count { it.subject != CostSubject.JOURNAL }}",
            )
            appendLine(
                "- After: structural=${model.structural.size} exclusions=${model.exclusions.size} " +
                    "laws=${model.laws.size} unfitted=${model.unfitted.size}",
            )
            appendLine(
                "- JOURNAL: structural=${model.structural.count { it.subject == CostSubject.JOURNAL }} " +
                    "exclusions=${model.exclusions.count { it.subject == CostSubject.JOURNAL }} " +
                    "fittedLaws=${model.laws.count { it.subject == CostSubject.JOURNAL }} " +
                    "unfitted=${model.unfitted.count { it.subject == CostSubject.JOURNAL }}",
            )
            appendLine("- Run harness SHA: `$harnessSha`")
            appendLine()
            appendLine("### Verdict")
            appendLine()
            appendLine(
                "The G-25 verdict is stated by the REAL companion entry below; this entry is " +
                    "the structural half, and arms (i) and (ii) of the rule read above.",
            )
        }
    }

    private fun metrics(measurement: JournalMeasurement): List<Pair<String, Double>> {
        val outcome = measurement.outcome as PointOutcome.Ok
        return listOf(
            "fileBytes" to measurement.fileBytes!!.toDouble(),
            "bytesPerRecord" to measurement.bytesPerRecord!!,
            "records" to measurement.records!!.toDouble(),
            "journalRecords" to outcome.counts.journalRecords.toDouble(),
            "retainedBytes (rounded to 0.1MiB)" to reportedRetainedBytes(measurement),
            "checkpointBytes" to measurement.checkpointBytes!!.toDouble(),
            "recordsAfterCheckpoint" to measurement.recordsAfterCheckpoint!!.toDouble(),
        )
    }

    private fun unit(metric: String): String = when (metric) {
        "fileBytes", "checkpointBytes" -> "bytes/point"
        "retainedBytes (rounded to 0.1MiB)" -> "MiB/point (rounded to 0.1MiB)"
        "bytesPerRecord" -> "bytes/record"
        "records", "recordsAfterCheckpoint" -> "records/point"
        "journalRecords" -> "journalRecords/point"
        else -> error("unknown journal findings metric '$metric'")
    }

    private fun tally(measurements: List<JournalMeasurement>): String =
        "admitted=${measurements.count { it.outcome is PointOutcome.Ok }} " +
            "NonConvergent=${measurements.count { it.outcome is PointOutcome.NonConvergent }} " +
            "NonDeterministic=${measurements.count { it.outcome is PointOutcome.NonDeterministic }} " +
            "Unschedulable=${measurements.count { !it.declaredOmission && it.outcome is PointOutcome.Unschedulable }} " +
            "Failed=${measurements.count { it.outcome is PointOutcome.Failed }} " +
            "Unreportable=${measurements.count { it.outcome is PointOutcome.Unreportable }} " +
            "Omitted=${measurements.count { it.declaredOmission }}"

    private fun outcomeKind(outcome: PointOutcome): String = when (outcome) {
        is PointOutcome.Ok -> "Ok"
        is PointOutcome.NonConvergent -> "NonConvergent"
        is PointOutcome.NonDeterministic -> "NonDeterministic"
        is PointOutcome.Unschedulable -> "Unschedulable"
        is PointOutcome.Failed -> "Failed"
        is PointOutcome.Unreportable -> "Unreportable"
    }

    private fun exclusionKind(measurement: JournalMeasurement): String =
        if (measurement.declaredOmission) "Omitted" else outcomeKind(measurement.outcome)

    private fun displayKind(measurement: JournalMeasurement): String =
        if (measurement.declaredOmission) "Omitted" else outcomeKind(measurement.outcome)

    private fun reason(measurement: JournalMeasurement): String = when (val outcome = measurement.outcome) {
        is PointOutcome.Ok -> "admitted"
        is PointOutcome.NonConvergent -> outcome.budget
        is PointOutcome.NonDeterministic -> "structural counts differ"
        is PointOutcome.Unschedulable -> if (measurement.declaredOmission) {
            outcome.reason
        } else {
            "${outcome.reason}; observedLimit=${outcome.observedLimit}"
        }
        is PointOutcome.Failed -> "${outcome.cause}; deadLetters=${outcome.deadLetters.size}"
        is PointOutcome.Unreportable -> "relativeDispersion=${outcome.result.relativeDispersion}"
    }

    private fun reportedRetainedBytes(measurement: JournalMeasurement): Double {
        val raw = requireNotNull(measurement.retainedBytes)
        return round(raw.toDouble() / BYTES_PER_MIB / RETAINED_MIB_GRANULARITY) *
            RETAINED_MIB_GRANULARITY
    }

    private fun Double.toBytes(): Double = this * BYTES_PER_MIB

    private fun coordinateLabel(at: Coordinate): String = "n=${at.cardinality} b=${at.batchSize}"

    private fun requiredHarnessSha(): String {
        val sha = System.getProperty(HARNESS_SHA_PROPERTY)
        require(!sha.isNullOrBlank()) {
            "set -D$HARNESS_SHA_PROPERTY=\$(git rev-parse --short HEAD): no run artifact records which harness commit produced the measurement"
        }
        return sha
    }

    private fun requiredRepoRoot(): String {
        val root = System.getProperty(REPO_ROOT_PROPERTY)
        require(!root.isNullOrBlank()) {
            "system property $REPO_ROOT_PROPERTY must name the export checkout"
        }
        return root
    }

    private fun Double.formatFraction(): String = "%.6f".format(Locale.ROOT, this)

    private data class FitRecord(
        val dimension: String,
        val metric: String,
        val slice: String,
        val fittedOver: List<Coordinate>,
        val result: FitResult,
    ) {
        fun render(): String = when (val fit = result) {
            is FitResult.Law ->
                "- dimension=$dimension slice=[$slice] metric=$metric form=${fit.law.form} " +
                    "coefficients=${fit.law.coefficients} R²=${fit.law.goodnessOfFit} " +
                    "fitted-over=${fittedOver.size} points=${fittedOver.joinToString()}"
            is FitResult.Unfitted ->
                "- dimension=$dimension slice=[$slice] metric=$metric Unfitted: ${fit.reason}; " +
                    "R²=${fit.goodnessOfFit}; fitted-over=${fittedOver.size} " +
                    "points=${fittedOver.joinToString()}"
        }
    }

    companion object {
        private const val BYTES_PER_MIB = 1_048_576.0
        private const val RETAINED_MIB_GRANULARITY = 0.1
        private const val HARNESS_SHA_PROPERTY = "civictech.bench.harnessSha"
        private const val DATE_PROPERTY = "civictech.bench.date"
        private const val REPO_ROOT_PROPERTY = "computenet.repo.root"
        private const val SIM_MODE =
            "SIM durable journal growth probe (JUnit @Tag(\"bench\"), one deterministic " +
                "BenchMesh per attempt; not JMH; warmup=1 names attempt 1 of the " +
                "determinism pair, iters=2 names both"
        private const val SUBJECT =
            "durable DIAMOND point on ONE host: journal file size, bytes per record, " +
                "records, retained bytes at replay, checkpoint size and the recovery check, " +
                "per (cardinality, batch size), SIM drive"
    }
}

/**
 * Runs the three D19 C=1e6 points in isolated JVMs with a stated heap. The
 * [JvmPeer] child is only a launcher: the measured grandchild receives the
 * explicit [FORKED_XMX] option, and the launcher's shutdown hook reaps it if
 * the per-point wait expires.
 */
@Tag("bench")
@Tag("multi-jvm")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForkedMillionJournalTest {

    @JvmField
    @TempDir
    var temporaryRoot: File? = null

    fun batches(): List<Int> = JournalPlan.BATCH_SIZES

    @ParameterizedTest(name = "C=1e6 batch={0}")
    @MethodSource("batches")
    @Timeout(value = FORK_TIMEOUT_MINUTES + 2, unit = MINUTES)
    fun `records the probe-owned stage from an explicitly sized fork`(batchSize: Int) {
        val peer = JvmPeer.launch(
            LAUNCHER_MAIN,
            FORKED_XMX,
            batchSize.toString(),
            File(requireNotNull(temporaryRoot), "batch-$batchSize").absolutePath,
        )
        try {
            JvmPeer.await(
                what = "C=1e6 batch=$batchSize forked journal outcome",
                peers = listOf(peer),
                timeoutMs = TimeUnit.MINUTES.toMillis(FORK_TIMEOUT_MINUTES),
            ) {
                !peer.process.isAlive || peer.output().lineSequence().any { it.startsWith(RESULT_PREFIX) }
            }
            check(peer.process.waitFor(10, TimeUnit.SECONDS)) {
                "fork reported an outcome but did not exit\n${peer.report()}"
            }
            val result = peer.output().lineSequence().filter { it.startsWith(RESULT_PREFIX) }.toList()
            assertEquals(1, result.size, peer.report())
            assertEquals(0, peer.process.exitValue(), peer.report())
            assertTrue(result.single().contains("batch=$batchSize|"), result.single())
            println(peer.output())
        } finally {
            JvmPeer.destroy(peer)
        }
    }

    private companion object {
        const val FORKED_XMX = "-Xmx8g"
        const val FORK_TIMEOUT_MINUTES = 30L
        const val LAUNCHER_MAIN =
            "civictech.bench.macro.journal.JournalMillionForkLauncherMain"
    }
}

/** JvmPeer-compatible launcher that gives the measured JVM its own explicit heap. */
object JournalMillionForkLauncherMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "expected: <xmx> <batchSize> <journalDir>" }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            java,
            args[0],
            "-cp",
            System.getProperty("java.class.path"),
            JournalMillionForkMain::class.java.name,
            args[1],
            args[2],
        ).redirectErrorStream(true).start()
        val reap = Thread { if (process.isAlive) process.destroyForcibly() }
        Runtime.getRuntime().addShutdownHook(reap)
        try {
            process.inputStream.copyTo(System.out)
            System.out.flush()
            exitProcess(process.waitFor())
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(reap) }
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

/** The measured JVM. Its result line keeps classification and stage separate. */
object JournalMillionForkMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "expected: <batchSize> <journalDir>" }
        val batchSize = args[0].toInt()
        val at = JournalPlan.coordinate(JournalPlan.SIM_OMITTED_CARDINALITY, batchSize)
        val inputArguments = ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")
        println(
            "$ENV_PREFIX os=${System.getProperty("os.name")}|arch=${System.getProperty("os.arch")}|" +
                "jdk=${System.getProperty("java.vendor")} ${System.getProperty("java.version")}|" +
                "vmArgs=${protocol(inputArguments)}|maxHeap=${Runtime.getRuntime().maxMemory()}",
        )
        System.out.flush()
        try {
            val measured = JournalProbe.runForked(
                at = at,
                journalDir = File(args[1]),
                stageObserver = { stage ->
                    println("$STAGE_PREFIX batch=$batchSize|stage=$stage")
                    System.out.flush()
                },
            )
            println(measured.resultLine(batchSize))
            System.out.flush()
        } catch (failure: Throwable) {
            println(
                "$RESULT_PREFIX batch=$batchSize|kind=Unclassified|stage=process|" +
                    "reason=${protocol("${failure.javaClass.name}: ${failure.message}")}",
            )
            failure.printStackTrace(System.out)
            System.out.flush()
            exitProcess(1)
        }
    }
}

private const val ENV_PREFIX = "JOURNAL_FORK_ENV"
private const val STAGE_PREFIX = "JOURNAL_FORK_STAGE"
private const val RESULT_PREFIX = "JOURNAL_FORK_RESULT"

private fun JournalMeasurement.resultLine(batchSize: Int): String =
    "$RESULT_PREFIX batch=$batchSize|kind=${forkOutcomeKind(outcome)}|stage=$stage|" +
        "replayCompleted=$replayCompleted|recoverCompleted=$recoverCompleted|" +
        "fileBytes=${fileBytes ?: "n/a"}|records=${records ?: "n/a"}|" +
        "retainedBytes=${retainedBytes ?: "n/a"}|maxHeap=$maxHeapBytes|" +
        "checkpointBytes=${checkpointBytes ?: "n/a"}|reason=${protocol(forkReason(outcome))}"

private fun forkOutcomeKind(outcome: PointOutcome): String = when (outcome) {
    is PointOutcome.Ok -> "Ok"
    is PointOutcome.NonConvergent -> "NonConvergent"
    is PointOutcome.NonDeterministic -> "NonDeterministic"
    is PointOutcome.Unschedulable -> "Unschedulable"
    is PointOutcome.Failed -> "Failed"
    is PointOutcome.Unreportable -> "Unreportable"
}

private fun forkReason(outcome: PointOutcome): String = when (outcome) {
    is PointOutcome.Ok -> "admitted"
    is PointOutcome.NonConvergent -> outcome.budget
    is PointOutcome.NonDeterministic -> "structural counts differ"
    is PointOutcome.Unschedulable -> "${outcome.reason}; observedLimit=${outcome.observedLimit}"
    is PointOutcome.Failed -> "${outcome.cause}; deadLetters=${outcome.deadLetters.size}"
    is PointOutcome.Unreportable -> "relativeDispersion=${outcome.result.relativeDispersion}"
}

private fun protocol(value: String?): String =
    value.orEmpty().replace('|', '/').replace('\n', ' ').replace('\r', ' ')
