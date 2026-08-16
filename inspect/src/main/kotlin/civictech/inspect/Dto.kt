package civictech.inspect

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The wire shapes of `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`.
 * The contract is binding: field names and value vocabularies here are copied
 * from it, not invented. Fields the milestone cannot answer yet carry their
 * contract-declared null/placeholder (`net`, `SearchResult.cost`) rather than
 * a guess.
 */
internal val inspectorJson = Json {
    // The contract's examples spell out every field, and the client applies
    // deltas by upsert — a field omitted because it equals its default would
    // read as "unchanged" instead of "null". Emit everything.
    encodeDefaults = true
}

/** `GET /api/inspect/topology`. SSE events carry `seq` greater than this one. */
@Serializable
data class TopologySnapshot(
    val seq: Long,
    val nodes: List<Node>,
    val edges: List<Edge>,
)

/** One live cell. [ref] is encoded `"<uuid>:<instanceId>"`. */
@Serializable
data class Node(
    val ref: String,
    /** Registry/debug name if known, else null. */
    val name: String? = null,
    val typeFqn: String,
    /** `PURE` / `BLOCKING` / `SUSPENDING`, or null when the cell has no generated descriptor. */
    val color: String? = null,
    val manifests: List<String> = emptyList(),
    val ports: List<NodePort> = emptyList(),
    /**
     * Process host (`ManagedHost`) name. Null for a peer-announced cell: a
     * mirrored location names a bridge, not a host (M5-NET).
     */
    val host: String? = null,
    /**
     * Network host / peer id. [LOCAL_NET] unless the launcher named this JVM
     * (`--net-name`); a peer-announced cell reports that connection's derived
     * label instead (M5-NET, see [Peers]).
     */
    val net: String = LOCAL_NET,
    val lifecycle: String = HOT,
    val generation: Long = 0,
    /**
     * The id of the connected component this cell belongs to (M4, see
     * [ComponentIndex]). Non-null for every published cell — an unlinked cell
     * is a component of one.
     */
    val graph: String? = null,
) {
    companion object {
        const val LOCAL_NET = "local"
        const val HOT = "HOT"

        /**
         * The contract's other `lifecycle` value. Reported for a cell the
         * kernel is not running: individually suspended, or on a drained host
         * — see [Heat] for the whole vocabulary and why those two collapse into
         * this one word (the contract offers `"HOT" | "SUSPENDED"` and no
         * third).
         */
        const val SUSPENDED = "SUSPENDED"
    }
}

/** One declared port of a cell, straight off its generated `CellDescriptor`. */
@Serializable
data class NodePort(
    val name: String,
    /** `IN` or `OUT`. */
    val dir: String,
    val contractFqn: String,
)

/** One endpoint of an [Edge]: the cell plus the port name it attaches to. */
@Serializable
data class Endpoint(val ref: String, val port: String)

/** One live, directional link. */
@Serializable
data class Edge(
    val id: String,
    val from: Endpoint,
    val to: Endpoint,
    /** `CONSUME` or `OBSERVE`. */
    val role: String = CONSUME,
    /**
     * Best-effort. `false` once M3 has a tap on the producing outlet, `true`
     * for a producing endpoint with no emission point of its own (a delegating
     * pass-through — spec 20/21 §Fusion), and `null` when this inspector
     * cannot tell: a producer it does not host, or an inspector running without
     * the flow feed (see [FlowBinding]).
     */
    val fused: Boolean? = null,
) {
    companion object {
        const val CONSUME = "CONSUME"

        /** The tap role. Never emitted yet — taps are not in the topology index. */
        const val OBSERVE = "OBSERVE"
    }
}

/**
 * `GET /api/inspect/cell/{ref}` — the contract's "[Node] plus" shape. The
 * served body is built by *merging* the encoded [Node] with the two extra
 * fields (see `InspectorModel.detailJson`), so the shared half can never drift
 * from the snapshot's; this class is the decode-side mirror of that merge, and
 * a [Node] field added without adding it here fails the detail test loudly.
 */
