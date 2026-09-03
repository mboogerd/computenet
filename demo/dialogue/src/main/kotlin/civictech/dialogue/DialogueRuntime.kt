package civictech.dialogue

import civictech.agora.AgoraService
import civictech.cell.CellRef
import civictech.cell.control.AttentionPolicy
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.HostScheduler
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.VirtualThreadScheduler
import civictech.cell.durability.Journal
import civictech.cell.link.LinkResult
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.dialogue.apply.BindingTable
import civictech.dialogue.apply.GraphApplier
import civictech.dialogue.apply.ReconcileReport
import civictech.dialogue.extract.ExtractionAccounting
import civictech.dialogue.extract.Extractor
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The AGO1 composition root (epic computenet-2aw §2.5 durability seam,
 * [AGO1-DUR-01]/[AGO1-DUR-02], [AGO1-REPLAY-02]/[AGO1-REPLAY-03], §4 BS-18 and
 * BS-19; task computenet-2aw.4.3).
 *
 * It owns — and is the only thing that owns — the [host], the [registry], the
 * [service], the pipeline [refs], the durable [bindings], the [applier], the
 * [source] and the optional [journalDir]. F5 wires an HTTP surface onto an
 * instance of this class; the tests drive one directly.
 *
 * ### Construction order is the deliverable (2aw.F4-D2, BS-18)
 *
 * `KeyedCells.recover()`'s KDoc pins the rule this class exists to obey: every
 * durably-known cell must be spawned **before** [ManagedHost.recoverFrom], or
 * the replayed frames addressed to it find no cell
 * (`ManagedHost`: `cells[cellRef] ?: return deadLetter(...)`) and replay
 * silently diverges — no exception, just a smaller graph. So the constructor
 * runs, in this exact order:
 *
 * 1. [LocationRegistry] and the [ManagedHost], whose WAL is
 *    `KeyedCells.hostJournal(journalDir)` — reusing that factory so the file
 *    name matches what a `KeyedCells` on the same directory would write.
 * 2. `DialoguePipeline.build(host, extractor, namespace = `[NAMESPACE]`)` —
 *    the namespace is what makes every pipeline cell's ref
 *    `nameUUIDFromBytes("dialogue:$handle")` instead of random, so this run's
 *    cells sit under the refs last run's journal frames were written against.
 * 3. [AgoraService] with `structureLog = graph.jsonl`, whose own init replays
 *    that log and rebuilds every claim/edge cell under its **recorded** ref
 *    with catch-ups suppressed.
 * 4. [BindingTable] on the same directory, replaying `bindings.jsonl`.
 * 5. [GraphApplier], which spawns its three deterministic-ref observation
 *    sinks and connects them.
 * 6. The utterances sink below — a `View.set` over `refs.utterances` under
 *    `dialogue:sink:utterances`, spawned for one purpose only: to read back
 *    what the WAL restored into the ingress `SetCell`, so [completeRecovery]
 *    can seed the driver's ledger from it.
 *
 * Only then may the caller run [recover] (step 7), drain the host, and call
 * [completeRecovery] (step 8). The drain between them is the caller's because
 * it is scheduler-shaped: a test steps its [civictech.cell.host.SimulationController]
 * to idle; a live process fences with [afterQuiescence].
 *
 * ### No startup checkpoint
 *
 * Deliberately absent, copying `AgoraApp`'s hazard note: `checkpoint` runs on
 * the management band and would jump ahead of the still-staged replay frames,
 * compacting the journal down to its PRE-replay state — data loss on the next
 * restart. Nothing in this class calls [ManagedHost.checkpoint].
 *
 * ### Ephemeral mode
 *
 * `journalDir = null` touches no file anywhere: no host WAL
 * ([KeyedCells.hostJournal] returns null), no `graph.jsonl`, no
 * `bindings.jsonl`. [source] is then constructed eagerly with an empty
 * recovered set, and [recover]/[completeRecovery] are no-ops.
 *
 * Not thread-safe, exactly like the [TranscriptSource] it drives: construct,
 * recover and reconcile from one thread.
 */
