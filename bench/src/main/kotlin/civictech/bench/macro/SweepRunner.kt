package civictech.bench.macro

import civictech.bench.Drive
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostSubject
import civictech.bench.cost.PointOutcome
import civictech.bench.macro.mesh.BenchMesh
import civictech.bench.macro.mesh.MeshBuild
import civictech.bench.macro.mesh.MeshTopology
import civictech.bench.macro.shape.GeneratedShape
import civictech.bench.macro.shape.Shape
import civictech.bench.macro.shape.ShapeSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.host.KeyedCells
import civictech.cell.host.SimulationController
import java.io.File

/** The per-point limits used by the simulated runner and the later real runner. */
data class PointBudget(
    val simSteps: Int = SimulationController.DEFAULT_BUDGET,
    val realMillis: Long = 30_000,
) {
    init {
        require(simSteps >= 0) { "simSteps must be non-negative" }
        require(realMillis >= 0) { "realMillis must be non-negative" }
    }
}

/** Test and measurement hook invoked after setup is excluded from the counters. */
fun interface PointHook {
    fun afterSetup(ctx: PointContext, attempt: Int)
}

/** Runtime context exposed to a [PointHook]. */
sealed interface PointContext {
    class Sim(val mesh: BenchMesh) : PointContext
}

/**
 * Drives one benchmark coordinate to exactly one [PointOutcome].
 *
 * A deterministic run keeps both successful attempts private until their counts
 * agree. If they disagree, [PointOutcome.NonDeterministic] is built and returned
 * directly; no intermediate [PointOutcome.Ok] is admitted or exposed. This matters
 * because `CostWriter` rejects an `Ok` followed by an exclusion at the same key.
 */
