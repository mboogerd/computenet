package civictech.demo.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val studioKinds = setOf("text", "gitRange", "issueSet", "judgment", "json", "any")

@Serializable
data class StudioType(val kind: String = "any", val extras: Set<String> = emptySet(), val schema: JsonObject? = null) {
    val fields: Set<String> get() = setOf("old", "new") + extras + when (kind) {
        "gitRange" -> setOf("repo")
        "issueSet" -> setOf("workspace", "added", "removed", "changed")
        "judgment" -> setOf("probability", "reason")
        else -> emptySet()
    }
}
@Serializable
data class InputRequirement(val fields: Set<String> = emptySet(), val kind: String? = null)
@Serializable
data class NodeTypes(val inputs: Map<String, InputRequirement> = emptyMap(), val output: StudioType = StudioType())
internal enum class Admission { ACCEPT, CHECK_ON_ARRIVAL, REJECT }
internal data class Compatibility(val admission: Admission, val message: String? = null)

internal data class Placeholder(val start: Int, val end: Int, val port: String, val field: String, val kind: String?)
private val token = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)(?::([A-Za-z_][A-Za-z0-9_]*))?(?:\\.([^{}]+))?}}")

/** One grammar for discovery, annotations, field requirements and substitution. Fields stay flat. */
internal fun placeholders(template: String): List<Placeholder> {
    val matches = token.findAll(template).toList()
    if (token.replace(template, "").let { "{{" in it }) bad("invalid placeholder")
    return matches.map {
        val (port, annotation, field) = it.destructured
        if (annotation.isNotEmpty() && annotation !in studioKinds) bad("$port: unknown type '$annotation'")
        Placeholder(it.range.first, it.range.last + 1, port, field.ifEmpty { "new" }, annotation.ifEmpty { null })
    }
}
internal fun infer(template: String): Map<String, InputRequirement> {
    val result = linkedMapOf<String, InputRequirement>()
    placeholders(template).forEach { p ->
        val prior = result[p.port] ?: InputRequirement()
        if (p.kind != null && prior.kind != null && p.kind != prior.kind) bad("${p.port}: conflicting types")
        result[p.port] = InputRequirement(prior.fields + p.field, p.kind ?: prior.kind)
    }
    return result
}
internal fun templateText(kind: String, config: JsonObject): String = when (kind) {
    "judge" -> config.string("question")
    "exec" -> if (beadsPreset(config.string("preset"))) beadsFields(config.string("preset")).joinToString("\n") { config.string(it) }
        else config.string(if (config.string("preset") == "custom") "command" else "prompt")
    else -> ""
}
internal fun requirements(node: StudioNode): Map<String, InputRequirement> = if (node.kind == "program") {
    val declared = when (val value = node.config["inputRequirements"]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> value
        else -> bad("inputRequirements must be an object")
    }
    if ((declared.keys - node.ports.toSet()).isNotEmpty()) bad("inputRequirements names an unknown port")
    node.ports.associateWith { port ->
        val r = declared[port]?.let { json.decodeFromJsonElement<InputRequirement>(it) } ?: InputRequirement(kind = "any")
        if (r.kind != null && r.kind !in studioKinds) bad("$port: unknown input type")
        if (r.fields.any { it.isBlank() }) bad("$port: invalid required field")
        r
    }
} else infer(templateText(node.kind, node.config))

internal fun satisfies(output: StudioType, requirement: InputRequirement, port: String = "c"): Compatibility {
    if (output.kind == "any") return Compatibility(Admission.CHECK_ON_ARRIVAL)
    val nominal = requirement.kind?.takeUnless { it == "any" }
    if (nominal != null && output.kind != nominal)
        return Compatibility(Admission.REJECT, "$port: requires $nominal; producer offers ${output.kind}")
    val missing = requirement.fields - output.fields
    if (missing.isNotEmpty()) return if (output.kind == "json") Compatibility(Admission.CHECK_ON_ARRIVAL)
        else Compatibility(Admission.REJECT, "$port: requires field '${missing.sorted().first()}'; ${output.kind} offers ${output.fields.sorted().joinToString()}")
    return Compatibility(Admission.ACCEPT)
}
internal fun gateOutput(passPort: String, inputs: Map<String, StudioType>): StudioType =
    (inputs[passPort] ?: StudioType()).let { it.copy(extras = it.extras + "gate") }

