package civictech.timetravel.reconstruct

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.HostScheduler
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.timetravel.fidelity.Fidelity
import civictech.timetravel.fidelity.Reason
import civictech.timetravel.fidelity.ReplayStable
import civictech.timetravel.fidelity.journalDefectsUpTo
import civictech.timetravel.fidelity.rollUp
import civictech.timetravel.fidelity.worst
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline
import java.io.Serializable
import java.util.concurrent.CompletableFuture
import civictech.cell.host.RecoveryIncomplete as KernelRecoveryIncomplete

/**
 * The outcome of [Reconstructor.of]: a usable [Ready] reconstructor, or a [Refusal] naming why
 * none can be built (computenet-6tm33 D12).
 */
sealed interface ReconstructorResult {
    data class Ready(val reconstructor: Reconstructor) : ReconstructorResult

    data class Refusal(val reason: Reason, val message: String) : ReconstructorResult
}

/**
 * Rebuilds a run's cell state at a [Position] offline, by replaying the resolved journal prefix
 * through the kernel's own recovery path (TTD1 F4, computenet-6tm33 D1/D2/D4, 6tm33-D6..D12;
 * epic computenet-ocv §3 "Reconstruction reuses the recovery path"; `[TTD1-20]`..`[TTD1-25]`,
 * `[TTD1-32]`).
 *
 * Every [stateAt] builds a **fresh** reconstruction host from [graph], feeds it
 * `reading.rawRecords[timeline.journalId][anchor until n]` through [ManagedHost.recoverFrom] —
 * the same decode, checkpoint restore and dedup the live recovery path runs, never a second copy
 * of it — drains it, reads every cell's snapshot, and tears the host down. `:timetravel` decodes
 * no frame and restores no checkpoint itself (`[TTD1-20]`, `[24-DUR-02]` made true offline).
 *
 * The host is `ManagedHost(scheduler, registry, journalFor = { DiscardingJournal })` with no
 * `journal` (6tm33-D9): a non-null selector makes the kernel install each outlet's ref-derived
 * durable epoch, as the live recovery does, while nothing is written anywhere.
 *
 * `open`, with [stateAt] final and composed of `protected` steps ([openSession], [replayInto],
 * [observe], [close]) so F5's cursor and suppression can extend it without re-deriving it
 * (6tm33-D12). [onBuilt] is F5's hook; this feature leaves it a no-op.
 *
 * Nothing is caught except the kernel's `RecoveryIncomplete` in [replayInto]: a [GraphSource]
 * that throws, or a `Stateful` cell whose `snapshot()` fails, propagates out of [stateAt].
 */
