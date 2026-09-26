package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.Aggregators
import civictech.cell.data.Windows
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.UUID

/**
 * KE4.3 (computenet-nt17o.2): [GroupByCell]'s class-level `waterline`/`late`
 * ports, floor tracking and late-drop guard — spec 24 §Lateness and
 * waterlines `[24-WL-03]` (the consumer's fixpoint), `[24-WL-04]`,
 * `[24-WL-07]`, `[24-WL-08]`, `[24-WL-11]`, and the floor half of
 * `[22-REC-01]` (nt17o-D4). Eviction on a floor rise is computenet-nt17o.3's
 * and appends to this file.
 *
 * Fixture: elements are `Long` event times, tumbling windows of 10 keyed by
 * their start, `keyTime` = the window's end, lateness 2, count per window.
 */
class GroupByEvictionTest {

    /** Identity event time; a named `Serializable` function value, as `[24-WL-01]` asks. */
    private object LongTime : (Long) -> Long, Serializable {
        override fun invoke(e: Long): Long = e
        private fun readResolve(): Any = LongTime
    }

    private fun tag(n: Long) = Timestamp(UUID(0, n), n)

    private fun windowed(ref: CellRef = CellRef(UUID.randomUUID())) = GroupByCell(
        ref = ref,
        keyFn = Windows.tumbling(10),
        aggregator = Aggregators.count<Long>(),
        lateness = Windows.Lateness(LongTime, 2),
        keyTime = { k: Long -> k + 10 },
    )

    private fun <T : Any> collect(outlet: civictech.cell.port.Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    /** A linked recording inlet: data deltas and metadata-plane [Progress] absorb-acks. */
    private class AckProbe<D>(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<D>>))
        val deltas = mutableListOf<D>()
        val acks = mutableListOf<Progress>()

