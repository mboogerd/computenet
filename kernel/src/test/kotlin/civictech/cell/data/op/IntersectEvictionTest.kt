package civictech.cell.data.op

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.control.Progress
import civictech.cell.control.absorbAck
import civictech.cell.data.Replicable
import civictech.cell.data.Windows
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TagState
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.data.tagFold
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
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
 * KE4.5 (computenet-3vd7k.3): [IntersectSetCell]'s class-level `waterline` /
 * `lateLeft` / `lateRight` ports, per-side late-drop guard and per-element
 * eviction through the membership fold — spec 24 §Lateness and waterlines
 * `[24-WL-03]`, `[24-WL-06]`, `[24-WL-07]`, `[24-WL-08]`, `[24-WL-09]`,
 * `[24-WL-11]`, `[24-WL-16]`, `[24-WL-17]` (per row); `[24-OP-INTERSECT-01]`'s
 * exit rule and its minted-tag policy (computenet-vvre), unchanged in
 * substance; `[KE4-25]`, `[KE4-32]`, `[KE4-40]`, join-side `[KE4-30]`.
 *
 * Fixture (3vd7k-D10's worked example, "Intersect"): elements are `Long`
 * event times identified with themselves (`LongTime`), `Lateness(LongTime, 0)`
 * per side unless a case says otherwise. The tombstone-folding consumer is
 * [tagFold] over every outlet delta.
 *
 * Helpers `collect`, `AckProbe`, `underWave`, `roundTrip` are copied from
 * [JoinFamilyEvictionTest] / [GroupByEvictionTest] (not shared: those files
 * belong to tasks .1 and KE4.3).
 */
class IntersectEvictionTest {

    /** Identity event time over `Long`; a named `Serializable` function value (`[24-WL-01]`). */
    private object LongTime : (Long) -> Long, Serializable {
        override fun invoke(e: Long): Long = e
        private fun readResolve(): Any = LongTime
    }

    private fun tag(n: Long) = Timestamp(UUID(0, n), n)

    private fun adds(vararg elements: Pair<Long, Timestamp>) =
        SetDelta(adds = elements.associate { (e, t) -> e to setOf(t) })

    private fun dels(vararg elements: Pair<Long, Timestamp>) =
        SetDelta(dels = elements.associate { (e, t) -> e to setOf(t) })

    private fun intersect(
        ref: CellRef = CellRef(UUID.randomUUID()),
        left: Boolean = true,
        right: Boolean = true,
        gated: Boolean = false,
    ) = IntersectSetCell<Long>(
        ref = ref,
        emitOnFrontier = gated,
        leftLateness = if (left) Windows.Lateness(LongTime, 0) else null,
        rightLateness = if (right) Windows.Lateness(LongTime, 0) else null,
    )

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    /** Every delta with the wave timestamp it arrived under. */
    private fun <T : Any> recordWaves(outlet: Subscribe<Propagate<T>>): MutableList<Pair<T, Timestamp?>> {
        val seen = mutableListOf<Pair<T, Timestamp?>>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                seen += value to CurrentContext.get()?.timestamp
            }
        }, PortRef.generate()))
        return seen
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

    @Suppress("UNCHECKED_CAST")
    private fun <D> probe(outlet: FanOutlet<Propagate<D>>): AckProbe<D> =
        AckProbe<D>().also { outlet.linkTo(it.inlet as LinkFrom<Propagate<D>>) }

    /** A late-joining subscriber: linking replays the cell's advertised elements (G-22 catch-up). */
    private class LateJoiner<E> {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<E>>>))
        val arrivals = mutableListOf<SetDelta<E>>()

        init {
            inlet.serve(object : Propagate<SetDelta<E>> {
                override fun propagate(value: SetDelta<E>) {
                    arrivals += value
                }
            })
        }
    }

    private fun <E> lateJoinFold(outlet: FanOutlet<Propagate<SetDelta<E>>>): Set<E> {
        val joiner = LateJoiner<E>()
        @Suppress("UNCHECKED_CAST")
        outlet.linkTo(joiner.inlet as LinkFrom<Propagate<SetDelta<E>>>)
        return tagFold(joiner.arrivals)
    }

    private fun <T> underWave(src: UUID, counter: Long, block: () -> T): T =
        CurrentContext.with(MessageContext(Timestamp(src, counter), PortRef.generate())) { block() }

    @Suppress("UNCHECKED_CAST")
    private fun roundTrip(state: Serializable): Serializable {
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(state) }
        return ObjectInputStream(bytes.toByteArray().inputStream()).use { it.readObject() as Serializable }
    }

    // ---- state readers: snapshot parts 0/1 (live elements), 2 (minted ledger)

    private fun parts(cell: IntersectSetCell<*>): List<*> = cell.snapshot() as List<*>

    /** A `TagState.snapshot()`'s live elements: a bare map, or `[live, tombstones]`. */
    private fun liveOf(part: Any?): Set<Any?> = when (part) {
        is Map<*, *> -> part.keys
        is List<*> -> (part[0] as Map<*, *>).keys
        else -> error("unexpected TagState snapshot $part")
    }

    private fun leftOf(cell: IntersectSetCell<*>) = liveOf(parts(cell)[0])
    private fun rightOf(cell: IntersectSetCell<*>) = liveOf(parts(cell)[1])

    /** The minted ledger's advertised elements (`MintedTags.snapshot()` = `[advertised, counter]`). */
    private fun ledgerOf(cell: IntersectSetCell<*>): Map<*, *> = (parts(cell)[2] as List<*>)[0] as Map<*, *>

    /** [ledgerOf] cast to its real key/value types, for `.getValue`. */
    @Suppress("UNCHECKED_CAST")
    private fun <E> ledgerTags(cell: IntersectSetCell<E>): Map<E, Timestamp> = ledgerOf(cell) as Map<E, Timestamp>

    // ------------------------------------------------------ [24-WL-11] identity

    @Test
    fun `without lateness the cell is today's operator and its lateness ports sit unlinked`() {
        // IntersectDiamondTagTest's shape, verbatim
        val ref = CellRef(UUID.randomUUID())
        val cell = IntersectSetCell<Long>(ref)
        val out = collect(cell.outlet)
        val lateL = collect(cell.lateLeft)
        val lateR = collect(cell.lateRight)

        val t1 = tag(1); val t2 = tag(2)
        cell.left.call.propagate(SetDelta(adds = mapOf(5L to setOf(t1))))
        tagFold(out) shouldBe emptySet()
        cell.right.call.propagate(SetDelta(adds = mapOf(5L to setOf(t2))))
        tagFold(out) shouldBe setOf(5L)
        val m1 = out[0].adds.getValue(5L).single()
        cell.left.call.propagate(SetDelta(dels = mapOf(5L to setOf(t1))))
        tagFold(out) shouldBe emptySet()
        // exactly the shipped deltas: one minted add, one exit under the same tag
        out shouldBe listOf(
            SetDelta(adds = mapOf(5L to setOf(m1))),
            SetDelta(dels = mapOf(5L to setOf(m1))),
        )

        lateL.shouldBeEmpty()
        lateR.shouldBeEmpty()
        cell.floor() shouldBe null
        cell.droppedBelowFloorLeft shouldBe 0L
        cell.droppedBelowFloorRight shouldBe 0L
        cell.waterline.linking.links.shouldBeEmpty()
        cell.lateLeft.linking.links.shouldBeEmpty()
        cell.lateRight.linking.links.shouldBeEmpty()
        // reached through the Api, only left/right/outlet exist
        val api: IntersectSetApi<Long> = cell
        api.left shouldBe cell.left
        api.right shouldBe cell.right
        api.outlet shouldBe cell.outlet
        // the snapshot keeps its three-part pre-lateness shape
        parts(cell).size shouldBe 3

        shouldThrow<IllegalStateException> {
            cell.waterline.call.propagate(WaterlineDelta(10))
        }.message shouldContain ref.toString()
        cell.floor() shouldBe null
    }

    // ----------------------------------- eviction, both sides declaring

    /** Setup: element 5 live on both sides (tags t1 left, t2 right) → entered m1. Returns m1. */
    private fun setup5(cell: IntersectSetCell<Long>): Timestamp {
        cell.left.call.propagate(adds(5L to tag(1)))
        cell.right.call.propagate(adds(5L to tag(2)))
        return ledgerOf(cell).values.single() as Timestamp
    }

    @Test
    fun `both sides declaring - a floor rise evicts both TagStates and the entry exits with its minted tag, in one delta`() {
        val cell = intersect()
        val seen = recordWaves(cell.outlet)
        val m1 = setup5(cell)
        seen.map { it.first } shouldBe listOf(SetDelta(adds = mapOf(5L to setOf(m1))))

        // element 12 stays live on both sides after the rise, and its entry stays
        cell.left.call.propagate(adds(12L to tag(3)))
        cell.right.call.propagate(adds(12L to tag(4)))
        val m2 = ledgerTags(cell).getValue(12L)

        val src = UUID(3, 3)
        underWave(src, 7) { cell.waterline.call.propagate(WaterlineDelta(10)) }

        // [KE4-32]: the tombstone-folding consumer holds only 12. Asserted FIRST —
        // the control mutation (exit tags dropped from the eviction delta) reddens exactly here.
        val consumer = tagFold(seen.map { it.first })
        consumer shouldBe setOf(12L)
        // [24-WL-05]: state == integrated output — a late joiner's catch-up equals the consumer
        lateJoinFold(cell.outlet) shouldBe consumer

        // [24-WL-06]/[24-WL-16]/[KE4-25]: exactly one delta, 5's exit under its minted tag, under (S, 7)
        val eviction = seen.drop(2)
        eviction.size shouldBe 1
        eviction.single().first shouldBe SetDelta(dels = mapOf(5L to setOf(m1)))
        eviction.single().second shouldBe Timestamp(src, 7)

        // 5 left both TagStates; 12's entry, and its tag, are unaffected
        leftOf(cell) shouldBe setOf(12L)
        rightOf(cell) shouldBe setOf(12L)
        ledgerOf(cell).keys shouldBe setOf(12L)
        ledgerTags(cell).getValue(12L) shouldBe m2
    }

    // ---------------------------------------- lateness on the LEFT only

    @Test
    fun `lateness on the left only - the right copy stays but the entry still exits, and re-add is late-dropped`() {
        val cell = intersect(left = true, right = false)
        val out = collect(cell.outlet)
        val m1 = setup5(cell)
        tagFold(out) shouldBe setOf(5L)

        cell.waterline.call.propagate(WaterlineDelta(10))

        // the entry exits (support gone on the left); the right copy of 5 stays live
        tagFold(out) shouldBe emptySet()
        out.last() shouldBe SetDelta(dels = mapOf(5L to setOf(m1)))
        leftOf(cell).shouldBeEmpty()
        rightOf(cell) shouldBe setOf(5L)

        // a later left add of 5 at t=5 is late-dropped, so it never re-enters
        val src = UUID(4, 4)
        val fresh = tag(9)
        underWave(src, 1) { cell.left.call.propagate(adds(5L to fresh)) }
        cell.droppedBelowFloorLeft shouldBe 1L
        leftOf(cell).shouldBeEmpty()
        tagFold(out) shouldBe emptySet()

        // a later left add of 12 with 12 on the right enters with a FRESH tag, distinct from m1
        cell.left.call.propagate(adds(12L to tag(10)))
        cell.right.call.propagate(adds(12L to tag(11)))
        tagFold(out) shouldBe setOf(12L)
        val m2 = out.last().adds.getValue(12L).single()
        (m2 == m1) shouldBe false
    }

    // ------------------------------------------------- B5: a del for an evicted copy

    @Test
    fun `B5 - a del for an evicted copy is a no-op, and no entry exits twice`() {
        val cell = intersect()
        val seen = recordWaves(cell.outlet)
        setup5(cell)
        cell.waterline.call.propagate(WaterlineDelta(10))
        val emitted = seen.size
        val outProbe = probe(cell.outlet)
        val lateProbe = probe(cell.lateLeft)
        val src = UUID(5, 5)

        // [24-WL-08]/[24-WL-09]: the del's tag is not live — nothing folds, nothing exits
        underWave(src, 1) { cell.left.call.propagate(dels(5L to tag(1))) }
        seen.size shouldBe emitted
        outProbe.deltas.shouldBeEmpty()
        outProbe.acks shouldBe listOf(Progress(src, 1))
        lateProbe.acks shouldBe listOf(Progress(src, 1))
        leftOf(cell).shouldBeEmpty()

        // a re-add of the evicted element is late-dropped ([24-WL-07]), so it cannot resurrect the entry
        val fresh = tag(9)
        underWave(src, 2) { cell.left.call.propagate(adds(5L to fresh)) }
        lateProbe.deltas shouldBe listOf(adds(5L to fresh))
        seen.size shouldBe emitted
        outProbe.acks shouldBe listOf(Progress(src, 1), Progress(src, 2))
        cell.droppedBelowFloorLeft shouldBe 1L
        tagFold(seen.map { it.first }) shouldBe emptySet()
    }

    // ---------------------------------------------------- [24-WL-07] per side

    @Test
    fun `a sub-floor add is excluded and forwarded verbatim on its own side's late outlet, and an at-floor add is admitted`() {
        val cell = intersect()
        val out = collect(cell.outlet)
        val lateL = probe(cell.lateLeft)
        val lateR = probe(cell.lateRight)
        cell.waterline.call.propagate(WaterlineDelta(10))
        val src = UUID(6, 6)

        val t3 = tag(3)
        underWave(src, 1) { cell.left.call.propagate(adds(3L to t3)) }
        lateL.deltas shouldBe listOf(adds(3L to t3))
        lateR.deltas.shouldBeEmpty()
        lateR.acks shouldBe listOf(Progress(src, 1))
        cell.droppedBelowFloorLeft shouldBe 1L
        cell.droppedBelowFloorRight shouldBe 0L
        leftOf(cell).shouldBeEmpty()

        // the strict boundary: timeFn == floor is admitted
        underWave(src, 2) { cell.left.call.propagate(adds(10L to tag(4))) }
        leftOf(cell) shouldBe setOf(10L)
        lateL.deltas.size shouldBe 1
        lateL.acks shouldBe listOf(Progress(src, 2))
        out.shouldBeEmpty()
    }

    @Test
    fun `a sub-floor add is excluded and counted even when the late outlet is unlinked`() {
        val cell = intersect()
        cell.waterline.call.propagate(WaterlineDelta(10))
        cell.right.call.propagate(adds(1L to tag(1), 11L to tag(2)))
        cell.lateRight.linking.links.shouldBeEmpty()
        cell.droppedBelowFloorRight shouldBe 1L
        rightOf(cell) shouldBe setOf(11L)
    }

    // ------------------------------------------------- [24-WL-03] fixpoint

    @Test
    fun `a redelivered or lower waterline after an eviction evicts nothing and acks all three outlets`() {
        val cell = intersect()
        val seen = recordWaves(cell.outlet)
        setup5(cell)
        cell.left.call.propagate(adds(15L to tag(3)))
        cell.waterline.call.propagate(WaterlineDelta(10))
        val emitted = seen.size
        val before = parts(cell)

        val outProbe = probe(cell.outlet)
        val lateL = probe(cell.lateLeft)
        val lateR = probe(cell.lateRight)
        val src = UUID(3, 4)
        underWave(src, 2) { cell.waterline.call.propagate(WaterlineDelta(10)) }
        underWave(src, 3) { cell.waterline.call.propagate(WaterlineDelta(5)) }

        cell.floor() shouldBe 10L
        seen.size shouldBe emitted
        parts(cell) shouldBe before
        val expected = listOf(Progress(src, 2), Progress(src, 3))
        for (p in listOf(outProbe, lateL, lateR)) {
            p.deltas.shouldBeEmpty()
            p.acks shouldBe expected
        }
    }

    // --------------------------------------------- [24-WL-17] per-row refusal

    @Test
    fun `an Owned element below the floor is refused per row while other passed elements evict in the same delta`() {
        // [24-WL-17] over E = Any: an Owned(5L) live on the left below the floor. Its "plain
        // twin" (a bare 5L that would match it) CANNOT exist — this is an identity join
        // (RS-5.3), so Owned(5L) and the Long 5L are different elements and never intersect.
        // So this pairs the Owned row with an UNRELATED plain element (5L, added to both sides)
        // to show that row still evicts normally in the same delta while the Owned row is
        // refused, stays live, and its del still folds.
        val owned = Owned(5L)
        val ref = CellRef(UUID.randomUUID())
        val cell = IntersectSetCell<Any>(
            ref = ref,
            leftLateness = Windows.Lateness({ e: Any -> if (e is Owned<*>) 5L else e as Long }, 0),
            rightLateness = Windows.Lateness({ e: Any -> e as Long }, 0),
        )
        val out = collect(cell.outlet)
        val ownedTag = tag(1)
        // the Owned row never intersects (no right-side twin can equal it)
        cell.left.call.propagate(SetDelta(adds = mapOf<Any, Set<Timestamp>>(owned to setOf(ownedTag))))
        out.shouldBeEmpty()
        // a plain element that DOES intersect, on both sides
        cell.left.call.propagate(SetDelta(adds = mapOf<Any, Set<Timestamp>>(5L to setOf(tag(2)))))
        cell.right.call.propagate(SetDelta(adds = mapOf<Any, Set<Timestamp>>(5L to setOf(tag(3)))))
        val m2 = ledgerTags(cell).getValue(5L)

        cell.waterline.call.propagate(WaterlineDelta(10))

        // the plain element 5 evicts and its entry exits; the Owned row is refused, stays live
        out.drop(1) shouldBe listOf(SetDelta(dels = mapOf<Any, Set<Timestamp>>(5L to setOf(m2))))
        leftOf(cell) shouldBe setOf<Any>(owned)
        rightOf(cell).shouldBeEmpty()
        ledgerOf(cell).size shouldBe 0

        val refusal = cell.refusedRows().single()
        refusal.cellRef shouldBe ref
        refusal.exclusiveCount shouldBe 1
        refusal.unit shouldBe "row"
        refusal.message!! shouldContain ref.toString()
        refusal.message!! shouldContain "row"
        refusal.message!! shouldContain "[24-WL-17]"
        cell.refusedEvictions shouldBe 1L

        // every later rise re-evaluates it
        cell.waterline.call.propagate(WaterlineDelta(11))
        cell.refusedRows().size shouldBe 1
        cell.refusedEvictions shouldBe 2L

        // [24-WL-08]: its tag is live, so an ordinary del still folds it
        cell.left.call.propagate(SetDelta(dels = mapOf<Any, Set<Timestamp>>(owned to setOf(ownedTag))))
        leftOf(cell).shouldBeEmpty()
        cell.waterline.call.propagate(WaterlineDelta(12))
        cell.refusedRows().shouldBeEmpty()
        cell.refusedEvictions shouldBe 2L
        tagFold(out) shouldBe emptySet()

        // the cell never consumed the exclusive: it is still takeable, exactly once
        owned.take() shouldBe 5L
    }

    // --------------------------------------------- 3vd7k-D9: waterline ungated

    private class LongSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())
    }

    @Test
    fun `gated - the waterline evicts immediately while buffered data waves stay buffered`() {
        val cell = intersect(gated = true)
        val seen = recordWaves(cell.outlet)
        val lateL = collect(cell.lateLeft)
        val leftSrc = LongSource()
        val rightSrc = LongSource()
        @Suppress("UNCHECKED_CAST")
        leftSrc.outlet.linkTo(cell.left as LinkFrom<Propagate<SetDelta<Long>>>)
        @Suppress("UNCHECKED_CAST")
        rightSrc.outlet.linkTo(cell.right as LinkFrom<Propagate<SetDelta<Long>>>)
        val s = UUID(8, 8)

        // wave 1 on both arms: 5 is minted at completeness
        underWave(s, 1) { leftSrc.outlet.call.propagate(adds(5L to tag(1))) }
        cell.bufferedWaves shouldBe 1
        underWave(s, 1) { rightSrc.outlet.call.propagate(adds(5L to tag(2))) }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe setOf(5L)
        val m1 = seen.single().first.adds.values.single().single()

        // wave 2 arrives on the left only: held
        underWave(s, 2) { leftSrc.outlet.call.propagate(adds(15L to tag(3))) }
        cell.bufferedWaves shouldBe 1

        // the waterline is not gated: eviction leaves at once, under its own wave
        val w = UUID(9, 9)
        underWave(w, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }
        seen.size shouldBe 2
        seen[1].first shouldBe SetDelta(dels = mapOf(5L to setOf(m1)))
        seen[1].second shouldBe Timestamp(w, 1)
        cell.bufferedWaves shouldBe 1

        // wave 3's left add is late-split at arrival: forwarded now, and the buffered remainder is empty
        underWave(s, 3) { leftSrc.outlet.call.propagate(adds(4L to tag(4))) }
        lateL shouldBe listOf(adds(4L to tag(4)))
        cell.bufferedWaves shouldBe 2

        // the right arm completes waves 2 and 3: 15 enters; 4 is never resurrected
        underWave(s, 2) { rightSrc.outlet.call.propagate(adds(15L to tag(5))) }
        underWave(s, 3) { rightSrc.outlet.absorbAck() }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe setOf(15L)
        leftOf(cell) shouldBe setOf(15L)
        cell.droppedBelowFloorLeft shouldBe 1L
    }

    @Test
    fun `gated - a row admitted before a floor rise but still buffered is evicted, not landed, at flush`() {
        // [24-WL-16]/[24-WL-19]/[24-WL-10]: the ungated cell under the same arrival order admits
        // 12 at floor 10, evicts it at the rise to 15, and never pairs it with the right's 16 — the
        // gated cell's settled state must be the same, not hold a sub-floor element until some
        // later rise (the AMENDS'd `dropPassedAdds` flush-time wrapper).
        val cell = intersect(gated = true)
        val seen = recordWaves(cell.outlet)
        val lateL = collect(cell.lateLeft)
        val leftSrc = LongSource()
        val rightSrc = LongSource()
        @Suppress("UNCHECKED_CAST")
        leftSrc.outlet.linkTo(cell.left as LinkFrom<Propagate<SetDelta<Long>>>)
        @Suppress("UNCHECKED_CAST")
        rightSrc.outlet.linkTo(cell.right as LinkFrom<Propagate<SetDelta<Long>>>)
        val s = UUID(8, 8)
        val w = UUID(9, 9)
        underWave(w, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }

        // 12 is at/above floor 10 at arrival: admitted (not late), buffered
        underWave(s, 1) { leftSrc.outlet.call.propagate(adds(12L to tag(1))) }
        cell.bufferedWaves shouldBe 1
        lateL.shouldBeEmpty()

        // the floor passes 12 while its wave is still buffered
        underWave(w, 2) { cell.waterline.call.propagate(WaterlineDelta(15)) }

        // the right arm completes the wave: 12 must not land, so no entry is minted for it
        underWave(s, 1) { rightSrc.outlet.call.propagate(adds(16L to tag(2))) }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe emptySet()
        ledgerOf(cell).size shouldBe 0
        leftOf(cell) shouldBe emptySet()
        rightOf(cell) shouldBe setOf(16L)
        // admitted at arrival, so evicted rather than late: never forwarded, never counted as dropped
        lateL.shouldBeEmpty()
        cell.droppedBelowFloorLeft shouldBe 0L
    }

    // ------------------------------------------ [24-WL-19]/[KE4-32] direct exit-tag regression

    @Test
    fun `a floor rise past the last support removes the element from the tombstone-folding consumer`() {
        // The narrowest form of the `both sides declaring` test's first post-rise assertion,
        // isolated so a mutation dropping the eviction delta's exit tags (see the bead's
        // mutation excerpt) reddens exactly this line, not vacuously.
        val cell = intersect()
        val out = collect(cell.outlet)
        setup5(cell)
        tagFold(out) shouldBe setOf(5L)
        cell.waterline.call.propagate(WaterlineDelta(10))
        tagFold(out) shouldBe emptySet()
    }

    // ------------------------------------------ [24-WL-18] the seam, reused

    /** A minimal `Replicable` host with a `TagState` — the shape `[24-WL-18]` refuses at the seam. */
    private class ReplicaHost(override val ref: CellRef = CellRef(UUID.randomUUID())) :
        Cell, Replicable<SetDelta<String>> {
        override val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())
        override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<SetDelta<String>>>())
        val state = TagState<String>()
    }

    @Test
    fun `the grown seam still refuses a Replicable host and leaves its state untouched`() {
        val host = ReplicaHost()
        host.state.apply(SetDelta(adds = mapOf("a" to setOf(tag(1)))))

        val ex = shouldThrow<IllegalStateException> { WaterlineEviction.evict(host, host.state) { true } }

        ex.message!! shouldContain "Replicable"
        ex.message!! shouldContain "[24-WL-18]"
        ex.message!! shouldContain host.ref.toString()
        host.state.asDelta() shouldBe SetDelta(adds = mapOf("a" to setOf(tag(1))))
    }

    // ------------------------------------------------------ 3vd7k-D7 snapshot

    @Test
    fun `a post-eviction snapshot restores floor and contents, and a legacy snapshot restores with no floor`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = intersect(ref)
        val out = collect(cell.outlet)
        setup5(cell)
        cell.left.call.propagate(adds(15L to tag(3)))
        cell.right.call.propagate(adds(15L to tag(4)))
        cell.waterline.call.propagate(WaterlineDelta(10))
        parts(cell).size shouldBe 4

        val restored = intersect(ref)
        restored.restore(roundTrip(cell.snapshot()))
        restored.floor() shouldBe 10L
        parts(restored) shouldBe parts(cell)
        lateJoinFold(restored.outlet) shouldBe tagFold(out)

        val restoredOut = collect(restored.outlet)
        // a replayed sub-floor add is dropped, not re-admitted
        restored.left.call.propagate(adds(5L to tag(1)))
        restored.droppedBelowFloorLeft shouldBe 1L
        restoredOut.shouldBeEmpty()
        leftOf(restored) shouldBe setOf(15L)

        // legacy: the pre-lateness three-element form
        withClue("legacy 3-part snapshot restores with floor null") {
            val legacy = ArrayList((cell.snapshot() as List<Serializable?>).take(3))
            val fromLegacy = intersect(ref)
            fromLegacy.restore(roundTrip(legacy))
            fromLegacy.floor() shouldBe null
            leftOf(fromLegacy) shouldBe leftOf(cell)
            parts(fromLegacy).size shouldBe 4
        }
    }
}
