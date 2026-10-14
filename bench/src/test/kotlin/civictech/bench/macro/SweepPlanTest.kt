package civictech.bench.macro

import civictech.bench.Drive
import civictech.bench.cost.Coordinate
import civictech.bench.macro.mesh.MeshTopology
import civictech.bench.macro.shape.KNOWN_FAILING_SEEDS
import civictech.bench.macro.shape.Shape
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class SweepPlanTest {
    @Test
    fun pointsAreDistinctStableSimCoordinatesWithTheDeclaredBaseline() {
        val points = SweepPlan.points()

        SweepPlan.BASELINE shouldBe Coordinate(
            shape = Shape.DIAMOND.name,
            shapeSeed = 1L,
            cardinality = 10_000L,
            batchSize = 10,
            hostCount = 3,
            fanOut = 4,
            replication = false,
            durability = false,
            drive = Drive.SIM,
        )
        points.size shouldBe 22
        points.toSet().size shouldBe 22
        points shouldBe SweepPlan.points()
        points.first() shouldBe SweepPlan.BASELINE
        points.map { it.drive }.toSet() shouldBe setOf(Drive.SIM)
        points.map { it.shapeSeed }.toSet() shouldBe setOf(SweepPlan.SHAPE_SEED)
        SweepPlan.TOPOLOGY shouldBe MeshTopology.FULL_MESH
        SweepPlan.DETERMINISM_CHECK shouldBe true
        KNOWN_FAILING_SEEDS.values.none { SweepPlan.SHAPE_SEED in it } shouldBe true
    }

    @Test
    fun pointsCoverEveryDeclaredDimensionValue() {
        val points = SweepPlan.points()

        SweepPlan.SHAPES shouldBe Shape.entries.toList()
        SweepPlan.CARDINALITIES shouldBe listOf(1_000L, 10_000L, 100_000L, 1_000_000L)
        SweepPlan.BATCH_SIZES shouldBe listOf(1, 10, 100)
        SweepPlan.HOST_COUNTS shouldBe listOf(1, 2, 3, 5, 8)
        SweepPlan.FAN_OUTS shouldBe listOf(1, 4, 16, 64, 256)
        SweepPlan.REPLICATION shouldBe listOf(false, true)
        SweepPlan.DURABILITY shouldBe listOf(false, true)
        points.map { Shape.valueOf(it.shape) }.toSet() shouldBe SweepPlan.SHAPES.toSet()
        points.map { it.cardinality }.toSet() shouldBe SweepPlan.CARDINALITIES.toSet()
        points.map { it.batchSize }.toSet() shouldBe SweepPlan.BATCH_SIZES.toSet()
        points.map { it.hostCount }.toSet() shouldBe SweepPlan.HOST_COUNTS.toSet()
        points.map { it.fanOut }.toSet() shouldBe SweepPlan.FAN_OUTS.toSet()
        points.map { it.replication }.toSet() shouldBe SweepPlan.REPLICATION.toSet()
        points.map { it.durability }.toSet() shouldBe SweepPlan.DURABILITY.toSet()
    }

    @Test
    fun ordinaryPointsAreOfatAndFanOutUsesTheTreeShape() {
        val ordinaryPoints = SweepPlan.points().drop(1).dropLast(2)

        ordinaryPoints.forEach { point ->
            val differences = differencesFromBaseline(point)
            if (point.fanOut != SweepPlan.BASELINE.fanOut) {
                differences shouldBe setOf("shape", "fanOut")
                point.shape shouldBe Shape.FAN_OUT_TREE.name
            } else {
                differences.size shouldBe 1
            }
        }
    }

    @Test
    fun cornersCrossTheTwoDeclaredDimensions() {
        val (cardinalityCorner, fanOutCorner) = SweepPlan.points().takeLast(2)

        differencesFromBaseline(cardinalityCorner) shouldBe setOf("cardinality", "hostCount")
        differencesFromBaseline(fanOutCorner) - "shape" shouldBe setOf("hostCount", "fanOut")
        fanOutCorner.shape shouldBe Shape.FAN_OUT_TREE.name
    }

    @Test
    fun budgetsScaleWithDeclaredMessageVolumeAndClampAtTheRunnerBoundary() {
        val budgets = SweepPlan.CARDINALITIES.map { cardinality ->
            SweepPlan.budgetFor(SweepPlan.BASELINE.copy(cardinality = cardinality)).simSteps
        }

        budgets.zipWithNext().forEach { (lower, higher) -> higher shouldBeGreaterThan lower }
        SweepPlan.budgetFor(SweepPlan.BASELINE).simSteps shouldBe 340_000
        SweepPlan.budgetFor(
            SweepPlan.BASELINE.copy(shape = Shape.FAN_OUT_TREE.name, fanOut = 64),
        ).simSteps shouldBe 4_540_000
        SweepPlan.budgetFor(
            SweepPlan.BASELINE.copy(shape = Shape.FAN_OUT_TREE.name, fanOut = 256),
        ).simSteps shouldBe 17_980_000
        SweepPlan.budgetFor(
            SweepPlan.BASELINE.copy(shape = Shape.FAN_OUT_TREE.name, fanOut = 256, hostCount = 8),
        ).simSteps shouldBe 17_980_000
        SweepPlan.budgetFor(SweepPlan.BASELINE.copy(replication = true)).simSteps shouldBe 900_000
        SweepPlan.budgetFor(SweepPlan.BASELINE.copy(fanOut = 256)).simSteps shouldBe 340_000
        SweepPlan.budgetFor(SweepPlan.BASELINE.copy(cardinality = Long.MAX_VALUE)).simSteps shouldBe
            Int.MAX_VALUE
    }

    @Test
    fun omissionsNameTheGroupedRemainderAndEveryOutsideDimension() {
        val omissions = SweepPlan.omissions()
        val grouped = omissions.single { it.reason == "OFAT design (mkyb1-D1)" }

        grouped.count shouldBe 5_978
        omissions.single { it.what == ":wire host counts 2 and 3" }.count shouldBe 2
        omissions.single { it.what == MeshTopology.STAR.name }.count shouldBe 1
        omissions.single { it.what.startsWith(Drive.REAL.name) }.count shouldBe 22
        omissions.all { it.what.isNotBlank() && it.reason.isNotBlank() } shouldBe true
    }

    @Test
    fun descriptionNamesEveryValueAndThePlanPolicy() {
        val description = SweepPlan.describe()
        val declaredValues = buildList {
            addAll(SweepPlan.SHAPES.map { it.name })
            addAll(SweepPlan.CARDINALITIES.map { it.toString() })
            addAll(SweepPlan.BATCH_SIZES.map { it.toString() })
            addAll(SweepPlan.HOST_COUNTS.map { it.toString() })
            addAll(SweepPlan.FAN_OUTS.map { it.toString() })
            addAll(SweepPlan.REPLICATION.map { it.toString() })
            addAll(SweepPlan.DURABILITY.map { it.toString() })
        }

        declaredValues.forEach { value -> description shouldContain value }
        description shouldContain "Sweep plan"
        description shouldContain "Baseline: point #1"
        description shouldContain "Topology: ${MeshTopology.FULL_MESH}"
        description shouldContain "Determinism check: true"
        description shouldContain "${SweepPlan.STEPS_PER_ELEMENT} * cardinality"
        description shouldContain "FAN_OUT_TREE ceil(fan-out / ${SweepPlan.BASELINE.fanOut})"
        description shouldContain "replication copies = host count"
        description shouldContain "Shape seed: ${SweepPlan.SHAPE_SEED}"
    }

    private fun differencesFromBaseline(point: Coordinate): Set<String> = buildSet {
        val baseline = SweepPlan.BASELINE
        if (point.shape != baseline.shape) add("shape")
        if (point.shapeSeed != baseline.shapeSeed) add("shapeSeed")
        if (point.cardinality != baseline.cardinality) add("cardinality")
        if (point.batchSize != baseline.batchSize) add("batchSize")
        if (point.hostCount != baseline.hostCount) add("hostCount")
        if (point.fanOut != baseline.fanOut) add("fanOut")
        if (point.replication != baseline.replication) add("replication")
        if (point.durability != baseline.durability) add("durability")
        if (point.drive != baseline.drive) add("drive")
    }
}