internal fun outputType(node: StudioNode, programSchema: JsonObject?): StudioType = when (node.kind) {
    "git" -> StudioType("gitRange")
    "beads" -> StudioType("issueSet")
    "judge" -> StudioType("judgment")
    "program" -> programSchema?.let { StudioType("json", schema = it) } ?: StudioType("text")
    "exec" -> if (node.config.string("preset") in setOf("claude", "codex"))
        outputSchema(node.config)?.let { StudioType("json", schema = it) } ?: StudioType("text") else StudioType("text")
    else -> StudioType("text")
}

internal data class TypedGraph(val nodes: Map<String, NodeTypes>, val edges: List<StudioEdge>)
/** Resolve selected gate inputs recursively in dependency order, without consulting hot held values. */
internal fun typedGraph(nodes: List<StudioNode>, edges: List<StudioEdge>, programSchemas: Map<String, JsonObject?> = emptyMap()): TypedGraph {
    val byId = nodes.associateBy { it.id }
    val resolved = linkedMapOf<String, NodeTypes>()
    val visiting = mutableSetOf<String>()
    val reasons = mutableMapOf<String, String>()
    fun resolve(id: String): NodeTypes {
        resolved[id]?.let { return it }
        val node = byId[id] ?: return NodeTypes()
        if (!visiting.add(id)) return NodeTypes() // kernel admission diagnoses cycles before append
        val inputs = requirements(node)
        val offered = linkedMapOf<String, StudioType>()
        edges.filter { it.to == id }.forEach { edge ->
            val source = resolve(edge.from).output
            val requirement = inputs[edge.port]
            val reason = if (requirement == null) "${edge.port}: input port does not exist"
                else satisfies(source, requirement, edge.port).message
            if (reason != null) reasons[edge.id] = reason else offered[edge.port] = source
        }
        val output = if (node.kind == "judge" && node.config.string("mode") == "gate")
            gateOutput(node.config.string("passPort").ifEmpty { node.ports.firstOrNull().orEmpty() }, offered)
            else outputType(node, programSchemas[id])
        visiting.remove(id)
        return NodeTypes(inputs, output).also { resolved[id] = it }
    }
    nodes.forEach { resolve(it.id) }
    return TypedGraph(resolved, edges.map { it.copy(status = if (it.id in reasons) "quarantined" else "ok", reason = reasons[it.id]) })
}

/** Presence checks never infer non-nullness. Nominal contracts also check the envelope shape. */
internal fun validateInput(value: JsonObject, r: InputRequirement, port: String) {
    check((value["new"] as? JsonPrimitive)?.isString == true && "old" in value &&
        (value["old"] == JsonNull || (value["old"] as? JsonPrimitive)?.isString == true)) { "$port: invalid change envelope" }
    r.fields.forEach { check(it in value) { "$port: missing field '$it'" } }
    when (r.kind) {
        "gitRange" -> check((value["repo"] as? JsonPrimitive)?.isString == true) { "$port: gitRange requires repo string" }
        "issueSet" -> {
            check((value["workspace"] as? JsonPrimitive)?.isString == true &&
                listOf("added", "removed", "changed").all { key -> (value[key] as? JsonArray)?.all { (it as? JsonPrimitive)?.isString == true } == true }) { "$port: invalid issueSet metadata" }
            check(json.parseToJsonElement(value.string("new")) is JsonArray) { "$port: issueSet new must encode an array" }
        }
        "judgment" -> check("probability" in value && "reason" in value &&
            (value["probability"] == JsonNull || (value["probability"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.let { it in 0.0..1.0 } == true) &&
            (value["reason"] == JsonNull || (value["reason"] as? JsonPrimitive)?.isString == true)) { "$port: invalid judgment metadata" }
        "json" -> StudioSchemas.parseValue(value.string("new"))
    }
}
