package civictech.bench.macro.rebaseline

import civictech.bench.Drive
import civictech.bench.cost.Coordinate
import civictech.bench.macro.PointBudget
import civictech.bench.macro.mesh.MeshTopology
import civictech.cell.host.SimulationController

/**
 * The declared G-43 RESTART re-baseline grid (j27rc-D10/D12/D19/D20).
 *
 * `Coordinate.shape` distinguishes local from remote taps here; these names are not
 * `civictech.bench.macro.shape.Shape` values. A coordinate from this plan must therefore
 * never be handed to `SweepRunner`, whose generated-shape path calls `Shape.valueOf`.
 *
 * The measured drive emits one delta containing the whole state, so its step allowance
 * scales with tap deliveries and mesh routing rather than with cardinality.
 */
object ReBaselinePlan {
    val CARDINALITIES: List<Long> = listOf(1_000L, 10_000L, 100_000L)
    val FAN_OUTS: List<Int> = listOf(1, 4, 16, 64, 256)
    val HOST_VARIANTS: List<HostVariant> = listOf(
        HostVariant(1, ReBaselineProbe.LOCAL_TAPS),
        HostVariant(3, ReBaselineProbe.LOCAL_TAPS),
        HostVariant(3, ReBaselineProbe.REMOTE_TAPS),
        HostVariant(8, ReBaselineProbe.LOCAL_TAPS),
        HostVariant(8, ReBaselineProbe.REMOTE_TAPS),
    )

    /**
     * observed: `measureRemoteBaselineStepsPerTap` ran
     * `ReBaselineProbe.run(coordinate(REMOTE_TAPS, 1_000, 4, 3),
     * PointBudget(Int.MAX_VALUE), determinismCheck = false)` and used 10 SIM steps on
     * 2026-10-15. Command, using the required temporary uncommitted test:
     * `./gradlew :bench:test -PbenchOnly=true --tests
     * 'civictech.bench.macro.rebaseline.MacroReBaselineTest.measureRemoteBaselineStepsPerTap'
     * --rerun --no-build-cache --no-daemon`. j27rc-D12 sets the factor to
     * `4 * ceil(10 / (4 * 3)) = 4`.
     */
    const val STEPS_PER_TAP: Long = 4L

    const val TIMEOUT_MINUTES: Long = 30L
    const val SHAPE_SEED: Long = 0L
    const val DETERMINISM_CHECK: Boolean = true
    val TOPOLOGY: MeshTopology = MeshTopology.FULL_MESH

    private val PLAN: List<Coordinate> = CARDINALITIES.flatMap { cardinality ->
        FAN_OUTS.flatMap { fanOut ->
            HOST_VARIANTS.map { variant ->
                ReBaselineProbe.coordinate(
                    variant = variant.variant,
                    cardinality = cardinality,
                    fanOut = fanOut,
                    hostCount = variant.hostCount,
                )
            }
        }
    }.also { plan ->
        check(plan.size == 75) { "re-baseline grid must contain 75 points, found ${plan.size}" }
    }

    /** The 75 stable SIM coordinates in cardinality -> fan-out -> host/variant order. */
    fun points(): List<Coordinate> = PLAN

    /** The measured-drive allowance, saturated at [PointBudget]'s `Int` boundary. */
    fun budgetFor(at: Coordinate): PointBudget {
        require(at.fanOut > 0) { "fanOut must be positive" }
        require(at.hostCount > 0) { "hostCount must be positive" }
        val remaining = Int.MAX_VALUE.toLong() - SimulationController.DEFAULT_BUDGET
        val variableSteps = cappedProduct(
            remaining,
            STEPS_PER_TAP,
            at.fanOut.toLong(),
            at.hostCount.toLong(),
        )
        return PointBudget(
            simSteps = SimulationController.DEFAULT_BUDGET + variableSteps.toInt(),
        )
    }

    data class HostVariant(
        val hostCount: Int,
        val variant: String,
    ) {
        override fun toString(): String = "H=$hostCount $variant"
    }

    data class Omission(
        val what: String,
        val count: Int,
        val reason: String,
    )

    /** Declared but unexecuted comparisons, each paired with its governing reason. */
    fun omissions(): List<Omission> = listOf(
        Omission(
            what = "shadow-promotion comparison point",
            count = 1,
            reason = "j27rc-D20: promotion drives a different lifecycle and is not comparable to the RESTART grid",
        ),
        Omission(
            what = "${Drive.REAL} drive at each grid coordinate",
            count = PLAN.size,
            reason = "SIM supplies structural counts ([BEN2-33]); the REAL JMH companion measures H=1 wall time (j27rc-D16)",
        ),
        Omission(
            what = "declared host counts H in {2, 5}",
            count = 2,
            reason = "the G-43 grid is H in {1, 3, 8} (j27rc-D2)",
        ),
        Omission(
            what = "replication-mode RESTART grid points",
            count = PLAN.size,
            reason = "replication-mode RESTART is G-43's open kernel-lane question, not this structural probe",
        ),
    )

    /** Markdown copied into the SIM findings entry by [MacroReBaselineTest]. */
    fun describe(): String = buildString {
        appendLine("### Plan")
        appendLine()
        appendLine("- Cardinalities: ${CARDINALITIES.joinToString()}")
        appendLine("- Fan-outs: ${FAN_OUTS.joinToString()}")
        appendLine("- Host/variant crossings: ${HOST_VARIANTS.joinToString()}")
        appendLine("- Executed combinations: ${CARDINALITIES.size} × ${FAN_OUTS.size} × ${HOST_VARIANTS.size} = ${PLAN.size}")
        appendLine("- Nesting: cardinality -> fan-out -> host/variant")
        appendLine("- Coordinate seed: $SHAPE_SEED; batchSize=1; replication=false; durability=false; drive=${Drive.SIM}")
        appendLine("- Topology: $TOPOLOGY")
        appendLine("- Determinism check: $DETERMINISM_CHECK for every point")
        appendLine(
            "- Budget: ${SimulationController.DEFAULT_BUDGET} + $STEPS_PER_TAP * fan-out * " +
                "host count, clamped to ${Int.MAX_VALUE} SIM steps",
        )
        appendLine("- Per-invocation timeout: $TIMEOUT_MINUTES minutes")
    }

    private fun cappedProduct(cap: Long, vararg factors: Long): Long {
        var product = 1L
        factors.forEach { factor ->
            if (factor == 0L) return 0L
            if (product > cap / factor) return cap
            product *= factor
        }
        return product
    }
}
