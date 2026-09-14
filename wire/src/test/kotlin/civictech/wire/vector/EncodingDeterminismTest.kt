package civictech.wire.vector

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The determinism guard (`[WIR1-C11]`, epic B3.11, task computenet-ncz.2.5):
 * every map-bearing and Double-bearing vector's bytes must not vary across
 * repeated in-process encoding. This is one half of the feature's determinism
 * evidence — the other half is running this SAME test class in two separate
 * `--rerun` JVM forks, plus one more independent JVM via `:wire:wireVectors`,
 * and recording that all three agree with the committed `encoded.utf8` (that
 * evidence lives in the PR/landing comment, not in a JUnit assertion, since a
 * fresh JVM is outside what one test method can drive).
 *
 * [SELECTED_TYPES] is the set of discriminators whose backing collections have
 * historically been the source of JVM iteration-order nondeterminism —
 * unordered maps/sets folded during merge, or a floating-point rendering that
 * can differ across JIT/locale states. `kotlin.Double` is included because
 * `Double.toString` is the other documented source of cross-runtime variance
 * (SCHEMA.md's Double call-out); it is not a collection, but the same "encode
 * this 100x and compare bytes" check applies and this is where the corpus
 * first pins a Double vector.
 *
 * A variation MUST be recorded in `NONDETERMINISM.md`, never silently
 * tolerated by loosening this test (`wire/corpus/NONDETERMINISM.md` "What
 * never belongs here"). At this base all three JVM runs agreed, so the ledger
 * table stays `_(none yet)_` and the Findings section records that fact.
 */
class EncodingDeterminismTest {

    @Test
    fun `each selected vector's bytes are identical across 100 in-process encodings`() {
        val documents = VectorLoader.locate().documents()
        val selected = documents.filter { doc ->
            (doc.kind == VectorKind.FRAME || doc.kind == VectorKind.HANDSHAKE_TEXT) &&
                doc.decoded?.let { typeNodesOf(it).any { t -> t in SELECTED_TYPES } } == true
        }
        assertTrue(
            selected.size >= 3,
            "vacuity guard: expected at least 3 selected vectors (map-bearing/Double-bearing), found ${selected.size}: " +
                "${selected.map { it.id }} out of ${documents.map { it.id }}",
        )

        for (doc in selected) {
            val expected = checkNotNull(doc.encoded?.utf8) { "${doc.id}: selected vector has no encoded.utf8" }
                .toByteArray(Charsets.UTF_8)
            for (iteration in 1..100) {
                val actual = NeutralValues.encodedBytesOf(doc)
                if (!actual.contentEquals(expected)) {
                    val offset = firstDifferingOffset(expected, actual)
                    org.junit.jupiter.api.fail(
                        "${doc.id}: iteration $iteration differs from encoded.utf8 at byte offset $offset " +
                            "(expected ${expected.size} bytes, got ${actual.size}); " +
                            "record this in wire/corpus/NONDETERMINISM.md, do not normalise",
                    )
                }
            }
        }
    }

    companion object {
        /**
         * The collection-bearing and Double-bearing discriminators this guard
         * covers, per the task's Deliverables: `SetDelta`/`WatermarkDelta` fold
         * over unordered backing collections during merge; `MapDelta`/
         * `TaggedMapDelta` are the structured-key-map vectors this task adds,
         * whose `puts`/`groups` iterate a `LinkedHashMap`; `kotlin.Double`
         * because JVM `Double.toString` rendering is the other documented
         * source of cross-run/cross-runtime byte variation (SCHEMA.md's Double
         * call-out, [WIR1-C16]).
         */
        val SELECTED_TYPES: Set<String> = setOf("SetDelta", "MapDelta", "TaggedMapDelta", "WatermarkDelta", "kotlin.Double")

        private fun typeNodesOf(decoded: kotlinx.serialization.json.JsonElement): Set<String> =
            RegistrationCoverageTest.typeNodes(decoded)

        private fun firstDifferingOffset(a: ByteArray, b: ByteArray): Int {
            val n = minOf(a.size, b.size)
            for (i in 0 until n) if (a[i] != b[i]) return i
            return n
        }
    }
}
