package civictech.demo.allocatorobserve.oracle

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * [ReportComparison] against the two things a differential harness needs from
 * it: that it does **not** fire on float noise, and that it fires exactly once,
 * at the right path, when a real value differs.
 *
 * Both halves matter equally. A comparator that reports last-bit differences
 * makes the oracle unusable; one whose tolerance swallows a real divergence
 * makes it dishonest. The documents here are built by hand from
 * [ReferenceReport]'s output — never from the served views, which this package
 * may not import.
 */
class ReportComparisonTest {

    private val reference = ReferenceReport.compute(LINES, HISTORY, NOW, WINDOW)

    /** Replace the value at a dotted path, rebuilding the objects along the way. */
    private fun JsonObject.withAt(path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        val replacement =
            if (path.size == 1) value else getValue(key).jsonObject.withAt(path.drop(1), value)
        return JsonObject(toMutableMap().also { it[key] = replacement })
    }

    private fun JsonObject.withoutAt(path: List<String>): JsonObject {
        val key = path.first()
        return if (path.size == 1) {
            JsonObject(toMutableMap().also { it.remove(key) })
        } else {
            JsonObject(toMutableMap().also { it[key] = getValue(key).jsonObject.withoutAt(path.drop(1)) })
        }
    }

    private fun JsonObject.numberAt(vararg path: String): Double =
        path.fold<String, JsonElement>(this) { element, key -> element.jsonObject.getValue(key) }
            .jsonPrimitive.content.toDouble()

    @Test
    fun `differences below the declared tolerance are not divergences`() {
        // 1e-9 on a share is four orders of magnitude under the 6-decimal-place
        // rounding; 1e-7 h is 0.36 ms, far under the whole-second hour rule.
        // Both are the size of a difference in summation order between two
        // independent implementations, which is exactly what must not be a
        // finding.
        val share = reference.numberAt("window", "perProject", "computenet", "enactedShare")
        val hours = reference.numberAt("window", "perProject", "computenet", "enactedHours")
        val nudged =
            reference
                .withAt(
                    listOf("window", "perProject", "computenet", "enactedShare"),
                    JsonPrimitive(share + 1e-9),
                )
                .withAt(
                    listOf("window", "perProject", "computenet", "enactedHours"),
                    JsonPrimitive(hours + 1e-7),
                )

        // The raw documents really do differ — the tolerance, not equality, is
        // what makes the comparison empty.
        (nudged == reference) shouldBe false
        ReportComparison.compare(reference, nudged) shouldBe emptyList()
    }

    @Test
    fun `a single wrong share is reported once, at its own path`() {
        val wrong =
            reference.withAt(
                listOf("window", "perProject", "glass-factory", "enactedShare"),
                JsonPrimitive(0.5),
            )

        val divergences = ReportComparison.compare(reference, wrong)

        divergences.map { it.path } shouldBe
            listOf("report.window.perProject.glass-factory.enactedShare")
        divergences.single().expected shouldBe JsonPrimitive(0.636364)
        divergences.single().actual shouldBe JsonPrimitive(0.5)
    }

    @Test
    fun `a missing key an extra key and a short array each diverge at their path`() {
        val missing = reference.withoutAt(listOf("cap", "capReached"))
        ReportComparison.compare(reference, missing).map { it.path } shouldBe listOf("report.cap.capReached")

        val extra =
            reference.withAt(
                listOf("cap"),
                JsonObject(
                    reference.getValue("cap").jsonObject.toMutableMap()
                        .also { it["capCeilingHit"] = JsonPrimitive(true) },
                ),
            )
        val extraDivergences = ReportComparison.compare(reference, extra)
        extraDivergences.map { it.path } shouldBe listOf("report.cap.capCeilingHit")
        extraDivergences.single().expected shouldBe null

        // Dropping the second sub-interval is one divergence at the array path
        // (the length), and no element divergences: the surviving element is
        // element 0 on both sides.
        val short =
            reference.withAt(
                listOf("window", "subIntervals"),
                JsonArray(reference.getValue("window").jsonObject.getValue("subIntervals").jsonArray.take(1)),
            )
        val shortDivergences = ReportComparison.compare(reference, short)
        shortDivergences.map { it.path } shouldBe listOf("report.window.subIntervals")
        shortDivergences.single().expected shouldBe JsonPrimitive(2)
        shortDivergences.single().actual shouldBe JsonPrimitive(1)
    }

    @Test
    fun `string fields are compared exactly`() {
        // `residualLabel` carries no numeric rule, so no tolerance applies: the
        // exchange shape pins the sentence, and a reworded one is a finding.
        val reworded =
            reference.withAt(
                listOf("window", "perProject", "computenet", "residualLabel"),
                JsonPrimitive("starvation attribution unavailable"),
            )

        ReportComparison.compare(reference, reworded).map { it.path } shouldBe
            listOf("report.window.perProject.computenet.residualLabel")
    }

    @Test
    fun `the root path is configurable and appears on every divergence`() {
        val wrong = reference.withAt(listOf("window", "totalHours"), JsonPrimitive(99.0))

        ReportComparison.compare(reference, wrong, rootPath = "external").map { it.path } shouldBe
            listOf("external.window.totalHours")
    }
}
