package civictech.bench.macro.journal

import civictech.bench.Drive
import civictech.bench.cost.Coordinate
import civictech.bench.cost.CostSubject
import civictech.bench.cost.PointOutcome
import civictech.bench.macro.CountingJournal
import civictech.bench.macro.PointBudget
import civictech.bench.macro.PointContext
import civictech.bench.macro.PointHook
import civictech.bench.macro.RealWorld
import civictech.bench.macro.SweepRunner
import civictech.bench.macro.mesh.BenchMesh
import civictech.bench.macro.mesh.MeshBuild
import civictech.bench.macro.mesh.MeshRefs
import civictech.bench.macro.mesh.MeshTopology
import civictech.bench.macro.shape.GeneratedShape
import civictech.bench.macro.shape.Shape
import civictech.bench.macro.shape.ShapeSpec
import civictech.bench.micro.HeapProbe
import civictech.cell.BoundedStateful
import civictech.cell.StatePage
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import civictech.cell.data.op.TaggedEntry
import civictech.cell.durability.FileJournal
import civictech.cell.durability.Journal
import civictech.cell.host.SimulationController
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One classified journal point and its one-file measurements (`[BEN2-37]`,
 * BS-14/BS-15, 9f7x5-D6..D9/D16).
 *
 * [records] counts the entire materialized file, including setup records;
 * `PointOutcome.Ok.counts.journalRecords` is the [SweepRunner] counter delta
 * after setup. [retainedBytes] is the live heap retained by the materialized
 * `replay()` list — the whole-file cost named by G-25. Nullable measurements
 * are exposed only when [outcome] is [PointOutcome.Ok].
 */
data class JournalMeasurement(
    val outcome: PointOutcome,
    /** The probe stage that produced the outcome, not a cause-text guess. */
    val stage: String,
    /** Whether the probe completed replay and the recovery fold check. */
    val replayCompleted: Boolean,
    val recoverCompleted: Boolean,
    /** True when the 2 GiB executor defers this coordinate to recorded fork evidence. */
    val inProcessDeferral: Boolean = false,
    val fileBytes: Long?,
    val secondAttemptFileBytes: Long?,
    val records: Long?,
    val appended: Long?,
    val bytesPerRecord: Double?,
    val retainedBytes: Long?,
    val maxHeapBytes: Long,
    val checkpointBytes: Long?,
    val recordsAfterCheckpoint: Long?,
) {
    init {
        require(maxHeapBytes > 0) { "maxHeapBytes must be positive" }
        require(stage.isNotBlank()) { "stage must not be blank" }
        require(!inProcessDeferral || stage == "forked-evidence") {
            "in-process deferrals must use the forked-evidence stage"
        }
        val requiredForOk = listOf(
            fileBytes,
            records,
            appended,
            bytesPerRecord,
            retainedBytes,
            checkpointBytes,
            recordsAfterCheckpoint,
        )
        require(
            if (outcome is PointOutcome.Ok) {
                requiredForOk.all { it != null }
            } else {
                requiredForOk.all { it == null } && secondAttemptFileBytes == null
            },
        ) { "journal figures must be present exactly for Ok outcomes" }
        require(!recoverCompleted || replayCompleted) {
            "recovery cannot complete before replay"
        }
    }
}

/**
 * Measures a durable one-host SIM point through the unchanged macro runner
 * (`[BEN2-37]`, BS-14/BS-15, 9f7x5-D6..D9/D16).
 *
 * The runner's mesh builder is wrapped solely to retain the exact
 * [CountingJournal] instance installed on the host. Checkpoint selection is by
 * journal identity, so reconstructing a [FileJournal] for the same path would
 * test the kernel's refusal path instead of checkpointing the measured host.
 * The whole-file [JournalMeasurement.records] and replay-retained bytes include
 * setup; the `Ok` structural counter remains the runner's post-setup delta.
 */
object JournalProbe {
    /** Run with the production file-backed recovery journal. */
    fun run(
        at: Coordinate,
        journalDir: File,
        budget: PointBudget = JournalPlan.budgetFor(at),
        determinismCheck: Boolean = JournalPlan.DETERMINISM_CHECK,
    ): JournalMeasurement = run(
        at = at,
        journalDir = journalDir,
        budget = budget,
        determinismCheck = determinismCheck,
        recoveryJournal = ::FileJournal,
    )

