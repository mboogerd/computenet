package civictech.timetravel.diff

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canonical text of a JSON element (TTD1 F6, feature D4): every [JsonObject] rebuilt with
 * its keys in `String` natural order, recursively; arrays keep their order; primitives are
 * verbatim (a [JsonPrimitive] keeps its `isString` quoting, and `JsonNull` renders `null`);
 * encoded compactly. Two elements that differ only in object-key order canonicalise equal, so
 * record-level args comparison needs no descriptor (`[TTD1-44]`'s record-level half).
 */
internal object CanonicalJson {

    fun canonical(element: JsonElement): String =
        Json.encodeToString(JsonElement.serializer(), sorted(element))

    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associateTo(LinkedHashMap()) { (key, value) -> key to sorted(value) },
        )

        is JsonArray -> JsonArray(element.map { sorted(it) })
        is JsonPrimitive -> element
    }
}
