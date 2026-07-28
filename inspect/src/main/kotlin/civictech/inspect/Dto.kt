package civictech.inspect

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
     * `"focus"` / `"idle"`, or null. **Always null in M1**: the attention band
     * lives on the cell object (`AttentionSupport.of(cell).band`, reachable
     * only through `ManagedHost`'s private `cells` map), and the ticket
     * forbids adding kernel surface for it — so the contract's null is the
     * honest answer rather than a guess.
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

/** `GET /api/inspect/cell/{ref}/state`. */
@Serializable
data class CellState(
    val ref: String,
    val frontier: WaveStamp? = null,
    /** [VIEW], [SNAPSHOT] or [UNAVAILABLE]. */
    val kind: String,
    /** The contract's `Value` — see [ValueEncoder]. `null` when [kind] is [UNAVAILABLE]. */
    val value: JsonElement = JsonNull,
    /** Milliseconds since the reported value last effectively changed. */
    val staleMs: Long = 0,
) {
    companion object {
        /** Read from a live observation's materialized fold — torn-read-free. */
        const val VIEW = "view"

        /** Read from a host-routed `Stateful.snapshot()`. */
        const val SNAPSHOT = "snapshot"

        /** No observation and no snapshot source — nothing honest to report. */
        const val UNAVAILABLE = "unavailable"
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
        const val FLOW_RATES = "flow.rates"
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
)

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
)

/** One `(ref, port)` group of currently parked traffic — a live gauge, never retained history. */
@Serializable
data class ParkedRow(
    val ref: String,
    val port: String,
    val count: Int,
    val oldestMs: Long,
)

/** One observed generation increase — [civictech.cell.host.ManagedHost.generationOf] going up. */
@Serializable
data class RestartRow(
    val ref: String,
    val generation: Long,
    val atMs: Long,
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
