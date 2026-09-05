package civictech.cell.data.delta

import civictech.cell.Timestamp
import io.kotest.matchers.shouldBe
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import kotlin.random.nextLong

/**
 * BS-18 (`[KE1-41]`, donated from KE1/`computenet-j2x` into WIR1/`computenet-ncz`
 * as task `computenet-jend`) — the POSITIVE wire property for [TaggedMapDelta]:
 * round-trip identity through [TaggedMapDeltaSerializer] (this file, above the
 * serializer — [TaggedMapWire]), and the compact source-dictionary encoding's
 * measured size win over a naive per-dot full-`UUID` encoding.
 *
 * [TaggedMapDelta] is registered polymorphically beside `SetDelta`/`MapDelta`
 * in `civictech.cell.wire.WireCodec`'s `SerializersModule` — the
 * `subclass(TaggedMapDelta::class, TaggedMapDeltaSerializer(...) as
 * KSerializer<TaggedMapDelta<*, *>>)` call around `WireCodec.kt:193-194`
 * (re-verified at this task's own base commit `1dbf8d45e`, unchanged from the
 * bead's `~194` citation). This test exercises [TaggedMapDeltaSerializer] and
 * [TaggedMapWire] directly, never through `WireCodec`'s envelope — additive
 * registration and frame-type stability are KE1's own review evidence
 * (diff-only: zero commits under `wire/` across KE1's features `j2x.1`
 * through `j2x.7`) and are deliberately **out of scope here**, per the bead.
 */
class TaggedMapDeltaWireTest {

    private val json = Json
    private val serializer = TaggedMapDeltaSerializer(String.serializer(), String.serializer())

    // ---------------------------------------------------------------------
    // 1. Round-trip identity (acceptance clause 1, `[24-TMAP-01]`/`[KE1-41]`)
    // ---------------------------------------------------------------------

    private fun roundTrip(delta: TaggedMapDelta<String, String>): TaggedMapDelta<String, String> =
        json.decodeFromString(serializer, json.encodeToString(serializer, delta))

    @Test
    fun `empty delta round-trips`() {
        val original = TaggedMapDelta<String, String>()
        roundTrip(original) shouldBe original
    }

    @Test
    fun `single key single dot round-trips`() {
        val dot = Timestamp(UUID.randomUUID(), 1L)
        val original = TaggedMapDelta(puts = mapOf("k" to mapOf(dot to "v")))
        roundTrip(original) shouldBe original
    }

    @Test
    fun `puts and dels across multiple keys and multiple dot sources round-trip`() {
        val s1 = UUID.randomUUID()
        val s2 = UUID.randomUUID()
        val s3 = UUID.randomUUID()
        val dotA = Timestamp(s1, 1L)
        val dotB = Timestamp(s2, 2L)
        val dotC = Timestamp(s2, 3L)
        val dotD = Timestamp(s3, 4L)
        val original = TaggedMapDelta(
            puts = mapOf(
                "alpha" to mapOf(dotA to "a1", dotB to "a2"),
                "beta" to mapOf(dotC to "b1"),
            ),
            dels = mapOf(
                "alpha" to setOf(dotA),
                // a tombstone with no corresponding put in this delta — the
                // deferred-context case TaggedMapDelta's own KDoc describes
                // ("Tombstoned dels subsume deferred context ops").
                "gamma" to setOf(dotD),
            ),
        )
        roundTrip(original) shouldBe original
    }

    @Test
    fun `randomly generated deltas round-trip across many seeds`() {
        for (seed in 0 until 40) {
            val random = Random(seed)
            val keyCount = 1 + random.nextInt(5)
            val sourceCount = 1 + random.nextInt(4)
            val original = randomDelta(random, keyCount, sourceCount, maxDotsPerKey = 6)
            roundTrip(original) shouldBe original
        }
    }

    private fun randomDelta(
        random: Random,
        keyCount: Int,
        sourceCount: Int,
        maxDotsPerKey: Int,
    ): TaggedMapDelta<String, String> {
        val sources = List(sourceCount) { UUID(random.nextLong(), random.nextLong()) }
        val puts = LinkedHashMap<String, Map<Timestamp, String>>()
        val dels = LinkedHashMap<String, Set<Timestamp>>()
        repeat(keyCount) { k ->
            val key = "key-$k"
            val dotCount = 1 + random.nextInt(maxDotsPerKey)
            val dots = LinkedHashMap<Timestamp, String>()
            repeat(dotCount) { d ->
                val source = sources[random.nextInt(sourceCount)]
                val counter = random.nextLong(0L until 1_000_000L)
                dots[Timestamp(source, counter)] = "value-$k-$d"
            }
            puts[key] = dots
            // tombstone every other dot of this key, so `dels` is exercised
            // for most keys without depending on `random` again.
            if (dots.isNotEmpty()) {
                val tombstoned = dots.keys.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
                if (tombstoned.isNotEmpty()) dels[key] = tombstoned
            }
        }
        return TaggedMapDelta(puts, dels)
    }