@Serializable
data class CellDetail(
    val ref: String,
    val name: String? = null,
    val typeFqn: String,
    val color: String? = null,
    val manifests: List<String> = emptyList(),
    val ports: List<NodePort> = emptyList(),
    val host: String? = null,
    val net: String = Node.LOCAL_NET,
    val lifecycle: String = Node.HOT,
    val generation: Long = 0,
    val graph: String? = null,
    /**
     * The cell's current attention band, lowercased: `"none"` / `"low"` /
     * `"normal"` / `"high"` (`civictech.cell.control.AttentionBand`), or null.
     *
     * V2-BE — no longer "always null". `ManagedHost.attentionOf(ref)`
     * (V2-KERNEL) made the band readable without touching the cell, so this
     * field now carries it. Null still means something precise and is never a
     * guess: the cell is not locally hosted, or its host runs without an
     * `AttentionPolicy` — with no policy no band is in effect anywhere, and
     * reporting `"normal"` would invent a scheduling fact.
     *
     * **Widens the contract**, which documents `"focus" | "idle" | null`
     * (`20-api-contract.md` §CellDetail). The widening is safe because the
     * field has never carried a non-null value in any release, so no client
     * can regress on it; flagged in the V2-BE report rather than edited into
     * the contract, which is orchestrator-owned.
     */
    val attention: String? = null,
    val links: LinkCounts,
)

/** `CellDetail.links` — the per-cell link census. */
@Serializable
data class LinkCounts(
    val inbound: Int,
    val outbound: Int,
    /**
     * Observe-role edges. Always 0 in M1: `FanOutlet.tap` attachments are not
     * recorded in the registry's `TopologyIndex` (only `ManagedHost.connect`
     * writes there), so there is nothing to count — the same limitation M0
     * reported as `Edge.role` always `CONSUME`.
     */
    val taps: Int,
)

/**
 * `GET /api/inspect/cell/{ref}/state`.
 *
 * V1C-BE added [provenance], [page] and [unreadable], all additive and all
 * defaulted, so an M1–V3 client decoding this shape is unaffected and a client
 * coded against this shape decodes an older server's response unchanged
 * (`10-design-notes.md` binding constraint 8).
 */
@Serializable
data class CellState(
    val ref: String,
    /**
     * A **wave** position, and deliberately still null for [PAGE]/[SNAPSHOT]:
     * only an observation's materialized fold has one ([StampedView]). A bounded
     * read's currency is a `TagFrontier` — a different clock, never a wave
     * position (`MessageContext.kt:58-72`, spec 20/21 §Pull, 93 I-24) — so
     * stamping a paged read with a wave would be exactly the lie this field
     * exists to prevent. What a paged read offers instead is
     * [StatePageView.walkStable].
     */
    val frontier: WaveStamp? = null,
    /** [VIEW], [SNAPSHOT], [PAGE] or [UNAVAILABLE]. */
    val kind: String,
    /** The contract's `Value` — see [ValueEncoder]. `null` when [kind] is [UNAVAILABLE]. */
    val value: JsonElement = JsonNull,
    /** Milliseconds since the reported value last effectively changed. */
    val staleMs: Long = 0,
    /**
     * V1C-BE — **where the bytes came from**: [LIVE], [LIVE_SUSPENDED] or
     * [CHECKPOINT], straight off the kernel's `Provenance`
     * (`ManagedHost.readState` mints it; the cell never sees [CHECKPOINT]).
     *
     * Non-null exactly when [kind] is [PAGE] or [SNAPSHOT]. Null for [VIEW] — a
     * fold materialized in the inspector's own heap is neither a live cell read
     * nor a checkpoint, and claiming [LIVE] would blur the one distinction this
     * field exists to make — and null for [UNAVAILABLE].
     */
    val provenance: String? = null,
    /** V1C-BE — present iff [kind] is [PAGE]; null otherwise. See [StatePageView]. */
    val page: StatePageView? = null,
    /**
     * V1C-BE — present iff [kind] is [UNAVAILABLE]: **which** nothing this is.
     * One of [MIGRATING], [REMOTE], [NOT_STATEFUL], [UNANSWERED], [TERMINATED],
     * [READ_FAILED] or [UNKNOWN].
     */
    val unreadable: String? = null,
) {
    companion object {
        /** Read from a live observation's materialized fold — torn-read-free. */
        const val VIEW = "view"

        /**
         * One whole copy of the cell's state. Unchanged in meaning since M1;
         * now also the answer for a cell that does not implement the kernel's
         * `BoundedStateful`, which is why it is not a legacy value.
         */
        const val SNAPSHOT = "snapshot"

        /** V1C-BE — one bounded page of a walk. Carries [page]. */
        const val PAGE = "page"

        /** Nothing honest to report; [unreadable] says which nothing. */
        const val UNAVAILABLE = "unavailable"

        // ---------------------------------------------------------- provenance

        /** Read from the running cell on its own execution context. */
        const val LIVE = "live"

        /**
         * Read from a SUSPENDED cell's own fold. Quiescent by construction, so
         * this is the *most* stable read in the graph, not a degraded one.
         * Reading it resumed nothing, woke nothing and raised no attention.
         */
        const val LIVE_SUSPENDED = "liveSuspended"

        /**
         * Read from the blob a DRAINED host already retains from its drain
         * (`ManagedHost.beginDrain`). State as of the drain, not as of now, and
         * no cell thread was scheduled to produce it. The one provenance that is
         * stale by construction; clients must label it.
         */
        const val CHECKPOINT = "checkpoint"

        // ---------------------------------------------------------- unreadable

        /**
         * Held for a repartition flip, or already migrated. The authoritative
         * instance is another host's; a stale local read would be a lie with a
         * timestamp on it.
         */
        const val MIGRATING = "migrating"

        /**
         * No local host (`Node.host == null`). A wave-neutral read is not an
         * emission and so passes through no disclosure filter; it does not cross
         * a bridge. Unchanged from M5, and deliberate.
         */
        const val REMOTE = "remote"

        /** The cell holds no readable state at all. */
        const val NOT_STATEFUL = "notStateful"

        /**
         * The read did not land inside the server's bounded wait. Nothing was
         * read; a retry may succeed.
         *
         * Also the answer when a page *was* read but could not be rendered whole
         * — the byte budget cut entries and the re-read that would have narrowed
         * the page to exactly what it shows could not be completed. Serving that
         * page would advance the cursor past entries appearing nowhere in the
         * response, so nothing is served instead. See `PagedState.paged`.
         *
         * **The only [UNAVAILABLE] arm that is transient, and the only one whose
         * `?cursor=` survives it.** Nothing was served, so the walk position the
         * request carried was not spent: the same cursor id stays resumable and
         * the correct client response is to re-send *that* request, not to
         * restart the walk. Every other arm is terminal for the walk. See
         * `PagedState.read`.
         */
        const val UNANSWERED = "unanswered"

        /** The host's scheduler is terminated — a dead host has no state to read. */
        const val TERMINATED = "terminated"

        /** The cell's own `readBounded`/`snapshot` threw. A broken cell, not a broken read. */
        const val READ_FAILED = "readFailed"

        /**
         * A kernel `StateReadResult.Reason` this server build does not map.
         * Forward-compatibility, never a guess.
         */
        const val UNKNOWN = "unknown"
    }
}

