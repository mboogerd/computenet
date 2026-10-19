package civictech.demo.studio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** The silent baseline is cell state, so recovery can compare the next observation. */
@Serializable
data class BeadsState(val watch: String, val set: String)

internal const val beadsDefaults = """{"workspace":"","query":"ready","intervalSec":60}"""
internal fun beadsPreset(preset: String) = preset in setOf("bd-comment", "bd-create")
internal fun beadsFields(preset: String): List<String> = when (preset) {
    "bd-comment" -> listOf("issue", "text")
    "bd-create" -> listOf("title", "description", "type", "priority", "labels")
    else -> emptyList()
}
internal fun beadsPresetDefaults(preset: String): String = when (preset) {
    "bd-comment" -> """"workspace":"","issue":"{{in.issue}}","text":"{{in}}""""
    "bd-create" -> """"workspace":"","title":"{{in}}","description":"","type":"task","priority":"2","labels":"""""
    else -> bad("unknown beads preset")
}

internal fun beadsPatchConfig(old: StudioNode, patch: JsonObject?): JsonObject {
    val merged = old.config + patch.orEmpty()
    return JsonObject(if (old.kind == "exec" && patch != null && beadsPreset(patch.string("preset")) &&
        patch.string("preset") != old.config.string("preset") && "auto" !in patch)
        merged + ("auto" to JsonPrimitive(false)) else merged)
}

internal fun validateBeadsConfig(config: JsonObject, source: Boolean) {
    val workspace = config.string("workspace")
    if (workspace.isNotBlank() && !Path.of(workspace).isAbsolute) bad("workspace must be absolute")
    if (source) {
        if (config.string("query") !in setOf("ready", "list")) bad("query must be ready or list")
        if (config.long("intervalSec", 0) <= 0) bad("intervalSec must be positive")
    }
}

internal fun beadsWorkspace(config: JsonObject): Path {
    val value = config.string("workspace")
    check(value.isNotBlank()) { "workspace is empty" }
    val path = Path.of(value)
    check(path.isAbsolute && Files.isDirectory(path.resolve(".beads"))) { "workspace must be an absolute directory containing .beads" }
    return path.toRealPath()
}

/** Pin discovery even when the Studio process inherited another workspace's BEADS_DIR. */
internal fun beadsEnvironment(config: JsonObject) = mapOf("BEADS_DIR" to beadsWorkspace(config).resolve(".beads").toString(),
    "BEADS_DB" to "", "BEADS_WORKING_DIR" to beadsWorkspace(config).toString())

internal fun beadsWatch(config: JsonObject) = buildJsonObject {
    for (key in listOf("workspace", "query") + if (config.string("query") == "list") listOf("status", "label", "type") else emptyList())
        put(key, config.string(key))
}.toString()

internal fun beadsReadArgv(config: JsonObject): List<String> = buildList {
    addAll(listOf("bd", "--directory", beadsWorkspace(config).toString(), "--readonly", "--sandbox",
        config.string("query"), "--json", "--limit=0"))
    if (config.string("query") == "list") for (key in listOf("status", "label", "type"))
        config.string(key).takeIf(String::isNotBlank)?.let { add("--$key=$it") }
}

internal fun StudioProcesses.readBeads(config: JsonObject, data: Path): String {
    val dir = Files.createTempDirectory(data, ".beads-poll-")
    try {
        val result = run(beadsReadArgv(config), beadsWorkspace(config), dir, 60, environment = beadsEnvironment(config))
        check(result.error == null) { result.error ?: "beads poll failed" }
        return beadsSet(result.stdout)
    } finally { dir.toFile().deleteRecursively() }
}

/** Canonicalize only the template vocabulary; timestamps/descriptions never trigger a change. */
internal fun beadsSet(stdout: String): String {
    val parsed = json.parseToJsonElement(stdout)
    val rows = if (parsed == JsonNull) emptyList() else parsed.jsonArray.toList()
    return JsonArray(rows.map { row ->
        val issue = row.jsonObject
        buildJsonObject {
            put("id", issue.getValue("id")); put("title", issue.getValue("title"))
            put("status", issue.getValue("status")); put("priority", issue.getValue("priority"))
            put("type", issue["issue_type"] ?: issue.getValue("type"))
            put("labels", JsonArray((issue["labels"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.distinct().sorted().map(::JsonPrimitive)))
        }
    }.sortedBy { it.string("id") }).toString()
}

internal fun beadsChange(old: String?, new: String, workspace: String): JsonObject {
    fun byId(set: String?) = set?.let { json.parseToJsonElement(it).jsonArray.associate { row -> row.jsonObject.string("id") to row } }.orEmpty()
    val before = byId(old); val after = byId(new)
    return buildJsonObject {
        put("old", old?.let(::JsonPrimitive) ?: JsonNull); put("new", new); put("workspace", workspace)
        put("added", JsonArray((after.keys - before.keys).sorted().map(::JsonPrimitive)))
        put("removed", JsonArray((before.keys - after.keys).sorted().map(::JsonPrimitive)))
        put("changed", JsonArray((before.keys intersect after.keys).filter { before[it] != after[it] }.sorted().map(::JsonPrimitive)))
    }
}

/** Recompute set deltas across a burst, keeping metadata (including the latest Fire). */
internal fun composeBeadsOrChange(pending: JsonObject, value: JsonObject): JsonObject {
    val old = pending["old"] ?: JsonNull
    if (listOf("workspace", "added", "removed", "changed").all { it in value })
        return JsonObject(value + beadsChange((old as? JsonPrimitive)?.contentOrNull, value.string("new"), value.string("workspace")))
    return JsonObject(value + ("old" to old))
}

/** Values stay single arguments, including leading dashes and shell metacharacters. Never parent. */
internal fun beadsWriteArgv(config: JsonObject, inputs: Map<String, JsonObject>): List<String> {
    val prefix = listOf("bd", "--directory", beadsWorkspace(config).toString(), "--sandbox")
    fun value(key: String) = substitute(config.string(key), inputs)
    return when (config.string("preset")) {
        "bd-comment" -> prefix + listOf("comment", "--", value("issue"), value("text"))
        "bd-create" -> prefix + listOf("create", "--json") + beadsFields("bd-create").map { "--$it=${value(it)}" }
        else -> bad("unknown beads preset")
    }
}