open class Reconstructor(
    private val reading: JournalReading,
    private val timeline: RunTimeline,
    private val graph: GraphSource,
    private val seed: Long = 0,
    private val replayStable: ReplayStable = ReplayStable.DEFAULT,
    private val onBuilt: (GraphBuild) -> Unit = {},
) {

    /**
     * One reconstruction host's lifetime: built by [openSession], fed by [replayInto], read by
     * [observe], torn down by [close]. [localRefs] is `registry.localRefs()` captured right after
     * the build drained — the graph's membership, against which the journal's refs are checked
     * (`[TTD1-24]`).
     */
    protected class Session(
        val registry: LocationRegistry,
        val controller: SimulationController,
        val scheduler: HostScheduler,
        val host: ManagedHost,
        val build: GraphBuild,
        val localRefs: Set<CellRef>,
    )

    /** The run's state after the prefix [position] resolves to. */
    fun stateAt(position: Position): Reconstruction {
        val n = timeline.resolve(position)
        val anchor = if (n == 0) 0 else timeline.nearestAnchorAtOrBefore(n - 1)
        val session = openSession(anchor)
        try {
            val unreturned = session.localRefs - session.build.cells.mapTo(mutableSetOf()) { it.ref }
            if (unreturned.isNotEmpty()) return incompleteSource(session, position, n, anchor, unreturned)
            val recovery = replayInto(session, from = anchor, until = n)
            return observe(session, position, n, anchor, recovery)
        } finally {
            close(session)
        }
    }

    /**
     * Builds a fresh reconstruction host and the graph on it, drives the (management-band,
     * asynchronous) spawns to idle, then hands the build to [onBuilt] (6tm33-D12). [anchor] is
     * where replay will start; the base implementation does not need it.
     */
    @Suppress("UNUSED_PARAMETER")
    protected open fun openSession(anchor: Int): Session {
        val registry = LocationRegistry()
        val controller = SimulationController(seed)
        val scheduler = controller.scheduler()
        val host = ManagedHost(scheduler = scheduler, registry = registry, journalFor = { DiscardingJournal })
        var opened = false
        try {
            val build = graph.build(host)
            controller.runToIdle()
            onBuilt(build)
            val session = Session(registry, controller, scheduler, host, build, registry.localRefs())
            opened = true
            return session
        } finally {
            if (!opened) scheduler.shutdown()
        }
    }

    /**
     * Feeds records `[from, until)` of this timeline's raw journal to [ManagedHost.recoverFrom]
     * through an [InMemoryJournal] seeded with exactly those bytes (6tm33-D6), then drains the
     * host. Returns the kernel's `RecoveryIncomplete` translated to timeline indices
     * (6tm33-D7), or `null` when every record applied.
     */
    protected open fun replayInto(session: Session, from: Int, until: Int): RecoveryIncomplete? {
        val raw = reading.rawRecords.getValue(timeline.journalId)
        val prefix = InMemoryJournal().apply { reset(raw.subList(from, until)) }
        val recovery = try {
            session.host.recoverFrom(prefix)
            null
        } catch (e: KernelRecoveryIncomplete) {
            RecoveryIncomplete(
                recordIndex = from + e.recordIndex,
                total = until - from,
                cause = e.cause?.toString() ?: e.message.orEmpty(),
            )
        }
        session.controller.runToIdle()
        return recovery
    }

    /**
     * Reads every built cell's state from the drained host and assigns each its verdict: the
     * class's own verdict (`NOT_STATEFUL`, or [ReplayStable.classify]) worsened by the run's
     * reasons — journal defects up to the prefix end, `GRAPH_MISMATCH`, `RECOVERY_INCOMPLETE`.
     */
    protected open fun observe(
        session: Session,
        requested: Position,
        n: Int,
        anchor: Int,
        recovery: RecoveryIncomplete?,
    ): Reconstruction {
        val summary = reading.journals.first { it.journalId == timeline.journalId }
        val mismatched = timeline.positions.subList(0, n).flatMapTo(mutableSetOf()) { it.touches } - session.localRefs

        val futures = LinkedHashMap<Cell, CompletableFuture<Serializable?>?>()
        for (cell in session.build.cells) {
            futures[cell] = if (cell is Stateful) session.host.snapshotOf(cell.ref) else null
        }
        session.controller.runToIdle()

        val runReasons = buildSet {
            addAll(journalDefectsUpTo(summary, reading.records.asIterable(), n - 1))
            if (mismatched.isNotEmpty()) add(Reason.GRAPH_MISMATCH)
            if (recovery != null) add(Reason.RECOVERY_INCOMPLETE)
        }
        val runDegradation: Fidelity = if (runReasons.isEmpty()) Fidelity.Faithful else Fidelity.Degraded(runReasons)

        val cells = LinkedHashMap<CellRef, CellReconstruction>()
        for ((cell, future) in futures) {
            val cls = cell.javaClass.name
            if (future == null) {
                val fidelity = worst(Fidelity.unreconstructible(Reason.NOT_STATEFUL), runDegradation)
                cells[cell.ref] = CellReconstruction.Unreconstructible(cls, fidelity as Fidelity.Unreconstructible)
                continue
            }
            val snapshot = future.get() ?: throw IllegalStateException("snapshot() of ${cell.ref} failed")
            val fidelity = worst(replayStable.classify(cell.javaClass), runDegradation)
            cells[cell.ref] = if (fidelity is Fidelity.Unreconstructible) {
                CellReconstruction.Unreconstructible(cls, fidelity)
            } else {
                CellReconstruction.Reconstructed(cls, snapshot, CellStateView.of(snapshot), fidelity)
            }
        }

        val details = buildSet<ReconstructionDetail> {
            mismatched.forEach { add(GraphMismatch(it)) }
            recovery?.let { add(it) }
        }
        return Reconstruction(
            position = ResolvedPosition(requested, n, anchor, n - anchor),
            cells = cells,
            run = rollUp(cells.mapValues { it.value.fidelity }, runReasons),
            details = details,
        )
    }

    /** Tears the reconstruction host down (6tm33-D12): its scheduler drops every queued task. */
    protected open fun close(session: Session) {
        session.scheduler.shutdown()
    }

    /**
     * The `GRAPH_SOURCE_INCOMPLETE` refusal (6tm33-D11): the graph source spawned cells it did
     * not hand back, so nothing is replayed and every cell — returned or not — is
     * `Unreconstructible`. No snapshot is read.
     */
    private fun incompleteSource(
        session: Session,
        requested: Position,
        n: Int,
        anchor: Int,
        unreturned: Set<CellRef>,
    ): Reconstruction {
        val verdict = Fidelity.Unreconstructible(setOf(Reason.GRAPH_SOURCE_INCOMPLETE))
        val cells = LinkedHashMap<CellRef, CellReconstruction>()
        for (cell in session.build.cells) {
            cells[cell.ref] = CellReconstruction.Unreconstructible(cell.javaClass.name, verdict)
        }
        for (ref in unreturned) {
            cells[ref] = CellReconstruction.Unreconstructible(session.registry.describe(ref)?.name, verdict)
        }
        return Reconstruction(
            position = ResolvedPosition(requested, n, anchor, replayedRecords = 0),
            cells = cells,
            run = rollUp(cells.mapValues { it.value.fidelity }, emptySet()),
            details = setOf(GraphSourceIncomplete(unreturned)),
        )
    }

    companion object {
        /**
         * The message of the [Reason.NO_GRAPH_SOURCE] refusal (`[TTD1-23]`): the journal records
         * invocations, never spawns, so without a topology source there is nothing to replay into.
         */
        const val NO_GRAPH_SOURCE_MESSAGE: String =
            "the journal carries no topology; supply a GraphSpec or a structure-log adapter — " +
                "doc/spec/20-dataflow-semantics/24-data-cells.md §Durability spectrum"

        /**
         * A [ReconstructorResult.Ready] reconstructor over [graph], or — when [graph] is `null` —
         * a [ReconstructorResult.Refusal] of [Reason.NO_GRAPH_SOURCE], returned before anything
         * (a host, a registry, a scheduler) is constructed (`[TTD1-23]`).
         */
        fun of(
            reading: JournalReading,
            timeline: RunTimeline,
            graph: GraphSource?,
            seed: Long = 0,
            replayStable: ReplayStable = ReplayStable.DEFAULT,
            onBuilt: (GraphBuild) -> Unit = {},
        ): ReconstructorResult {
            if (graph == null) return ReconstructorResult.Refusal(Reason.NO_GRAPH_SOURCE, NO_GRAPH_SOURCE_MESSAGE)
            return ReconstructorResult.Ready(Reconstructor(reading, timeline, graph, seed, replayStable, onBuilt))
        }
    }
}