/**
 * `CellState.page` (V1C-BE) — one bounded page of a walk over a cell's state,
 * served by `GET /api/inspect/cell/{ref}/state?cursor=&limit=`.
 *
 * Present exactly when `CellState.kind` is [CellState.PAGE]. A whole
 * [CellState.SNAPSHOT] has no page contract — that is the older unbounded seam,
 * and its absence is how a client knows no bounded read was available.
 */
@Serializable
data class StatePageView(
    /**
     * **Opaque.** Echo it back verbatim as `?cursor=` to fetch the next page;
     * null means the walk is complete. Never parse one, never construct one,
     * never reuse one: each response mints a fresh cursor and retires the one
     * that produced it. A stale, unknown, expired or wrong-cell cursor answers
     * **410** — drop it and restart the walk from page 1.
     *
     * Server-minted id into a bounded table, never an encoding of the kernel's
     * own `Cursor`: no client-supplied bytes are ever deserialized (see
     * [CursorTable]).
     */
    val cursor: String? = null,
    /**
     * The limit actually applied. The server clamps `?limit=` to
     * `1..InspectorServer.PAGE_LIMIT_MAX`, so this is how a client learns its
     * request was reduced.
     */
    val limit: Int,
    /**
     * Entries in **this** page, as the cell counted them before encoding — what
     * the cursor advanced past.
     *
     * `value` renders every one of them that the contract's state interpretation
     * keeps: the server never serves a page whose entries the encoder's byte
     * budget cut, it re-reads a smaller page instead. So a `$truncated` marker
     * inside `value` means one *value* was abbreviated, never that entries went
     * missing; [cursor] is the one and only signal that more state exists.
     */
    val entries: Int,
    /**
     * Entries on this page whose value is an `Owned`/`Leased` payload. The
     * kernel pages a presence descriptor (key, declared type, disposition) for
     * those and never a copy of the payload (spec 23 §Ownership; V1C-KERNEL
     * Decision 3), so `> 0` means this page is deliberately incomplete in a way
     * no further page will ever fill in. Render it: it is a fact about the data,
     * not a diagnostic about the read.
     */
    val exclusivesElided: Int = 0,
    /**
     * The only consistency claim a paged read makes, and it is **verified**
     * server-side rather than promised (V1C-KERNEL Decision 5): the walk's
     * closing tag frontier compared against its opening one.
     *
     * - `false` — **proof** the fold changed mid-walk. The union is a SMEARED
     *   read: it contains every entry present for the whole walk, may contain
     *   entries added mid-walk, and may miss entries removed mid-walk after
     *   being passed over. It is never torn at entry granularity and never
     *   returns an entry twice.
     * - `true` — the closing stamp equalled the opening one. **Necessary, not
     *   sufficient**, for "the union is a snapshot", and how far short it falls
     *   depends on the cell family: a `TagFrontier` measures tag *gains* only,
     *   so an OR-set observed-remove (which mints no tag) is invisible to it in
     *   every family; and in the non-retaining families — every cell under
     *   `civictech.cell.data.op` and `ShardCell` — the stamp can also *fall*, so
     *   a mid-walk gain can be masked by a mid-walk loss and equality excludes
     *   nothing at all. Render `true` as "not observed to change", never as
     *   "this is a snapshot".
     * - `null` — not determined: the walk has not closed and this page carries
     *   only the opening stamp ([STALE_FRONTIER]), or the cell reports no tag
     *   frontier at all. Render it as neither; it is not a `false`.
     */
    val walkStable: Boolean? = null,
    /**
     * The kernel's own declared weakenings for this page ([STALE_FRONTIER],
     * [POSITIONAL_CURSOR]) — `StatePage.caveats`, forwarded rather than
     * inferred, and accumulated across the walk so a client joining at page 4
     * still learns that this walk's cursor is positional.
     */
    val caveats: List<String> = emptyList(),
    /**
     * Cell-level state that is not a per-entry row and rides *every* page —
     * `SetCell`'s tag `counter`, `ShardCell`'s `interest`/`assignedEpoch`,
     * `OperatorPaging`'s `mintCounter`/`lanes`. Each value is encoded as a
     * contract `Value`, like [CellState.value].
     *
     * Surfaced rather than dropped, deliberately: a client reading page 4 of a
     * shard walk would otherwise be unable to tell whether the walk straddled a
     * repartition.
     */
    val attributes: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        /**
         * `ReadCaveat.STALE_FRONTIER` — this page carries the walk's opening
         * frontier, not the fold's frontier at this page's production. The first
         * and last page of a walk always carry an exact one.
         */
        const val STALE_FRONTIER = "staleFrontier"

        /**
         * `ReadCaveat.POSITIONAL_CURSOR` — the cursor is positional, not
         * key-based (the documented exception for a family with no element
         * identity, `ListCell`). A removal earlier in the sequence can shift or
         * skip an entry, so "no entry twice in one walk" and "every surviving
         * entry appears" both weaken to best-effort.
         */
        const val POSITIONAL_CURSOR = "positionalCursor"
    }
}