    /**
     * Executes a point that the in-process plan omits, in a bounded fork whose heap
     * is isolated from the Gradle test executor. The caller owns that process bound;
     * [stageObserver] exposes the probe's current stage so a killed fork is not
     * diagnosed from an exception-message prefix.
     */
    internal fun runForked(
        at: Coordinate,
        journalDir: File,
        budget: PointBudget = JournalPlan.budgetFor(at),
        determinismCheck: Boolean = JournalPlan.DETERMINISM_CHECK,
        stageObserver: (String) -> Unit = {},
    ): JournalMeasurement = run(
        at = at,
        journalDir = journalDir,
        budget = budget,
        determinismCheck = determinismCheck,
        recoveryJournal = ::FileJournal,
        honorDeclaredOmission = false,
        stageObserver = stageObserver,
    )

    /**
     * Test seams for proving recovery classification and that both recovery drains receive
     * the point-specific SIM budget rather than [SimulationController.DEFAULT_BUDGET].
     */
    internal fun run(
        at: Coordinate,
        journalDir: File,
        budget: PointBudget = JournalPlan.budgetFor(at),
        determinismCheck: Boolean = JournalPlan.DETERMINISM_CHECK,
        recoveryJournal: (File) -> Journal,
        recoveryDrain: (BenchMesh, Int) -> Unit = { mesh, simSteps -> mesh.runToIdle(simSteps) },
        honorDeclaredOmission: Boolean = true,
        stageObserver: (String) -> Unit = {},
    ): JournalMeasurement {
        require(at.durability) { "journal probe requires durability=true" }
        require(at.hostCount == 1) { "journal probe requires hostCount=1" }
        require(at.drive == Drive.SIM) { "journal probe requires SIM drive" }

        val maxHeapBytes = Runtime.getRuntime().maxMemory()
        if (honorDeclaredOmission) {
            JournalPlan.inProcessDeferralFor(at)?.let { omission ->
                return deferredToFork(at, omission, maxHeapBytes)
            }
        }
        val generated = generatedShape(at)
        val meshes = mutableMapOf<Int, BenchMesh>()
        val journals = mutableMapOf<Int, MutableMap<Int, CountingJournal>>()
        var buildAttempt = 0
        var sweepStage = "setup"

        val runner = SweepRunner(
            budget = budget,
            topology = MeshTopology.FULL_MESH,
            journalDir = journalDir,
            determinismCheck = determinismCheck,
            hook = PointHook { context, attempt ->
                sweepStage = "drive"
                stageObserver(sweepStage)
                val mesh = (context as? PointContext.Sim)?.mesh
                    ?: error("journal probe requires SweepRunner's SIM context")
                meshes[attempt] = mesh
            },
            meshBuilder = { hostCount, seed, topology, journalFor, replication ->
                sweepStage = "setup"
                stageObserver(sweepStage)
                val attempt = ++buildAttempt
                BenchMesh.build(
                    hostCount = hostCount,
                    seed = seed,
                    topology = topology,
                    journalFor = { peer ->
                        journalFor(peer)?.also { journal ->
                            check(journal is CountingJournal) {
                                "SweepRunner installed ${journal.javaClass.name}, expected CountingJournal"
                            }
                            journals.getOrPut(attempt) { mutableMapOf() }[peer] = journal
                        }
                    },
                    replication = replication,
                )
            },
        )

        val outcome = try {
            runner.run(at).reSubjected()
        } catch (error: OutOfMemoryError) {
            // SweepRunner classifies OOMs from setup/drive, but a mesh builder can exhaust
            // the heap before its attempt-level catch is entered. Keep the declared point
            // in the export as an honest SIM exclusion instead of letting the JUnit executor
            // die and losing the rest of the grid.
            return excluded(
                unschedulable(at, sweepStage, error, maxHeapBytes),
                maxHeapBytes,
                stage = sweepStage,
            )
        }
        if (outcome !is PointOutcome.Ok) {
            return excluded(outcome, maxHeapBytes, stage = sweepStage)
        }

        stageObserver("replay")
        val file = File(journalDir, "attempt1/peer0/host.journal")
        val secondFile = File(journalDir, "attempt2/peer0/host.journal")
        val firstMesh: BenchMesh
        val firstJournal: CountingJournal
        val fileBytes: Long
        val secondAttemptFileBytes: Long?
        val records: Long
        val appended: Long
        val bytesPerRecord: Double
        try {
            firstMesh = checkNotNull(meshes[1]) { "SweepRunner did not expose attempt 1's mesh" }
            firstJournal = checkNotNull(journals[1]?.get(0)) {
                "SweepRunner did not expose attempt 1 peer 0's CountingJournal"
            }
            fileBytes = file.length()
            secondAttemptFileBytes = if (determinismCheck) {
                check(secondFile.isFile) { "determinism attempt 2 did not write ${secondFile.path}" }
                secondFile.length()
            } else {
                null
            }
            records = FileJournal(file).replay().size.toLong()
            appended = firstJournal.appends.get()
            check(records > 0L) { "journal replay returned no records" }
            bytesPerRecord = fileBytes.toDouble() / records.toDouble()
        } catch (error: OutOfMemoryError) {
            return excluded(
                unschedulable(at, "replay", error, maxHeapBytes),
                maxHeapBytes,
                stage = "replay",
            )
        } catch (error: Throwable) {
            return excluded(
                failed(at, error, meshes.values),
                maxHeapBytes,
                stage = "replay",
            )
        }

        // SweepRunner keeps no attempt after run() returns. Removing our second-attempt
        // captures before the heap probe ensures retainedBytes measures replay(), not a
        // second live mesh retained by this wrapper.
        meshes.remove(2)
        journals.remove(2)

        stageObserver("retained")
        val retainedBytes = try {
            HeapProbe.requireCollectableHeap()
            HeapProbe.retainedBytes { FileJournal(file).replay() }
        } catch (error: OutOfMemoryError) {
            return excluded(
                unschedulable(at, "retained", error, maxHeapBytes),
                maxHeapBytes,
                stage = "retained",
                replayCompleted = true,
            )
        } catch (error: Throwable) {
            return excluded(
                failed(at, error, meshes.values),
                maxHeapBytes,
                stage = "retained",
                replayCompleted = true,
            )
        }

        var recoveredMesh: BenchMesh? = null
        var recoverCompleted = false
        stageObserver("recover")
        try {
            val originalFold = sinkFold(firstMesh, generated.sinks)
            lateinit var installedRecoveryJournal: Journal
            val recoveredBuild = BenchMesh.build(
                hostCount = 1,
                seed = at.shapeSeed,
                topology = MeshTopology.FULL_MESH,
                journalFor = {
                    recoveryJournal(file).also { installedRecoveryJournal = it }
                },
                replication = false,
            )
            if (recoveredBuild is MeshBuild.Unschedulable) {
                val isOutOfMemory = recoveredBuild.reason.contains(OutOfMemoryError::class.java.name)
                return excluded(
                    PointOutcome.Unschedulable(
                        at = at,
                        subject = CostSubject.JOURNAL,
                        reason = "recover: ${recoveredBuild.reason}",
                        observedLimit = if (isOutOfMemory) {
                            "maxHeap=$maxHeapBytes"
                        } else {
                            "peers=${recoveredBuild.observedLimit}"
                        },
                    ),
                    maxHeapBytes,
                    stage = "recover",
                    replayCompleted = true,
                )
            }
            recoveredMesh = (recoveredBuild as MeshBuild.Built).mesh
            recoveredMesh.apply(generated.spec)
            recoveryDrain(recoveredMesh, budget.simSteps)
            val recovery = recoveredMesh.peers[0].host.recoverFrom(installedRecoveryJournal)
            recoveryDrain(recoveredMesh, budget.simSteps)

            val deadLetters = (firstMesh.deadLetters + recoveredMesh.deadLetters).map { it.toString() }
            if (deadLetters.isNotEmpty()) {
                return excluded(
                    PointOutcome.Failed(at, CostSubject.JOURNAL, "dead letter", deadLetters),
                    maxHeapBytes,
                    stage = "recover",
                    replayCompleted = true,
                )
            }

            if (recovery.replayedFrames == 0) {
                return excluded(
                    PointOutcome.Failed(
                        at = at,
                        subject = CostSubject.JOURNAL,
                        cause = "recovery restored no journal frames",
                        deadLetters = emptyList(),
                    ),
                    maxHeapBytes,
                    stage = "recover",
                    replayCompleted = true,
                )
            }

            val recoveredFold = sinkFold(recoveredMesh, generated.sinks)
            val mismatches = generated.sinks.count { originalFold[it] != recoveredFold[it] }
            if (mismatches != 0) {
                return excluded(
                    PointOutcome.Failed(
                        at = at,
                        subject = CostSubject.JOURNAL,
                        cause = "recovery fold mismatch: $mismatches of ${generated.sinks.size} sinks differ",
                        deadLetters = emptyList(),
                    ),
                    maxHeapBytes,
                    stage = "recover",
                    replayCompleted = true,
                )
            }
            recoverCompleted = true
        } catch (error: OutOfMemoryError) {
            return excluded(
                unschedulable(at, "recover", error, maxHeapBytes),
                maxHeapBytes,
                stage = "recover",
                replayCompleted = true,
            )
        } catch (error: Throwable) {
            return excluded(
                failed(at, error, listOfNotNull(firstMesh, recoveredMesh)),
                maxHeapBytes,
                stage = "recover",
                replayCompleted = true,
            )
        }

        val checkpointBytes: Long
        val recordsAfterCheckpoint: Long
        stageObserver("checkpoint")
        try {
            firstMesh.peers[0].host.checkpoint(firstJournal)
            checkpointBytes = file.length()
            recordsAfterCheckpoint = FileJournal(file).replay().size.toLong()
        } catch (error: OutOfMemoryError) {
            return excluded(
                unschedulable(at, "checkpoint", error, maxHeapBytes),
                maxHeapBytes,
                stage = "checkpoint",
                replayCompleted = true,
                recoverCompleted = recoverCompleted,
            )
        } catch (error: Throwable) {
            return excluded(
                failed(at, error, listOfNotNull(firstMesh, recoveredMesh)),
                maxHeapBytes,
                stage = "checkpoint",
                replayCompleted = true,
                recoverCompleted = recoverCompleted,
            )
        }

        stageObserver("complete")
        return JournalMeasurement(
            outcome = outcome,
            stage = "complete",
            replayCompleted = true,
            recoverCompleted = recoverCompleted,
            fileBytes = fileBytes,
            secondAttemptFileBytes = secondAttemptFileBytes,
            records = records,
            appended = appended,
            bytesPerRecord = bytesPerRecord,
            retainedBytes = retainedBytes,
            maxHeapBytes = maxHeapBytes,
            checkpointBytes = checkpointBytes,
            recordsAfterCheckpoint = recordsAfterCheckpoint,
        )
    }
}