class SweepRunner(
    val budget: PointBudget = PointBudget(),
    val topology: MeshTopology = MeshTopology.FULL_MESH,
    val journalDir: File? = null,
    val determinismCheck: Boolean = true,
    val hook: PointHook = PointHook { _, _ -> },
) {
    fun run(at: Coordinate): PointOutcome {
        if (at.drive == Drive.REAL) {
            return PointOutcome.Unschedulable(
                at = at,
                subject = CostSubject.PROPAGATION,
                reason = "REAL drive not yet wired",
                observedLimit = "",
            )
        }
        require(!at.durability || journalDir != null) {
            "journalDir is required when durability is enabled"
        }
        require(at.cardinality >= 0) { "cardinality must be non-negative" }
        require(at.batchSize >= 1) { "batchSize must be at least 1" }

        val shape = Shape.valueOf(at.shape)
        val generated = ShapeSpec(
            shape = shape,
            seed = at.shapeSeed,
            cardinality = nodeCount(shape, at.fanOut),
            fanOut = at.fanOut,
            hostCount = at.hostCount,
        ).generate()

        val first = attempt(at, generated, attempt = 1)
        if (!determinismCheck || first !is PointOutcome.Ok) return first

        val second = attempt(at, generated, attempt = 2)
        if (second !is PointOutcome.Ok) return second
        return if (first.counts != second.counts) {
            PointOutcome.NonDeterministic(
                at = at,
                subject = CostSubject.PROPAGATION,
                first = first.counts,
                second = second.counts,
            )
        } else {
            first
        }
    }

    private fun attempt(
        at: Coordinate,
        generated: GeneratedShape,
        attempt: Int,
    ): PointOutcome {
        val journals = mutableListOf<CountingJournal>()
        val build = BenchMesh.build(
            hostCount = at.hostCount,
            seed = at.shapeSeed,
            topology = topology,
            journalFor = { peer ->
                if (!at.durability) {
                    null
                } else {
                    CountingJournal(
                        KeyedCells.hostJournal(
                            File(requireNotNull(journalDir), "attempt$attempt/peer$peer"),
                        )!!,
                    ).also(journals::add)
                }
            },
            replication = at.replication,
        )
        if (build is MeshBuild.Unschedulable) return unschedulableOutcome(at, build)
        val mesh = (build as MeshBuild.Built).mesh

        try {
            mesh.apply(generated.spec)
            mesh.runToIdle()
        } catch (error: IllegalArgumentException) {
            if (error.message.orEmpty().contains("has no loopback under")) {
                return PointOutcome.Unschedulable(
                    at = at,
                    subject = CostSubject.PROPAGATION,
                    reason = describe(error),
                    observedLimit = "topology=$topology",
                )
            }
            throw error
        } catch (_: IllegalStateException) {
            return PointOutcome.NonConvergent(
                at = at,
                subject = CostSubject.PROPAGATION,
                budget = "setup:simSteps=${SimulationController.DEFAULT_BUDGET}",
            )
        }

        val sourcePeers = sourcePeers(generated)
        val (counters, registration) = Counters.installOn(mesh.world, journals)
        val start = counters.snapshot()
        var simSteps = 0L
        var failure: Throwable? = null
        var exhausted = false
        try {
            hook.afterSetup(PointContext.Sim(mesh), attempt)

            var element = 0L
            batch@ while (element < at.cardinality) {
                val end = minOf(at.cardinality, element + at.batchSize.toLong())
                while (element < end) {
                    val source = generated.sources[(element % generated.sources.size).toInt()]
                    val peer = if (at.replication) 0 else sourcePeers.getValue(source)
                    mesh.sourceInlet(source, peer).add(element)
                    element++
                }

                while (simSteps < budget.simSteps.toLong()) {
                    if (!mesh.world.controller.step()) continue@batch
                    simSteps++
                }
                // The probe distinguishes quiescence exactly at the limit from
                // pending work. It deliberately does not contribute to a discarded
                // point's structural count.
                if (mesh.world.controller.step()) {
                    exhausted = true
                    break@batch
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            registration.close()
        }

        val deadLetters = mesh.deadLetters.map { it.toString() }
        return when {
            failure != null -> PointOutcome.Failed(
                at = at,
                subject = CostSubject.PROPAGATION,
                cause = describe(failure),
                deadLetters = deadLetters,
            )

            deadLetters.isNotEmpty() -> PointOutcome.Failed(
                at = at,
                subject = CostSubject.PROPAGATION,
                cause = "dead letter",
                deadLetters = deadLetters,
            )

            exhausted -> PointOutcome.NonConvergent(
                at = at,
                subject = CostSubject.PROPAGATION,
                budget = "simSteps=${budget.simSteps}",
            )

            else -> PointOutcome.Ok(
                at = at,
                subject = CostSubject.PROPAGATION,
                counts = counters.counts(start, simSteps),
                wall = null,
            )
        }
    }

    private fun sourcePeers(generated: GeneratedShape): Map<String, Int> =
        generated.spec.lowered()
            .filterIsInstance<SpawnStep>()
            .filter { it.handle in generated.sources }
            .associate { step ->
                val placement = requireNotNull(step.placement) {
                    "source '${step.handle}' has no placement"
                }
                step.handle to placement.removePrefix("host").toInt()
            }

    companion object {
        const val CHAIN_NODES = 8
        const val DIAMOND_NODES = 8

        fun nodeCount(shape: Shape, fanOut: Int): Int = when (shape) {
            Shape.CHAIN -> CHAIN_NODES
            Shape.DIAMOND -> DIAMOND_NODES
            Shape.FAN_OUT_TREE,
            Shape.FAN_IN_LATTICE,
            Shape.RANDOM_DAG,
            -> 1 + fanOut
        }
    }
}

internal fun unschedulableOutcome(
    at: Coordinate,
    build: MeshBuild.Unschedulable,
): PointOutcome.Unschedulable = PointOutcome.Unschedulable(
    at = at,
    subject = CostSubject.PROPAGATION,
    reason = build.reason,
    observedLimit = "peers=${build.observedLimit}",
)

private fun describe(error: Throwable): String =
    "${error.javaClass.name}: ${error.message}"
