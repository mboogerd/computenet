package civictech.inspect.edit

import civictech.cell.CellRef
import civictech.cell.graph.BoundaryLink
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.Direction
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.GraphStep
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.InstanceSetStep
import civictech.cell.graph.InstanceSpec
import civictech.cell.graph.Plan
import civictech.cell.graph.PlannedAction
import civictech.cell.graph.PlannedStep
import civictech.cell.graph.RefusalCode
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.StepCheck
import civictech.cell.graph.Verdict
import civictech.cell.link.Interest
import civictech.inspect.InspectorServer.Companion.decodeRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.UUID

/*
 * WKB2 F12 (computenet-va0c4, va0c4-D5..D8) — lowers a browser draft to an
 * ordinary kernel GraphSpec ([WKB2-02]: parameters, not verbs) plus the
 * boundary links that join it to live cells. Every node resolves through
 * [Catalogue]; nothing here constructs a cell, and `compile` takes no host, so
 * a refused draft trivially spawns nothing (feature rule 4).
 */

/**
 * The browser's draft graph (va0c4-D6). **This is the wire DTO for
 * `POST /apply/precheck` too: WKB2 F6 imports it from here and must not
 * redefine it.** kotlinx-serializable and `inspectorJson`-compatible.
 */
@Serializable
data class DraftDto(
    val nodes: List<DraftNodeDto>,
    val edges: List<DraftEdgeDto> = emptyList(),
)

/**
 * One draft node: a catalogue entry instantiated under a spec-local [handle].
 * [params] is decoded by the entry's [ParamSchema] kinds; [replaces] is an
 * encoded live ref (`"<uuid>:<instanceId>"`) whose logical id the new cell
 * inherits; [replicas] (≥ 1) makes the node an `Interest.Total` instance set.
 * [parent] is the handle of a draft node to nest under (organelle nesting) and
 * is not expressible on an instance set.
 */
@Serializable
data class DraftNodeDto(
    val handle: String,
    val catalogueId: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val replaces: String? = null,
    val parent: String? = null,
    val replicas: Int? = null,
)

/** A draft edge, outlet side [from] to inlet side [to]. At most one side may be a live ref. */
@Serializable
data class DraftEdgeDto(val from: DraftEndpointDto, val to: DraftEndpointDto)

/** One edge endpoint: exactly one of a draft [handle] or an encoded live [ref], and the [port] name. */
@Serializable
data class DraftEndpointDto(
    val handle: String? = null,
    val ref: String? = null,
    val port: String,
)

/**
 * A draft malformed at the DTO level (va0c4-D7): shape, not a node's refusal.
 * The route answers it 400 with [reason].
 */
class DraftException(val reason: String) : IllegalArgumentException(reason)

/** The compiler's answer (va0c4-D7). */
sealed interface Compiled {
    /** Every node resolved: the spec (instance sets unlowered, va0c4-D8) and its boundary links. */
    data class Ok(val spec: GraphSpec, val boundary: List<BoundaryLink>) : Compiled

    /**
     * At least one node did not resolve: one refused `SPAWN` step per faulty
     * node, keyed by its handle, every faulty node reported together, under
     * `Verdict.NotAppliable`. Good nodes are not planned — they never reached
     * precheck.
     */
    data class Refused(val plan: Plan) : Compiled
}

object DraftCompiler {

    /**
     * Lowers [draft] (va0c4-D6). Throws [DraftException] for a DTO-level fault
     * (va0c4-D7): an endpoint with both or neither of `handle`/`ref`, a
     * ref→ref edge, an undecodable `ref`/`replaces`, `replicas < 1`, a
     * duplicate handle, or a `parent` on an instance set. An edge naming a
     * handle no node declares is not a compile error: its `ConnectStep` or
     * `BoundaryLink` is emitted and precheck refuses it `UNRESOLVED_HANDLE`.
     */
    fun compile(draft: DraftDto): Compiled {
        checkShape(draft)

        val steps = mutableListOf<GraphStep>()
        val refused = mutableListOf<PlannedStep>()
        draft.nodes.forEach { node ->
            when (val lowered = lowerNode(node)) {
                is NodeResult.Lowered -> steps += lowered.step
                is NodeResult.Faulty -> refused += PlannedStep(
                    key = node.handle,
                    handle = node.handle,
                    action = PlannedAction.SPAWN,
                    touches = emptySet(),
                    result = StepCheck.Refused(lowered.code, lowered.reason),
                )
            }
        }
        if (refused.isNotEmpty()) return Compiled.Refused(Plan(refused, Verdict.NotAppliable(refused)))

        val boundary = mutableListOf<BoundaryLink>()
        draft.edges.forEach { edge ->
            val from = edge.from
            val to = edge.to
            when {
                from.handle != null && to.handle != null ->
                    steps += ConnectStep(from.handle, from.port, to.handle, to.port)
                from.ref != null && to.handle != null ->
                    boundary += BoundaryLink(ref(from.ref, "edge source"), from.port, to.handle, to.port, Direction.INBOUND)
                from.handle != null && to.ref != null ->
                    boundary += BoundaryLink(ref(to.ref, "edge target"), to.port, from.handle, from.port, Direction.OUTBOUND)
                else -> error("unreachable: checkShape admits no ref->ref edge")
            }
        }
        return Compiled.Ok(GraphSpec(steps), boundary)
    }

