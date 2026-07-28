package civictech.inspect

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.data.Aggregators
import civictech.cell.data.CounterCell
import civictech.cell.data.MapCell
import civictech.cell.data.PnCounterCell
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.GroupByCell
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/** A public record shape, so the encoder's column decomposition can reach it. */
data class Reading(val sensor: String, val celsius: Double)

/** No `componentN`, no record components — the encoder's `opaque` last resort. */
class Opaque(private val label: String) {
    override fun toString() = "Opaque($label)"
}

/**
 * Golden encodings for `20-api-contract.md` §Value. The inputs are the kernel's
 * *real* snapshot shapes — produced by driving actual cells, not by hand-writing
 * the maps they happen to return today — so a kernel change to a snapshot format
 * fails here instead of silently degrading the inspector's state panel.
 */
class ValueEncoderTest {

    private val json = Json

    private fun encoded(state: Any?) = ValueEncoder.encode(state).toString()

    private fun tag(counter: Long) = Timestamp(UUID(0, counter), counter)

    // ------------------------------------------------------------ kernel shapes

    @Test
    fun `a SetCell snapshot encodes live membership, tombstoned elements excluded`() {
        val cell = SetCell<String>()
        cell.inlet.call.add("grace")
        cell.inlet.call.add("ada")
        cell.inlet.call.add("linus")
        cell.inlet.call.remove("linus")

        // the raw snapshot is OR-set tag algebra ({adds, dels, counter}); the
        // *state* it encodes is membership, and a removed element is gone from
        // it even though its add-tag is still in `adds`
        encoded(cell.snapshot()) shouldBe """["ada","grace"]"""
    }

    @Test
    fun `a re-added element survives its own tombstone`() {
        val cell = SetCell<String>()
        cell.inlet.call.add("ada")
        cell.inlet.call.remove("ada")
        cell.inlet.call.add("ada")

        encoded(cell.snapshot()) shouldBe """["ada"]"""
    }

    @Test
    fun `a MapCell snapshot encodes as a key-value table`() {
        val cell = MapCell<String, Long>()
        cell.inlet.call.put("b", 2L)
        cell.inlet.call.put("a", 1L)

        encoded(cell.snapshot()) shouldBe
            """{"${'$'}table":{"columns":["key","value"],"rows":[["a",1],["b",2]]}}"""
    }

    @Test
    fun `a CounterCell snapshot encodes as a scalar`() {
        val cell = CounterCell()
        cell.inlet.call.increment(7)
        cell.inlet.call.decrement(2)

        encoded(cell.snapshot()) shouldBe "5"
    }

    @Test
    fun `a PnCounterCell snapshot encodes as its net total`() {
        val cell = PnCounterCell()
        cell.inlet.call.increment(10)
        cell.inlet.call.decrement(4)

        encoded(cell.snapshot()) shouldBe "6"
    }

    @Test
    fun `an operator cell snapshot encodes each part of its ledger`() {
        val cell = GroupByCell<String, Int, Long, Long>(
            keyFn = { it.length },
            aggregator = Aggregators.count(),
        )
        cell.inlet.call.propagate(
            SetDelta(adds = mapOf("ada" to setOf(tag(1)), "bob" to setOf(tag(2)), "grace" to setOf(tag(3)))),
        )

        // GroupByCell.snapshot() is [tagged input membership, per-key groups];
        // the tag map folds to its live elements, the groups to a table
        val parts = ValueEncoder.encode(cell.snapshot()).jsonArray
        parts.size shouldBe 2
        parts[0] shouldBe json.parseToJsonElement("""["ada","bob","grace"]""")
        val groups = parts[1].jsonObject.getValue(ValueEncoder.TABLE).jsonObject
        groups.getValue("columns") shouldBe json.parseToJsonElement("""["key","value"]""")
        // key 3 holds two elements, key 5 one — [count, accumulator] per group
        groups.getValue("rows") shouldBe json.parseToJsonElement("""[[3,[2,2]],[5,[1,1]]]""")
    }

    // ----------------------------------------------------------- generic shapes

    @Test
    fun `a snapshot-only cell's records decompose into table columns`() {
        val cell = Thermometer()

        encoded(cell.snapshot()) shouldBe
            """{"${'$'}table":{"columns":["sensor","celsius"],"rows":[["roof",1.5],["cellar",-2.0]]}}"""
    }

    @Test
    fun `an empty collection of records is a list, since columns need an element`() {
        // the shape a cell's state takes when it empties out; the client must
        // accept both this and the `$table` the same cell reports when full
        encoded(emptySet<Reading>()) shouldBe "[]"
        encoded(listOf(Reading("roof", 1.5))) shouldContain ValueEncoder.TABLE
    }

    @Test
    fun `a value with no safe decomposition is marked opaque, never guessed at`() {
        val encoded = ValueEncoder.encode(Opaque("x")).jsonObject.getValue(ValueEncoder.OPAQUE).jsonObject

        encoded.getValue("type").jsonPrimitive.content shouldBe "civictech.inspect.Opaque"
        encoded.getValue("text").jsonPrimitive.content shouldBe "Opaque(x)"
    }

    @Test
    fun `a non-string map key survives as a table cell rather than being stringified`() {
        val state = mapOf(Reading("roof", 1.5) to 3L)

        encoded(state) shouldBe
            """{"${'$'}table":{"columns":["key","value"],"rows":[[{"sensor":"roof","celsius":1.5},3]]}}"""
    }