/** A wave position: `civictech.cell.Timestamp` on the wire. */
@Serializable
data class WaveStamp(val source: String, val counter: Long)

/**
 * `flow.rates` (contract §SSE) — one aggregation window. Edges that carried no
 * traffic in the window are omitted, so an all-quiet window is an empty
 * [edges]; the batch itself is still sent, because the client's decay rule
 * counts *received* windows.
 */
@Serializable
data class FlowBatch(
    /** The aggregation window in milliseconds. */
    val window: Long,
    val edges: List<FlowEdgeRate>,
)

/** One edge's traffic in one [FlowBatch] window. */
@Serializable
data class FlowEdgeRate(
    /** The [Edge.id] this rate belongs to. */
    val id: String,
    /** Messages per second over the window. */
    val rate: Double,
    /** The wave the window's last observed emission carried, when one was stamped. */
    val lastWave: WaveStamp? = null,
    /** That emission's `MessageContext.hop`. */
    val hop: Int? = null,
)

/**
 * V2 — one row of the activity feed (`98-inspector-v4-plan/10-design-notes.md`
 * §"Verticals → V2"): *when* a cell changed lifecycle, not merely what it is
 * now. The `lifecycle` SSE event says "this cell is suspended"; this says "it
 * was passivated at 14:02:11 and woken at 14:02:40".
 *
 * Built from extracted primitives at capture time and never holding anything
 * of the cell — the same rule [DeadLetterRow] follows, for the same reason.
 * See [Activity] for the five kinds' sources.
 */
