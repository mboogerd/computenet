package civictech.concord.driver.kernel

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.evolve.Effectful
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.ObserveCell
import civictech.cell.observe.View
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.concord.driver.CellId
import civictech.concord.driver.DeadLetter
import civictech.concord.driver.Effect
import civictech.concord.driver.HostId
import civictech.concord.driver.LinkResult
import civictech.concord.value.Value
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID

/**
 * The `dur`-profile driver capability (CONCORD-PLAN W4-B, §3 Durability): the
 * journal / crash-restart / effectful-sink surface the core [KernelDriver] does
 * not carry. It composes the kernel's real durability machinery — a per-cell
 * write-ahead [Journal] selector on [ManagedHost], `checkpoint`/`recoverFrom`,
 * and the `Effectful` processed-frontier (spec 24 §Durability spectrum,
 * `24-DUR-01…05`; kernel `EffectfulRecoveryTest`/`CrashRecoveryTest`) — so the
 * kernel is **not** modified. This file is the only durability-specific code;
 * [KernelDriver] delegates a handful of verbs here (see its `dur` field).
 *
 * ## What is honestly drivable (and what is not)
 *
 * The kernel implements exactly-once effect firing across crash+replay **for
 * real** (`EffectfulRecoveryTest`): an [Effectful] inlet journals a durable
 * processed-frontier — the last applied `(sourceId, counter)` per inlet — and a
 * replayed invocation at or behind that frontier is dropped as already-acted
 * (`24-DUR-05`). The frontier keys on the invocation's `MessageContext.timestamp`,
 * which is minted by the **producing outlet's** wave.
 *
 * A **journaled** outlet's `sourceId` is no longer a random per-instance value:
 * since the replay-stable wave identity fix (`24-DUR-04`,
 * `OutletWaveState.durable`, kernel `OutletWaveRecoveryTest`), it is
 * **ref-derived** (`UUID.nameUUIDFromBytes`, installed on the live path at
 * spawn), and its whole epoch — identity *and* counter high-water — is
 * journaled at checkpoint and adopted on recovery, rewound to the checkpoint's
 * high-water so a replayed tail reproduces exactly the `(sourceId, counter)`
 * pairs the pre-crash run emitted. Before any checkpoint exists there is no
 * epoch record to adopt and the derivation alone carries it: the rebuilt outlet
 * starts on the same derived id at counter 0, and replaying the whole frame
 * history walks it back through the very same pairs. Consequence: **a journaled source feeding an
 * effectful sink now fires each logical delta exactly once across a crash** —
 * the replayed re-emission carries the identity the sink's restored frontier
 * already recorded and is suppressed as already-acted, while post-recovery live
 * traffic's counter continues past every pre-crash value so it is never
 * mistaken for already-acted in turn. This driver's own `journal-set-source` →
 * `effect-sink` construction is exactly that shape, and it is the corpus's
 * headline coverage for it: `DUR-SRCID-01` (uncheckpointed) and `DUR-SRCID-02`
 * (checkpointed), both `covers: [24-DUR-04, 24-DUR-05]`. `DUR-REPLAY-01`
 * (`covers: [24-DUR-01, 24-DUR-02, 24-DUR-05]`) still feeds its journaled sink
 * from a **volatile** source, in a subgraph held independent of its
 * data-recovery subgraph; that construction remains valid coverage, it is
 * simply no longer the *only* way to drive an `Effectful` sink exactly-once
 * through this driver.
 *
 * One boundary this fix does not touch: a frame that reaches an `Effectful`
 * inlet with **no** `MessageContext` — the externally-driven-root shape, e.g. a
 * connector ingress calling in directly — has no `(sourceId, counter)` position
 * to be deduped against at all, so it is never suppressed and re-fires on
 * replay. That is a decided, recorded bounded limit against `24-DUR-05`
 * (KFX-16), not an oversight; see `concord/corpus/DISPUTES.md`'s W4-B section
 * for the full boundary notes, including this one.
 *
 * ## Scenario surface (neutral, expressed through the existing script verbs)
 *
 * Durability rides the frozen W0 script vocabulary (no new steps): a durable
 * subgraph lives on the reserved host id [DUR_HOST] (`host: dur`), and a
 * `journal`-typed **controller** pseudo-cell is the crash handle —
 * `despawn`-ing it crashes and recovers the whole durable host in one step
 * (discard every live instance, rebuild the graph with the same [CellRef]s,
 * `recoverFrom` the surviving journal). Catalog types:
 *
 *  - `journal-set-source` — a journaled [SetCell] (its accepted ops tee to the WAL).
 *  - `journal-set-view`   — a journaled [ObserveCell] set fold (recovers checkpoint+tail).
 *  - `effect-sink`        — a journaled [Effectful] sink; each added element fires an
 *                           [Effect] into [effectLog] (the `effect-count` surface).
 *  - `set-source` / `set-view` on `host: dur` — **volatile** members of the durable
 *                           host: rebuilt fresh on crash, never journaled/replayed
 *                           (per-cell durability, `24-DUR-01`/`24-DUR-03`).
 *  - `quorum-set`         — the volatile lane-counting SET fan-in a journaled arm
 *                           replays into (`24-REPLAY-01`); the catalog id and its
 *                           `k` are the core profile's, only the dur binding is new.
 *                           With `glitch-free: true` it additionally carries the
 *                           core profile's wave-alignment wrapper (`DUR-GF-01`,
 *                           see [glitchFreeDelegate]).
 *  - `journal`            — the crash/recover controller (no kernel cell).
 *
 * A `snapshot` of a journaled cell lowers to `host.checkpoint(journal)` (state +
 * frontier compaction); a `snapshot`/`restore` of a volatile durable cell is the
 * ordinary raw [Stateful] round-trip (the checkpoint half of a view's recovery).
 */
