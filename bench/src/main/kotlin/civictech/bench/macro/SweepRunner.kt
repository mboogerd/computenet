package civictech.bench.macro

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import civictech.bench.medianOf
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostSubject
import civictech.bench.cost.PointOutcome
import civictech.bench.cost.StructuralCounts
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
import java.util.concurrent.TimeUnit

/** The per-point limits used by the simulated and real runners. */
data class PointBudget(
    val simSteps: Int = SimulationController.DEFAULT_BUDGET,
    val realMillis: Long = 30_000,
) {
    init {
        require(simSteps >= 0) { "simSteps must be non-negative" }
        require(realMillis >= 0) { "realMillis must be non-negative" }
    }
}

/** Options for repeated wall-clock observations of one REAL point. */
data class RealOptions(
    val trials: Int = 3,
    val env: RunEnvironment,
) {
    init {
        require(trials >= 1) { "trials must be at least 1" }
    }
}

/** Test and measurement hook invoked after setup is excluded from the counters. */
fun interface PointHook {
    fun afterSetup(ctx: PointContext, attempt: Int)
}

/** Hook used to exercise actions that are drained while the graph is being applied. */
fun interface SetupHook {
    fun beforeApply(mesh: BenchMesh)
}

/** Runtime context exposed to a [PointHook]. */
sealed interface PointContext {
    class Sim(val mesh: BenchMesh) : PointContext
    class Real(val world: RealWorld) : PointContext
}

/**
 * Drives one benchmark coordinate to exactly one [PointOutcome].
 *
 * A deterministic run keeps both successful attempts private until their counts
 * agree. If they disagree, [PointOutcome.NonDeterministic] is built and returned
 * directly; no intermediate [PointOutcome.Ok] is admitted or exposed. This matters
 * because `CostWriter` rejects an `Ok` followed by an exclusion at the same key.
 *
 * REAL observations do not use [determinismCheck]: their structural counts are not
 * reproducible seed-for-seed. Their `BenchResult.dispersion` is the explicitly
 * stated equivalent half-range of the trial wall times, not JMH's reported error.
 */