@Serializable
data class ActivityEntry(
    /** `InspectorServer.encodeRef` — the same `"<uuid>:<instanceId>"` every other row carries. */
    val ref: String,
    /** [ACTIVATED], [PASSIVATED], [DRAINED], [WOKEN] or [RESTARTED]. */
    val kind: String,
    /** Wall clock at capture, as [DeadLetterRow.atMs]. */
    val atMs: Long,
    /**
     * The new recovery generation — present on a [RESTARTED] entry, **absent**
     * on every other kind.
     *
     * `@EncodeDefault(NEVER)` rather than the module-wide `encodeDefaults =
     * true` (see [inspectorJson]): here the default genuinely means "this kind
     * of entry has no generation", and the contract V2-FE codes against says
     * absent, not `null`. The client's optional-field decode reads both the
     * same way, so this is a fidelity choice, not a compatibility one.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @OptIn(ExperimentalSerializationApi::class)
    val generation: Long? = null,
) {
    companion object {
        /** A cell resumed, or was reactivated by `resumeHost`. */
        const val ACTIVATED = "activated"

        /** A cell was suspended — explicit `suspend`, or `SupervisionPolicy.SUSPEND`. */
        const val PASSIVATED = "passivated"

        /** The cell's host finished draining; one entry per cell it held. */
        const val DRAINED = "drained"

        /** The inspector's own `POST /graph/{id}/wake` acted on this cell (see [Waker]). */
        const val WOKEN = "woken"

        /** A supervision restart, observed as a generation increase (see [Errors]). */
        const val RESTARTED = "restarted"
    }
}

/**
 * `GET /api/inspect/activity` — the retained activity ring, oldest first, at
 * most `Activity.RING_CAPACITY` entries; an empty ring answers
 * `{"entries": []}`, never a 404.
 */
@Serializable
data class ActivitySnapshot(val entries: List<ActivityEntry>)

/** The SSE envelope: `data: {"seq":…,"kind":…,"payload":{…}}\n\n`. */
@Serializable
data class Event(
    val seq: Long,
    val kind: String,
    val payload: JsonObject,
) {
    companion object {
        const val TOPOLOGY_NODE = "topology.node"
        const val TOPOLOGY_LINK = "topology.link"
        const val LIFECYCLE = "lifecycle"
        const val STATE_SUMMARY = "state.summary"
        const val ERROR_DEAD_LETTER = "error.deadLetter"
        const val ERROR_PARKED = "error.parked"
        const val ERROR_RESTART = "error.restart"

        /**
         * V3 — one [WaveHealthRow], the live half of `ErrorSnapshot.waveHealth`.
         * A row carrying `state: "cleared"` clears the open row with the same
         * `id`, the same convention `error.parked`'s `count: 0` established.
         */
        const val ERROR_WAVE_HEALTH = "error.waveHealth"
        const val FLOW_RATES = "flow.rates"

        /** V2 — one [ActivityEntry], the live half of `GET /api/inspect/activity`. */
        const val ACTIVITY = "activity"
        const val GRAPHS_CHANGED = "graphs.changed"
        const val HEARTBEAT = "heartbeat"

        const val ADDED = "added"
        const val REMOVED = "removed"
    }
}

/** `GET /api/inspect/errors`. */
@Serializable
data class ErrorSnapshot(
    val counters: ErrorCounters,
    val deadLetters: List<DeadLetterRow>,
    val parked: List<ParkedRow>,
    val restarts: List<RestartRow>,
    /**
     * V3 — the **currently open** wave-health rows (see [WaveHealthRow] and
     * [WaveHealth]), a live gauge in the manner of [parked] and deliberately
     * *not* a history log: a row appears while its condition holds and
     * disappears — with one `state: "cleared"` SSE event — when it resolves.
     * Bounded by [WaveHealth.WAVE_HEALTH_MAX_OPEN].
     */
    val waveHealth: List<WaveHealthRow> = emptyList(),
)

/**
 * `ErrorSnapshot.counters` — running totals across every inspected host, read
 * straight off [civictech.cell.host.ManagedHost.supervisionAccounting] (deadLetters,
 * restarts, drainedOnTeardown) except [parked], which has no accounting
 * counterpart in the kernel and is instead the live sum of every currently
 * parked row (a gauge, not a monotonic count — it falls as parked traffic
 * drains).
 */
@Serializable
data class ErrorCounters(
    val deadLetters: Long,
    val parked: Long,
    val restarts: Long,
    val drainedOnTeardown: Long,
    /**
     * V3 — how many wave-health rows are open **right now**. Like [parked] and
     * unlike its three monotonic siblings this is a *gauge*: it falls as
     * conditions resolve. It counts a heuristic diagnostic, never a
     * kernel-grade detection — see [WaveHealthRow].
     */
    val waveHealth: Long = 0,
)