    private sealed interface NodeResult {
        data class Lowered(val step: GraphStep) : NodeResult
        data class Faulty(val code: RefusalCode, val reason: String) : NodeResult
    }

    private fun lowerNode(node: DraftNodeDto): NodeResult {
        val entry = Catalogue.entry(node.catalogueId)
            ?: return unknownId(node)
        val faults = mutableListOf<String>()
        val declared = entry.schema.params.associateBy { it.name }
        val params = mutableMapOf<String, ParamValue>()
        node.params.forEach { (name, json) ->
            val spec = declared[name]
            if (spec == null) {
                faults += "unknown parameter '$name'"
            } else {
                decode(spec, json)?.let { params[name] = it }
                    ?: run { faults += "parameter '$name' expects ${spec.kind}${enumValues(spec)}, got $json" }
            }
        }
        if (faults.isNotEmpty()) return invalidParams(node, faults.joinToString("; "))

        val factory = try {
            Catalogue.resolve(node.catalogueId, params)
        } catch (e: IllegalArgumentException) {
            // An entry unregistered between `entry` and `resolve` is still an unknown id.
            if (Catalogue.entry(node.catalogueId) == null) return unknownId(node)
            return invalidParams(node, e.message ?: e.toString())
        }

        val incumbent = node.replaces?.let { ref(it, "replaces of node '${node.handle}'") }
        val replicas = node.replicas
        val step = if (replicas == null) {
            SpawnStep(
                handle = node.handle,
                factory = factory,
                identity = incumbent?.let { IdentityBinding.NewInstanceOf(it.id) } ?: IdentityBinding.FreshLogical,
                parent = node.parent,
            )
        } else {
            // va0c4-D8: left unlowered; precheck and the applier lower it.
            InstanceSetStep(
                handle = node.handle,
                logicalId = incumbent?.id ?: UUID.randomUUID(),
                factory = CatalogueInstanceFactory(factory.id, factory.params),
                instances = (0 until replicas).map { InstanceSpec(Interest.Total, it) },
            )
        }
        return NodeResult.Lowered(step)
    }

    private fun unknownId(node: DraftNodeDto) = NodeResult.Faulty(
        RefusalCode.UNKNOWN_CATALOGUE_ID,
        "draft node '${node.handle}': catalogue id '${node.catalogueId}' is not registered",
    )

    private fun invalidParams(node: DraftNodeDto, detail: String) = NodeResult.Faulty(
        RefusalCode.INVALID_PARAMS,
        "draft node '${node.handle}' (catalogue entry '${node.catalogueId}'): $detail",
    )

    private fun enumValues(spec: ParamSpec) = if (spec.kind == ParamKind.ENUM) " (one of ${spec.values})" else ""

    /** va0c4-D6: a JSON value decoded by [spec]'s kind; null when it does not fit. */
    private fun decode(spec: ParamSpec, json: JsonElement): ParamValue? {
        if (json !is JsonPrimitive || json is JsonNull) return null
        return when (spec.kind) {
            ParamKind.STRING -> json.takeIf { it.isString }?.let { ParamValue.Str(it.content) }
            ParamKind.ENUM -> json.takeIf { it.isString }?.let { ParamValue.Enum(it.content) }
            ParamKind.INT -> json.takeUnless { it.isString }?.content?.toIntOrNull()?.let(ParamValue::I32)
            ParamKind.LONG -> json.takeUnless { it.isString }?.content?.toLongOrNull()?.let(ParamValue::I64)
            ParamKind.BOOLEAN -> json.takeUnless { it.isString }?.booleanOrNull?.let(ParamValue::Bool)
            ParamKind.REF -> json.takeIf { it.isString }?.let { decodeRef(it.content) }?.let(ParamValue::Ref)
        }
    }

    private fun ref(encoded: String, role: String): CellRef =
        decodeRef(encoded) ?: throw DraftException("$role: '$encoded' is not an encoded cell ref (\"<uuid>:<instanceId>\")")

    /** Every va0c4-D7 DTO-level fault, checked before any node is resolved. */
    private fun checkShape(draft: DraftDto) {
        val seen = mutableSetOf<String>()
        draft.nodes.forEach { node ->
            if (!seen.add(node.handle)) throw DraftException("duplicate draft handle '${node.handle}'")
            node.replicas?.let {
                if (it < 1) throw DraftException("draft node '${node.handle}': replicas must be >= 1, got $it")
                if (node.parent != null) {
                    throw DraftException("draft node '${node.handle}': parent is not expressible on an instance set (replicas = $it)")
                }
            }
            node.replaces?.let { ref(it, "replaces of node '${node.handle}'") }
        }
        draft.edges.forEachIndexed { i, edge ->
            endpoint(edge.from, "edge $i source")
            endpoint(edge.to, "edge $i target")
            if (edge.from.ref != null && edge.to.ref != null) {
                throw DraftException("edge $i joins two live refs (${edge.from.ref} -> ${edge.to.ref}); an edge must touch the draft")
            }
        }
    }

    private fun endpoint(endpoint: DraftEndpointDto, role: String) {
        when {
            endpoint.handle != null && endpoint.ref != null ->
                throw DraftException("$role names both handle '${endpoint.handle}' and ref '${endpoint.ref}'; exactly one is allowed")
            endpoint.handle == null && endpoint.ref == null ->
                throw DraftException("$role names neither a handle nor a ref")
            endpoint.ref != null -> ref(endpoint.ref, role)
        }
    }
}
