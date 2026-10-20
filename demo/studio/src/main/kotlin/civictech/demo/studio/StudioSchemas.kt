package civictech.demo.studio

import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.dialect.Dialects
import kotlinx.serialization.json.*

internal fun outputSchema(config: JsonObject): JsonObject? = when (val value = config["outputSchema"]) {
    null, JsonNull -> null
    is JsonObject -> value
    else -> conflict("outputSchema must be a JSON Schema object or null")
}
/** Legacy schema-less hashes remain byte-for-byte compatible. Schema artifacts use canonical JSON. */
internal fun artifactHash(code: String, schema: JsonObject?): String =
    if (schema == null) sha256(code) else sha256("studio-artifact-v1\u0000$code\u0000${canonical(schema)}")
private fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { json.parseToJsonElement(canonical(it.value)) }).toString()
    is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
    else -> value.toString()
}

/** Bounded cache; fixed 2020-12 dialect; no remote/file loaders and no recursive references. */
internal object StudioSchemas {
    const val DIALECT = "https://json-schema.org/draft/2020-12/schema"
    private val keywords = setOf("\$schema", "\$defs", "\$ref", "type", "properties", "required", "additionalProperties",
        "items", "enum", "const", "title", "description", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
        "multipleOf", "minLength", "maxLength", "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties")
    private val cache = object : LinkedHashMap<String, Schema>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Schema>) = size > 128
    }
    private fun registry() = SchemaRegistry.withDialect(Dialects.getDraft202012())
    private val meta by lazy { registry().getSchema(SchemaLocation.of(DIALECT)) }

    @Synchronized fun compile(schema: JsonObject): Schema {
        try { bounded(schema.toString(), 65_536, 32) } catch (ex: Exception) { conflict("invalid outputSchema: ${ex.message}") }
        val key = canonical(schema)
        cache[key]?.let { return it }
        try {
            bounded(key, 65_536, 32)
            val definitions = schema["\$defs"] as? JsonObject ?: JsonObject(emptyMap())
            var visits = 0
            fun visit(element: JsonElement, refs: Set<String> = emptySet()) {
                check(++visits <= 4096) { "schema reference expansion exceeds 4096 nodes" }
                if (element is JsonPrimitive && element.booleanOrNull != null) return
                val node = element as? JsonObject ?: error("subschema must be an object or boolean")
                val unknown = node.keys - keywords
                check(unknown.isEmpty()) { "unsupported schema keyword '${unknown.sorted().first()}'" }
                node["\$schema"]?.let { check(it == JsonPrimitive(DIALECT)) { "only draft 2020-12 is supported" } }
                node["\$ref"]?.let {
                    val ref = (it as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("invalid reference")
                    check(ref.startsWith("#/\$defs/") && ref.removePrefix("#/\$defs/").let { name -> name.isNotEmpty() && '/' !in name && '~' !in name && '%' !in name }) {
                        "only local #/\$defs/name references are supported"
                    }
                    check(ref !in refs && refs.size < 16) { "recursive or excessive schema references are unsupported" }
                    visit(definitions[ref.removePrefix("#/\$defs/")] ?: error("unresolved reference '$ref'"), refs + ref)
                }
                for (map in listOf("properties", "\$defs")) node[map]?.jsonObject?.values?.forEach { visit(it, refs) }
                for (child in listOf("items", "additionalProperties")) node[child]?.let { visit(it, refs) }
            }
            visit(schema)
            val errors = meta.validate(key, InputFormat.JSON)
            check(errors.isEmpty()) { errors.take(3).joinToString { it.toString() } }
            // A fresh registry prevents unrelated schemas from sharing reference identities.
            return registry().getSchema(key, InputFormat.JSON).also { it.initializeValidators(); cache[key] = it }
        } catch (ex: Exception) { conflict("invalid outputSchema: ${ex.message ?: ex.javaClass.simpleName}") }
    }
    fun validate(schema: JsonObject, text: String) {
        parseValue(text)
        val errors = compile(schema).validate(text, InputFormat.JSON)
        check(errors.isEmpty()) { "output schema validation failed: ${errors.take(3).joinToString { it.toString() }}" }
    }
    fun parseValue(text: String): JsonElement {
        bounded(text, 1_048_576, 64)
        return json.parseToJsonElement(text)
    }
    /** Check nesting before either JSON parser can recurse. */
    private fun bounded(text: String, bytes: Int, maxDepth: Int) {
        check(text.toByteArray(Charsets.UTF_8).size <= bytes) { "JSON exceeds $bytes bytes" }
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; check(depth <= maxDepth) { "JSON exceeds depth $maxDepth" } }
                '}', ']' -> depth--
            }
        }
    }
}

/** Synthetic fixtures and real CLI responses use this same fail-closed adapter. */
internal fun claudeOutput(stdout: String, structured: Boolean): Pair<String, Double?> {
    val envelope = StudioSchemas.parseValue(stdout).jsonObject
    check(!envelope.bool("is_error")) { "claude reported an error" }
    val output = if (structured) {
        check(envelope.string("subtype") == "success" && envelope["is_error"] == JsonPrimitive(false)) { "claude structured response was not successful" }
        check("structured_output" in envelope && envelope["structured_output"] != JsonNull) { "claude response has no structured_output" }
        envelope.getValue("structured_output").toString()
    } else (envelope["result"] as? JsonPrimitive)?.content ?: error("claude response has no result")
    return output to (envelope["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull
}