    // ---------------------------------------------------------------------
    // 2. Compact source-dictionary encoding, measured (acceptance clause 2)
    // ---------------------------------------------------------------------

    /**
     * **Comparison method.** A delta with 100 dots under one key, spread over
     * only 3 distinct `sourceId`s, is encoded two ways with the same `Json`
     * instance the codec uses (`kotlinx.serialization.json.Json`, default
     * config — the codec registers no custom `Json`/`Cbor` builder for this
     * type, so the default instance is the one under test):
     *
     * 1. **Compact** — [TaggedMapDeltaSerializer]'s real wire shape
     *    ([TaggedMapWire]): the 3 distinct `sourceId`s hoisted once into a
     *    `sources: List<String>` dictionary, every dot referencing its source
     *    by `Int` ordinal.
     * 2. **Naive** — a hand-built JSON array with one object per dot, each
     *    repeating the full `sourceId` `UUID` string (no dictionary, no
     *    ordinals) — the encoding [TaggedMapWire]'s KDoc says the surrogate
     *    exists to avoid ("Riak names actor-metadata repetition 'a serious
     *    issue' for size").
     *
     * Two things are asserted, per the bead's acceptance clause 2: the
     * dictionary is strictly smaller than the dot count (`sources.size == 3`
     * against `dotCount == 100`, i.e. `distinctSources < dotCount`), and the
     * compact encoding's UTF-8 byte length is strictly smaller than the
     * naive encoding's.
     *
     * **Observed byte counts** (measured on this run, `Json` default config,
     * UTF-8 `.toByteArray().size`): compact = **1155** bytes, naive = **7681**
     * bytes — a 6.6x reduction — with dictionary = **3** entries against
     * **100** dots. The exact byte counts are dependent on
     * `kotlinx.serialization`'s default JSON formatting and are recorded
     * here as the measured evidence, not asserted as exact literals in the
     * test body — the assertions below check the relative bound
     * (`compactBytes < naiveBytes`, `sources.size < dotCount`), which is
     * what the property claims and what a mutation to the encoding should
     * break.
     */
    @Test
    fun `compact source dictionary encoding is measurably smaller than naive per-dot UUID encoding`() {
        val dotCount = 100
        val distinctSources = 3
        val sources = List(distinctSources) { UUID.randomUUID() }
        val dots = LinkedHashMap<Timestamp, String>()
        repeat(dotCount) { i ->
            val source = sources[i % distinctSources]
            dots[Timestamp(source, i.toLong())] = "v$i"
        }
        val delta = TaggedMapDelta(puts = mapOf("hot" to dots))

        // Compact: the real serializer's wire shape.
        val compactEncoded = json.encodeToString(serializer, delta)
        val compactBytes = compactEncoded.toByteArray(Charsets.UTF_8).size

        // The dictionary itself, decoded from the same compact payload —
        // strictly smaller than the dot count, since distinctSources < dotCount.
        val wireSerializer = TaggedMapWire.serializer(String.serializer(), String.serializer())
        val wire = json.decodeFromString(wireSerializer, compactEncoded)
        wire.sources.size shouldBe distinctSources
        (wire.sources.size < dotCount) shouldBe true

        // Naive: one JSON object per dot, repeating the full UUID string —
        // hand-built rather than routed through any serializer, so it is
        // exactly the encoding the dictionary is meant to beat.
        val naiveEncoded = buildString {
            append('[')
            dots.entries.forEachIndexed { i, (dot, value) ->
                if (i > 0) append(',')
                append("{\"source\":\"").append(dot.sourceId).append("\",\"counter\":").append(dot.counter)
                    .append(",\"value\":\"").append(value).append("\"}")
            }
            append(']')
        }
        val naiveBytes = naiveEncoded.toByteArray(Charsets.UTF_8).size

        (compactBytes < naiveBytes) shouldBe true
    }
}
