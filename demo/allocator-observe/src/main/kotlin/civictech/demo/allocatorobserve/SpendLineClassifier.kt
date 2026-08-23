package civictech.demo.allocatorobserve

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The total classification of one JSONL spend-log line
 * (computenet-fpml.1.1). Exactly three shapes, matching the feature's
 * fourth example (`computenet-fpml.1`):
 *
 * - [Valid] — a well-formed v1 record: all six fields present, correctly
 *   typed, and no extra keys.
 * - [Malformed] — the line is not a JSON object, or it claims `v == 1` but
 *   is missing a field, has a wrong-typed field, or carries an unknown key.
 *   `v` gates the schema: a `v:1` line with extra fields is malformed rather
 *   than merely "extended", which is what keeps the oracle comparison exact.
 * - [UnknownVersion] — a well-formed JSON object whose integer `v != 1`. Its
 *   other fields are not validated, since the v1 schema does not govern them.
 */
sealed interface LineClassification {
    data class Valid(val record: SpendRecord) : LineClassification
    data object Malformed : LineClassification
    data class UnknownVersion(val v: Int) : LineClassification
}

private val REQUIRED_V1_KEYS = setOf("v", "project", "machine", "work_item", "started", "ended")

/**
 * Classifies one JSONL spend-log line. Total: never throws, for any input
 * string.
 */
fun classifySpendLine(line: String): LineClassification {
    val element =
        try {
            Json.parseToJsonElement(line)
        } catch (_: SerializationException) {
            return LineClassification.Malformed
        } catch (_: IllegalArgumentException) {
            return LineClassification.Malformed
        }

    val obj = element as? JsonObject ?: return LineClassification.Malformed

    val vElement = obj["v"] as? JsonPrimitive ?: return LineClassification.Malformed
    if (vElement.isString) return LineClassification.Malformed
    val v = vElement.intOrNull ?: return LineClassification.Malformed

    if (v != 1) return LineClassification.UnknownVersion(v)

    if (obj.keys != REQUIRED_V1_KEYS) return LineClassification.Malformed

    fun stringField(key: String): String? {
        val fieldElement = obj[key] as? JsonPrimitive ?: return null
        if (!fieldElement.isString) return null
        return fieldElement.jsonPrimitive.content
    }

    val project = stringField("project") ?: return LineClassification.Malformed
    val machine = stringField("machine") ?: return LineClassification.Malformed
    val workItem = stringField("work_item") ?: return LineClassification.Malformed
    val started = stringField("started") ?: return LineClassification.Malformed
    val ended = stringField("ended") ?: return LineClassification.Malformed

    return LineClassification.Valid(
        SpendRecord(
            v = v,
            project = project,
            machine = machine,
            workItem = workItem,
            started = started,
            ended = ended,
        ),
    )
}