internal class KernelDriverDur(
    private val controller: SimulationController,
    private val registry: LocationRegistry,
    /** Dead letters observed on the durable host flow into the driver's shared list. */
    private val onDeadLetter: (DeadLetter) -> Unit,
) {

    /** The reserved host id a scenario places its durable subgraph on. */
    companion object {
        const val DUR_HOST: HostId = "dur"
        private val JOURNALED_TYPES = setOf("journal-set-source", "journal-set-view", "effect-sink")

        /** The lane-counting SET fan-in: its edges are real links, not intake subscriptions (see `linkEdge`). */
        private const val LANE_FAN_IN = "quorum-set"
    }

    /** One surviving write-ahead journal, shared across crashes (it *is* "the disk"). */
    private val journal: Journal = InMemoryJournal()

    /** The refs whose accepted invocations tee to [journal] (per-cell selector, `24-DUR-01`). */
    private val journaledRefs = HashSet<CellRef>()

    /** Spec of every durable-host cell, replayed verbatim to rebuild the graph after a crash. */
    private data class Spec(val cellId: CellId, val ref: CellRef, val type: String, val params: Map<String, Value>)

    private val specs = LinkedHashMap<CellId, Spec>()

    /** Links among durable-host cells, re-established on rebuild before `recoverFrom`. */
    private data class LinkRec(val from: CellId, val to: CellId, val inlet: String?, val outlet: String?, val role: String?)

    private val linkRecs = mutableListOf<LinkRec>()

    /** Controller (`journal`) pseudo-cells — the crash handles; not real kernel cells. */
    private val controllers = mutableSetOf<CellId>()

    private data class Bound(
        val ref: CellRef,
        val type: String,
        val cell: Cell,
        val sink: ObservationSink<*>?,
        val viewKind: KernelCatalog.ViewKind,
        /**
         * When the cell declared `glitch-free: true`, the downstream
         * [GlitchFreeCell] its output is routed through (see [glitchFreeDelegate]).
         * A downstream edge reads *this* ref's outlet, so the consumer sees the
         * wave-aligned stream — the same indirection [KernelDriver.Bound.outletDelegate]
         * performs on the core profile.
         */
        val outletDelegate: CellRef? = null,
    )

    private val cells = LinkedHashMap<CellId, Bound>()

    /**
     * Stable [CellRef]s for the [GlitchFreeCell] wrappers, minted once per cell id
     * and reused across every rebuild — a wrapper is as much part of the recovered
     * graph as the cell it fronts, and the dur driver rebuilds under recorded refs.
     */
    private val glitchFreeRefs = LinkedHashMap<CellId, CellRef>()

    /** Observation streams, keyed by cell id so they survive a crash+rebuild. */
    private val logs = LinkedHashMap<CellId, MutableList<Value>>()

    /** External effect logs (outside any cell instance, so a true double-fire is catchable). */
    private val effectLogs = LinkedHashMap<CellId, MutableList<Effect>>()

    private var host: ManagedHost = newHost()

    private fun newHost(): ManagedHost =
        ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            journalFor = { ref -> if (ref in journaledRefs) journal else null },
        ).also { h ->
            h.deadLetterOutlet.subscribe(
                Use.fixed(
                    Propagate<civictech.cell.host.DeadLetter> { dl ->
                        onDeadLetter(DeadLetter(host = DUR_HOST, cell = null, reason = dl.description))
                    },
                    PortRef.generate(),
                ),
            )
        }

    /** True iff [cellId] is a durability-managed cell (routed here by [KernelDriver]). */
    fun owns(cellId: CellId): Boolean = cellId in specs || cellId in controllers

    fun spawn(cellId: CellId, type: String, params: Map<String, Value>) {
        if (type == "journal") {
            controllers += cellId
            return
        }
        val ref = specs[cellId]?.ref ?: CellRef(UUID.randomUUID())
        specs[cellId] = Spec(cellId, ref, type, params)
        if (type in JOURNALED_TYPES) journaledRefs += ref
        instantiate(specs.getValue(cellId))
    }

    /** Build + spawn one durable cell on the current [host] and register its bookkeeping. */
    private fun instantiate(spec: Spec) {
        val built = build(spec)
        host.managementInlet.call.spawn(built.cell)
        cells[spec.cellId] = built.copy(outletDelegate = glitchFreeDelegate(spec, built.ref))
        // The observation stream is recorded at the fold in [build] (into this
        // cell's surviving [logs] entry), not through `sink.onChange` — the sink's
        // listener dispatch is asynchronous and nothing here waits for it, so a
        // recovered view's replayed observations could be missing at check time.
    }

    /**
     * `glitch-free: true` on the lane-counting fan-in (`BS-40`, `DUR-GF-01`): spawn
     * the kernel's own [GlitchFreeCell] downstream of [upstream] and route the
     * fan-in's output through it over a **real host link**, so the wrapper's
     * `WaveFrontier` sees the `EdgeOpen`/progress a link handshake announces. This
     * is the core driver's construction verbatim ([KernelDriver.spawn]) —
     * `glitch-free` is an existing descriptor param on an existing catalog id
     * (`concord/schema/scenario.md`, `cell-catalog.md`), so nothing new is minted
     * here; only the *durable* binding is new.
     *
     * Two dur-specific details. The wrapper is built under a **recorded** ref
     * ([glitchFreeRefs]) rather than a fresh random one, because the durable host is
     * torn down and rebuilt on every crash and every cell — wrapper included — must
     * come back under the ref the pre-crash graph used (PN-1 derived port identity).
     * And the wrapper is **volatile**: it is never added to [journaledRefs], so a
     * crash re-mints it with an empty frontier. What makes an empty frontier
     * survivable is PN-2, not `[24-DUR-04]`: `recoverFrom` stamps the replayed
     * re-emission `MessageContext.baseline`, and `WaveFrontier.offer` takes its
     * baseline branch *before* any edge/lane lookup — releasing immediately and
     * excluding the invocation from every wave-completeness set — so the replayed
     * cone is never a torn second lane the frontier could not complete. The source's
     * identity is not consulted on that path, which is why `DUR-GF-01` carries
     * `24-DUR-02` and not `24-DUR-04` (kernel `DurableGlitchFreeReplayTest`: control
     * (a) stalls on every seed when the baseline stamp is removed, while control (b)
     * stays green with replay-stable identity reverted).
     *
     * Returns `null` for every cell that did not request wave alignment.
     */
    private fun glitchFreeDelegate(spec: Spec, upstream: CellRef): CellRef? {
        if (spec.type != LANE_FAN_IN) return null
        if ((spec.params["glitch-free"] as? Value.BoolVal)?.value != true) return null
        val gfRef = glitchFreeRefs.getOrPut(spec.cellId) { CellRef(UUID.randomUUID()) }
        @Suppress("UNCHECKED_CAST")
        val gf = GlitchFreeCell(Propagate::class.java as Class<Propagate<Any>>, gfRef)
        host.managementInlet.call.spawn(gf)
        val result = host.managementInlet.call.connect(upstream, "outlet", gfRef, "inlet")
        check(result is civictech.cell.link.LinkResult.Connected) {
            "durable glitch-free wrapper for '${spec.cellId}' was not admitted: $result"
        }
        return gfRef
    }

    private fun build(spec: Spec): Bound = when (spec.type) {
        "set-source", "journal-set-source" ->
            Bound(spec.ref, spec.type, SetCell<Any?>(spec.ref), null, KernelCatalog.ViewKind.NONE)

        "set-view", "journal-set-view" -> {
            // The fold is wrapped so the observation stream is recorded on the
            // folding thread ([RecordedView]) into this cell id's log, which
            // outlives the crash+rebuild cycle. Constructing over the *surviving*
            // list is what keeps a recovered view's stream continuous: the wrapper
            // appends one catch-up entry per rebuild, exactly as the sink's
            // late-join `onChange` used to.
            val log = logs.getOrPut(spec.cellId) { mutableListOf() }
            val cell = ObserveCell(RecordedView(View.set<Any?>(), KernelCatalog.ViewKind.SET, log), spec.ref)
            Bound(spec.ref, spec.type, cell, cell, KernelCatalog.ViewKind.SET)
        }

        "effect-sink" -> {
            val cell = EffectSinkCell(spec.ref, effectLogs.getOrPut(spec.cellId) { mutableListOf() })
            Bound(spec.ref, spec.type, cell, null, KernelCatalog.ViewKind.NONE)
        }

        // `quorum-set` (`24-REPLAY-01`): the lane-counting SET fan-in, always a
        // **volatile** durable-host member — it is the *consumer* of a journaled
        // arm's replayed baseline, never journaled itself (mirroring
        // `DurableQuorumReplayTest`, where only the durable arm tees to the WAL and
        // the quorum is re-minted empty on every rebuild). Constructed here rather
        // than through [KernelCatalog.build] for one reason: the dur driver rebuilds
        // every cell under its **recorded** [CellRef] after a crash (PN-1's derived
        // port identity), and `KernelCatalog.build` mints a fresh random ref. The
        // `k` reading is the catalog's own (`k` optional, default `n` ⇒ intersection).
        "quorum-set" -> {
            val k = (spec.params["k"] as? Value.IntVal)?.value?.toInt()
            val cell = if (k != null) QuorumSetCell<Any?>(spec.ref) { k } else QuorumSetCell.intersection(spec.ref)
            Bound(spec.ref, spec.type, cell, null, KernelCatalog.ViewKind.NONE)
        }

        else -> throw UnsupportedCatalogBinding("no durable kernel binding for catalog type '${spec.type}'")
    }

    /**
     * A durable link is wired **through the host intake** (subscribe the source's
     * outlet to a [HostedCellProxy] of the sink), never as a raw port `linkTo`.
     * The intake ([ManagedHost.enqueueHostedInvocation]) is the single funnel that
     * (a) tees a wire frame to a journaled sink's WAL and (b) enforces the
     * `Effectful` processed-frontier — a direct `managementInlet.connect` bypasses
     * both, so nothing would be journaled or deduped (this is exactly how the
     * kernel's `EffectfulRecoveryTest` wires its sink). Admission checks
     * (single-writer/cycle) are not exercised on the `dur` profile, so no
     * [LinkResult.Rejected] arises here.
     */
    fun connect(from: CellId, to: CellId, inlet: String?, outlet: String?, role: String?): LinkResult {
        wire(cells.getValue(from), cells.getValue(to))
        linkRecs += LinkRec(from, to, inlet, outlet, role)
        return LinkResult.Connected("dur:$from->$to")
    }

    /**
     * Subscribe [src]'s outlet to an intake-routed proxy of [dst]'s inlet — the
     * durable default. The one exception, an edge incident to a lane-counting
     * fan-in, is [linkEdge].
     */
    private fun wire(src: Bound, dst: Bound) {
        // An edge incident to a lane-counting fan-in must be a REAL link, not an
        // intake subscription — see [linkEdge].
        if (src.type == LANE_FAN_IN || dst.type == LANE_FAN_IN) return linkEdge(src, dst)
        val sinkInlet = (HostedCellProxy.create(dst.ref, host, DeltaSink::class.java) as DeltaSink).inlet.call
        @Suppress("UNCHECKED_CAST")
        (src.cell as SetApi<Any?>).outlet.subscribe(Use.fixed(sinkInlet, PortRef.generate()))
    }

    /**
     * The one edge shape the intake subscription above cannot express: an edge
     * touching a **lane-counting fan-in** (`quorum-set` / `PresenceLanes`).
     * Such a cell keeps one `TagState` lane per *open source link* and opens a
     * lane on the `EdgeOpen` a link handshake announces; a `subscribe(Use.fixed(…))`
     * is not a link, so no lane would ever open, every delivery would be
     * unattributable, and the cell would silently fold nothing at all (the exact
     * shape `PresenceLanes.laneFor` returns null for). So this edge is installed
     * through the kernel's own link admission — the same
     * `ManagedHost.connect(from, "outlet", to, "inlet")` every core-profile link
     * uses — which is *not* the "raw port `linkTo`" the durability modeling notes
     * warn against.
     *
     * Nothing is bypassed by that choice, because the intake funnel's two
     * guarantees are about the **destination**: it tees a journaled sink's frames
     * to the WAL and enforces the `Effectful` processed-frontier. A `quorum-set`
     * is neither journaled nor `Effectful` (see [build]), and the *view* it feeds
     * on this path is a volatile one; the journaled endpoint in a `24-REPLAY-01`
     * graph is the arm **source**, whose own accepted ops still ride the intake
     * through [apply]. This mirrors `DurableQuorumReplayTest` exactly: there too
     * the root→journaled-relay edge goes through the host queue while both
     * fan-in arms are ordinary `linkTo` links.
     */
    private fun linkEdge(src: Bound, dst: Bound) {
        // A wave-aligned fan-in publishes on its [GlitchFreeCell] wrapper's outlet, so
        // the consumer reads the aligned stream rather than the operator's raw output.
        val srcRef = src.outletDelegate ?: src.ref
        val result = host.managementInlet.call.connect(srcRef, "outlet", dst.ref, "inlet")
        check(result is civictech.cell.link.LinkResult.Connected) {
            "durable link ${src.type} -> ${dst.type} was not admitted: $result"
        }
    }

    /**
     * A durable source op is driven **through the host intake** via a
     * [HostedCellProxy] too, so a journaled source's accepted `add`/`remove` is
     * teed to the WAL (a raw `routerInlet.route` invokes the inlet directly and is
     * never journaled). The op reuses the neutral set-source verbs.
     */
    fun apply(cellId: CellId, op: String, value: Value?) {
        @Suppress("UNCHECKED_CAST")
        val ops = (HostedCellProxy.create(cells.getValue(cellId).ref, host, SetApi::class.java) as SetApi<Any?>).inlet.call
        when (op) {
            "add" -> ops.add(KernelCatalog.unwrap(value))
            "remove" -> ops.remove(KernelCatalog.unwrap(value))
            else -> throw UnsupportedCatalogBinding("durable source op '$op' unbound (set add/remove only)")
        }
    }

    fun readView(cellId: CellId): Value {
        val bound = cells.getValue(cellId)
        val sink = bound.sink ?: error("durable cell '$cellId' (${bound.type}) is not a view")
        return KernelCatalog.readView(bound.viewKind, sink.current())
    }

    fun observationLog(cellId: CellId): List<Value> = logs[cellId]?.toList() ?: emptyList()

    fun effectLog(cellId: CellId): List<Effect> = effectLogs[cellId]?.toList() ?: emptyList()

    /**
     * `snapshot` of a **journaled** durable cell is a kernel checkpoint (state +
     * processed-frontier compaction of the WAL, `24-DUR-02`); of a **volatile**
     * durable cell it is the ordinary raw [Stateful] round-trip a view uses to
     * recover its own state across the crash. The returned blob is opaque; a
     * checkpoint's tag is a sentinel (recovery reads the journal, not the blob).
     */
    fun snapshot(cellId: CellId): ByteArray {
        if (cellId in controllers) return byteArrayOf(CTRL_MARKER)
        val bound = cells.getValue(cellId)
        if (bound.ref in journaledRefs) {
            host.checkpoint(journal)
            drain()
            return byteArrayOf(CHECKPOINT_MARKER)
        }
        val state = (bound.cell as? Stateful)?.snapshot()
            ?: error("durable cell '$cellId' is not Stateful; cannot snapshot")
        return ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(state) } }.toByteArray()
    }

    fun restore(cellId: CellId, blob: ByteArray) {
        // A journaled cell recovers through the crash-controller's `recoverFrom`, and
        // the controller's own restore is a no-op (the despawn already recovered it).
        if (cellId in controllers) return
        if (blob.size == 1 && (blob[0] == CHECKPOINT_MARKER || blob[0] == CTRL_MARKER)) return
        val cell = cells.getValue(cellId).cell as? Stateful
            ?: error("durable cell '$cellId' is not Stateful; cannot restore")
        cell.restore(ObjectInputStream(ByteArrayInputStream(blob)).readObject() as java.io.Serializable)
    }

    /**
     * `despawn` of the `journal` controller is the crash+restart verb (`24-DUR-02`,
     * `EffectfulRecoveryTest` structure): discard the whole durable host and every
     * live instance, rebuild the graph with the **same** [CellRef]s, re-establish
     * the links, then `recoverFrom` the surviving journal. A journaled cell
     * restores its checkpoint and replays the frame tail; a volatile cell comes
     * back empty (re-delivered nothing, `24-DUR-03`); an effectful sink's restored
     * frontier suppresses every already-applied frame (`24-DUR-05`).
     */
    fun despawn(cellId: CellId) {
        if (cellId in controllers) {
            crashAndRecover()
            return
        }
        cells.remove(cellId)?.let { bound ->
            host.managementInlet.call.despawn(bound.ref)
            bound.outletDelegate?.let { host.managementInlet.call.despawn(it) }
        }
        glitchFreeRefs.remove(cellId)
        specs.remove(cellId)
        linkRecs.removeAll { it.from == cellId || it.to == cellId }
    }

    private fun crashAndRecover() {
        // CRASH: the old host, registry entries, and every live instance vanish; only
        // the journal survives. A fresh host re-derives the same per-cell selector.
        host = newHost()
        cells.clear()
        // Rebuild the graph (spawn the same cells with the same refs), then re-link —
        // recovery replays onto a wired graph so a source's restored deltas reach its view.
        specs.values.forEach { instantiate(it) }
        linkRecs.toList().forEach { l -> wire(cells.getValue(l.from), cells.getValue(l.to)) }
        host.recoverFrom(journal)
        drain()
    }

    /** Drive the shared controller to quiescence (checkpoint tasks / replayed frames settle). */
    private fun drain() {
        controller.runToIdle()
    }
}

