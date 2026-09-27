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
import civictech.cell.data.Windows
import civictech.cell.data.delta.SetDelta
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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.IdentityHashMap
import java.util.UUID

/**
 * KE4.5 (computenet-3vd7k.2): [SemiJoinCell]'s class-level `waterline` /
 * `lateLeft` / `lateRight` ports, per-side late-drop guard, row-granular
 * eviction through the reconcile fold, and the antijoin's normative transient
 * admit — spec 24 §Lateness and waterlines `[24-WL-03]`, `[24-WL-06]`,
 * `[24-WL-07]`, `[24-WL-08]`, `[24-WL-09]`, `[24-WL-10]`, `[24-WL-11]`,
 * `[24-WL-16]`, `[24-WL-17]` (per row), `[24-WL-18]` (the seam's `Replicable`
 * refusal, reused from [JoinFamilyEvictionTest]), `[24-WL-19]` (structural,
 * rows); `[KE4-25]`, `[KE4-32]`, join-side `[KE4-30]`, `[KE4-40]`;
 * `[24-OP-SEMIJOIN-01..03]` stay green through this cell unchanged.
 *
 * Fixture (3vd7k-D10's worked examples, this task's key): rows `Row(k, t)`,
 * event time `t`, key = `k` (no windowing component — the antijoin admit
 * scenario turns on event time versus the floor, not on key structure),
 * `Lateness(RowTime, 0)` per side unless a case says otherwise. The
 * tombstone-folding consumer is [tagFold] over every outlet delta, of the
 * cell's own output type (a `Row`, not a pair — semijoin advertises rows).
 *
 * Helpers `collect`, `AckProbe`, `underWave`, `roundTrip` are copied from
 * [JoinFamilyEvictionTest] (not shared: that file belongs to task .1).
 */
class SemiJoinEvictionTest {

    data class Row(val k: String, val t: Long) : Serializable

    /** Identity event time over [Row]; a named `Serializable` function value (`[24-WL-01]`). */
    private object RowTime : (Row) -> Long, Serializable {
        override fun invoke(r: Row): Long = r.t
        private fun readResolve(): Any = RowTime
    }

    private fun key(r: Row): String = r.k

    private fun tag(n: Long) = Timestamp(UUID(0, n), n)

    private fun adds(vararg rows: Pair<Row, Timestamp>) =
        SetDelta(adds = rows.associate { (r, t) -> r to setOf(t) })

    private fun dels(vararg rows: Pair<Row, Timestamp>) =
        SetDelta(dels = rows.associate { (r, t) -> r to setOf(t) })

