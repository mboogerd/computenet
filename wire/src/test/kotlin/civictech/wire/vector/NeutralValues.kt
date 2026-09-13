package civictech.wire.vector

import civictech.cell.wire.DecodedWireFrame
import civictech.cell.wire.WireCodec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The neutral-grammar adapter (decision ncz.2-D8 / ncz.2.1-D1): a codec value
 * is built from a vector's `decoded` tree by a pure structural rewrite into the
 * wire-shaped JSON tree, followed by the codec's OWN decoder
 * ([WireCodec.decodeFrame]). The rewrite is exactly the four mappings
 * `wire/corpus/SCHEMA.md` §The neutral `decoded` grammar states, and nothing
 * else — it knows no discriminator, field name or registration:
 *
 * 1. the top-level `{"type": "frame", "fields": F}` becomes `F` itself
 *    (§The frame envelope — no `["frame", …]` wrapper);
 * 2. an object whose key set is exactly `{type, fields}` or `{type, value}`
 *    becomes `[type, rewrite(x)]` (§Polymorphic positions), at every depth;
 * 3. an object whose key set is exactly `{entries}`, holding an array of
 *    `{key, value}` objects, becomes `[k1, v1, k2, v2, …]` (§Maps,
 *    structured-key map);
 * 4. everything else is copied — object key order, array element order, and
 *    each primitive as the literal the document parser produced, so a 64-bit
 *    integer or `1e21` keeps its spelling (§The `encoded` block).
 *
 * **What this does and does not check, stated honestly.** Because the value
 * is what the DECODER constructs from the wire-shaped tree, `encode(built) ==
 * pinned bytes` (`[WIR1-I01]`) is a golden-bytes check of the ENCODER against
 * a value the decoder built. A change that alters encoder and decoder
 * symmetrically is caught by the pinned bytes, not by this build step — which
 * is precisely the property the corpus adds over an in-process round-trip
 * (epic D2). A contract rename (ncz.2-D5) is caught by construction:
 * [WireCodec.decodeFrame] refuses an unknown contract/method id pair naming
 * both ids, so no second registry lookup is made here.
 */
object NeutralValues {
    /** Rewrites a neutral `decoded` tree into the wire-shaped JSON tree (rules 1–4 above). */
    fun wireElement(decoded: JsonElement): JsonElement {
        if (decoded is JsonObject && decoded.keys == setOf("type", "fields") && decoded.typeName() == FRAME) {
            return rewrite(decoded.getValue("fields"))
        }
        return rewrite(decoded)
    }

    /**
     * The [DecodedWireFrame] a `frame`-shaped vector's `decoded` denotes, built
     * by [wireElement] then [WireCodec.decodeFrame]. Refuses a `handshake-text`
     * document (not a frame; task 2's `HandshakeLines` owns it), a document
     * with no `decoded`, and a `decoded` that is not the frame envelope.
     */
    fun frameOf(doc: VectorDocument): DecodedWireFrame {
        if (doc.kind == VectorKind.HANDSHAKE_TEXT) {
            throw VectorSchemaException("${doc.file}: kind `handshake-text` is a handshake line, not a frame")
        }
        val decoded = doc.decoded
            ?: throw VectorSchemaException("${doc.file}: kind `${doc.kind.word}` direction `${doc.direction.word}` carries no `decoded` to build a frame from")
        if (!(decoded is JsonObject && decoded.keys == setOf("type", "fields") && decoded.typeName() == FRAME)) {
            throw VectorSchemaException("${doc.file}: `decoded` is not the frame envelope {\"type\": \"frame\", \"fields\": {…}} (SCHEMA.md §The frame envelope)")
        }
        return WireCodec.decodeFrame(wireText(decoded).toByteArray(Charsets.UTF_8))
    }

    /**
     * [wireElement] rendered as compact JSON text, every primitive written as
     * the literal the document parser kept. Deliberately NOT
     * `Json.encodeToString(JsonElement.serializer(), …)`: kotlinx's
     * `JsonLiteralSerializer` routes a non-integer unquoted literal through
     * `encodeDouble`, so `1e21` would reach the codec respelled `1.0E21` — the
     * rewrite would then be normalising numbers, which rule 4 forbids.
     */
    fun wireText(decoded: JsonElement): String = buildString { emit(wireElement(decoded)) }

    private fun StringBuilder.emit(element: JsonElement) {
        when (element) {
            // JsonPrimitive.toString(): a string literal quoted and escaped by
            // kotlinx's own printQuoted; any other literal as its content, verbatim.
            is JsonPrimitive -> append(element.toString())
            is JsonArray -> {
                append('[')
                element.forEachIndexed { i, e -> if (i > 0) append(','); emit(e) }
                append(']')
            }
            is JsonObject -> {
                append('{')
                var first = true
                for ((k, v) in element) {
                    if (!first) append(',')
                    first = false
                    append(JsonPrimitive(k).toString())
                    append(':')
                    emit(v)
                }
                append('}')
            }
        }
    }

    /** `WireCodec.encode` of the value [frameOf] builds — the bytes `[WIR1-I01]` compares with `encoded`. */
    fun encodedBytesOf(doc: VectorDocument): ByteArray = WireCodec.encode(frameOf(doc).invocation)

    private const val FRAME = "frame"

    private fun JsonObject.typeName(): String? =
        (this["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun rewrite(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> element
        is JsonArray -> JsonArray(element.map(::rewrite))
        is JsonObject -> rewriteObject(element)
    }

    private fun rewriteObject(obj: JsonObject): JsonElement {
        val keys = obj.keys
        val type = obj.typeName()
        if (type != null && (keys == setOf("type", "fields") || keys == setOf("type", "value"))) {
            val payload = obj["fields"] ?: obj.getValue("value")
            return JsonArray(listOf(JsonPrimitive(type), rewrite(payload)))
        }
        if (keys == setOf("entries")) {
            val entries = obj.getValue("entries")
            if (entries is JsonArray && entries.all { it is JsonObject && it.keys == setOf("key", "value") }) {
                return JsonArray(
                    entries.flatMap { e ->
                        val entry = e as JsonObject
                        listOf(rewrite(entry.getValue("key")), rewrite(entry.getValue("value")))
                    },
                )
            }
        }
        return JsonObject(obj.mapValuesTo(LinkedHashMap()) { (_, v) -> rewrite(v) })
    }
}
