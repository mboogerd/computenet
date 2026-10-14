package civictech.bench.macro

import civictech.bench.Drive
import civictech.bench.cost.Coordinate
import civictech.bench.macro.mesh.MeshTopology
import civictech.bench.macro.shape.KNOWN_FAILING_SEEDS
import civictech.bench.macro.shape.Shape
import civictech.cell.host.SimulationController

/**
 * The declared BEN2 macro sweep: one factor at a time (OFAT) around [BASELINE],
 * followed by the two declared corner points (mkyb1-D1).
 *
 * The fan-out sweep deliberately uses [Shape.FAN_OUT_TREE] instead of the diamond
 * baseline because diamond ignores fan-out; that D16 deviation prevents four
 * nominal fan-out points from measuring the same graph. The D9 cardinality allowance
 * is multiplied by a message-volume scale: fan-out trees use their fan-out relative to
 * the baseline (rounded up), while replication uses one copy per full-mesh host. The
 * composed allowance is clamped to [Int.MAX_VALUE].
 */
object SweepPlan {
    val SHAPES: List<Shape> = Shape.entries.toList()
    val CARDINALITIES: List<Long> = listOf(1_000L, 10_000L, 100_000L, 1_000_000L)
    val BATCH_SIZES: List<Int> = listOf(1, 10, 100)
    val HOST_COUNTS: List<Int> = listOf(1, 2, 3, 5, 8)
    val FAN_OUTS: List<Int> = listOf(1, 4, 16, 64, 256)
    val REPLICATION: List<Boolean> = listOf(false, true)
    val DURABILITY: List<Boolean> = listOf(false, true)

    /** Seed 1 is absent from every entry in [KNOWN_FAILING_SEEDS] as of 2026-10-14. */
    const val SHAPE_SEED: Long = 1L

    /**
     * observed: `SweepRunner(PointBudget(Int.MAX_VALUE)).run(BASELINE)` used 63,166
     * SIM steps for 10,000 elements (6.3166 steps/element) on 2026-10-14. Command,
     * using the required temporary uncommitted test:
     * `./gradlew :bench:test --tests 'civictech.bench.macro.SweepPlanTest.measureBaselineStepsPerElement' --rerun --no-build-cache --no-daemon`.
     * D9 sets this factor to `4 * ceil(6.3166) = 28`.
     */
    const val STEPS_PER_ELEMENT: Long = 28L

    val TOPOLOGY: MeshTopology = MeshTopology.FULL_MESH
    const val DETERMINISM_CHECK: Boolean = true

    val BASELINE: Coordinate = Coordinate(
        shape = Shape.DIAMOND.name,
        shapeSeed = SHAPE_SEED,
        cardinality = 10_000L,
        batchSize = 10,
        hostCount = 3,
        fanOut = 4,
        replication = false,
        durability = false,
        drive = Drive.SIM,
    )

    private val PLAN: List<Coordinate> = buildList {
        add(BASELINE)
        SHAPES.filterNot { it == Shape.DIAMOND }.forEach { shape ->
            add(BASELINE.copy(shape = shape.name))
        }
        CARDINALITIES.filterNot { it == BASELINE.cardinality }.forEach { cardinality ->
            add(BASELINE.copy(cardinality = cardinality))
        }
        BATCH_SIZES.filterNot { it == BASELINE.batchSize }.forEach { batchSize ->
            add(BASELINE.copy(batchSize = batchSize))
        }
        HOST_COUNTS.filterNot { it == BASELINE.hostCount }.forEach { hostCount ->
            add(BASELINE.copy(hostCount = hostCount))
        }
        FAN_OUTS.filterNot { it == BASELINE.fanOut }.forEach { fanOut ->
            add(BASELINE.copy(shape = Shape.FAN_OUT_TREE.name, fanOut = fanOut))
        }
        REPLICATION.filterNot { it == BASELINE.replication }.forEach { replication ->
            add(BASELINE.copy(replication = replication))
        }
        DURABILITY.filterNot { it == BASELINE.durability }.forEach { durability ->
            add(BASELINE.copy(durability = durability))
        }
        add(BASELINE.copy(cardinality = CARDINALITIES.last(), hostCount = HOST_COUNTS.last()))
        add(
            BASELINE.copy(
                shape = Shape.FAN_OUT_TREE.name,
                hostCount = HOST_COUNTS.last(),
                fanOut = FAN_OUTS.last(),
            ),
        )
    }

    /** The 22 stable SIM coordinates in the declared execution order. */
    fun points(): List<Coordinate> = PLAN