/**
 * A single-host production-scheduler rig for the REAL half of the journal study
 * (`[BEN2-37]`, BS-14/BS-15, 9f7x5-D6..D9/D16).
 *
 * The rig accepts any [Journal], including no journal, so BS-15 can compare the
 * same graph and drive under file-backed and in-memory persistence. Recovery
 * uses the very [FileJournal] instance installed on the fresh host; checkpoint
 * likewise receives the caller's installed instance, preserving D8's identity
 * rule. Each batch waits on the instrumented scheduler's completion notification,
 * not a sampling interval, so the REAL wall time is drive + scheduler idle rather
 * than drive + harness polling. Fold reads page the sink's whole bounded state
 * instead of taking an unbounded snapshot; see [SinkFold].
 */
class JournalRealRig(
    val at: Coordinate,
    journal: Journal?,
    val generated: GeneratedShape = generatedShape(at),
) : AutoCloseable {
    val world: RealWorld = RealWorld(journal)

    private var graphApplied = false

    init {
        require(at.hostCount == 1) { "journal REAL rig requires hostCount=1" }
        require(!at.replication) { "journal REAL rig does not enable replication" }
        require(at.cardinality >= 0) { "cardinality must be non-negative" }
        require(at.batchSize >= 1) { "batchSize must be at least 1" }
    }

    /** Apply the generated graph and wait for setup to become idle. */
    fun applyGraph() {
        check(!graphApplied) { "JournalRealRig.applyGraph may only be called once" }
        world.apply(generated.spec)
        graphApplied = true
        val setupMillis = JournalPlan.budgetFor(at).realMillis
        check(world.awaitIdle(setupMillis)) {
            "real setup did not quiesce within ${setupMillis}ms"
        }
        requireHealthy()
    }

    /**
     * Drive exactly [Coordinate.cardinality] elements in the declared batches,
     * waiting for scheduler completion after each batch without a polling floor.
     */
    fun drive(realMillis: Long = 30_000): Long {
        check(graphApplied) { "applyGraph() must run before drive()" }
        require(realMillis >= 0) { "realMillis must be non-negative" }

        val started = System.nanoTime()
        var element = 0L
        while (element < at.cardinality) {
            val end = minOf(at.cardinality, element + at.batchSize.toLong())
            while (element < end) {
                val source = generated.sources[(element % generated.sources.size).toInt()]
                world.sourceInlet(source).add(element)
                element++
            }

            val elapsedNanos = System.nanoTime() - started
            val budgetNanos = TimeUnit.MILLISECONDS.toNanos(realMillis)
            val remainingMillis = if (elapsedNanos >= budgetNanos) {
                0L
            } else {
                TimeUnit.NANOSECONDS.toMillis(budgetNanos - elapsedNanos).coerceAtLeast(1L)
            }
            check(world.awaitIdle(remainingMillis)) {
                "real drive did not quiesce within ${realMillis}ms"
            }
            requireHealthy()
        }
        return System.nanoTime() - started
    }

    /** Checkpoint through the journal instance installed on this rig's host. */
    fun checkpoint(journal: Journal) {
        check(graphApplied) { "applyGraph() must run before checkpoint()" }
        world.host.checkpoint(journal)
        requireHealthy()
    }

    /** Read every generated sink's state fold. */
    fun sinkFold(): SinkFold {
        check(graphApplied) { "applyGraph() must run before sinkFold()" }
        requireHealthy()
        return generated.sinks.associateWith { handle ->
            val ref = MeshRefs.ref(handle, 0, 1)
            foldEntries { request ->
                // KRD-27 recorded exception: a verification read of one local host's
                // sink after the drive (or the recovery) has quiesced, compared whole
                // against the other rig's fold; it is not a consumer walk, so it does
                // not route through walkRouted.
                when (val result = world.host.readState(ref, request).get(30, TimeUnit.SECONDS)) {
                    is StateReadResult.Page -> result.page
                    is StateReadResult.Unavailable -> error(
                        "sink '$handle' state unavailable: ${result.reason}",
                    )
                    is StateReadResult.Unbounded -> error("sink '$handle' returned an unbounded state")
                }
            }
        }
    }

    /** Recover the same graph into a fresh REAL host over [file]. */
    fun recoverInto(file: File, timeoutMs: Long = 30_000): JournalRealRig {
        val recoveryJournal = FileJournal(file)
        val recovered = JournalRealRig(at, recoveryJournal, generated)
        try {
            recovered.applyGraph()
            val recovery = recovered.world.host.recoverFrom(recoveryJournal)
            recovery.awaitApplied(timeoutMs)
            check(recovery.replayedFrames > 0) { "recovery restored no journal frames" }
            recovered.requireHealthy()
            return recovered
        } catch (error: Throwable) {
            recovered.close()
            throw error
        }
    }

    override fun close() {
        world.close()
    }

    private fun requireHealthy() {
        world.scheduler.failures.firstOrNull()?.let { failure ->
            throw IllegalStateException("REAL scheduler failure: ${describe(failure)}", failure)
        }
        check(world.deadLetters.isEmpty()) {
            "REAL journal rig emitted dead letters: ${world.deadLetters.joinToString()}"
        }
    }
}