/**
 * V3 — one wave-health row: a **heuristic diagnostic**, never a kernel-grade
 * detection of a lost or stuck wave.
 *
 * Computed by [WaveHealth] on the inspector's own scheduler thread by
 * correlating two things it already holds — the last wave observed on a tapped
 * producing outlet ([FlowCollector]) and the frontier stamp of a cell a client
 * explicitly observed ([StampedView.frontier]). Real detection needs per-source
 * per-edge watermarks, `Progress` absorb-acks and typed `Stall` markers that do
 * not exist (`doc/spec/20-dataflow-semantics/22-consistency.md` §Completeness
 * over silent or stuck edges, **G-40**), so every row says so: [heuristic] is
 * always `true` and the word "heuristic" always opens [description]. No row
 * asserts that a wave *is* lost, that a cell *is* stuck, or that glitch-freedom
 * *is* violated.
 */
@Serializable
data class WaveHealthRow(
    /**
     * `"<kind>:<edgeId>:<ref>"` — stable per (kind, edge, cell), so the open
     * row, its updates and its clear all carry one id.
     */
    val id: String,
    /** [FRONTIER_LAG] or [STALLED_WAVE]. */
    val kind: String,
    /** [OPEN] or [CLEARED]; a [CLEARED] row retires the open row with the same [id]. */
    val state: String,
    /** The observed cell whose frontier trails — always one of `Observations.openRefs`. */
    val ref: String,
    /** The tapped [Edge.id] the comparison used. */
    val edge: String,
    /**
     * The wave this row is about on that edge: the last live wave observed on
     * it for [FRONTIER_LAG], and the pinned wave that never arrived for
     * [STALLED_WAVE]. Baseline and re-baseline emissions are never taken as a
     * site's wave position (a baseline is deliberately not a wave position —
     * spec 20/21 §Pull, 93 I-24).
     */
    val wave: WaveStamp? = null,
    /** The observed cell's frontier at evaluation time. */
    val frontier: WaveStamp? = null,
    /** `wave.counter - frontier.counter`; only ever populated when the two share a source. */
    val lagWaves: Long? = null,
    /** How long the condition has held continuously, in milliseconds. */
    val heldMs: Long,
    val atMs: Long,
    /** Always `true`. This feed never claims certainty. */
    val heuristic: Boolean = true,
    /** Human-readable, always beginning with the word "heuristic". */
    val description: String,
) {
    companion object {
        /** An observed frontier trailing an upstream tapped outlet's last wave by more than a threshold. */
        const val FRONTIER_LAG = "frontierLag"

        /** A wave observed on a tapped edge that the observed frontier never reached. */
        const val STALLED_WAVE = "stalledWave"

        const val OPEN = "open"
        const val CLEARED = "cleared"
    }
}

/**
 * One retained dead letter — the ring buffer's element. Built once, at
 * capture time, from extracted primitives only: the [civictech.cell.host.DeadLetter]
 * and its [civictech.cell.proxy.HostedPortInvocation] are never held past that
 * conversion (ownership invariant — a dead letter can carry a sanitized but
 * still potentially large payload, and the inspector must not become a second
 * retention path for it).
 */
@Serializable
data class DeadLetterRow(
    val ref: String,
    /** The thrown exception's simple class name, or null for a drop (unknown target, no exception). */
    val cause: String? = null,
    val description: String,
    val wave: WaveStamp? = null,
    val atMs: Long,
    /**
     * V3 — what the failing call *was*, when the dead letter carried a
     * `HostedPortInvocation` at all. Null for a plain host-level drop (a
     * routing failure that never reached a target port, say).
     */
    val invocation: InvocationSummary? = null,
    /**
     * V3 — one entry per argument of [invocation], in argument order; empty
     * when there was no invocation or it took no arguments. Names the kernel's
     * own sanitization outcome, never a value — see [ArgDisposition].
     */
    val disposition: List<ArgDisposition> = emptyList(),
    /**
     * computenet-usd.7 — non-null exactly when this row reports a
     * `BoundaryPolicy` refusal ([civictech.cell.host.DeadLetter.denial]),
     * never a fault. Before this field, a refusal and a plain host-level drop
     * were both `cause == null`, and the only discriminator a client had was
     * parsing [description]'s `"boundary denial at exposure"` prefix — the
     * defect this field closes. Additive and defaulted, so an older client
     * decoding this shape is unaffected.
     */
    val denial: BoundaryDenialSummary? = null,
)