class SweepRunner(
    val budget: PointBudget = PointBudget(),
    val topology: MeshTopology = MeshTopology.FULL_MESH,
    val journalDir: File? = null,
    val determinismCheck: Boolean = true,
    val real: RealOptions? = null,
    val hook: PointHook = PointHook { _, _ -> },
    val setupHook: SetupHook = SetupHook { },
) {
    fun run(at: Coordinate): PointOutcome {
        if (at.drive == Drive.REAL) {
            val options = requireNotNull(real) { "REAL drive needs RealOptions" }
            if (at.hostCount != 1 || at.replication) {
                return PointOutcome.Unschedulable(
                    at = at,
                    subject = CostSubject.PROPAGATION,
                    reason = "REAL drive is single-host: the mesh harness is SIM-only ([BEN2-13])",
                    observedLimit = "hostCount=1, replication=false",
                )
            }
            validateCoordinate(at)
            val generated = generatedShape(at)
            return runReal(at, generated, options)
        }
        validateCoordinate(at)
        val generated = generatedShape(at)

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

    private fun runReal(
        at: Coordinate,
        generated: GeneratedShape,
        options: RealOptions,
    ): PointOutcome {
        val trials = mutableListOf<RealTrial.Completed>()
        for (trial in 1..options.trials) {
            when (val result = realTrial(at, generated, trial)) {
                is RealTrial.Excluded -> return result.outcome
                is RealTrial.Completed -> trials += result
            }
        }

        val wallMillis = trials.map { it.wallMillis }
        return PointOutcome.Ok(
            at = at,
            subject = CostSubject.PROPAGATION,
            counts = StructuralCounts(
                messages = null,
                messagesNotMeasured = Counters.MESSAGES_NOT_MEASURED,
                crossHostFrames = 0,
                wireBytes = 0,
                journalRecords = trials.last().journalRecords,
                simSteps = 0,
            ),
            wall = BenchResult(
                value = medianOf(wallMillis),
                unit = "ms/point",
                dispersion = (wallMillis.max() - wallMillis.min()) / 2.0,
                drive = Drive.REAL,
                env = options.env,
            ),
        )
    }

    private fun realTrial(
        at: Coordinate,
        generated: GeneratedShape,
        trial: Int,
    ): RealTrial {
        val journal = if (!at.durability) {
            null
        } else {
            CountingJournal(
                KeyedCells.hostJournal(
                    File(requireNotNull(journalDir), "trial$trial/peer0"),
                )!!,
            )
        }
        val world = RealWorld(journal)
        try {
            try {
                world.apply(generated.spec)
            } catch (error: Throwable) {
                return RealTrial.Excluded(failed(at, error, world))
            }
            if (!world.awaitIdle(budget.realMillis)) {
                return RealTrial.Excluded(nonConvergent(at, setup = true))
            }

            val journalStart = journal?.appends?.get() ?: 0L
            try {
                hook.afterSetup(PointContext.Real(world), trial)
            } catch (error: Throwable) {
                return RealTrial.Excluded(failed(at, error, world))
            }

            val started = System.nanoTime()
            var element = 0L
            var exhausted = false
            try {
                while (element < at.cardinality) {
                    val end = minOf(at.cardinality, element + at.batchSize.toLong())
                    while (element < end) {
                        val source = generated.sources[(element % generated.sources.size).toInt()]
                        world.sourceInlet(source).add(element)
                        element++
                    }

                    val elapsedNanos = System.nanoTime() - started
                    val budgetNanos = TimeUnit.MILLISECONDS.toNanos(budget.realMillis)
                    val remainingMillis = if (elapsedNanos >= budgetNanos) {
                        0
                    } else {
                        TimeUnit.NANOSECONDS.toMillis(budgetNanos - elapsedNanos).coerceAtLeast(1)
                    }
                    if (!world.awaitIdle(remainingMillis)) {
                        exhausted = true
                        break
                    }
                }
            } catch (error: Throwable) {
                return RealTrial.Excluded(failed(at, error, world))
            }
            val wallMillis = (System.nanoTime() - started) / 1_000_000.0
            val deadLetters = world.deadLetters.map { it.toString() }
            val schedulerFailure = world.scheduler.failures.firstOrNull()
            val outcome = when {
                schedulerFailure != null -> PointOutcome.Failed(
                    at = at,
                    subject = CostSubject.PROPAGATION,
                    cause = describe(schedulerFailure),
                    deadLetters = deadLetters,
                )

                deadLetters.isNotEmpty() -> PointOutcome.Failed(
                    at = at,
                    subject = CostSubject.PROPAGATION,
                    cause = "dead letter",
                    deadLetters = deadLetters,
                )

                exhausted -> nonConvergent(at, setup = false)
                else -> null
            }
            return if (outcome != null) {
                RealTrial.Excluded(outcome)
            } else {
                RealTrial.Completed(
                    wallMillis = wallMillis,
                    journalRecords = (journal?.appends?.get() ?: 0L) - journalStart,
                )
            }
        } finally {
            world.close()
        }
    }

    private fun validateCoordinate(at: Coordinate) {
        require(!at.durability || journalDir != null) {
            "journalDir is required when durability is enabled"
        }
        require(at.cardinality >= 0) { "cardinality must be non-negative" }
        require(at.batchSize >= 1) { "batchSize must be at least 1" }
    }

    private fun generatedShape(at: Coordinate): GeneratedShape {
        val shape = Shape.valueOf(at.shape)
        return ShapeSpec(
            shape = shape,
            seed = at.shapeSeed,
            cardinality = nodeCount(shape, at.fanOut),
            fanOut = at.fanOut,
            hostCount = at.hostCount,
        ).generate()
    }

    private fun failed(at: Coordinate, error: Throwable, world: RealWorld): PointOutcome.Failed =
        failed(at, error, world.deadLetters.map { it.toString() })

    private fun failed(at: Coordinate, error: Throwable, mesh: BenchMesh): PointOutcome.Failed =
        failed(at, error, mesh.deadLetters.map { it.toString() })

    private fun failed(
        at: Coordinate,
        error: Throwable,
        deadLetters: List<String>,
    ): PointOutcome.Failed =
        PointOutcome.Failed(
            at = at,
            subject = CostSubject.PROPAGATION,
            cause = describe(error),
            deadLetters = deadLetters,
        )

    private fun nonConvergent(at: Coordinate, setup: Boolean): PointOutcome.NonConvergent =
        PointOutcome.NonConvergent(
            at = at,
            subject = CostSubject.PROPAGATION,
            budget = if (setup) {
                "setup:realMillis=${budget.realMillis}"
            } else {
                "realMillis=${budget.realMillis}"
            },
        )

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
            setupHook.beforeApply(mesh)
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
            return failed(at, error, mesh)
        } catch (error: Throwable) {
            return if (error is IllegalStateException &&
                error.message == setupBudgetExhaustionMessage()
            ) {
                PointOutcome.NonConvergent(
                    at = at,
                    subject = CostSubject.PROPAGATION,
                    budget = "setup:simSteps=${SimulationController.DEFAULT_BUDGET}",
                )
            } else {
                failed(at, error, mesh)
            }
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

    private sealed interface RealTrial {
        data class Completed(val wallMillis: Double, val journalRecords: Long) : RealTrial
        data class Excluded(val outcome: PointOutcome) : RealTrial
    }

    private fun setupBudgetExhaustionMessage(): String =
        "simulation did not quiesce within ${SimulationController.DEFAULT_BUDGET} steps — likely livelock"
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
