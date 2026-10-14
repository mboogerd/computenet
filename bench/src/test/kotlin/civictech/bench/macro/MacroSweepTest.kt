package civictech.bench.macro

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
import civictech.bench.cost.Envelope
import civictech.bench.cost.PointOutcome
import civictech.bench.cost.StructuralCounts
import civictech.bench.micro.FootprintReport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

/** Runs and exports the declared BEN2 structural macro sweep (mkyb1-D3/D14/D15/D17). */
@Tag("bench")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MacroSweepTest {

    @JvmField
    @TempDir
    var temporaryRoot: File? = null

    private val outcomes = LinkedHashMap<Coordinate, PointOutcome>()
    private val invocationCoordinates = ConcurrentHashMap<String, Coordinate>()

    @JvmField
    @RegisterExtension
    val timeoutObserver: TestWatcher = TimeoutObserver()

    /** Kept as the single plan seam so the prescribed three-point mutation can slice it. */
    fun points(): List<Coordinate> = SweepPlan.points()

    @ParameterizedTest(name = "{0}")
    @MethodSource("points")
    @Timeout(value = POINT_TIMEOUT_MINUTES, unit = MINUTES)
    fun `runs every declared SIM point with determinism enabled`(at: Coordinate) {
        val pointNumber = points().indexOf(at) + 1
        val journalDir = File(requireNotNull(temporaryRoot), "point-$pointNumber")
        val outcome = SweepRunner(
            budget = SweepPlan.budgetFor(at),
            topology = SweepPlan.TOPOLOGY,
            journalDir = journalDir,
            determinismCheck = SweepPlan.DETERMINISM_CHECK,
        ).run(at)

        assertNull(outcomes.put(at, outcome), "plan coordinate produced more than one outcome: $at")
        assertEquals(at, outcome.at, "runner outcome must retain the coordinate it measured")
    }

    /**
     * JUnit's SAME_THREAD timeout decides its final verdict only after the test body
     * returns. The interceptor records the parameterized invocation's coordinate;
     * [TestWatcher] then observes the finalized [TimeoutException] and replaces even
     * a successfully returned runner outcome before [AfterAll] exports the sweep.
     */
    private inner class TimeoutObserver : InvocationInterceptor, TestWatcher {
        override fun interceptTestTemplateMethod(
            invocation: InvocationInterceptor.Invocation<Void>,
            invocationContext: ReflectiveInvocationContext<Method>,
            extensionContext: ExtensionContext,
        ) {
            val at = invocationContext.arguments.singleOrNull() as? Coordinate
                ?: error("macro sweep invocation must carry exactly one Coordinate")
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

    private fun timeoutOutcome(at: Coordinate): PointOutcome = PointOutcome.NonConvergent(
        at = at,
        subject = CostSubject.PROPAGATION,
        budget = "junitTimeout=${POINT_TIMEOUT_MINUTES}m",
    )

    @AfterAll
    fun `writes the structural export and prints its findings entry`() {
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
        orderedOutcomes.forEach(writer::admit)

        val export = File(requiredRepoRoot(), CostSchema.STABLE_PATH).toPath()
        writer.write(export)
        val model = CostSchema.parse(Files.readString(export))
        assertExport(model, harnessSha, orderedOutcomes)

        val date = System.getProperty(DATE_PROPERTY)
            ?.takeIf(String::isNotBlank)
            ?: LocalDate.now().toString()
        print(renderFindings(date, harnessSha, model, orderedOutcomes))
    }

    private fun assertExport(
        model: CostModel,
        harnessSha: String,
        orderedOutcomes: List<PointOutcome>,
    ) {
        assertEquals(CostSchema.VERSION, model.schemaVersion)
        assertEquals(harnessSha, model.harnessSha)
        assertTrue(model.calibration.isEmpty(), "the SIM export precedes calibration merge")

        val admitted = orderedOutcomes.filterIsInstance<PointOutcome.Ok>()
        val excluded = orderedOutcomes.filterNot { it is PointOutcome.Ok }
        assertEnvelopeMatches(model, admitted)
        assertEquals(
            admitted.map { it.subject to it.at },
            model.structural.map { it.subject to it.at },
            "only admitted outcomes may produce structural entries, in plan order",
        )
        assertExclusionsMatch(model, excluded)

        val structuralKeys = model.structural.map { it.subject to it.at }.toSet()
        val exclusionKeys = model.exclusions.map { it.subject to it.at }.toSet()
        assertTrue(
            structuralKeys.intersect(exclusionKeys).isEmpty(),
            "no structural entry may share a coordinate and subject with an exclusion",
        )

        val query = CostQuery(model)
        model.structural.forEach { entry ->
            assertTrue(
                query.coLocatedEdge(entry.at) is CostAnswer.NotMeasured,
                "co-located edge must remain NotMeasured without a dispatch count/calibration",
            )
            assertTrue(
                query.crossHostEdge(entry.at) is CostAnswer.NotMeasured,
                "cross-host edge must remain NotMeasured without a dispatch count/calibration",
            )
            assertTrue(
                query.cellResidency(entry.at) is CostAnswer.NotMeasured,
                "cell residency becomes answerable only after retainedBytesPerElement is merged",
            )
        }
    }

    private fun assertEnvelopeMatches(
        model: CostModel,
        admitted: List<PointOutcome.Ok>,
    ) {
        val coordinates = admitted.map { it.at }
        assertFalse(coordinates.isEmpty(), "CostWriter cannot export a sweep with no admitted point")
        assertEquals(
            coordinates.range { it.cardinality.toDouble() },
            model.envelope.numeric.getValue(Dimensions.CARDINALITY),
            "coverage envelope cardinality must equal the admitted-point extent",
        )
        assertEquals(
            coordinates.range { it.batchSize.toDouble() },
            model.envelope.numeric.getValue(Dimensions.BATCH_SIZE),
            "coverage envelope batch size must equal the admitted-point extent",
        )
        assertEquals(
            coordinates.range { it.hostCount.toDouble() },
            model.envelope.numeric.getValue(Dimensions.HOST_COUNT),
            "coverage envelope host count must equal the admitted-point extent",
        )
        assertEquals(
            coordinates.range { it.fanOut.toDouble() },
            model.envelope.numeric.getValue(Dimensions.FAN_OUT),
            "coverage envelope fan-out must equal the admitted-point extent",
        )
        assertEquals(
            coordinates.mapTo(linkedSetOf()) { it.shape },
            model.envelope.categorical.getValue(Dimensions.SHAPE),
            "coverage envelope shapes must equal the admitted-point values",
        )
        assertEquals(
            coordinates.mapTo(linkedSetOf()) { it.replication.toString() },
            model.envelope.categorical.getValue(Dimensions.REPLICATION),
            "coverage envelope replication values must equal the admitted-point values",
        )
        assertEquals(
            coordinates.mapTo(linkedSetOf()) { it.durability.toString() },
            model.envelope.categorical.getValue(Dimensions.DURABILITY),
            "coverage envelope durability values must equal the admitted-point values",
        )
    }

    private fun assertExclusionsMatch(model: CostModel, excluded: List<PointOutcome>) {
        assertEquals(excluded.size, model.exclusions.size, "every non-Ok outcome must be exported")
        excluded.zip(model.exclusions).forEach { (outcome, actual) ->
            assertEquals(outcome.at, actual.at)
            assertEquals(outcome.subject, actual.subject)
            assertEquals(exclusionKind(outcome), actual.kind)
            assertTrue(actual.reason.isNotBlank(), "excluded point must retain a reason: ${outcome.at}")
        }
    }

    private fun renderFindings(
        date: String,
        harnessSha: String,
        model: CostModel,
        orderedOutcomes: List<PointOutcome>,
    ): String {
        val env = RunEnvironment.forRun(
            measuringJvm = FootprintReport.inProcessMeasuringJvm(),
            jmhMode = SWEEP_MODE,
            forkCount = 1,
            warmupIterations = 1,
            measurementIterations = 2,
            harnessCommitSha = harnessSha,
        )
        val rows = mutableListOf<BenchResult>()
        val labels = mutableListOf<String>()
        model.structural.forEach { entry ->
            metrics(entry.counts).forEach { (metric, value) ->
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
            append(SweepPlan.describe())
            appendLine()
            appendLine("### Omissions")
            appendLine()
            SweepPlan.omissions().forEach { omission ->
                appendLine("- ${omission.count} ${omission.what} — reason: ${omission.reason}")
            }
            model.exclusions.forEach { exclusion ->
                appendLine("- ${coordinateLabel(exclusion.at)}: ${exclusion.kind} — ${exclusion.reason}")
            }
            appendLine()
            appendLine("### Outcome tally")
            appendLine()
            appendLine(tally(orderedOutcomes))
            appendLine()
            appendLine("### Cost-query consequences")
            appendLine()
            appendLine(
                "This structural export has an empty calibration list. `CostQuery.coLocatedEdge` " +
                    "and `CostQuery.crossHostEdge` answer `NotMeasured` at every point until a " +
                    "per-host dispatch counter exists; `CostQuery.cellResidency` becomes answerable " +
                    "when task 4 merges `retainedBytesPerElement`.",
            )
            appendLine()
            appendLine("### Candidate G-item")
            appendLine()
            append(Counters.MESSAGES_NOT_MEASURED)
        }
    }

    private fun metrics(counts: StructuralCounts): List<Pair<String, Long>> = listOf(
        "crossHostFrames" to counts.crossHostFrames,
        "wireBytes" to counts.wireBytes,
        "journalRecords" to counts.journalRecords,
        "simSteps" to counts.simSteps,
    )

    private fun tally(outcomes: List<PointOutcome>): String =
        "admitted=${outcomes.count { it is PointOutcome.Ok }} " +
            "NonConvergent=${outcomes.count { it is PointOutcome.NonConvergent }} " +
            "NonDeterministic=${outcomes.count { it is PointOutcome.NonDeterministic }} " +
            "Unschedulable=${outcomes.count { it is PointOutcome.Unschedulable }} " +
            "Failed=${outcomes.count { it is PointOutcome.Failed }} " +
            "Unreportable=${outcomes.count { it is PointOutcome.Unreportable }}"

    private fun exclusionKind(outcome: PointOutcome): String = when (outcome) {
        is PointOutcome.Ok -> error("an admitted outcome has no exclusion kind")
        is PointOutcome.NonConvergent -> "NonConvergent"
        is PointOutcome.NonDeterministic -> "NonDeterministic"
        is PointOutcome.Unschedulable -> "Unschedulable"
        is PointOutcome.Failed -> "Failed"
        is PointOutcome.Unreportable -> "Unreportable"
    }

    private fun coordinateLabel(at: Coordinate): String =
        "${at.shape} n=${at.cardinality} b=${at.batchSize} h=${at.hostCount} " +
            "f=${at.fanOut} repl=${if (at.replication) "on" else "off"} " +
            "dur=${if (at.durability) "on" else "off"}"

    private fun List<Coordinate>.range(selector: (Coordinate) -> Double): Envelope.Range {
        val values = map(selector)
        return Envelope.Range(values.min(), values.max())
    }

    private fun requiredHarnessSha(): String {
        val sha = System.getProperty(HARNESS_SHA_PROPERTY)
        require(!sha.isNullOrBlank()) {
            "set -D$HARNESS_SHA_PROPERTY=\$(git rev-parse --short HEAD): no run artifact " +
                "records which harness commit produced a measurement, and RunEnvironment " +
                "refuses to exist without it"
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

    companion object {
        const val POINT_TIMEOUT_MINUTES = 60L
        private const val HARNESS_SHA_PROPERTY = "civictech.bench.harnessSha"
        private const val DATE_PROPERTY = "civictech.bench.date"
        private const val REPO_ROOT_PROPERTY = "computenet.repo.root"
        private const val SWEEP_MODE =
            "SIM structural sweep (JUnit @Tag(\"bench\"), one deterministic " +
                "SimulationController per point; not JMH; warmup=1 names attempt 1 of the " +
                "determinism pair, iters=2 names both attempts)"
        private const val SUBJECT =
            "macro sweep — structural counts per point over the OFAT plan (mkyb1-D1/D16): " +
                "shape × cardinality × batch × host count × fan-out with replication and " +
                "durability switches, FULL_MESH loopback, SIM drive, determinism check on every point"
    }
}