/**
 * Sink handle -> (sub-state, element) -> entry multiplicity, over every page of
 * the sink's bounded state (9f7x5-D8 c).
 *
 * Every sub-state is kept: the DIAMOND sink is a binary operator whose state is
 * `left`/`right`/`ledger` entries, so filtering to one sub-state (as the
 * `"live"` membership read in MeshApplyTest does for a set-like operator)
 * reads an empty fold on both sides and makes the recovery comparison vacuous.
 * Tags are dropped; the comparison is of content, not of tag identity.
 */
typealias SinkFold = Map<String, Map<Pair<String, Any?>, Int>>

private const val SINK_PAGE_LIMIT = 1_000_000

private fun generatedShape(at: Coordinate): GeneratedShape {
    val shape = Shape.valueOf(at.shape)
    return ShapeSpec(
        shape = shape,
        seed = at.shapeSeed,
        cardinality = SweepRunner.nodeCount(shape, at.fanOut),
        fanOut = at.fanOut,
        hostCount = at.hostCount,
    ).generate()
}

private fun sinkFold(mesh: BenchMesh, sinks: List<String>): SinkFold =
    sinks.associateWith { handle ->
        val cell = mesh.cell(handle)
        check(cell is BoundedStateful) {
            "sink '$handle' is ${cell.javaClass.name}, not BoundedStateful"
        }
        foldEntries(cell::readBounded)
    }