        init {
            inlet.serve(object : Propagate<D> {
                override fun propagate(value: D) {
                    deltas += value
                }
            })
            ProtocolSupport.of(inlet).handle(Protocols.Progress) { _, message -> acks += message as Progress }
        }
    }

    private fun <T> underWave(src: UUID, counter: Long, block: () -> T): T =
        CurrentContext.with(MessageContext(Timestamp(src, counter), PortRef.generate())) { block() }

    @Suppress("UNCHECKED_CAST")
    private fun roundTrip(state: Serializable): Serializable {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(state) }
        return ObjectInputStream(bytes.toByteArray().inputStream()).use { it.readObject() as Serializable }
    }

    // ------------------------------------------------------ [24-WL-11] identity

    @Test
    fun `without lateness the cell is today's operator and its lateness ports sit unlinked`() {
        // GroupByCellTest's first test, verbatim inputs
        fun key(e: String) = e.first().toString()
        fun amount(e: String) = e.drop(1).toLong()
        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        val inputs = listOf(
            SetDelta(adds = mapOf("a3" to setOf(t1), "a4" to setOf(t2), "b5" to setOf(t3))),
            SetDelta(dels = mapOf("a3" to setOf(t1))),
            SetDelta(dels = mapOf("b5" to setOf(t3))),
        )
        val expected = listOf(
            MapDelta(mapOf("a" to 7L, "b" to 5L), emptySet()),
            MapDelta(mapOf("a" to 4L), emptySet()),
            MapDelta(emptyMap<String, Long>(), setOf("b")),
        )

        val cell = GroupByCell(keyFn = ::key, aggregator = Aggregators.sumOf(::amount))
        val out = collect(cell.outlet)
        val late = collect(cell.late)
        inputs.forEach { cell.inlet.call.propagate(it) }

        out shouldBe expected
        late.shouldBeEmpty()
        cell.floor() shouldBe null
        cell.droppedBelowFloor shouldBe 0L
        cell.waterline.linking.links.shouldBeEmpty()
        cell.late.linking.links.shouldBeEmpty()
        // [24-WL-11]: reached through the Api, only inlet/outlet exist
        val api: GroupByApi<String, String, Long> = cell
        api.inlet shouldBe cell.inlet
        // the snapshot keeps its two-element pre-lateness shape
        (cell.snapshot() as List<*>).size shouldBe 2
    }

    // ---------------------------------------------------------- [24-WL-07] guard

    @Test
    fun `an add at or above the floor, or under a null floor, is an ordinary add and late stays silent`() {
        val cell = windowed()
        val out = collect(cell.outlet)
        val late = collect(cell.late)

        // null floor: the identity admits everything, even t=1
        cell.inlet.call.propagate(SetDelta(adds = mapOf(1L to setOf(tag(1)))))
        out.last().puts shouldBe mapOf(0L to 1L)

        cell.waterline.call.propagate(WaterlineDelta(8))
        cell.floor() shouldBe 8L
        // the strict boundary: timeFn(8) == floor is not below it (B2's admitted side)
        cell.inlet.call.propagate(SetDelta(adds = mapOf(8L to setOf(tag(2)))))
        out.last().puts shouldBe mapOf(0L to 2L)

        late.shouldBeEmpty()
        cell.droppedBelowFloor shouldBe 0L
    }

    @Test
    fun `a sub-floor add is excluded from the fold and forwarded verbatim on late`() {
        val cell = windowed()
        val out = collect(cell.outlet)
        val late = collect(cell.late)
        cell.waterline.call.propagate(WaterlineDelta(8))

        val t1 = setOf(tag(1))
        cell.inlet.call.propagate(SetDelta(adds = mapOf(1L to t1, 25L to setOf(tag(2)))))

        out shouldBe listOf(MapDelta(mapOf(20L to 1L), emptySet()))
        late shouldBe listOf(SetDelta(adds = mapOf(1L to t1)))
        late.single().adds.getValue(1L) shouldBe t1
        cell.droppedBelowFloor shouldBe 1L
        // no group was created for the dropped element's window
        cell.snapshot().let { (it as List<*>)[1] as Map<*, *> }.keys shouldBe setOf(20L)
    }

    @Test
    fun `a sub-floor add is excluded and counted even when late is unlinked`() {
        val cell = windowed()
        val out = collect(cell.outlet)
        cell.waterline.call.propagate(WaterlineDelta(8))

        cell.inlet.call.propagate(SetDelta(adds = mapOf(1L to setOf(tag(1)))))

        out.shouldBeEmpty()
        cell.late.linking.links.shouldBeEmpty()
        cell.droppedBelowFloor shouldBe 1L
        (cell.snapshot() as List<*>)[1] shouldBe HashMap<Long, Any>()
    }

    @Test
    fun `a sub-floor re-add of a live element is dropped without touching the live copy`() {
        val cell = windowed()
        val out = collect(cell.outlet)
        val late = collect(cell.late)

        cell.inlet.call.propagate(SetDelta(adds = mapOf(7L to setOf(tag(1)))))
        out shouldBe listOf(MapDelta(mapOf(0L to 1L), emptySet()))

        cell.waterline.call.propagate(WaterlineDelta(8))
        val fresh = setOf(tag(2))
        cell.inlet.call.propagate(SetDelta(adds = mapOf(7L to fresh)))

        late shouldBe listOf(SetDelta(adds = mapOf(7L to fresh)))
        out.size shouldBe 1 // no removal, no put: the group is untouched
        cell.droppedBelowFloor shouldBe 1L
        cell.contents().adds shouldBe mapOf(7L to setOf(tag(1))) // still live under its original tag only
    }

    // --------------------------------------------------------- [24-WL-08] dels

    @Test
    fun `a del folds iff its tag is live, whatever its event time`() {
        val cell = windowed()
        val out = collect(cell.outlet)
        val late = collect(cell.late)
        val t1 = setOf(tag(1))
        cell.inlet.call.propagate(SetDelta(adds = mapOf(7L to t1)))
        cell.waterline.call.propagate(WaterlineDelta(8))

        // lxo-D7: 7 < floor, but its tag is live — the del folds
        cell.inlet.call.propagate(SetDelta(dels = mapOf(7L to t1)))
        out.last() shouldBe MapDelta(emptyMap(), setOf(0L))

        // a del for a never-seen tag: no emission, no failure
        cell.inlet.call.propagate(SetDelta(dels = mapOf(3L to setOf(tag(9)))))
        out.size shouldBe 2
        late.shouldBeEmpty()
        cell.droppedBelowFloor shouldBe 0L
    }

    // ------------------------------------------ [24-WL-03] fixpoint, [24-WL-04]

    @Test
    fun `a non-raising waterline changes nothing and absorb-acks outlet and late`() {
        val cell = windowed()
        val outProbe = AckProbe<MapDelta<Long, Long>>()
        val lateProbe = AckProbe<SetDelta<Long>>()
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(outProbe.inlet as LinkFrom<Propagate<MapDelta<Long, Long>>>)
        @Suppress("UNCHECKED_CAST")
        cell.late.linkTo(lateProbe.inlet as LinkFrom<Propagate<SetDelta<Long>>>)
        val src = UUID(7, 7)

        underWave(src, 1) { cell.waterline.call.propagate(WaterlineDelta(8)) }
        cell.floor() shouldBe 8L

        underWave(src, 2) { cell.waterline.call.propagate(WaterlineDelta(8)) }
        underWave(src, 3) { cell.waterline.call.propagate(WaterlineDelta(5)) }
        cell.floor() shouldBe 8L

        // no data ever left either outlet: the floor is read, never re-emitted ([24-WL-04])
        outProbe.deltas.shouldBeEmpty()
        lateProbe.deltas.shouldBeEmpty()
        // each delivery is acked on both outlets under its own wave (the raising one
        // by the nt17o.3 eviction hook, which in this task evicts nothing)
        val expected = listOf(Progress(src, 1), Progress(src, 2), Progress(src, 3))
        outProbe.acks shouldBe expected
        lateProbe.acks shouldBe expected
    }

    @Test
    fun `an inlet delivery acks late when nothing is dropped and emits on it when something is`() {
        val cell = windowed()
        val lateProbe = AckProbe<SetDelta<Long>>()
        @Suppress("UNCHECKED_CAST")
        cell.late.linkTo(lateProbe.inlet as LinkFrom<Propagate<SetDelta<Long>>>)
        val src = UUID(7, 8)

        underWave(src, 1) { cell.inlet.call.propagate(SetDelta(adds = mapOf(12L to setOf(tag(1))))) }
        underWave(src, 2) { cell.waterline.call.propagate(WaterlineDelta(8)) }
        underWave(src, 3) { cell.inlet.call.propagate(SetDelta(adds = mapOf(1L to setOf(tag(2))))) }

        lateProbe.acks shouldBe listOf(Progress(src, 1), Progress(src, 2))
        lateProbe.deltas shouldBe listOf(SetDelta(adds = mapOf(1L to setOf(tag(2)))))
    }

    // ------------------------------------------------------------ construction

    @Test
    fun `lateness and keyTime come together, and a waterline on a cell without them throws`() {
        shouldThrow<IllegalArgumentException> {
            GroupByCell(
                keyFn = Windows.tumbling(10),
                aggregator = Aggregators.count<Long>(),
                lateness = Windows.Lateness(LongTime, 2),
            )
        }.message shouldContain "keyTime"
        shouldThrow<IllegalArgumentException> {
            GroupByCell(
                keyFn = Windows.tumbling(10),
                aggregator = Aggregators.count<Long>(),
                keyTime = { k: Long -> k + 10 },
            )
        }.message shouldContain "lateness"

        val ref = CellRef(UUID.randomUUID())
        val plain = GroupByCell(ref, Windows.tumbling(10), Aggregators.count<Long>())
        shouldThrow<IllegalStateException> {
            plain.waterline.call.propagate(WaterlineDelta(8))
        }.message shouldContain ref.toString()
        plain.floor() shouldBe null
    }

    // ------------------------------------------------------ nt17o-D4 snapshot

    @Test
    fun `the floor survives a snapshot round-trip and a legacy snapshot restores with no floor`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = windowed(ref)
        cell.inlet.call.propagate(SetDelta(adds = mapOf(12L to setOf(tag(1)))))
        cell.waterline.call.propagate(WaterlineDelta(8))

        val restored = windowed(ref)
        restored.restore(roundTrip(cell.snapshot()))
        restored.floor() shouldBe 8L
        restored.contents() shouldBe cell.contents()
        // the restored floor still guards: a sub-floor add is dropped
        restored.inlet.call.propagate(SetDelta(adds = mapOf(1L to setOf(tag(2)))))
        restored.droppedBelowFloor shouldBe 1L

        // legacy: the pre-lateness two-element form
        val legacy = ArrayList((cell.snapshot() as List<Serializable?>).take(2))
        val fromLegacy = windowed(ref)
        fromLegacy.restore(roundTrip(legacy))
        fromLegacy.floor() shouldBe null
        fromLegacy.contents() shouldBe cell.contents()
    }
}