class DialogueRuntime(
    extractor: Extractor,
    /** The loaded transcript [TranscriptSource.replay] and `step` draw from. */
    private val transcript: List<Utterance> = emptyList(),
    /**
     * Durable state directory: `host.journal` (the WAL), `graph.jsonl` (agora's
     * structure log) and `bindings.jsonl` (the key → ref table) all live here.
     * `null` = fully ephemeral; see the class doc.
     */
    val journalDir: File? = null,
    /**
     * The host's scheduler. `null` mints the production
     * [VirtualThreadScheduler] — the same default [ManagedHost] would apply,
     * held explicitly here because [afterQuiescence] needs a handle to fence
     * against (`ManagedHost` keeps its own private). Tests pass
     * `SimulationController.scheduler()`.
     */
    scheduler: HostScheduler? = null,
    /** Agora's cycle-head absorb threshold; also the tests' credence tolerance base. */
    quiescence: Double = 1e-3,
) {

    val registry = LocationRegistry()

    /** Held explicitly so [afterQuiescence] can submit onto it. */
    private val hostScheduler: HostScheduler =
        scheduler ?: VirtualThreadScheduler("DialogueRuntime-${UUID.randomUUID()}")

    private val volatileRefs: Set<CellRef> =
        (DERIVED_HANDLES.map { pipelineRef(it) } + SINK_NAMES.map { sinkRef(it) }).toSet()

    private val journal: Journal? = KeyedCells.hostJournal(journalDir)

    // (1) host — WAL first, because everything below spawns into it.
    //
    // `journalFor`, not `journal`: durability here is per-cell (CP-C1). See
    // [isDurable] for which cells are journaled and why the derived pipeline
    // deliberately is not.
    val host = ManagedHost(
        scheduler = hostScheduler,
        registry = registry,
        attention = AttentionPolicy(magnitudeBands = AgoraService.MAGNITUDE_BANDS),
        journalFor = { ref -> if (isDurable(ref)) journal else null },
    )

    // (2) pipeline, under replay-stable refs.
    private val built = DialoguePipeline.build(host, extractor, namespace = NAMESPACE)

    val refs: DialoguePipeline.Refs = built.refs

    /** The extraction ledger ([AGO1-EXTR-06]'s status surface). */
    val accounting: ExtractionAccounting = built.accounting

    // (3) agora, whose init replays graph.jsonl under recorded refs.
    val service = AgoraService(
        host,
        registry,
        quiescence = quiescence,
        structureLog = journalDir?.let { File(it, STRUCTURE_LOG) },
    )

    // (4) the durable binding table, replaying bindings.jsonl.
    val bindings = BindingTable(journalDir)

    // (5) the applier, which spawns its own deterministic-ref sinks.
    val applier = GraphApplier(host, refs, service, bindings)

    /** The ingress handle the driver writes through. */
    private val utteranceOps: SetOps<Utterance> = DialoguePipeline.utteranceOps(host, refs)

    // (6) the recovery-only ingress sink. Spawned in every mode (its ref must
    //     be stable across restarts whether or not this run recovers), read
    //     only by completeRecovery().
    private val utterancesSink: ObserveCell<SetDelta<Utterance>, Set<Utterance>> = run {
        val cell = ObserveCell(View.set<Utterance>(), sinkRef("utterances"))
        val management = host.managementInlet.call
        management.spawn(cell)
        val result = management.connect(refs.utterances.ref, "outlet", cell.ref, "inlet")
        check(result !is LinkResult.Rejected) {
            "DialogueRuntime: link utterances.outlet -> recovery sink rejected: " +
                "${(result as LinkResult.Rejected).reason}"
        }
        cell
    }

    private var recovered: TranscriptSource? =
        if (journalDir == null) TranscriptSource(utteranceOps, transcript, recovered = emptyList()) else null

    /**
     * The transcript drive.
     *
     * In ephemeral mode it exists from construction. With a [journalDir] it is
     * built by [completeRecovery], because it cannot be built any earlier: its
     * ledger has to be seeded from what WAL replay actually restored, and that
     * is not known until the host has been recovered and drained.
     */
    val source: TranscriptSource
        get() = recovered ?: error(
            "DialogueRuntime.source is not available yet: call recover(), drain the host, " +
                "then completeRecovery() (journalDir = $journalDir)",
        )

    /**
     * Step 7 — replay the host WAL. A no-op without a [journalDir].
     *
     * This does **nothing else**: the caller must drain the host afterwards
     * (`SimulationController.runToIdle()`, or [afterQuiescence]) and then call
     * [completeRecovery]. Replay only stages frames; the ingress `SetCell`
     * does not hold the recovered utterances until they have been dispatched.
     */
    fun recover() {
        val journal = KeyedCells.hostJournal(journalDir) ?: return
        host.recoverFrom(journal)
    }

    /**
     * Step 8 — seed the driver's ledger from the recovered ingress set and
     * make [source] available. Idempotent, and a no-op without a [journalDir].
     *
     * The seeding goes through [TranscriptSource]'s `recovered` argument
     * rather than through `offer`, precisely so no `SetOps.add` is made for an
     * utterance the cell already holds: a second add would mint a second
     * add-tag and diverge the tagged set from what the journal recorded.
     */
    fun completeRecovery() {
        if (journalDir == null || recovered != null) return
        recovered = TranscriptSource(utteranceOps, transcript, recovered = utterancesSink.current())
    }

    /**
     * Apply the canonical sets to the agora graph — call at quiescence only
     * ([AGO1-APPLY-04]).
     */
    fun reconcile(): ReconcileReport = applier.reconcile()

    /**
     * [AGO1-REPLAY-03]/BS-19 — retract the whole transcript. The caller then
     * drains and calls [reconcile], which is what empties the agora graph and
     * the binding table (the retraction has to reach the canonical folds
     * before the applier can see them shrink).
     */
    fun reset() {
        source.reset()
    }

    /**
     * The live-scheduler quiescence fence: run [block] on the calling thread
     * once this host's queue has actually drained.
     *
     * Six lines lifted from `civictech.testkit.awaitDrained`, whose KDoc
     * carries the full argument; `:testkit` is a `testImplementation`
     * dependency and cannot be imported from a main source set, so it is
     * re-implemented rather than reused. The essentials: one task submitted at
     * [Int.MAX_VALUE] priority sorts strictly below every band the host uses
     * (management 0, data 20, drain 30), and [HostScheduler.submit]'s
     * `(priority, submission)` ordering over a single-threaded drain means it
     * reaches the front only when the queue holds nothing else — not the work
     * queued before it, and not the work that work enqueued, however deep the
     * cascade. Its completion is a **positive** event, so a starved host makes
     * this block rather than answer wrongly; [timeoutMs] is a hang backstop,
     * not a convergence budget.
     *
     * F5 uses this for per-utterance reconciliation (epic §8/R4:
     * quiescence-scoped per utterance, never mid-wave). Tests on a
     * [civictech.cell.host.SimulationController] do not need it — and must not
     * call it, since nothing steps that scheduler while this thread waits.
     *
     * @throws IllegalStateException if the host never drained within [timeoutMs].
     */
    fun afterQuiescence(timeoutMs: Long = 30_000, block: () -> Unit) {
        val drained = CountDownLatch(1)
        hostScheduler.submit(Int.MAX_VALUE) { drained.countDown() }
        check(drained.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            "DialogueRuntime.afterQuiescence: host queue never drained within ${timeoutMs}ms"
        }
        block()
    }

    /**
     * Per-cell durability (CP-C1, `ManagedHost.journalFor`): **the ingress
     * cell and agora's cells are journaled; every derived pipeline cell and
     * every observation sink is volatile.**
     *
     * That is the recovery design, not an optimization. Derived state is a
     * pure function of the admitted-utterance set: replay restores the
     * ingress `SetCell`'s adds, dispatching them re-runs segmentation,
     * extraction, minting and projection, and the applier then reconciles a
     * canonical set it recomputed rather than one it replayed. Journaling the
     * derived cells would duplicate all of it — and, as `AgoraApp`'s init
     * comment notes, replay dispatch re-journals those derived re-emissions
     * anyway, so the WAL would grow by a full derived copy per restart.
     *
     * It is also what makes the WAL *encodable at all* today. A journaled
     * frame is encoded through `WireCodec`, which needs a polymorphic
     * registration per payload type. `Utterance` gets one from
     * [DialogueWireSerializers] (`META-INF/services/civictech.cell.wire.WireSerializers`);
     * the derived payloads — `RelationCandidate`, `ClaimAggregate`, `StanceAggregate`,
     * `StanceJoinRow`, the provenance entries — carry no `@Serializable` at
     * all, and `projectedStances` is keyed by a `Pair`, which has no
     * polymorphic registration to give it. Journaling them is therefore not
     * merely wasteful but impossible without first making the whole mint
     * vocabulary wire-capable, which is nobody's task here (see this class's
     * follow-up bead).
     *
     * The volatile set is spelled as [DERIVED_HANDLES] rather than derived
     * from [DialoguePipeline.Refs], because `Refs` does not expose every cell
     * `build` spawns (`nonSelfRelations`, `claimProvenanceEntries` and
     * `relationProvenanceEntries` have no field). A handle added to
     * `DialoguePipeline` and not here fails **loudly** — the first frame that
     * cell accepts hits a `SerializationException` on encode — which is why
     * this list is safe to hand-maintain but must not be pruned to silence
     * one.
     */
    private fun isDurable(ref: CellRef): Boolean = ref !in volatileRefs

    companion object {
        /**
         * Every handle [DialoguePipeline.build] spawns **except** `utterances`
         * — i.e. the whole derived pipeline, which [isDurable] makes volatile.
         */
        private val DERIVED_HANDLES = listOf(
            "segments",
            "extractedItems",
            "extractedClaims",
            "claimKeys",
            "canonicalClaims",
            "extractedRelations",
            "relationCandidates",
            "rejectedRelations",
            "nonSelfRelations",
            "sourceResolvedRelations",
            "resolvableRelations",
            "canonicalRelations",
            "extractedStances",
            "stanceJoinRows",
            "projectedStances",
            "claimProvenanceEntries",
            "claimProvenance",
            "relationProvenanceEntries",
            "relationProvenance",
        )

        /** [GraphApplier]'s three observation sinks, plus this class's own. */
        private val SINK_NAMES = listOf("claims", "relations", "stances", "utterances")

        /**
         * The pipeline's cell-ref namespace. Fixed, not a parameter: two runs
         * over the same [journalDir] must agree on it or WAL replay
         * dead-letters, and a caller-supplied namespace is exactly the kind of
         * thing that silently differs between a process and its restart.
         */
        const val NAMESPACE = "dialogue"

        /** Agora's durable structure log inside [journalDir]. */
        const val STRUCTURE_LOG = "graph.jsonl"

        /**
         * Ref prefix for this runtime's own observation sinks — disjoint from
         * `BindingTable`'s `dialogue:claim:`/`dialogue:relation:`, from the
         * pipeline's `dialogue:$handle`, and from `agora:hub`. Shared with
         * `GraphApplier`'s three sinks by construction, which is why the name
         * `utterances` is picked to differ from its `claims`/`relations`/
         * `stances`.
         */
        const val SINK_PREFIX = "dialogue:sink"

        /** The ref `DialoguePipeline.build(namespace = `[NAMESPACE]`)` gives [handle]. */
        fun pipelineRef(handle: String): CellRef =
            CellRef(UUID.nameUUIDFromBytes("$NAMESPACE:$handle".toByteArray()))

        /** The ref an observation sink named [name] lives under. */
        fun sinkRef(name: String): CellRef =
            CellRef(UUID.nameUUIDFromBytes("$SINK_PREFIX:$name".toByteArray()))
    }
}
