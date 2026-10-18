package civictech.demo.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
internal fun JsonObject.string(key: String, default: String = "") = (this[key] as? JsonPrimitive)?.contentOrNull ?: default
internal fun JsonObject.bool(key: String, default: Boolean = false) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
internal fun JsonObject.long(key: String, default: Long) = (this[key] as? JsonPrimitive)?.longOrNull ?: default
internal fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*entries))
internal fun change(old: String?, new: String, repo: String? = null) = buildJsonObject {
    put("old", old?.let(::JsonPrimitive) ?: JsonNull)
    put("new", new)
    repo?.let { put("repo", it) }
}

@Serializable
data class StudioNode(val id: String, val kind: String, val title: String, val x: Double = 0.0,
                      val y: Double = 0.0, val config: JsonObject, val ports: List<String>)
@Serializable
data class StudioEdge(val id: String, val from: String, val to: String, val port: String)
@Serializable
data class RunSummary(val runId: String, val startedAt: String, val durationMs: Long,
                      val exitCode: Int, val costUsd: Double? = null)
@Serializable
data class RunDetail(val runId: String, val startedAt: String, val durationMs: Long,
                     val exitCode: Int, val costUsd: Double? = null,
                     val inputs: Map<String, JsonObject>, val argv: List<String>, val cwd: String,
                     val stdout: String, val stderr: String, val error: String? = null) {
    fun summary() = RunSummary(runId, startedAt, durationMs, exitCode, costUsd)
}
@Serializable
data class NodeState(val id: String, val status: String = "idle", val output: JsonObject? = null,
                     val inputs: Map<String, JsonObject> = emptyMap(), val error: String? = null,
                     val runCount: Int = 0, val lastRun: RunSummary? = null,
                     val text: String = "", val ref: String? = null, val code: String? = null,
                     val codeHash: String? = null, val approvedHash: String? = null)
@Serializable
data class StudioGraph(val nodes: List<StudioNode>, val edges: List<StudioEdge>,
                       val state: Map<String, NodeState> = emptyMap())

class StudioException(val status: Int, message: String) : RuntimeException(message)
internal fun conflict(message: String): Nothing = throw StudioException(409, message)
internal fun bad(message: String): Nothing = throw StudioException(400, message)

internal val placeholder = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)(?:\\.([^{}]+))?}}")
internal val portName = Regex("[A-Za-z_][A-Za-z0-9_]*")

internal fun nodeConfig(kind: String, supplied: JsonObject): JsonObject {
    val defaults = when (kind) {
        "text" -> "{}"
        "git" -> """{"repo":"","branch":"","intervalSec":60,"fetch":true}"""
        "program" -> """{"prompt":"","inputs":["in"],"generator":"claude","auto":true,"timeoutSec":120}"""
        "exec" -> {
            val preset = supplied.string("preset", "custom")
            val fields = when (preset) {
                "custom" -> """"command":"echo {{in}}""""
                "claude" -> """"prompt":"","model":"","maxTurns":10,"maxBudgetUsd":1.0,"tools":"readonly""""
                "codex" -> """"prompt":"","model":"","effort":"medium""""
                else -> bad("unknown exec preset: $preset")
            }
            """{"preset":"$preset","auto":${preset == "custom"},"timeoutSec":600,"cwd":"",$fields}"""
        }
        else -> bad("unknown node kind: $kind")
    }
    val config = JsonObject(json.parseToJsonElement(defaults).jsonObject + supplied)
    if (kind == "exec" && config.string("preset") == "custom") tokenize(config.string("command"))
    if (kind in setOf("exec", "program") && config.long("timeoutSec", 0) <= 0) bad("timeoutSec must be positive")
    if (kind == "git" && config.long("intervalSec", 0) <= 0) bad("intervalSec must be positive")
    if (kind == "git" && config.string("repo").let { it.isNotBlank() && !java.io.File(it).isAbsolute }) bad("repo must be absolute")
    if (kind == "exec" && config.string("preset") == "claude") {
        if (config.string("tools") !in setOf("readonly", "none")) bad("tools must be readonly or none")
        if (config.long("maxTurns", 0) <= 0 || (config["maxBudgetUsd"] as? JsonPrimitive)?.doubleOrNull?.let { it > 0 && it.isFinite() } != true)
            bad("maxTurns and maxBudgetUsd must be positive")
    }
    if (kind == "exec" && config.string("preset") == "codex" && config.string("effort") !in setOf("low", "medium", "high")) bad("invalid effort")
    if (kind == "program" && config.string("generator") !in setOf("claude", "codex")) bad("invalid generator")
    return config
}

internal fun inputPorts(kind: String, config: JsonObject): List<String> = when (kind) {
    "exec" -> placeholder.findAll(config.string(if (config.string("preset") == "custom") "command" else "prompt"))
        .map { it.groupValues[1] }.distinct().toList()
    "program" -> {
        val ports = (config["inputs"] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: bad("invalid input port")
        } ?: bad("inputs must be an array")
        if (ports.any { !portName.matches(it) } || ports.distinct().size != ports.size) bad("invalid or duplicate input ports")
        ports
    }
    else -> emptyList()
}