/**
 * computenet-usd.7 — [DeadLetterRow.denial]: the `BoundaryPolicy` refusal a
 * denial dead letter reports, extracted to primitives at capture time exactly
 * as [InvocationSummary] and [ArgDisposition] are — no kernel domain object
 * (`civictech.cell.BoundaryDenial`) is held past that conversion.
 */
@Serializable
data class BoundaryDenialSummary(
    /** `BoundarySeam.name` — which seam refused (`ADMISSION`, `LINK_AUTHORITY`, `PROTOCOL_AUTHORITY`, `DISCLOSURE`, `INTEGRITY`). */
    val seam: String,
    /** `DenialReason.name` — why it refused. */
    val reason: String,
    /** The membrane `Exposure.externalName` the refused crossing was addressed to. */
    val exposure: String,
    /**
     * The `PeerId` this refusal is attributed to, or null — see
     * `civictech.cell.BoundaryDenial.principal`'s KDoc: the convention differs
     * by seam, and null does not always mean the same thing.
     */
    val principal: String? = null,
    /** What was refused, named per seam (protocol id, contract/method, or null) — see `BoundaryDenial.subject`. */
    val subject: String? = null,
    /** Free-text specifics for the audit trail (the observed counter, the offending auth level, the refusing policy). */
    val detail: String? = null,
)

/**
 * V3 — [DeadLetterRow.invocation]: the failing call's *shape*, read off the
 * `HostedPortInvocation` at capture time and extracted to primitives there.
 * Declared type names only; no argument value, encoded or otherwise, appears
 * here or anywhere else on the row.
 */
@Serializable
data class InvocationSummary(
    /** `HostedPortInvocation.portName`. */
    val port: String,
    /** `PORT_API` / `PORT_MANAGEMENT` / `PORT_PROTOCOL` — `HostedPortInvocation.Type`. */
    val type: String,
    /** `Invocation.methodName`. */
    val method: String,
    /** `Invocation.parameterTypes` — *declared* type names, not values. */
    val parameterTypes: List<String> = emptyList(),
    val argCount: Int,
    /** The invocation context's `MessageContext.hop`, or null off the data path. */
    val hop: Int? = null,
)

/**
 * V3 — one argument's **ownership disposition**: the outcome of the kernel's
 * own dead-letter sanitization (`civictech.cell.host.DeadLetters`), read back.
 *
 * The dead-letter outlet is a fan-out, so a live exclusive handle must never
 * enter it (spec 23 R8, G-46): an `Owned` arrives already frozen (or
 * `Redacted` when it had been consumed before capture), a `Leased` arrives
 * released and replaced by a `Redacted` marker. That outcome is precisely what
 * an operator needs to see — "the exclusive payload on this failed call was
 * frozen / was released / was already consumed".
 *
 * [OWNED] and [LEASED] stay in the vocabulary as the honesty case: a live
 * exclusive handle reaching this outlet would be a kernel invariant violation,
 * and the row says so rather than mislabelling it.
 */
@Serializable
data class ArgDisposition(
    /** Position in `Invocation.args`. */
    val index: Int,
    /** [FROZEN], [REDACTED], [BORROWED], [OWNED], [LEASED] or [PLAIN]. */
    val ownership: String,
    /**
     * The kernel-authored `Redacted.reason`, truncated at
     * `Errors.REDACTION_REASON_MAX` characters. Null for every other
     * disposition — this is the one string on the row that comes from the
     * payload side, and it is written by the kernel, never by a value.
     */
    val reason: String? = null,
) {
    companion object {
        /** `civictech.cell.Frozen` — an `Owned` degenerated at capture. */
        const val FROZEN = "frozen"

        /** `civictech.cell.Redacted` — a released `Leased`, or an already-consumed `Owned`. */
        const val REDACTED = "redacted"

        /** `civictech.cell.Borrowed` — a read-only snapshot view, fan-out safe. */
        const val BORROWED = "borrowed"

        /** `civictech.cell.Owned` reaching the fan-out outlet live — never expected. */
        const val OWNED = "owned"

        /** `civictech.cell.Leased` reaching the fan-out outlet live — never expected. */
        const val LEASED = "leased"

        /** An ordinary value under no ownership contract. */
        const val PLAIN = "plain"
    }
}

/** One `(ref, port)` group of currently parked traffic — a live gauge, never retained history. */
@Serializable
data class ParkedRow(
    val ref: String,
    val port: String,
    val count: Int,
    val oldestMs: Long,
)