private fun foldEntries(read: (StateRead) -> StatePage): Map<Pair<String, Any?>, Int> {
    val counts = linkedMapOf<Pair<String, Any?>, Int>()
    var request = StateRead(limit = SINK_PAGE_LIMIT, byteBudget = Int.MAX_VALUE)
    while (true) {
        val page = read(request)
        page.entries.forEach { entry ->
            val key = if (entry is TaggedEntry) entry.subState to entry.element else entry.javaClass.name to entry
            counts.merge(key, 1, Int::plus)
        }
        val next = page.next ?: break
        request = request.copy(cursor = next)
    }
    return counts
}

private fun PointOutcome.reSubjected(): PointOutcome = when (this) {
    is PointOutcome.Ok -> copy(subject = CostSubject.JOURNAL)
    is PointOutcome.Unreportable -> copy(subject = CostSubject.JOURNAL)
    is PointOutcome.NonConvergent -> copy(subject = CostSubject.JOURNAL)
    is PointOutcome.NonDeterministic -> copy(subject = CostSubject.JOURNAL)
    is PointOutcome.Unschedulable -> copy(subject = CostSubject.JOURNAL)
    is PointOutcome.Failed -> copy(subject = CostSubject.JOURNAL)
}

private fun excluded(
    outcome: PointOutcome,
    maxHeapBytes: Long,
    stage: String,
    replayCompleted: Boolean = false,
    recoverCompleted: Boolean = false,
): JournalMeasurement =
    JournalMeasurement(
        outcome = outcome,
        stage = stage,
        replayCompleted = replayCompleted,
        recoverCompleted = recoverCompleted,
        fileBytes = null,
        secondAttemptFileBytes = null,
        records = null,
        appended = null,
        bytesPerRecord = null,
        retainedBytes = null,
        maxHeapBytes = maxHeapBytes,
        checkpointBytes = null,
        recordsAfterCheckpoint = null,
    )