    /**
     * The per-point step allowance, saturated at the runner's `Int` boundary.
     *
     * Cardinality remains the calibrated base workload. [Shape.FAN_OUT_TREE] emits one
     * branch per fan-out target, so its scale is `ceil(fanOut / baselineFanOut)`; other
     * shapes ignore fan-out. Replication on the full mesh sends the state to every host,
     * so its scale is [Coordinate.hostCount]. These independent message sources compose.
     */
    fun budgetFor(at: Coordinate): PointBudget {
        require(at.cardinality >= 0) { "cardinality must be non-negative" }
        require(at.fanOut > 0) { "fanOut must be positive" }
        require(at.hostCount > 0) { "hostCount must be positive" }
        val remaining = Int.MAX_VALUE.toLong() - SimulationController.DEFAULT_BUDGET
        val baselineFanOut = BASELINE.fanOut.toLong()
        val fanOutScale = if (at.shape == Shape.FAN_OUT_TREE.name) {
            (at.fanOut.toLong() + baselineFanOut - 1L) / baselineFanOut
        } else {
            1L
        }
        val replicationScale = if (at.replication) at.hostCount.toLong() else 1L
        val variableSteps = cappedProduct(
            remaining,
            STEPS_PER_ELEMENT,
            at.cardinality,
            fanOutScale,
            replicationScale,
        )
        val simSteps = SimulationController.DEFAULT_BUDGET + variableSteps.toInt()
        return PointBudget(simSteps = simSteps)
    }

    data class Omission(
        val what: String,
        val count: Int,
        val reason: String,
    )

    /** Declared but unexecuted spaces, each paired with its governing reason. */
    fun omissions(): List<Omission> = listOf(
        Omission(
            what = "declared cross-product cells not in the plan",
            count = declaredCellCount() - PLAN.size,
            reason = "OFAT design (mkyb1-D1)",
        ),
        Omission(
            what = ":wire host counts 2 and 3",
            count = 2,
            reason = "sibling feature, the real-transport cross-check",
        ),
        Omission(
            what = MeshTopology.STAR.name,
            count = 1,
            reason = "mkyb1-D8: not in the declared space",
        ),
        Omission(
            what = "${Drive.REAL.name} drive at each plan coordinate",
            count = PLAN.size,
            reason = "structural counts are SIM-derived ([BEN2-33]); calibration is the JMH benchmark",
        ),
    )

    /** Markdown copied into the macro sweep findings entry by the owning suite. */
    fun describe(): String = buildString {
        appendLine("### Sweep plan")
        appendLine()
        appendLine("- Baseline: point #1 — $BASELINE")
        appendDimension("Shapes", SHAPES) { coordinate, value -> coordinate.shape == value.name }
        appendDimension("Cardinalities", CARDINALITIES) { coordinate, value -> coordinate.cardinality == value }
        appendDimension("Batch sizes", BATCH_SIZES) { coordinate, value -> coordinate.batchSize == value }
        appendDimension("Host counts", HOST_COUNTS) { coordinate, value -> coordinate.hostCount == value }
        appendDimension("Fan-outs", FAN_OUTS) { coordinate, value -> coordinate.fanOut == value }
        appendDimension("Replication", REPLICATION) { coordinate, value -> coordinate.replication == value }
        appendDimension("Durability", DURABILITY) { coordinate, value -> coordinate.durability == value }
        appendLine("- Corners: point #${PLAN.size - 1} — ${PLAN[PLAN.size - 2]}; point #${PLAN.size} — ${PLAN.last()}")
        appendLine("- Topology: $TOPOLOGY")
        appendLine("- Determinism check: $DETERMINISM_CHECK for every SIM point")
        appendLine(
            "- Budget: ${SimulationController.DEFAULT_BUDGET} + $STEPS_PER_ELEMENT * cardinality * " +
                "message-volume scale (FAN_OUT_TREE ceil(fan-out / ${BASELINE.fanOut}); " +
                "replication copies = host count; otherwise 1), clamped to ${Int.MAX_VALUE} steps",
        )
        appendLine("- Shape seed: $SHAPE_SEED")
    }

    private fun declaredCellCount(): Int =
        SHAPES.size * CARDINALITIES.size * BATCH_SIZES.size * HOST_COUNTS.size *
            FAN_OUTS.size * REPLICATION.size * DURABILITY.size

    private fun cappedProduct(cap: Long, vararg factors: Long): Long {
        var product = 1L
        factors.forEach { factor ->
            if (factor == 0L) return 0L
            if (product > cap / factor) return cap
            product *= factor
        }
        return product
    }

    private fun <T> StringBuilder.appendDimension(
        name: String,
        values: List<T>,
        matches: (Coordinate, T) -> Boolean,
    ) {
        val rendered = values.joinToString { value ->
            val indices = PLAN.withIndex()
                .filter { (_, coordinate) -> matches(coordinate, value) }
                .joinToString(prefix = "[", postfix = "]") { (index, _) -> "#${index + 1}" }
            "$value $indices"
        }
        appendLine("- $name: $rendered")
    }
}
