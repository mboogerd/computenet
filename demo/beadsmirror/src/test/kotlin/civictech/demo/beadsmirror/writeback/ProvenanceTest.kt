package civictech.demo.beadsmirror.writeback

import civictech.cell.Timestamp
import civictech.cell.data.delta.TaggedMapDelta
import civictech.demo.beadsmirror.projector.MirrorKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-6wc.3.2: [Provenance.cnDotOf] (the DOT_ORDER-max winning dot
 * across every key an issue holds) and [Provenance.strip] (removing
 * [Provenance.STAMP_KEYS] from a `metadata` object). Hand-built
 * [TaggedMapDelta]s throughout — pure, no `bd`/`dolt` on PATH, no
 * [civictech.demo.beadsmirror.projector.MirrorProjector] involved.
 *
 * Sources and packed counters are the exact values recorded on
 * computenet-6wc.1's comment thread (`WinnerDerivationTest`'s fixtures,
 * corrected 2026-09-12): `DotMinter("A").sourceId` = `srcA`,
 * `DotMinter("B").sourceId` = `srcB`, `srcA > srcB` (`UUID.compareTo`, signed
 * long comparison — the hex spelling reads the other way round). Reused here
 * rather than re-derived so a reader who has seen that thread recognizes the
 * fixtures.
 */
class ProvenanceTest {

    private val srcA: UUID = UUID.fromString("7b9bff97-40e3-34a1-a404-d948c52a72a1")
    private val srcB: UUID = UUID.fromString("fce0b75a-f2e7-3b9a-b580-9d83e00b24ec")

    // h5/ki0, h5/ki1, h6/ki1, h7/ki1, h8/ki0, h8/ki1 — DotMinter.counter(position, keyIndex).
    private val h5ki0 = 10_737_418_240L
    private val h5ki1 = 10_737_418_241L
    private val h7ki1 = 15_032_385_537L
    private val h8ki1 = 17_179_869_185L

    private fun delta(puts: Map<MirrorKey, Map<Timestamp, String>>, dels: Map<MirrorKey, Set<Timestamp>> = emptyMap()) =
        TaggedMapDelta(puts, dels)

    // ------------------------------------------------------------- cnDotOf

    @Test
    fun `cnDotOf picks the DOT_ORDER-max live dot across an issue's keys, presence included`() {
        val presence = MirrorKey.presence("I")
        val priority = MirrorKey("I", "priority")
        val notes = MirrorKey("I", "notes")
        val state = delta(
            puts = mapOf(
                presence to mapOf(Timestamp(srcA, h5ki0) to MirrorKey.PRESENT_VALUE),
                priority to mapOf(Timestamp(srcA, h5ki1) to "3"),
                // The highest counter of the three, on a THIRD key — cnDotOf
                // must not be keyed on any single field.
                notes to mapOf(Timestamp(srcB, h8ki1) to "\"n\""),
            ),
        )

        Provenance.cnDotOf(state, "I") shouldBe "$srcB:$h8ki1"
    }

    @Test
    fun `a counter tie across two keys is broken by sourceId`() {
        val priority = MirrorKey("I", "priority")
        val other = MirrorKey("I", "other")
        // Same counter (h7ki1) on both sides, minted by different sources —
        // exactly the WinnerDerivationTest tie shape, replayed against cnDotOf.
        val state = delta(
            puts = mapOf(
                priority to mapOf(Timestamp(srcA, h7ki1) to "3"),
                other to mapOf(Timestamp(srcB, h7ki1) to "1"),
            ),
        )

        // srcA > srcB, so srcA's dot wins the tie.
        Provenance.cnDotOf(state, "I") shouldBe "$srcA:$h7ki1"
    }

    @Test
    fun `only keys matching the issue id are considered`() {
        val mine = MirrorKey("I", "priority")
        val other = MirrorKey("OTHER", "priority")
        val state = delta(
            puts = mapOf(
                mine to mapOf(Timestamp(srcA, h5ki0) to "3"),
                // A far higher counter, but on an unrelated issue.
                other to mapOf(Timestamp(srcB, h8ki1) to "9"),
            ),
        )

        Provenance.cnDotOf(state, "I") shouldBe "$srcA:$h5ki0"
    }

    @Test
    fun `a fully tombstoned key contributes no dot`() {
        val priority = MirrorKey("I", "priority")
        val dot = Timestamp(srcA, h5ki0)
        val state = delta(
            puts = mapOf(priority to mapOf(dot to "3")),
            dels = mapOf(priority to setOf(dot)),
        )

        Provenance.cnDotOf(state, "I") shouldBe null
    }

    @Test
    fun `null when the issue holds no key at all`() {
        val state = delta(puts = mapOf(MirrorKey("OTHER", "priority") to mapOf(Timestamp(srcA, h5ki0) to "3")))

        Provenance.cnDotOf(state, "I") shouldBe null
    }

    @Test
    fun `null on an entirely empty state`() {
        Provenance.cnDotOf(delta(emptyMap()), "I") shouldBe null
    }

    // -------------------------------------------------------------- strip

    @Test
    fun `strip of null is null`() {
        Provenance.strip(null) shouldBe null
    }

    @Test
    fun `strip of an object carrying only stamp keys is null, not an empty object`() {
        val metadata = JsonObject(
            mapOf(
                Provenance.CN_DOT to JsonPrimitive("$srcA:$h5ki0"),
                Provenance.CN_ECHO to JsonPrimitive("tok-1"),
            ),
        )

        Provenance.strip(metadata) shouldBe null
    }

    @Test
    fun `strip removes only the stamp keys, leaving user metadata intact`() {
        val metadata = JsonObject(
            mapOf(
                "foo" to JsonPrimitive("bar"),
                Provenance.CN_DOT to JsonPrimitive("$srcA:$h5ki0"),
                Provenance.CN_ECHO to JsonPrimitive("tok-1"),
            ),
        )

        Provenance.strip(metadata) shouldBe JsonObject(mapOf("foo" to JsonPrimitive("bar")))
    }

    @Test
    fun `strip of an object with no stamp keys is unchanged`() {
        val metadata = JsonObject(mapOf("foo" to JsonPrimitive("bar")))

        Provenance.strip(metadata) shouldBe metadata
    }

    @Test
    fun `strip of an empty object is null`() {
        Provenance.strip(JsonObject(emptyMap())) shouldBe null
    }

    // ------------------------------------------------------------- render

    @Test
    fun `render is sourceId colon counter`() {
        Provenance.render(Timestamp(srcA, h7ki1)) shouldBe "$srcA:$h7ki1"
    }
}
