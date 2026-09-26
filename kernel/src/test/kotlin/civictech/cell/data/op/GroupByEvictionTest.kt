package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.data.Aggregators
import civictech.cell.data.Replicable
import civictech.cell.data.Windows
import civictech.cell.data.delta.TagState
import civictech.cell.data.mapFold
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
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
 * `[KE4-45]` (nt17o-D4). computenet-nt17o.3 appends eviction on a floor
 * rise: `[24-WL-05]`, `[24-WL-06]`, `[24-WL-09]`, `[24-WL-17]` (per-window
 * exclusive refusal), `[24-WL-18]` (the `Replicable` seam refusal), the
 * structural half of `[24-WL-19]`, and the snapshot half of B13.
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
        // and the dropped element never entered the tag state: only the admitted part was folded
        cell.contents().adds shouldBe mapOf(25L to setOf(tag(2)))
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
        // by the eviction step, which here has no window to evict)
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

    // ================================================ eviction (computenet-nt17o.3)

    /** A late-joining subscriber: linking replays the cell's current aggregates (G-22 catch-up). */
    private class LateJoiner<K, V> {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<K, V>>>))
        val arrivals = mutableListOf<MapDelta<K, V>>()

        init {
            inlet.serve(object : Propagate<MapDelta<K, V>> {
                override fun propagate(value: MapDelta<K, V>) {
                    arrivals += value
                }
            })
        }
    }

    /** Link a fresh subscriber to [cell] now and return the fold of what it caught up with. */
    private fun <K, V> lateJoinFold(cell: GroupByCell<*, K, V, *>): Map<K, V> {
        val joiner = LateJoiner<K, V>()
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(joiner.inlet as LinkFrom<Propagate<MapDelta<K, V>>>)
        return mapFold(joiner.arrivals)
    }

    /** Subscriber A: every delta with the wave timestamp it arrived under (as `WaterlineCellTest.record`). */
    private fun <K, V> recordWaves(cell: GroupByCell<*, K, V, *>): MutableList<Pair<MapDelta<K, V>, Timestamp?>> {
        val seen = mutableListOf<Pair<MapDelta<K, V>, Timestamp?>>()
        cell.outlet.subscribe(Use.fixed(object : Propagate<MapDelta<K, V>> {
            override fun propagate(value: MapDelta<K, V>) {
                seen += value to CurrentContext.get()?.timestamp
            }
        }, PortRef.generate()))
        return seen
    }

    /** The keys [cell] holds in `groups`, read through its snapshot. */
    private fun groupKeys(cell: GroupByCell<*, *, *, *>): Set<Any?> =
        ((cell.snapshot() as List<*>)[1] as Map<*, *>).keys

    /** Windows 0 {1, 5}, 10 {12}, 20 {25, 27} under tags 1..5. */
    private fun populated(cell: GroupByCell<Long, Long, Long, Long>) = cell.inlet.call.propagate(
        SetDelta(adds = mapOf(1L to setOf(tag(1)), 5L to setOf(tag(2)), 12L to setOf(tag(3)), 25L to setOf(tag(4)), 27L to setOf(tag(5)))),
    )

    @Test
    fun `B3 - a floor rise evicts every passed window as one removal delta under the waterline's wave`() {
        val cell = windowed()
        val a = recordWaves(cell)
        populated(cell)
        a.map { it.first } shouldBe listOf(MapDelta(mapOf(0L to 2L, 10L to 1L, 20L to 2L), emptySet()))

        val src = UUID(3, 3)
        underWave(src, 7) { cell.waterline.call.propagate(WaterlineDelta(20)) }

        // [24-WL-05]: state == integrated output — A's fold equals a late joiner's catch-up.
        // Asserted first: the B3 control (eviction emission dropped) reddens exactly here.
        val foldA = mapFold(a.map { it.first })
        lateJoinFold(cell) shouldBe foldA
        foldA shouldBe mapOf(20L to 2L)

        // [24-WL-06]: exactly one MapDelta, removals {0, 10}, no puts, under (S, 7) ([24-OP-GROUPBY-03])
        val eviction = a.drop(1)
        eviction.size shouldBe 1
        eviction.single().first shouldBe MapDelta(emptyMap(), setOf(0L, 10L))
        eviction.single().second shouldBe Timestamp(src, 7)

        // [24-WL-19] structural half: no window with keyTime <= floor remains (none refused here)
        groupKeys(cell).filter { (it as Long) + 10 <= 20 }.shouldBeEmpty()
        cell.contents().adds.keys shouldBe setOf(25L, 27L)
        cell.refusedWindows() shouldBe emptyMap()
        cell.refusedEvictions shouldBe 0L
    }

    @Test
    fun `B1 - an add for an evicted window is late-dropped and re-creates no group`() {
        val cell = windowed()
        val out = collect(cell.outlet)
        val late = collect(cell.late)
        populated(cell)
        cell.waterline.call.propagate(WaterlineDelta(20))
        val emitted = out.size

        val t9 = setOf(tag(9))
        cell.inlet.call.propagate(SetDelta(adds = mapOf(7L to t9)))

        out.size shouldBe emitted // no put for key 0
        late shouldBe listOf(SetDelta(adds = mapOf(7L to t9)))
        groupKeys(cell) shouldBe setOf(20L)
        cell.droppedBelowFloor shouldBe 1L
    }

    @Test
    fun `B4 - a del in flight for an evicted element is a no-op, never a negative count`() {
        val cell = windowed()
        val out = collect(cell.outlet)
        val t1 = setOf(tag(1))
        cell.inlet.call.propagate(SetDelta(adds = mapOf(7L to t1, 15L to setOf(tag(2)))))

        cell.waterline.call.propagate(WaterlineDelta(12))
        out.last() shouldBe MapDelta(emptyMap(), setOf(0L)) // window 0 evicted; window 10 (end 20) kept
        val emitted = out.size

        // [24-WL-09]: the del's tag is no longer live — no emission, no "retract for untracked group"
        cell.inlet.call.propagate(SetDelta(dels = mapOf(7L to t1)))
        out.size shouldBe emitted
        groupKeys(cell) shouldBe setOf(10L)
        mapFold(out) shouldBe mapOf(10L to 1L)
        lateJoinFold(cell) shouldBe mapFold(out)
    }

    @Test
    fun `a redelivered waterline after an eviction evicts and emits nothing`() {
        val cell = windowed()
        val probe = AckProbe<MapDelta<Long, Long>>()
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(probe.inlet as LinkFrom<Propagate<MapDelta<Long, Long>>>)
        populated(cell)
        val src = UUID(3, 4)
        underWave(src, 1) { cell.waterline.call.propagate(WaterlineDelta(20)) }
        val deltas = probe.deltas.toList()
        val contents = cell.contents()

        // [24-WL-03] fixpoint: equal, then lower
        underWave(src, 2) { cell.waterline.call.propagate(WaterlineDelta(20)) }
        underWave(src, 3) { cell.waterline.call.propagate(WaterlineDelta(15)) }

        probe.deltas shouldBe deltas
        probe.acks shouldBe listOf(Progress(src, 2), Progress(src, 3))
        cell.contents() shouldBe contents
        groupKeys(cell) shouldBe setOf(20L)
    }

    @Test
    fun `B15 - a passed window holding an Owned element is refused per window while the others evict`() {
        // elements are Long or Owned<Long>; an Owned's event time comes from a test-side identity map,
        // so the cell never borrows or takes it to read a time
        val owned = Owned(3L)
        val ownedTime = java.util.IdentityHashMap<Any, Long>().apply { put(owned, 3L) }
        val timeOf: (Any) -> Long = { e -> if (e is Long) e else ownedTime.getValue(e) }
        val window = Windows.tumbling(10)
        val ref = CellRef(UUID.randomUUID())
        val cell = GroupByCell<Any, Long, Long, Long>(
            ref = ref,
            keyFn = { e -> window(timeOf(e)) },
            aggregator = Aggregators.count(),
            lateness = Windows.Lateness(timeOf, 2),
            keyTime = { k: Long -> k + 10 },
        )
        val out = collect(cell.outlet)
        val late = collect(cell.late)
        val ownedTag = setOf(tag(1))
        cell.inlet.call.propagate(
            SetDelta(adds = mapOf(owned to ownedTag, 5L to setOf(tag(2)), 12L to setOf(tag(3)), 15L to setOf(tag(4)))),
        )

        cell.waterline.call.propagate(WaterlineDelta(20))

        // one delta, window 10 only; window 0 untouched, nothing emitted for it
        out.drop(1) shouldBe listOf(MapDelta(emptyMap(), setOf(10L)))
        groupKeys(cell) shouldBe setOf(0L)
        mapFold(out) shouldBe mapOf(0L to 2L)
        lateJoinFold(cell) shouldBe mapFold(out) // state still equals integrated output
        val refusal = cell.refusedWindows().getValue(0L)
        cell.refusedWindows().keys shouldBe setOf(0L)
        refusal.cellRef shouldBe ref
        refusal.windowKey shouldBe 0L
        refusal.exclusiveCount shouldBe 1
        refusal.message!! shouldContain ref.toString()
        refusal.message!! shouldContain "window 0 "
        refusal.message!! shouldContain "[24-WL-17]"
        cell.refusedEvictions shouldBe 1L

        // a sub-floor add into the refused window is still late-dropped
        cell.inlet.call.propagate(SetDelta(adds = mapOf(4L to setOf(tag(5)))))
        late shouldBe listOf(SetDelta(adds = mapOf<Any, Set<Timestamp>>(4L to setOf(tag(5)))))
        out.size shouldBe 2

        // [24-WL-08]: the refused window's tags stay live, so an ordinary del retracts normally
        cell.inlet.call.propagate(SetDelta(dels = mapOf<Any, Set<Timestamp>>(owned to ownedTag)))
        out.last() shouldBe MapDelta(mapOf(0L to 1L), emptySet())

        // the next rise re-evaluates it: no exclusive left, so it is evicted now
        cell.waterline.call.propagate(WaterlineDelta(21))
        out.last() shouldBe MapDelta(emptyMap(), setOf(0L))
        cell.refusedWindows() shouldBe emptyMap()
        cell.refusedEvictions shouldBe 1L
        groupKeys(cell).shouldBeEmpty()

        // the cell never consumed the exclusive: it is still takeable, exactly once
        owned.take() shouldBe 3L
    }

    /** A minimal `Replicable` host with a `TagState` — the shape `[24-WL-18]` refuses at the seam. */
    private class ReplicaHost(override val ref: CellRef = CellRef(UUID.randomUUID())) :
        Cell, Replicable<SetDelta<String>> {
        override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
        override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<SetDelta<String>>>())
        val state = TagState<String>()
    }

    @Test
    fun `B17 - the eviction seam refuses a Replicable host and leaves its state untouched`() {
        // DELETION TRIGGER: remove when the floor is tied to Replication.stableFrontier and [24-WL-18] is lifted.
        val host = ReplicaHost()
        host.state.apply(SetDelta(adds = mapOf("a" to setOf(tag(1)))))

        val ex = shouldThrow<IllegalStateException> { WaterlineEviction.evict(host, host.state) { true } }

        ex.message!! shouldContain "Replicable"
        ex.message!! shouldContain "[24-WL-18]"
        ex.message!! shouldContain "stableFrontier"
        ex.message!! shouldContain host.ref.toString()
        (ex.message!!.contains("E3.7")) shouldBe false
        host.state.asDelta() shouldBe SetDelta(adds = mapOf("a" to setOf(tag(1))))
    }

    @Test
    fun `a post-eviction snapshot restores the same fold and floor, and still late-drops`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = windowed(ref)
        val a = collect(cell.outlet)
        populated(cell)
        cell.waterline.call.propagate(WaterlineDelta(20))

        val restored = windowed(ref)
        restored.restore(roundTrip(cell.snapshot()))

        restored.floor() shouldBe 20L
        lateJoinFold(restored) shouldBe mapFold(a)
        restored.contents() shouldBe cell.contents()
        // a replayed sub-floor add for an evicted window is dropped, not re-admitted
        val restoredOut = collect(restored.outlet)
        restored.inlet.call.propagate(SetDelta(adds = mapOf(1L to setOf(tag(1)))))
        restoredOut.shouldBeEmpty()
        restored.droppedBelowFloor shouldBe 1L
        groupKeys(restored) shouldBe setOf(20L)
    }
}