/**
 * One observed generation increase — [civictech.cell.host.ManagedHost.generationOf]
 * going up.
 *
 * V3 adds the connective tissue of the supervision *timeline*
 * (`ManagedHost`'s RESTART branch, in order: dead-letter the failure → bump the
 * generation → mint a fresh emission epoch per outlet → re-baseline if the cell
 * is `ReBaselineEmitting`). The kernel reports those as unrelated events, so
 * the two extra halves below are **observed correlations, not kernel-reported
 * facts**, and each says exactly how far its honesty reaches.
 */
@Serializable
data class RestartRow(
    val ref: String,
    val generation: Long,
    val atMs: Long,
    /**
     * The simple class name of the throwable on the most recent dead letter
     * captured for this same ref within `Errors.RESTART_CAUSE_WINDOW_MS`
     * *preceding* this generation bump.
     *
     * **A time-window correlation, not a kernel-reported restart cause.** No
     * seam reports the failure and the restart as one event; a coincidental
     * dead letter for the same ref inside the window would be attributed here.
     * Null when no candidate exists — never a guess.
     */
    val cause: String? = null,
    /** When that dead letter was captured, or null when [cause] is null. */
    val causeAtMs: Long? = null,
    /**
     * When a re-baseline beat was **observed** on one of this cell's tapped
     * outgoing edges, or null.
     *
     * **`null` means "not observed", never "did not happen".** Only a cell
     * implementing `civictech.cell.ReBaselineEmitting` re-baselines at all
     * (today `civictech.cell.data.op.UnionSetCell` is the only kernel
     * implementation), and the cell must additionally have at least one tapped
     * outgoing edge for the inspector to see the beat. A client must render
     * absence, not a negative claim.
     */
    val reBaselineAtMs: Long? = null,
)

/** `GET /api/inspect/graphs` — every connected component this inspector can see. */
@Serializable
data class GraphList(val graphs: List<GraphSummary>)

/** One component's navigator card. */
@Serializable
data class GraphSummary(
    /** `g-<lexicographically-min member uuid>` — see [ComponentIndex]. */
    val id: String,
    /** A host-supplied annotation ([InspectorServer.nameGraph]); null = unnamed, and the UI renders [id]. */
    val name: String? = null,
    val cells: Int,
    /** Distinct process-host (`ManagedHost`) names among the members. */
    val hosts: Int,
    /**
     * Distinct network hosts among the members — 1 for a single-JVM component,
     * more once a peer's cells join it (M5-NET).
     */
    val nets: Int,
    val health: GraphHealth,
    /**
     * The contract's `"hot" | "cold"`, lowercase (unlike [Node.lifecycle]).
     * [COLD] once every member cell is parked — see [Component.lifecycle] for
     * the predicate and [Heat] for what each parked state means.
     */
    val lifecycle: String = HOT,
) {
    companion object {
        const val HOT = "hot"

        /** M5-COLD: every member cell is suspended, or on a drained host. */
        const val COLD = "cold"
    }
}

/** `GraphSummary.health` — error counters scoped to one component's refs. */
@Serializable
data class GraphHealth(
    val deadLetters: Int,
    val parked: Int,
    val restarts: Int,
)

/** `GET /api/inspect/search`. */
@Serializable
data class SearchResult(
    /** [NAME], [PROBLEMS] or [DATA]. */
    val mode: String,
    val hits: List<SearchHit>,
    /**
     * Data-mode only, and non-null on every data response — including one that
     * matched nothing, since "this query cost four cell reads and found
     * nothing" is exactly the answer a user needs. Null for [NAME]/[PROBLEMS],
     * which read only metadata the inspector already holds. See [DataSearch].
     */
    val cost: SearchCost? = null,
) {
    companion object {
        const val NAME = "name"
        const val PROBLEMS = "problems"
        const val DATA = "data"
    }
}

/** One search hit: a graph, optionally a cell inside it. */
@Serializable
data class SearchHit(
    val graph: String,
    /** The cell this hit points at, or null for a whole-graph hit. */
    val ref: String? = null,
    val label: String,
    val detail: String,
)

/**
 * `SearchResult.cost` — what a data-mode fan-out touched (M5-SEARCH). Surfacing
 * this is a product requirement, not diagnostics: content search is the one
 * inspector read that costs the graph something, so the price is part of the
 * answer. See [DataSearch] for what each number counts.
 */
@Serializable
data class SearchCost(
    /** Cells whose state this search actually read. */
    val cellsQueried: Int,
    /** Candidate cells skipped as not hot — suspended, or held mid-migration. */
    val coldSkipped: Int,
)