/** Sentinel snapshot tags: recovery of these reads the journal, not the blob body. */
private const val CHECKPOINT_MARKER: Byte = 1
private const val CTRL_MARKER: Byte = 2

/**
 * Proxy view of a durable sink's set-delta `inlet` (an [ObserveCell] or an
 * [EffectSinkCell]). A [HostedCellProxy] built against it routes each delivered
 * delta through the host intake — the journaling + `Effectful`-frontier funnel —
 * rather than a raw port link.
 */
private interface DeltaSink {
    val inlet: Use<Propagate<SetDelta<Any?>>>
}

/**
 * A durable effect-boundary sink (CONCORD-PLAN §1.4 `effect-count`; spec
 * `24-DUR-05`): an [Effectful] cell that, for every element it is delivered,
 * records one [Effect] into an **external** [effectLog] (a list owned by
 * [KernelDriverDur], outside this instance's lifecycle — so a crashed instance is
 * discarded entirely and only a re-fire the recovered instance performs can be
 * caught, exactly as `EffectfulRecoveryTest`'s external `world`).
 *
 * Being `Effectful`, its host journals a processed-frontier per applied
 * invocation and suppresses any replayed (or post-recovery) invocation at or
 * behind it — so across a crash+journal-replay each `(sourceId, counter)` fires
 * exactly once. The dedup key the [Effect] carries is the element itself, so
 * `effect-count(sink, exactly: 1)` reads "each element acted on exactly once".
 */
internal class EffectSinkCell(
    override val ref: CellRef,
    private val effectLog: MutableList<Effect>,
) : Cell, Effectful {

    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Any?>>>())

    init {
        inlet.serve(
            Propagate<SetDelta<Any?>> { delta ->
                // Every newly-added element is one effect; the host's Effectful
                // frontier is what guarantees a replayed add does not re-fire.
                delta.adds.keys.forEach { element ->
                    effectLog += Effect(key = element?.toString(), payload = Value.of(element))
                }
            },
        )
    }
}