private fun deferredToFork(
    at: Coordinate,
    omission: JournalPlan.Omission,
    maxHeapBytes: Long,
): JournalMeasurement = JournalMeasurement(
    outcome = PointOutcome.Unschedulable(
        at = at,
        subject = CostSubject.JOURNAL,
        reason = omission.reason,
        observedLimit = "",
    ),
    stage = "forked-evidence",
    replayCompleted = false,
    recoverCompleted = false,
    inProcessDeferral = true,
    fileBytes = null,
    secondAttemptFileBytes = null,
    records = null,
    appended = null,
    bytesPerRecord = null,
    retainedBytes = null,
    maxHeapBytes = maxHeapBytes,
    checkpointBytes = null,
    recordsAfterCheckpoint = null,
)

private fun unschedulable(
    at: Coordinate,
    stage: String,
    error: OutOfMemoryError,
    maxHeapBytes: Long,
): PointOutcome.Unschedulable = PointOutcome.Unschedulable(
    at = at,
    subject = CostSubject.JOURNAL,
    reason = "$stage: ${describe(error)}",
    observedLimit = "maxHeap=$maxHeapBytes",
)

private fun failed(
    at: Coordinate,
    error: Throwable,
    meshes: Collection<BenchMesh>,
): PointOutcome.Failed = PointOutcome.Failed(
    at = at,
    subject = CostSubject.JOURNAL,
    cause = describe(error),
    deadLetters = meshes.flatMap { mesh -> mesh.deadLetters.map { it.toString() } },
)

private fun describe(error: Throwable): String =
    "${error.javaClass.name}: ${error.message}"
