package civictech.bench.macro.rebaseline

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.Findings
import civictech.bench.FindingsTable
import civictech.bench.RunEnvironment
import civictech.bench.TriggerClaim
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostAnswer
import civictech.bench.cost.CostModel
import civictech.bench.cost.CostQuery
import civictech.bench.cost.CostSchema
import civictech.bench.cost.CostSubject
import civictech.bench.cost.CostWriter
import civictech.bench.cost.Dimensions
import civictech.bench.cost.FitResult
import civictech.bench.cost.GrowthForm
import civictech.bench.cost.PointOutcome
import civictech.bench.cost.StructuralCounts
import civictech.bench.cost.SubjectMerge
import civictech.bench.micro.FootprintReport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.lang.reflect.Method
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.MINUTES
import java.util.concurrent.TimeoutException

/** Runs, merges, and renders the G-43 structural re-baseline grid (j27rc-D10..D15). */
@Tag("bench")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroReBaselineTest {

    @JvmField
    @TempDir
    var temporaryRoot: File? = null

    private val outcomes = LinkedHashMap<Coordinate, ReBaselineOutcome>()
    private val invocationWallMillis = LinkedHashMap<Coordinate, Long>()
    private val invocationCoordinates = ConcurrentHashMap<String, Coordinate>()

    @JvmField
    @RegisterExtension
    val timeoutObserver: TestWatcher = TimeoutObserver()

    /** Kept as the single plan seam so reviewer mutations can run a bounded slice. */
    fun points(): List<Coordinate> = ReBaselinePlan.points()

    @ParameterizedTest(name = "{0}")
    @MethodSource("points")
    @Timeout(value = ReBaselinePlan.TIMEOUT_MINUTES, unit = MINUTES)
    fun `runs every declared re-baseline point with determinism enabled`(at: Coordinate) {
        val started = System.nanoTime()
        val measured = ReBaselineProbe.run(
            at = at,
            budget = ReBaselinePlan.budgetFor(at),
            determinismCheck = ReBaselinePlan.DETERMINISM_CHECK,
        )
        invocationWallMillis[at] = (System.nanoTime() - started) / 1_000_000L

        assertNull(outcomes.put(at, measured), "plan coordinate produced more than one outcome: $at")
        assertEquals(at, measured.outcome.at, "probe outcome must retain the coordinate it measured")
    }

    /**
     * SAME_THREAD timeouts decide their verdict after the body returns. The watcher replaces
     * even a successfully returned probe result before [AfterAll] exports the study.
     */
    private inner class TimeoutObserver : InvocationInterceptor, TestWatcher {
        override fun interceptTestTemplateMethod(
            invocation: InvocationInterceptor.Invocation<Void>,
            invocationContext: ReflectiveInvocationContext<Method>,
            extensionContext: ExtensionContext,
        ) {
            val at = invocationContext.arguments.singleOrNull() as? Coordinate
                ?: error("re-baseline invocation must carry exactly one Coordinate")
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
            if (cause?.hasTimeoutCause() == true) outcomes[at] = timeoutOutcome(at)
        }
    }

    private fun Throwable.hasTimeoutCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is TimeoutException }

    private fun timeoutOutcome(at: Coordinate): ReBaselineOutcome = ReBaselineOutcome(
        outcome = PointOutcome.NonConvergent(
            at = at,
            subject = CostSubject.REBASELINE,
            budget = "junitTimeout=${ReBaselinePlan.TIMEOUT_MINUTES}m",
        ),
        reassertedElements = null,
        reassertedTags = null,
        notices = null,
    )

    @AfterAll
    fun `merges the structural export and prints the SIM findings entry`() {
        val plan = points()
        assertTrue(
            invocationCoordinates.isEmpty(),
            "every parameterized invocation must receive its finalized JUnit result",
        )
        plan.filterNot(outcomes::containsKey).forEach { at -> outcomes[at] = timeoutOutcome(at) }
        assertEquals(plan.size, outcomes.size, "every plan coordinate must have exactly one outcome")
        assertEquals(plan.toSet(), outcomes.keys.toSet(), "outcomes must cover the declared plan")

        val harnessSha = requiredHarnessSha()
        val orderedOutcomes = plan.map(outcomes::getValue)
        val writer = CostWriter(harnessSha)
        orderedOutcomes.map { it.outcome }.forEach(writer::admit)
        val admittedCoordinates = orderedOutcomes
            .filter { it.outcome is PointOutcome.Ok }
            .mapTo(linkedSetOf()) { it.outcome.at }
        val fits = fitGrowthLaws(writer, admittedCoordinates)

        val replacementPath = File(requireNotNull(temporaryRoot), "rebaseline-model.json").toPath()
        writer.write(replacementPath)
        val replacement = CostSchema.parse(Files.readString(replacementPath))

        val export = File(requiredRepoRoot(), CostSchema.STABLE_PATH).toPath()
        val before = CostSchema.parse(Files.readString(export))
        val merged = SubjectMerge.replaceSubject(export, CostSubject.REBASELINE, replacement)
        val model = CostSchema.parse(Files.readString(export))
        assertEquals(merged, model, "the stable export must parse as the validated merge result")
        assertExport(model, before, harnessSha, orderedOutcomes, fits)

        val date = System.getProperty(DATE_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: LocalDate.now().toString()
        val rendered = renderFindings(date, harnessSha, model, orderedOutcomes, fits)

        println("REBASELINE_OUTCOME_TALLY ${tally(orderedOutcomes)}")
        val corner = ReBaselineProbe.coordinate(
            ReBaselineProbe.REMOTE_TAPS,
            ReBaselinePlan.CARDINALITIES.last(),
            ReBaselinePlan.FAN_OUTS.last(),
            hostCount = 8,
        )
        println(
            "REBASELINE_CORNER_WALL ${coordinateLabel(corner)} " +
                "elapsedMs=${invocationWallMillis[corner] ?: "not-recorded"} " +
                "outcome=${outcomeKind(outcomes.getValue(corner).outcome)}",
        )
        print(rendered)
    }

    private fun fitGrowthLaws(
        writer: CostWriter,
        admitted: Set<Coordinate>,
    ): List<FitRecord> = buildList {
        ReBaselinePlan.CARDINALITIES.forEach { cardinality ->
            ReBaselinePlan.HOST_VARIANTS.forEach { hostVariant ->
                add(
                    fit(
                        writer = writer,
                        admitted = admitted,
                        dimension = Dimensions.FAN_OUT,
                        metric = "messages",
                        slice = "n=$cardinality ${hostVariant}",
                        declaredPoints = points().filter { at ->
                            at.cardinality == cardinality &&
                                at.hostCount == hostVariant.hostCount &&
                                at.shape == hostVariant.variant
                        },
                    ),
                )
            }
        }
        ReBaselinePlan.FAN_OUTS.forEach { fanOut ->
            listOf(3, 8).forEach { hostCount ->
                add(
                    fit(
                        writer = writer,
                        admitted = admitted,
                        dimension = Dimensions.CARDINALITY,
                        metric = "wireBytes",
                        slice = "f=$fanOut H=$hostCount ${ReBaselineProbe.REMOTE_TAPS}",
                        declaredPoints = points().filter { at ->
                            at.fanOut == fanOut &&
                                at.hostCount == hostCount &&
                                at.shape == ReBaselineProbe.REMOTE_TAPS
                        },
                    ),
                )
            }
        }
        ReBaselinePlan.CARDINALITIES.forEach { cardinality ->
            ReBaselinePlan.FAN_OUTS.forEach { fanOut ->
                add(
                    fit(
                        writer = writer,
                        admitted = admitted,
                        dimension = Dimensions.HOST_COUNT,
                        metric = "crossHostFrames",
                        slice = "n=$cardinality f=$fanOut ${ReBaselineProbe.REMOTE_TAPS}",
                        declaredPoints = points().filter { at ->
                            at.cardinality == cardinality &&
                                at.fanOut == fanOut &&
                                at.shape == ReBaselineProbe.REMOTE_TAPS
                        },
                    ),
                )
            }
        }
    }

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
                subject = CostSubject.REBASELINE,
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
        orderedOutcomes: List<ReBaselineOutcome>,
        fits: List<FitRecord>,
    ) {
        assertEquals(CostSchema.VERSION, model.schemaVersion)
        assertEquals(before.schemaVersion, model.schemaVersion)
        assertEquals(before.harnessSha, model.harnessSha, "subject merge keeps the export harness SHA")
        assertEquals(
            before.structural.filter { it.subject == CostSubject.PROPAGATION },
            model.structural.filter { it.subject == CostSubject.PROPAGATION },
            "PROPAGATION entries must remain unchanged",
        )
        assertEquals(before.calibration, model.calibration, "calibration must remain unchanged")

        val admitted = orderedOutcomes.filter { it.outcome is PointOutcome.Ok }
        val excluded = orderedOutcomes.filterNot { it.outcome is PointOutcome.Ok }
        val structural = model.structural.filter { it.subject == CostSubject.REBASELINE }
        assertEquals(
            admitted.map { it.outcome.at },
            structural.map { it.at },
            "only admitted outcomes may produce REBASELINE entries, in plan order",
        )
        admitted.zip(structural).forEach { (measured, entry) ->
            val outcome = measured.outcome as PointOutcome.Ok
            assertEquals(outcome.counts, entry.counts)
            assertEquals(harnessSha, entry.harnessSha)
        }

        val actualExclusions = model.exclusions.filter { it.subject == CostSubject.REBASELINE }
        assertEquals(excluded.size, actualExclusions.size, "every non-Ok outcome must be exported")
        excluded.zip(actualExclusions).forEach { (measured, actual) ->
            assertEquals(measured.outcome.at, actual.at)
            assertEquals(outcomeKind(measured.outcome), actual.kind)
            assertTrue(actual.reason.isNotBlank(), "excluded point must retain a reason: ${actual.at}")
        }

        val expectedLaws = fits.mapNotNull { fit ->
            (fit.result as? FitResult.Law)?.law
        }
        assertEquals(
            expectedLaws,
            model.laws.filter { it.subject == CostSubject.REBASELINE },
            "every honestly fitted REBASELINE law must enter the merged export",
        )
        val expectedUnfitted = fits.mapNotNull { fit ->
            (fit.result as? FitResult.Unfitted)?.record
        }
        assertEquals(
            expectedUnfitted,
            model.unfitted.filter { it.subject == CostSubject.REBASELINE },
            "every unfitted REBASELINE slice must enter the merged export",
        )

        val shapes = model.envelope.categorical.getValue(Dimensions.SHAPE)
        if (structural.any { it.at.shape == ReBaselineProbe.LOCAL_TAPS }) {
            assertTrue(ReBaselineProbe.LOCAL_TAPS in shapes)
        }
        if (structural.any { it.at.shape == ReBaselineProbe.REMOTE_TAPS }) {
            assertTrue(ReBaselineProbe.REMOTE_TAPS in shapes)
        }
        structural.filter { it.at.shape == ReBaselineProbe.REMOTE_TAPS }.forEach { remote ->
            assertTrue(remote.counts.crossHostFrames > 0, "remote point must cross a host boundary")
            assertTrue(remote.counts.wireBytes > 0, "remote point must encode wire bytes")
            val localCoordinate = remote.at.copy(shape = ReBaselineProbe.LOCAL_TAPS)
            assertNotNull(
                structural.singleOrNull { it.at == localCoordinate },
                "remote point must remain distinct from its admitted local counterpart",
            )
        }

        if (model.calibration.isEmpty()) {
            println("REBASELINE_QUERY_SKIPPED no calibration constants in stable export")
        } else {
            val query = CostQuery(model)
            structural.forEach { entry ->
                assertTrue(
                    query.reBaseline(entry.at) is CostAnswer.Priced,
                    "admitted REBASELINE point must be priced when calibration is present: ${entry.at}",
                )
            }
        }
    }

    private fun renderFindings(
        date: String,
        harnessSha: String,
        model: CostModel,
        orderedOutcomes: List<ReBaselineOutcome>,
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
        val byCoordinate = orderedOutcomes.associateBy { it.outcome.at }
        val rows = mutableListOf<BenchResult>()
        val labels = mutableListOf<String>()
        model.structural.filter { it.subject == CostSubject.REBASELINE }.forEach { entry ->
            val measured = byCoordinate.getValue(entry.at)
            metrics(entry.counts, measured).forEach { (metric, value) ->
                rows += BenchResult(
                    value = value.toDouble(),
                    unit = "$metric/point",
                    dispersion = 0.0,
                    drive = Drive.SIM,
                    env = env,
                )
                labels += "${coordinateLabel(entry.at)} $metric"
            }
        }
        val entry = Findings.entry(
            date = date,
            subject = SUBJECT,
            results = FindingsTable(rows, labels),
            trigger = TriggerClaim.None,
        )

        return buildString {
            appendLine(entry)
            appendLine()
            append(ReBaselinePlan.describe())
            appendLine()
            appendLine("### Omissions")
            appendLine()
            ReBaselinePlan.omissions().forEach { omission ->
                appendLine("- ${omission.count} ${omission.what} — reason: ${omission.reason}")
            }
            model.exclusions.filter { it.subject == CostSubject.REBASELINE }.forEach { exclusion ->
                appendLine("- ${coordinateLabel(exclusion.at)}: ${exclusion.kind} — ${exclusion.reason}")
            }
            appendLine()
            appendLine("### Growth laws")
            appendLine()
            fits.forEach { fit -> appendLine(fit.render()) }
            appendLine()
            appendLine("### Outcome tally")
            appendLine()
            appendLine(tally(orderedOutcomes))
            appendLine()
            appendLine("### Export")
            appendLine()
            appendLine("- Run harness SHA: `$harnessSha`")
            appendLine(
                "- Merged REBASELINE structural entries: " +
                    model.structural.count { it.subject == CostSubject.REBASELINE },
            )
            appendLine(
                "- A `MacroSweepTest` re-run rewrites this file whole; " +
                    "`MacroReBaselineTest` and `MacroCalibrationRenderTest` must then be " +
                    "re-run or re-merged.",
            )
            appendLine()
            appendLine("### Verdict")
            appendLine()
            appendLine(
                "The G-43 verdict is stated by the REAL companion entry below; " +
                    "this entry is the structural half.",
            )
        }
    }

    private fun metrics(
        counts: StructuralCounts,
        measured: ReBaselineOutcome,
    ): List<Pair<String, Long>> = listOf(
        "messages" to requireNotNull(counts.messages),
        "crossHostFrames" to counts.crossHostFrames,
        "wireBytes" to counts.wireBytes,
        "simSteps" to counts.simSteps,
        "reassertedElements" to requireNotNull(measured.reassertedElements),
        "reassertedTags" to requireNotNull(measured.reassertedTags),
    )

    private fun tally(outcomes: List<ReBaselineOutcome>): String =
        "admitted=${outcomes.count { it.outcome is PointOutcome.Ok }} " +
            "NonConvergent=${outcomes.count { it.outcome is PointOutcome.NonConvergent }} " +
            "NonDeterministic=${outcomes.count { it.outcome is PointOutcome.NonDeterministic }} " +
            "Unschedulable=${outcomes.count { it.outcome is PointOutcome.Unschedulable }} " +
            "Failed=${outcomes.count { it.outcome is PointOutcome.Failed }} " +
            "Unreportable=${outcomes.count { it.outcome is PointOutcome.Unreportable }}"

    private fun outcomeKind(outcome: PointOutcome): String = when (outcome) {
        is PointOutcome.Ok -> "Ok"
        is PointOutcome.NonConvergent -> "NonConvergent"
        is PointOutcome.NonDeterministic -> "NonDeterministic"
        is PointOutcome.Unschedulable -> "Unschedulable"
        is PointOutcome.Failed -> "Failed"
        is PointOutcome.Unreportable -> "Unreportable"
    }

    private fun coordinateLabel(at: Coordinate): String =
        "${at.shape} n=${at.cardinality} f=${at.fanOut} h=${at.hostCount}"

    private fun requiredHarnessSha(): String {
        val sha = System.getProperty(HARNESS_SHA_PROPERTY)
        require(!sha.isNullOrBlank()) {
            "set -D$HARNESS_SHA_PROPERTY=\$(git rev-parse --short HEAD): no run artifact " +
                "records which harness commit produced the measurement"
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
        private const val HARNESS_SHA_PROPERTY = "civictech.bench.harnessSha"
        private const val DATE_PROPERTY = "civictech.bench.date"
        private const val REPO_ROOT_PROPERTY = "computenet.repo.root"
        private const val SIM_MODE =
            "SIM RESTART re-baseline probe (JUnit @Tag(\"bench\"), one deterministic " +
                "BenchMesh per attempt; not JMH; warmup=1 names attempt 1 of the " +
                "determinism pair, iters=2 names both)"
        private const val SUBJECT =
            "RESTART re-baseline structural counts per (cardinality, fan-out, host count), " +
                "SIM drive — G-43 study, SIM half"
    }
}