    @Test
    fun `scalars, nulls and enums encode as themselves`() {
        encoded(null) shouldBe "null"
        encoded("text") shouldBe "\"text\""
        encoded(true) shouldBe "true"
        encoded(3.5) shouldBe "3.5"
        encoded(UUID(0, 1)) shouldBe "\"00000000-0000-0000-0000-000000000001\""
        encoded(Season.WINTER) shouldBe "\"WINTER\""
    }

    // -------------------------------------------------------------- truncation

    @Test
    fun `a list past the row limit is truncated with the contract's marker`() {
        val big = (1..250).map { "e%03d".format(it) }.toSet()

        val encoded = ValueEncoder.encode(big).jsonArray

        // 200 admitted rows plus the appended marker
        encoded.size shouldBe ValueEncoder.MAX_ROWS + 1
        encoded.first().jsonPrimitive.content shouldBe "e001"
        encoded[ValueEncoder.MAX_ROWS - 1].jsonPrimitive.content shouldBe "e200"
        marker(encoded.last()) shouldBe (250 to 200)
    }

    @Test
    fun `a table past the row limit is truncated with the contract's marker`() {
        val big = (1..250).associate { "k%03d".format(it) to it.toLong() }

        val encoded = ValueEncoder.encode(big).jsonObject

        encoded.getValue(ValueEncoder.TABLE).jsonObject.getValue("rows").jsonArray.size shouldBe
            ValueEncoder.MAX_ROWS
        val truncated = encoded.getValue(ValueEncoder.TRUNCATED).jsonObject
        truncated.getValue("total").jsonPrimitive.content shouldBe "250"
        truncated.getValue("shown").jsonPrimitive.content shouldBe "200"
    }

    @Test
    fun `the byte budget truncates well before the row limit on fat rows`() {
        val fat = (1..20).associate { "k%02d".format(it) to "x".repeat(5_000) }

        val encoded = ValueEncoder.encode(fat).jsonObject

        val rows = encoded.getValue(ValueEncoder.TABLE).jsonObject.getValue("rows").jsonArray
        // 20 rows is far under MAX_ROWS, so only the 50 KB budget can cut here
        (rows.size in 1 until 20) shouldBe true
        encoded.getValue(ValueEncoder.TRUNCATED).jsonObject.getValue("total").jsonPrimitive.content shouldBe "20"
        encoded.toString().length shouldBe encoded.toString().length.coerceAtMost(ValueEncoder.MAX_BYTES * 2)
    }

    @Test
    fun `the budget is per response, not per nested container`() {
        // two sibling 150-row tables: the first exhausts most of the 200-row
        // allowance, so the second is cut — the budget cannot be enlarged by
        // nesting rows one level deeper
        val nested = listOf(
            (1..150).associate { "a%03d".format(it) to it.toLong() },
            (1..150).associate { "b%03d".format(it) to it.toLong() },
        )

        val parts = ValueEncoder.encode(nested).jsonArray
        val rows = parts.map { it.jsonObject.getValue(ValueEncoder.TABLE).jsonObject.getValue("rows").jsonArray.size }

        // the first table nearly exhausts the allowance; the second is cut and
        // says so, and no arrangement of nesting exceeds the total
        rows[0] shouldBe 150
        (rows[1] < 150) shouldBe true
        (rows.sum() <= ValueEncoder.MAX_ROWS) shouldBe true
        parts[1].jsonObject.containsKey(ValueEncoder.TRUNCATED) shouldBe true
    }

    // ------------------------------------------------------------- cardinality

    @Test
    fun `cardinality summarizes size, and only where size means something`() {
        val cell = SetCell<String>()
        cell.inlet.call.add("ada")
        cell.inlet.call.add("grace")

        ValueEncoder.cardinality(cell.snapshot()) shouldBe "2 rows"
        ValueEncoder.cardinality(setOf("only")) shouldBe "1 row"
        ValueEncoder.cardinality(emptyMap<String, String>()) shouldBe "0 rows"
        ValueEncoder.cardinality(42L) shouldBe null
        ValueEncoder.cardinality(null) shouldBe null
    }

    @Test
    fun `a truncated table still reports the full cardinality`() {
        val big = (1..250).associate { "k%03d".format(it) to it.toLong() }

        ValueEncoder.cardinality(big) shouldBe "250 rows"
        (ValueEncoder.encode(big) as JsonObject).containsKey(ValueEncoder.TRUNCATED) shouldBe true
    }

    @Test
    fun `an encoded state never mentions a reserved key it did not mean`() {
        // guards the reserved-key vocabulary: these three are the only ones
        // the contract's client has to know about
        val encoded = encoded(mapOf("a" to listOf(1, 2)))

        encoded shouldContain ValueEncoder.TABLE
        (ValueEncoder.TRUNCATED in encoded) shouldBe false
        (ValueEncoder.OPAQUE in encoded) shouldBe false
    }

    private fun marker(element: kotlinx.serialization.json.JsonElement): Pair<Int, Int> {
        val truncated = element.jsonObject.getValue(ValueEncoder.TRUNCATED).jsonObject
        return truncated.getValue("total").jsonPrimitive.content.toInt() to
            truncated.getValue("shown").jsonPrimitive.content.toInt()
    }

    private fun JsonArray.getValue(index: Int) = this[index]

    enum class Season { WINTER }

    /**
     * A cell with state but no delta outlet: exactly the "snapshot-only" shape
     * the ticket names. Nothing observes it — the encoder is fed its snapshot
     * directly.
     */
    private class Thermometer(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Stateful {
        private val readings = listOf(Reading("roof", 1.5), Reading("cellar", -2.0))
        override fun snapshot(): Serializable = ArrayList(readings)
        override fun restore(state: Serializable) = Unit
    }
}
