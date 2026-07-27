package civictech.inspect

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The wire shapes of `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`.
 * The contract is binding: field names and value vocabularies here are copied
 * from it, not invented. Fields the milestone cannot answer yet carry their
 * contract-declared null/placeholder (`fused`, `graph`, `net`) rather than a
 * guess.
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
    /** Process host (`ManagedHost`) name. */
    val host: String? = null,
    /** Network host / peer id — `"local"` until M5. */
    val net: String = LOCAL_NET,
    val lifecycle: String = HOT,
    val generation: Long = 0,
    /** Component id — null until M4. */
    val graph: String? = null,
) {
    companion object {
        const val LOCAL_NET = "local"
        const val HOT = "HOT"
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
    /** Best-effort; null when unknown — M0 does not detect fusion and never guesses. */
    val fused: Boolean? = null,
) {
    companion object {
        const val CONSUME = "CONSUME"
    }
}

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
        const val HEARTBEAT = "heartbeat"

        const val ADDED = "added"
        const val REMOVED = "removed"
    }
}
