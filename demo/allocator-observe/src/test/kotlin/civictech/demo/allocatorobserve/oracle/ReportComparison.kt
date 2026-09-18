package civictech.demo.allocatorobserve.oracle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode

/** One field on which two exchange documents disagree, at its full path. */
data class Divergence(
    val path: String,
    val expected: JsonElement?,
    val actual: JsonElement?,
) {
    override fun toString(): String = "$path: expected=$expected actual=$actual"
}

/**
 * The comparator both oracle layers use (design entry fpml.5-D5): normalise two
 * `report` documents, then list every path on which they disagree.
 *
 * ## Why normalisation comes first
 *
 * The documents' numbers are raw doubles on both sides — the served document
 * encodes them unrounded by design, and two independent implementations of the
 * same arithmetic will differ in the last bits whenever they sum in a different
 * order. Comparing raw doubles would report those as findings, and a harness
 * that cries wolf is one nobody runs. So values are normalised **by field name,
 * wherever that name occurs**, including as the name of an object whose values
 * are the numbers ([SHARE_FIELDS] and [HOUR_FIELDS] below are mostly
 * project-keyed maps):
 *
 * - shares, fractions and weights -> `BigDecimal` at 6 decimal places, HALF_UP;
 * - hour counts -> whole seconds, `Math.round(hours * 3600)`;
 * - strings, booleans, nulls and everything else -> compared exactly.
 *
 * The tolerance is therefore *declared*, not a fuzz factor: 1e-6 of a share and
 * half a second of time. A real disagreement is larger than both.
 *
 * ## What it is not
 *
 * It is not an equality assertion. It returns the whole list, in document order,
 * so a diverging run reports every field at once rather than the first one a
 * `shouldBe` happened to reach — the failure of a differential oracle is a
 * finding to read, not a red light to clear.
 *
 * Note that the `expected`/`actual` values carried on a [Divergence] are the
 * **normalised** ones (a rounded share, a whole second count). That is
 * deliberate: they are what the comparison actually saw, so a reader never has
 * to wonder whether a reported difference was below the tolerance.
 */
object ReportComparison {

    /** Fields normalised to 6 decimal places: shares, fractions, drifts and raw weights. */
    val SHARE_FIELDS: Set<String> =
        setOf("enactedShare", "declaredShare", "diff", "drift", "residual", "elapsedFraction", "weights")

    /** Fields normalised to whole seconds. */
    val HOUR_FIELDS: Set<String> =
        setOf(
            "enactedHours",
            "totalHours",
            "hoursToDate",
            "capHours",
            "projectedMonthEndHours",
            "monthlyCapHours",
            "beforeFirstDeclarationHours",
        )

    /** Decimal places shares and fractions are rounded to before comparison. */
    const val SHARE_SCALE: Int = 6

    /** [doc] with every numeric field normalised per the rules above. */
    fun normalise(doc: JsonElement): JsonElement = normalise(doc, fieldName = null)

    /**
     * Every path on which [expected] and [actual] disagree after normalisation,
     * in document order, rooted at [rootPath].
     *
     * An empty list is the passing outcome.
     */
    fun compare(
        expected: JsonElement,
        actual: JsonElement,
        rootPath: String = "report",
    ): List<Divergence> {
        val found = mutableListOf<Divergence>()
        walk(normalise(expected), normalise(actual), rootPath, found)
        return found
    }

    // -----------------------------------------------------------------------

    private fun normalise(element: JsonElement, fieldName: String?): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element.mapValues { (key, value) ->
                        // A field name carrying a numeric rule passes that rule down to
                        // its object's values: `diff` is `{"computenet": -0.07, ...}`,
                        // and the rule belongs to `diff`, not to `computenet`. A field
                        // with no rule yields to its children's own names.
                        normalise(value, if (hasRule(fieldName)) fieldName else key)
                    },
                )

            is JsonArray -> JsonArray(element.map { normalise(it, fieldName) })

            is JsonPrimitive -> normalisePrimitive(element, fieldName)
        }

    private fun hasRule(fieldName: String?): Boolean =
        fieldName != null && (fieldName in SHARE_FIELDS || fieldName in HOUR_FIELDS)

    private fun normalisePrimitive(primitive: JsonPrimitive, fieldName: String?): JsonPrimitive {
        if (fieldName == null || primitive.isString) return primitive
        val number = primitive.content.toDoubleOrNull() ?: return primitive
        return when (fieldName) {
            in SHARE_FIELDS ->
                JsonPrimitive(BigDecimal(number).setScale(SHARE_SCALE, RoundingMode.HALF_UP).toDouble())

            in HOUR_FIELDS -> JsonPrimitive(Math.round(number * 3600.0))
            else -> primitive
        }
    }

    private fun walk(
        expected: JsonElement,
        actual: JsonElement,
        path: String,
        found: MutableList<Divergence>,
    ) {
        when {
            expected is JsonObject && actual is JsonObject -> {
                expected.forEach { (key, value) ->
                    val other = actual[key]
                    if (other == null) {
                        found += Divergence("$path.$key", value, null)
                    } else {
                        walk(value, other, "$path.$key", found)
                    }
                }
                actual.keys.filterNot { it in expected }.forEach { key ->
                    found += Divergence("$path.$key", null, actual[key])
                }
            }

            expected is JsonArray && actual is JsonArray -> {
                if (expected.size != actual.size) {
                    found += Divergence(path, JsonPrimitive(expected.size), JsonPrimitive(actual.size))
                }
                (0 until minOf(expected.size, actual.size)).forEach { i ->
                    walk(expected[i], actual[i], "$path[$i]", found)
                }
            }

            expected != actual -> found += Divergence(path, expected, actual)
        }
    }
}