    private fun semiJoined(
        ref: CellRef = CellRef(UUID.randomUUID()),
        left: Boolean = true,
        right: Boolean = true,
        negated: Boolean = false,
        gated: Boolean = false,
    ): SemiJoinCell<Row, Row, String> = SemiJoinCell(
        ref = ref,
        leftKey = ::key,
        rightKey = ::key,
        negated = negated,
        emitOnFrontier = gated,
        leftLateness = if (left) Windows.Lateness(RowTime, 0) else null,
        rightLateness = if (right) Windows.Lateness(RowTime, 0) else null,
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

    /** A late-joining subscriber: linking replays the cell's advertised rows (G-22 catch-up). */
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

    // ---- state readers: snapshot parts 0/1 (live rows), 2 (minted ledger), and the key indexes

    private fun parts(cell: SemiJoinCell<*, *, *>): List<*> = cell.snapshot() as List<*>

    /** A `TagState.snapshot()`'s live rows: a bare map, or `[live, tombstones]`. */
    private fun liveOf(part: Any?): Set<Any?> = when (part) {
        is Map<*, *> -> part.keys
        is List<*> -> (part[0] as Map<*, *>).keys
        else -> error("unexpected TagState snapshot $part")
    }

    private fun leftRows(cell: SemiJoinCell<*, *, *>) = liveOf(parts(cell)[0])
    private fun rightRows(cell: SemiJoinCell<*, *, *>) = liveOf(parts(cell)[1])

    /** The minted ledger's advertised rows (`MintedTags.snapshot()` = `[advertised, counter]`). */
    private fun ledger(cell: SemiJoinCell<*, *, *>): Map<*, *> = (parts(cell)[2] as List<*>)[0] as Map<*, *>

    /**
     * The derived key indexes, read reflectively: they are not in the snapshot
     * (rebuilt on restore) and, since the reconcile fold checks membership
     * before minting, a stale index entry is invisible in emissions — only a
     * direct read shows the row left it.
     */
    private fun indexes(cell: SemiJoinCell<*, *, *>): Pair<Map<*, *>, Map<*, *>> {
        val field = SemiJoinCell::class.java.getDeclaredField("join").apply { isAccessible = true }
        val join = field.get(cell) as KeyedBinarySetJoin<*, *, *>
        return join.leftIndex to join.rightIndex
    }

    private class RowSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Row>>>())
    }

    private class AnySource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Any>>>())
    }

    // ------------------------------------------------------ [24-WL-11] identity

    @Test
    fun `without lateness the cell is today's operator and its lateness ports sit unlinked`() {
        // SemiJoinCellTest's first case (the difference re-entry scenario), verbatim
        val diff = differenceSet<String>()
        val out = collect(diff.outlet)
        val lateL = collect(diff.lateLeft)
        val lateR = collect(diff.lateRight)

        diff.left.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(1)))))
        tagFold(out) shouldBe setOf("x")
        val firstEntry = out[0].adds.getValue("x").single()

        diff.right.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(2)))))
        tagFold(out) shouldBe emptySet()

        diff.right.call.propagate(SetDelta(dels = mapOf("x" to setOf(tag(2)))))
        tagFold(out) shouldBe setOf("x")
        val secondEntry = out[2].adds.getValue("x").single()
        firstEntry shouldNotBe secondEntry

        lateL.shouldBeEmpty()
        lateR.shouldBeEmpty()
        diff.floor() shouldBe null
        diff.droppedBelowFloorLeft shouldBe 0L
        diff.droppedBelowFloorRight shouldBe 0L
        diff.waterline.linking.links.shouldBeEmpty()
        diff.lateLeft.linking.links.shouldBeEmpty()
        diff.lateRight.linking.links.shouldBeEmpty()
        // reached through the Api, only left/right/outlet exist
        val api: SemiJoinApi<String, String> = diff
        api.left shouldBe diff.left
        api.right shouldBe diff.right
        api.outlet shouldBe diff.outlet
        // the snapshot keeps its three-part pre-lateness shape
        parts(diff).size shouldBe 3

        shouldThrow<IllegalStateException> {
            diff.waterline.call.propagate(WaterlineDelta(10))
        }.message shouldContain diff.ref.toString()
        diff.floor() shouldBe null
    }

    // ------------------------------------- plain semijoin eviction, [24-WL-16]

    @Test
    fun `plain semijoin - a floor rise evicts both support rows and the row exits with its minted tag, in one delta`() {
        val cell = semiJoined()
        val seen = recordWaves(cell.outlet)
        cell.left.call.propagate(adds(Row("a", 5) to tag(1)))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2)))
        val m1 = ledger(cell).values.single() as Timestamp
        seen.map { it.first } shouldBe listOf(SetDelta(adds = mapOf(Row("a", 5) to setOf(m1))))

        val src = UUID(3, 3)
        underWave(src, 7) { cell.waterline.call.propagate(WaterlineDelta(10)) }

        // [KE4-32]: the tombstone-folding consumer holds nothing. Asserted FIRST —
        // the control mutation (exit tags dropped from the eviction delta) reddens exactly here.
        val consumer = tagFold(seen.map { it.first })
        consumer shouldBe emptySet()
        lateJoinFold(cell.outlet) shouldBe consumer

        val eviction = seen.drop(1)
        eviction.size shouldBe 1
        eviction.single().first shouldBe SetDelta(dels = mapOf(Row("a", 5) to setOf(m1)))
        eviction.single().second shouldBe Timestamp(src, 7)

        leftRows(cell).shouldBeEmpty()
        rightRows(cell).shouldBeEmpty()
        ledger(cell).size shouldBe 0
        indexes(cell).first shouldBe emptyMap<Any, Any>()
        indexes(cell).second shouldBe emptyMap<Any, Any>()

        // a follow-up right add under the evicted key is also below the floor: late
        cell.right.call.propagate(adds(Row("a", 7) to tag(3)))
        seen.size shouldBe 2
        cell.droppedBelowFloorRight shouldBe 1L
    }

    @Test
    fun `plain semijoin - lateness on the right only evicts the match, and the surviving left row can re-enter with a fresh tag`() {
        val cell = semiJoined(left = false, right = true)
        val out = collect(cell.outlet)
        cell.left.call.propagate(adds(Row("a", 5) to tag(1)))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2)))
        val m1 = ledger(cell).values.single() as Timestamp
        tagFold(out) shouldBe setOf(Row("a", 5))

        cell.waterline.call.propagate(WaterlineDelta(10))
        out.last() shouldBe SetDelta(dels = mapOf(Row("a", 5) to setOf(m1)))
        tagFold(out) shouldBe emptySet()
        // the left row itself stays live — its side declares no lateness
        leftRows(cell) shouldBe setOf(Row("a", 5))
        rightRows(cell).shouldBeEmpty()

        val fresh = tag(9)
        cell.right.call.propagate(adds(Row("a", 12) to fresh))
        tagFold(out) shouldBe setOf(Row("a", 5))
        val m2 = out.last().adds.getValue(Row("a", 5)).single()
        m2 shouldNotBe m1
    }

    // --------------------------------------- antijoin admits on eviction, [24-WL-06]/[24-WL-16]

    @Test
    fun `antijoin - evicting a left row's last match admits it with a fresh tag in the same delta`() {
        // acknowledged design cost (3vd7k-D6): l15's window is still open ([10,20)),
        // but its only match ages out at floor 10, so it transiently reads as "no match"
        val cell = semiJoined(left = false, right = true, negated = true)
        val seen = recordWaves(cell.outlet)
        cell.left.call.propagate(adds(Row("a", 15) to tag(1))) // no right match yet: unmatched, so it is already advertised
        tagFold(seen.map { it.first }) shouldBe setOf(Row("a", 15))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2))) // now matched: excluded
        tagFold(seen.map { it.first }) shouldBe emptySet()

        val src = UUID(4, 4)
        underWave(src, 3) { cell.waterline.call.propagate(WaterlineDelta(10)) } // evicts right (a,6)

        val eviction = seen.last()
        eviction.first.dels shouldBe emptyMap()
        eviction.first.adds.keys shouldBe setOf(Row("a", 15))
        eviction.second shouldBe Timestamp(src, 3)
        tagFold(seen.map { it.first }) shouldBe setOf(Row("a", 15))

        // control: the same input without the WaterlineDelta holds nothing (matched, excluded)
        val control = semiJoined(left = false, right = true, negated = true)
        val controlOut = collect(control.outlet)
        control.left.call.propagate(adds(Row("a", 15) to tag(1)))
        control.right.call.propagate(adds(Row("a", 6) to tag(2)))
        tagFold(controlOut) shouldBe emptySet()
    }

    // ---------------------------------------- B5: dels of already-evicted rows

    @Test
    fun `B5 - dels of evicted rows are no-ops and no row exits twice`() {
        val cell = semiJoined()
        val seen = recordWaves(cell.outlet)
        cell.left.call.propagate(adds(Row("a", 5) to tag(1)))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2)))
        cell.waterline.call.propagate(WaterlineDelta(10))
        val emitted = seen.size
        val outProbe = probe(cell.outlet)
        val lateProbe = probe(cell.lateLeft)
        val src = UUID(5, 5)

        // [24-WL-08]/[24-WL-09]: the del's tag is not live — nothing folds, nothing exits
        underWave(src, 1) { cell.left.call.propagate(dels(Row("a", 5) to tag(1))) }
        seen.size shouldBe emitted
        outProbe.deltas.shouldBeEmpty()
        outProbe.acks shouldBe listOf(Progress(src, 1))
        lateProbe.acks shouldBe listOf(Progress(src, 1))
        leftRows(cell).shouldBeEmpty()

        underWave(src, 2) { cell.right.call.propagate(dels(Row("a", 6) to tag(2))) }
        seen.size shouldBe emitted
        outProbe.acks shouldBe listOf(Progress(src, 1), Progress(src, 2))

        // a re-add of the evicted row is late-dropped ([24-WL-07]), so it cannot resurrect it
        val fresh = tag(9)
        underWave(src, 3) { cell.left.call.propagate(adds(Row("a", 5) to fresh)) }
        seen.size shouldBe emitted
        cell.droppedBelowFloorLeft shouldBe 1L
        tagFold(seen.map { it.first }) shouldBe emptySet()
    }

    // ---------------------------------------------------- [24-WL-07] per side

    @Test
    fun `a sub-floor add is excluded and forwarded verbatim on its own side's late outlet`() {
        val cell = semiJoined()
        val out = collect(cell.outlet)
        val lateL = probe(cell.lateLeft)
        val lateR = probe(cell.lateRight)
        cell.waterline.call.propagate(WaterlineDelta(10))
        val src = UUID(6, 6)

        val t3 = tag(3)
        underWave(src, 1) { cell.left.call.propagate(adds(Row("a", 3) to t3)) }
        lateL.deltas shouldBe listOf(adds(Row("a", 3) to t3))
        lateR.deltas.shouldBeEmpty()
        lateR.acks shouldBe listOf(Progress(src, 1))
        cell.droppedBelowFloorLeft shouldBe 1L
        cell.droppedBelowFloorRight shouldBe 0L
        leftRows(cell).shouldBeEmpty()

        // the strict boundary: timeFn == floor is admitted
        underWave(src, 2) { cell.left.call.propagate(adds(Row("a", 10) to tag(4))) }
        leftRows(cell) shouldBe setOf(Row("a", 10))
        lateL.deltas.size shouldBe 1
        lateL.acks shouldBe listOf(Progress(src, 2))
        out.shouldBeEmpty()
    }

    @Test
    fun `a sub-floor add is excluded and counted even when the late outlet is unlinked`() {
        val cell = semiJoined()
        cell.waterline.call.propagate(WaterlineDelta(10))
        cell.right.call.propagate(adds(Row("a", 1) to tag(1), Row("a", 11) to tag(2)))
        cell.lateRight.linking.links.shouldBeEmpty()
        cell.droppedBelowFloorRight shouldBe 1L
        rightRows(cell) shouldBe setOf(Row("a", 11))
    }

    @Test
    fun `a side declaring no lateness is never guarded and never evicted`() {
        val cell = semiJoined(left = true, right = false)
        val out = collect(cell.outlet)
        val lateR = probe(cell.lateRight)
        cell.waterline.call.propagate(WaterlineDelta(10))
        val src = UUID(6, 7)

        underWave(src, 1) { cell.right.call.propagate(adds(Row("b", 3) to tag(1))) }
        rightRows(cell) shouldBe setOf(Row("b", 3))
        cell.droppedBelowFloorRight shouldBe 0L
        lateR.deltas.shouldBeEmpty()
        lateR.acks shouldBe listOf(Progress(src, 1)) // still acked so a linked consumer's frontier advances

        cell.left.call.propagate(adds(Row("b", 4) to tag(2)))
        cell.droppedBelowFloorLeft shouldBe 1L
        leftRows(cell).shouldBeEmpty()

        cell.waterline.call.propagate(WaterlineDelta(20))
        rightRows(cell) shouldBe setOf(Row("b", 3))
        out.shouldBeEmpty()
    }

    // ------------------------------------------------- [24-WL-03] fixpoint

    @Test
    fun `a redelivered or lower waterline after an eviction evicts nothing and acks all three outlets`() {
        val cell = semiJoined()
        val seen = recordWaves(cell.outlet)
        cell.left.call.propagate(adds(Row("a", 5) to tag(1)))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2)))
        cell.left.call.propagate(adds(Row("a", 15) to tag(3)))
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

    @Test
    fun `a raising waterline that evicts no row acks the outlet and both late outlets`() {
        val cell = semiJoined()
        val outProbe = probe(cell.outlet)
        val lateL = probe(cell.lateLeft)
        val lateR = probe(cell.lateRight)
        cell.right.call.propagate(adds(Row("a", 5) to tag(1))) // no left match: evicting it exits nothing
        val src = UUID(3, 5)
        underWave(src, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }
        rightRows(cell).shouldBeEmpty()
        for (p in listOf(outProbe, lateL, lateR)) {
            p.deltas.shouldBeEmpty()
            p.acks shouldBe listOf(Progress(src, 1))
        }
    }

    // --------------------------------------------- [24-WL-17] per-row refusal

    @Test
    fun `an Owned row below the floor is refused per row while its right match still evicts`() {
        // the left row is Row or Owned<Row>; an Owned's row comes from a test-side identity
        // map, so the cell never borrows or takes it to read a time or a key
        val owned = Owned(Row("a", 5))
        val rowOf = IdentityHashMap<Any, Row>().apply { put(owned, Row("a", 5)) }
        val asRow: (Any) -> Row = { e -> e as? Row ?: rowOf.getValue(e) }
        val ref = CellRef(UUID.randomUUID())
        val cell = SemiJoinCell<Any, Row, String>(
            ref = ref,
            leftKey = { e -> key(asRow(e)) },
            rightKey = ::key,
            leftLateness = Windows.Lateness({ e: Any -> asRow(e).t }, 0),
            rightLateness = Windows.Lateness(RowTime, 0),
        )
        val out = collect(cell.outlet)
        val ownedTag = tag(1)
        cell.left.call.propagate(SetDelta(adds = mapOf<Any, Set<Timestamp>>(owned to setOf(ownedTag))))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2)))
        val m1 = out.single().adds.getValue(owned).single()

        cell.waterline.call.propagate(WaterlineDelta(10))

        // the right row is evicted and the owned row's entry exits (its support left) with its tag
        out.drop(1) shouldBe listOf(SetDelta(dels = mapOf<Any, Set<Timestamp>>(owned to setOf(m1))))
        rightRows(cell).shouldBeEmpty()
        // the Owned row stays live, untouched
        leftRows(cell) shouldBe setOf<Any>(owned)
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
        leftRows(cell).shouldBeEmpty()
        cell.waterline.call.propagate(WaterlineDelta(12))
        cell.refusedRows().shouldBeEmpty()
        cell.refusedEvictions shouldBe 2L
        tagFold(out) shouldBe emptySet()

        // the cell never consumed the exclusive: it is still takeable, exactly once
        owned.take() shouldBe Row("a", 5)
    }

    // --------------------------------------------- 3vd7k-D9: waterline ungated

    @Test
    fun `gated - the waterline evicts immediately while buffered data waves stay buffered`() {
        val cell = semiJoined(gated = true)
        val seen = recordWaves(cell.outlet)
        val lateL = collect(cell.lateLeft)
        val leftSrc = RowSource()
        val rightSrc = RowSource()
        @Suppress("UNCHECKED_CAST")
        leftSrc.outlet.linkTo(cell.left as LinkFrom<Propagate<SetDelta<Row>>>)
        @Suppress("UNCHECKED_CAST")
        rightSrc.outlet.linkTo(cell.right as LinkFrom<Propagate<SetDelta<Row>>>)
        val s = UUID(8, 8)

        // wave 1 on both arms: (a,5) is minted at completeness (matched, kept)
        underWave(s, 1) { leftSrc.outlet.call.propagate(adds(Row("a", 5) to tag(1))) }
        cell.bufferedWaves shouldBe 1
        underWave(s, 1) { rightSrc.outlet.call.propagate(adds(Row("a", 6) to tag(2))) }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe setOf(Row("a", 5))
        val m1 = seen.single().first.adds.values.single().single()

        // wave 2 arrives on the left only: held
        underWave(s, 2) { leftSrc.outlet.call.propagate(adds(Row("b", 15) to tag(3))) }
        cell.bufferedWaves shouldBe 1

        // the waterline is not gated: eviction leaves at once, under its own wave
        val w = UUID(9, 9)
        underWave(w, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }
        seen.size shouldBe 2
        seen[1].first shouldBe SetDelta(dels = mapOf(Row("a", 5) to setOf(m1)))
        seen[1].second shouldBe Timestamp(w, 1)
        cell.bufferedWaves shouldBe 1

        // wave 3's left add is late-split at arrival: forwarded now, and the buffered remainder is empty
        underWave(s, 3) { leftSrc.outlet.call.propagate(adds(Row("a", 4) to tag(4))) }
        lateL shouldBe listOf(adds(Row("a", 4) to tag(4)))
        cell.bufferedWaves shouldBe 2

        // the right arm completes waves 2 and 3: (b,15) matches (b,16); a4 is never resurrected
        underWave(s, 2) { rightSrc.outlet.call.propagate(adds(Row("b", 16) to tag(5))) }
        underWave(s, 3) { rightSrc.outlet.absorbAck() }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe setOf(Row("b", 15))
        leftRows(cell) shouldBe setOf(Row("b", 15))
        cell.droppedBelowFloorLeft shouldBe 1L
    }

    @Test
    fun `gated - a row admitted before a floor rise but still buffered is evicted, not landed, at flush`() {
        // [24-WL-16]/[24-WL-19]/[24-WL-10]: the ungated cell under the same arrival order admits
        // l12 at floor 10, evicts it at the rise to 15, and never pairs it with a16 — the gated
        // cell's settled state must be the same, not hold a sub-floor row until some later rise.
        val cell = semiJoined(gated = true)
        val seen = recordWaves(cell.outlet)
        val lateL = collect(cell.lateLeft)
        val leftSrc = RowSource()
        val rightSrc = RowSource()
        @Suppress("UNCHECKED_CAST")
        leftSrc.outlet.linkTo(cell.left as LinkFrom<Propagate<SetDelta<Row>>>)
        @Suppress("UNCHECKED_CAST")
        rightSrc.outlet.linkTo(cell.right as LinkFrom<Propagate<SetDelta<Row>>>)
        val s = UUID(8, 8)
        val w = UUID(9, 9)
        underWave(w, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }

        // l12 is at/above floor 10 at arrival: admitted (not late), buffered
        underWave(s, 1) { leftSrc.outlet.call.propagate(adds(Row("a", 12) to tag(1))) }
        cell.bufferedWaves shouldBe 1
        lateL.shouldBeEmpty()

        // the floor passes l12 while its wave is still buffered
        underWave(w, 2) { cell.waterline.call.propagate(WaterlineDelta(15)) }

        // the right arm completes the wave: l12 must not land, so no entry is minted for it
        underWave(s, 1) { rightSrc.outlet.call.propagate(adds(Row("a", 16) to tag(2))) }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe emptySet()
        ledger(cell).size shouldBe 0
        leftRows(cell) shouldBe emptySet()
        rightRows(cell) shouldBe setOf(Row("a", 16))
        indexes(cell).first shouldBe emptyMap<Any, Any>()
        // admitted at arrival, so evicted rather than late: never forwarded, never counted as dropped
        lateL.shouldBeEmpty()
        cell.droppedBelowFloorLeft shouldBe 0L
    }

    @Test
    fun `gated - an Owned add a floor rise passed while buffered lands live and is refused at that same flush`() {
        // computenet-7y4sm: the flush-time drop of passed adds (WaterlineEviction.dropPassedAdds)
        // must not silently drop an exclusive (23 §Taps) AND must not silently omit the [24-WL-17]
        // diagnostic either. The ungated cell under the same arrival order admits the Owned row at
        // floor 10, refuses its eviction at the rise to 15 (stays live, refused right then), then
        // advertises it — the gated cell must agree at quiescence with no further rise (`[24-WL-10]`):
        // it lands the row at flush and records the same refusal immediately.
        val owned = Owned(Row("a", 12))
        val rowOf = IdentityHashMap<Any, Row>().apply { put(owned, Row("a", 12)) }
        val asRow: (Any) -> Row = { e -> e as? Row ?: rowOf.getValue(e) }
        val ref = CellRef(UUID.randomUUID())
        val cell = SemiJoinCell<Any, Row, String>(
            ref = ref,
            leftKey = { e -> key(asRow(e)) },
            rightKey = ::key,
            emitOnFrontier = true,
            leftLateness = Windows.Lateness({ e: Any -> asRow(e).t }, 0),
            rightLateness = Windows.Lateness(RowTime, 0),
        )
        val out = collect(cell.outlet)
        val lateL = collect(cell.lateLeft)
        val leftSrc = AnySource()
        val rightSrc = RowSource()
        @Suppress("UNCHECKED_CAST")
        leftSrc.outlet.linkTo(cell.left as LinkFrom<Propagate<SetDelta<Any>>>)
        @Suppress("UNCHECKED_CAST")
        rightSrc.outlet.linkTo(cell.right as LinkFrom<Propagate<SetDelta<Row>>>)
        val s = UUID(8, 8)
        val w = UUID(9, 9)
        underWave(w, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }

        // the Owned row (t=12) is at/above floor 10 at arrival: admitted, buffered
        val ownedTag = tag(1)
        underWave(s, 1) { leftSrc.outlet.call.propagate(SetDelta(adds = mapOf<Any, Set<Timestamp>>(owned to setOf(ownedTag)))) }
        cell.bufferedWaves shouldBe 1

        // the floor passes it while buffered; it is not in state yet, so this rise refuses nothing
        underWave(w, 2) { cell.waterline.call.propagate(WaterlineDelta(15)) }
        cell.refusedRows().shouldBeEmpty()

        // flush: the exclusive is kept — it lands live and advertises, never dropped, never late —
        // and the flush records the refusal itself, right here, with no further rise needed
        underWave(s, 1) { rightSrc.outlet.call.propagate(adds(Row("a", 16) to tag(2))) }
        cell.bufferedWaves shouldBe 0
        leftRows(cell) shouldBe setOf<Any>(owned)
        tagFold(out) shouldBe setOf<Any>(owned)
        lateL.shouldBeEmpty()
        cell.droppedBelowFloorLeft shouldBe 0L
        val flushRefusal = cell.refusedRows().single()
        flushRefusal.cellRef shouldBe ref
        flushRefusal.unit shouldBe "row"
        flushRefusal.message!! shouldContain "[24-WL-17]"
        cell.refusedEvictions shouldBe 1L

        // the next rise re-evaluates it like any passed row and refuses it again per row
        underWave(w, 3) { cell.waterline.call.propagate(WaterlineDelta(16)) }
        val refusal = cell.refusedRows().single()
        refusal.cellRef shouldBe ref
        refusal.unit shouldBe "row"
        cell.refusedEvictions shouldBe 2L
        leftRows(cell) shouldBe setOf<Any>(owned)
        tagFold(out) shouldBe setOf<Any>(owned)

        // the cell never consumed the exclusive
        owned.take() shouldBe Row("a", 12)
    }

    @Test
    fun `gated negated - a buffered left row a floor rise passes settles exactly as the ungated cell would`() {
        // AMENDS (task review of computenet-3vd7k.1): for a negated cell, dropPassedAdds's
        // flush-time eviction must settle identically to what the ungated cell would do
        // under the same arrival order — here, an antijoin add followed immediately by
        // its own eviction, netting to nothing in both cells.
        fun scenario(gated: Boolean): Pair<Set<Row>, Long> {
            val ref = CellRef(UUID.randomUUID())
            val cell = semiJoined(ref = ref, left = true, right = true, negated = true, gated = gated)
            val out = collect(cell.outlet)
            val leftSrc = RowSource()
            val rightSrc = RowSource()
            @Suppress("UNCHECKED_CAST")
            leftSrc.outlet.linkTo(cell.left as LinkFrom<Propagate<SetDelta<Row>>>)
            @Suppress("UNCHECKED_CAST")
            rightSrc.outlet.linkTo(cell.right as LinkFrom<Propagate<SetDelta<Row>>>)
            val s = UUID(8, 8)
            val w = UUID(9, 9)
            underWave(w, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }
            // l12 is unmatched (no right row under key "a" yet): the ungated cell would
            // admit-then-advertise it immediately; the gated cell buffers it
            underWave(s, 1) { leftSrc.outlet.call.propagate(adds(Row("a", 12) to tag(1))) }
            // the rise passes l12 before its wave completes (ungated: after it entered; gated: still buffered)
            underWave(w, 2) { cell.waterline.call.propagate(WaterlineDelta(15)) }
            // completes wave 1 on the right: a plain unrelated right row, no bearing on l12's key match
            underWave(s, 1) { rightSrc.outlet.call.propagate(adds(Row("b", 16) to tag(2))) }
            return tagFold(out) to cell.droppedBelowFloorLeft
        }
        val ungated = scenario(gated = false)
        val gated = scenario(gated = true)
        gated shouldBe ungated
    }

    // ------------------------------------------------------ 3vd7k-D7 snapshot

    @Test
    fun `a post-eviction snapshot restores floor, contents and indexes, and a legacy snapshot restores with no floor`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = semiJoined(ref)
        val out = collect(cell.outlet)
        cell.left.call.propagate(adds(Row("a", 5) to tag(1)))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2)))
        cell.left.call.propagate(adds(Row("a", 15) to tag(3)))
        cell.waterline.call.propagate(WaterlineDelta(10))
        parts(cell).size shouldBe 4

        val restored = semiJoined(ref)
        restored.restore(roundTrip(cell.snapshot()))
        restored.floor() shouldBe 10L
        parts(restored) shouldBe parts(cell)
        indexes(restored) shouldBe indexes(cell)
        lateJoinFold(restored.outlet) shouldBe tagFold(out)

        val restoredOut = collect(restored.outlet)
        // a replayed sub-floor add is dropped, not re-admitted
        restored.left.call.propagate(adds(Row("a", 5) to tag(1)))
        restored.droppedBelowFloorLeft shouldBe 1L
        restoredOut.shouldBeEmpty()
        // an above-floor matching add pairs with the restored row (the index was rebuilt)
        restored.right.call.propagate(adds(Row("a", 16) to tag(4)))
        tagFold(restoredOut) shouldBe setOf(Row("a", 15))

        // legacy: the pre-lateness three-element form
        val legacy = ArrayList((cell.snapshot() as List<Serializable?>).take(3))
        val fromLegacy = semiJoined(ref)
        fromLegacy.restore(roundTrip(legacy))
        fromLegacy.floor() shouldBe null
        leftRows(fromLegacy) shouldBe leftRows(cell)
        parts(fromLegacy).size shouldBe 4
    }
}
