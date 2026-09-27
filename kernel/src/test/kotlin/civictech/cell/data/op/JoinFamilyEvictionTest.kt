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
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.IdentityHashMap
import java.util.UUID

/**
 * KE4.5 (computenet-3vd7k.1): [JoinSetCell]'s class-level `waterline` /
 * `lateLeft` / `lateRight` ports, per-side late-drop guard and row-granular
 * eviction through the reconcile fold — spec 24 §Lateness and waterlines
 * `[24-WL-03]`, `[24-WL-06]`, `[24-WL-07]`, `[24-WL-08]`, `[24-WL-09]`,
 * `[24-WL-11]`, `[24-WL-16]`, `[24-WL-17]` (per row), `[24-WL-18]` (the
 * seam's `Replicable` refusal, reused), the structural half of `[24-WL-19]`
 * for rows; `[KE4-25]`, `[KE4-32]` (B14), `[KE4-30]` join form (B5),
 * `[KE4-40]`.
 *
 * Fixture (3vd7k-D10's worked examples): rows `Row(k, t)`, event time `t`,
 * a tumbling window of 10 in the join key (`window(t) to k`), `Lateness(RowTime, 0)`
 * per side unless a case says otherwise, `combine = a to b`. The tombstone-folding
 * consumer is [tagFold] over every outlet delta.
 *
 * Helpers `collect`, `AckProbe`, `underWave`, `roundTrip` are copied from
 * [GroupByEvictionTest] (not shared: that file belongs to KE4.3).
 */
class JoinFamilyEvictionTest {

    data class Row(val k: String, val t: Long) : Serializable

    /** Identity event time over [Row]; a named `Serializable` function value (`[24-WL-01]`). */
    private object RowTime : (Row) -> Long, Serializable {
        override fun invoke(r: Row): Long = r.t
        private fun readResolve(): Any = RowTime
    }

    private fun window(t: Long): Long = Math.floorDiv(t, 10) * 10

    private fun key(r: Row): Pair<Long, String> = window(r.t) to r.k

    private fun tag(n: Long) = Timestamp(UUID(0, n), n)

    private fun adds(vararg rows: Pair<Row, Timestamp>) =
        SetDelta(adds = rows.associate { (r, t) -> r to setOf(t) })

    private fun dels(vararg rows: Pair<Row, Timestamp>) =
        SetDelta(dels = rows.associate { (r, t) -> r to setOf(t) })

    private fun joined(
        ref: CellRef = CellRef(UUID.randomUUID()),
        left: Boolean = true,
        right: Boolean = true,
        gated: Boolean = false,
        keyFn: (Row) -> Any = ::key,
    ) = JoinSetCell<Row, Row, Any, Pair<Row, Row>>(
        ref = ref,
        leftKey = keyFn,
        rightKey = keyFn,
        emitOnFrontier = gated,
        leftLateness = if (left) Windows.Lateness(RowTime, 0) else null,
        rightLateness = if (right) Windows.Lateness(RowTime, 0) else null,
    ) { a, b -> a to b }

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

    /** A late-joining subscriber: linking replays the cell's advertised pairs (G-22 catch-up). */
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

    private fun parts(cell: JoinSetCell<*, *, *, *>): List<*> = cell.snapshot() as List<*>

    /** A `TagState.snapshot()`'s live rows: a bare map, or `[live, tombstones]`. */
    private fun liveOf(part: Any?): Set<Any?> = when (part) {
        is Map<*, *> -> part.keys
        is List<*> -> (part[0] as Map<*, *>).keys
        else -> error("unexpected TagState snapshot $part")
    }

    private fun leftRows(cell: JoinSetCell<*, *, *, *>) = liveOf(parts(cell)[0])
    private fun rightRows(cell: JoinSetCell<*, *, *, *>) = liveOf(parts(cell)[1])

    /** The minted ledger's advertised pairs (`MintedTags.snapshot()` = `[advertised, counter]`). */
    private fun ledger(cell: JoinSetCell<*, *, *, *>): Map<*, *> = (parts(cell)[2] as List<*>)[0] as Map<*, *>

    /**
     * The derived key indexes, read reflectively: they are not in the snapshot
     * (rebuilt on restore) and, since the reconcile fold checks membership
     * before minting, a stale index entry is invisible in emissions — only a
     * direct read shows the row left it.
     */
    private fun indexes(cell: JoinSetCell<*, *, *, *>): Pair<Map<*, *>, Map<*, *>> {
        val field = JoinSetCell::class.java.getDeclaredField("join").apply { isAccessible = true }
        val join = field.get(cell) as KeyedBinarySetJoin<*, *, *>
        return join.leftIndex to join.rightIndex
    }

    // ------------------------------------------------------ [24-WL-11] identity

    @Test
    fun `without lateness the cell is today's operator and its lateness ports sit unlinked`() {
        // JoinSetCellTest's first case, verbatim inputs
        fun key(e: String) = e.first().toString()
        val ref = CellRef(UUID.randomUUID())
        val cell = JoinSetCell<String, String, String, Pair<String, String>>(ref, ::key, ::key) { a, b -> a to b }
        val out = collect(cell.outlet)
        val lateL = collect(cell.lateLeft)
        val lateR = collect(cell.lateRight)

        val t1 = tag(1); val t2 = tag(2)
        cell.left.call.propagate(SetDelta(adds = mapOf("ax" to setOf(t1))))
        out.shouldBeEmpty()
        cell.right.call.propagate(SetDelta(adds = mapOf("a1" to setOf(t2))))
        tagFold(out) shouldBe setOf("ax" to "a1")
        cell.left.call.propagate(SetDelta(dels = mapOf("ax" to setOf(t1))))
        tagFold(out) shouldBe emptySet()
        // exactly the shipped deltas: one minted add, one exit under the same tag
        val m1 = out[0].adds.getValue("ax" to "a1").single()
        out shouldBe listOf(
            SetDelta(adds = mapOf(("ax" to "a1") to setOf(m1))),
            SetDelta(dels = mapOf(("ax" to "a1") to setOf(m1))),
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
        val api: JoinSetApi<String, String, Pair<String, String>> = cell
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

    // ----------------------------------------- B14: pairs exit with their tag

    /** B14's setup: left (a,5) t1, right (a,6) t2 → pair minted m1. Returns m1. */
    private fun b14Setup(cell: JoinSetCell<Row, Row, Any, Pair<Row, Row>>): Timestamp {
        cell.left.call.propagate(adds(Row("a", 5) to tag(1)))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2)))
        return ledger(cell).values.single() as Timestamp
    }

    @Test
    fun `B14 - a floor rise evicts both support rows and the pair exits with its minted tag, in one delta`() {
        val cell = joined()
        val seen = recordWaves(cell.outlet)
        val m1 = b14Setup(cell)
        val pair = Row("a", 5) to Row("a", 6)
        seen.map { it.first } shouldBe listOf(SetDelta(adds = mapOf(pair to setOf(m1))))

        val src = UUID(3, 3)
        underWave(src, 7) { cell.waterline.call.propagate(WaterlineDelta(10)) }

        // [KE4-32]: the tombstone-folding consumer holds nothing. Asserted FIRST —
        // the control mutation (exit tags dropped from the eviction delta) reddens exactly here.
        val consumer = tagFold(seen.map { it.first })
        consumer shouldBe emptySet()
        // [24-WL-05]: state == integrated output — a late joiner's catch-up equals the consumer
        lateJoinFold(cell.outlet) shouldBe consumer

        // [24-WL-06]/[24-WL-16]/[KE4-25]: exactly one delta, the pair's exit under its minted tag, under (S, 7)
        val eviction = seen.drop(1)
        eviction.size shouldBe 1
        eviction.single().first shouldBe SetDelta(dels = mapOf(pair to setOf(m1)))
        eviction.single().second shouldBe Timestamp(src, 7)

        // both rows left their TagState and their key index; the ledger is empty
        leftRows(cell).shouldBeEmpty()
        rightRows(cell).shouldBeEmpty()
        ledger(cell).size shouldBe 0
        indexes(cell).first shouldBe emptyMap<Any, Any>()
        indexes(cell).second shouldBe emptyMap<Any, Any>()
        // a follow-up right add under the evicted key yields no pair (it is also below the floor: late)
        cell.right.call.propagate(adds(Row("a", 7) to tag(3)))
        seen.size shouldBe 2
        cell.droppedBelowFloorRight shouldBe 1L
    }

    // ---------------------------------------- B5: a del for an evicted row

    @Test
    fun `B5 - a del for an evicted row is a no-op that absorb-acks, and no pair exits twice`() {
        val cell = joined()
        val seen = recordWaves(cell.outlet)
        b14Setup(cell)
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
        indexes(cell).first shouldBe emptyMap<Any, Any>()

        // a re-add of the evicted row is late-dropped ([24-WL-07]), so it cannot resurrect the pair
        val fresh = tag(9)
        underWave(src, 2) { cell.left.call.propagate(adds(Row("a", 5) to fresh)) }
        lateProbe.deltas shouldBe listOf(adds(Row("a", 5) to fresh))
        seen.size shouldBe emitted
        outProbe.acks shouldBe listOf(Progress(src, 1), Progress(src, 2))
        cell.droppedBelowFloorLeft shouldBe 1L
        tagFold(seen.map { it.first }) shouldBe emptySet()
    }

    // --------------------------------------------------- row granularity

    @Test
    fun `a floor inside a window evicts only that window's rows below it`() {
        val cell = joined()
        val out = collect(cell.outlet)
        val r12 = Row("a", 12); val r17 = Row("a", 17)
        cell.left.call.propagate(adds(r12 to tag(1), r17 to tag(2)))
        cell.right.call.propagate(adds(r12 to tag(3), r17 to tag(4)))
        tagFold(out) shouldBe setOf(r12 to r12, r12 to r17, r17 to r12, r17 to r17)

        cell.waterline.call.propagate(WaterlineDelta(15))

        // one eviction delta: the three pairs with a t=12 support exit; (17,17) stays
        out.size shouldBe 2 // the right add's four-pair entry, then the eviction
        out.last().adds shouldBe emptyMap()
        out.last().dels.keys shouldBe setOf(r12 to r12, r12 to r17, r17 to r12)
        tagFold(out) shouldBe setOf(r17 to r17)
        leftRows(cell) shouldBe setOf(r17)
        rightRows(cell) shouldBe setOf(r17)
        ledger(cell).keys shouldBe setOf(r17 to r17)
        lateJoinFold(cell.outlet) shouldBe tagFold(out)
    }

    // ---------------------------------------------------- [24-WL-07] per side

    @Test
    fun `a sub-floor add is excluded and forwarded verbatim on its own side's late outlet`() {
        val cell = joined()
        val out = collect(cell.outlet)
        val lateL = probe(cell.lateLeft)
        val lateR = probe(cell.lateRight)
        cell.waterline.call.propagate(WaterlineDelta(10))
        val src = UUID(6, 6)

        val t3 = tag(3)
        underWave(src, 1) { cell.left.call.propagate(adds(Row("a", 3) to t3)) }
        lateL.deltas shouldBe listOf(adds(Row("a", 3) to t3))
        lateL.deltas.single().adds.getValue(Row("a", 3)) shouldBe setOf(t3)
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
        val cell = joined()
        cell.waterline.call.propagate(WaterlineDelta(10))
        cell.right.call.propagate(adds(Row("a", 1) to tag(1), Row("a", 11) to tag(2)))
        cell.lateRight.linking.links.shouldBeEmpty()
        cell.droppedBelowFloorRight shouldBe 1L
        rightRows(cell) shouldBe setOf(Row("a", 11))
    }

    @Test
    fun `a side declaring no lateness is never guarded and never evicted`() {
        val cell = joined(left = true, right = false)
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

    @Test
    fun `a del of a live sub-floor row folds, whatever its event time`() {
        // [24-WL-08]: liveness, not time — a left-only declaration keeps a right row live below the floor
        val cell = joined(left = false, right = true)
        val out = collect(cell.outlet)
        cell.left.call.propagate(adds(Row("a", 3) to tag(1)))
        cell.waterline.call.propagate(WaterlineDelta(2))
        cell.right.call.propagate(adds(Row("a", 4) to tag(2)))
        tagFold(out) shouldBe setOf(Row("a", 3) to Row("a", 4))

        cell.waterline.call.propagate(WaterlineDelta(10)) // right (a,4) evicted; left undeclared, stays
        tagFold(out) shouldBe emptySet()
        leftRows(cell) shouldBe setOf(Row("a", 3))
        cell.left.call.propagate(dels(Row("a", 3) to tag(1)))
        leftRows(cell).shouldBeEmpty()
    }

    // ------------------------------------------------- [24-WL-03] fixpoint

    @Test
    fun `a redelivered or lower waterline after an eviction evicts nothing and acks all three outlets`() {
        val cell = joined()
        val seen = recordWaves(cell.outlet)
        b14Setup(cell)
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
    fun `a raising waterline that evicts no pair acks the outlet and both late outlets`() {
        val cell = joined()
        val outProbe = probe(cell.outlet)
        val lateL = probe(cell.lateLeft)
        val lateR = probe(cell.lateRight)
        cell.left.call.propagate(adds(Row("a", 5) to tag(1))) // no partner: evicting it exits nothing
        val src = UUID(3, 5)
        underWave(src, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }
        leftRows(cell).shouldBeEmpty()
        for (p in listOf(outProbe, lateL, lateR)) {
            p.deltas.shouldBeEmpty()
            p.acks shouldBe listOf(Progress(src, 1))
        }
    }

    // --------------------------------------------- [24-WL-17] per-row refusal

    @Test
    fun `an Owned row below the floor is refused per row while the other passed rows evict`() {
        // left rows are Row or Owned<Row>; an Owned's row comes from a test-side identity map,
        // so the cell never borrows or takes it to read a time or a key
        val owned = Owned(Row("a", 5))
        val rowOf = IdentityHashMap<Any, Row>().apply { put(owned, Row("a", 5)) }
        val asRow: (Any) -> Row = { e -> e as? Row ?: rowOf.getValue(e) }
        val ref = CellRef(UUID.randomUUID())
        val cell = JoinSetCell<Any, Row, Any, Pair<Any, Row>>(
            ref = ref,
            leftKey = { e -> key(asRow(e)) },
            rightKey = ::key,
            leftLateness = Windows.Lateness({ e: Any -> asRow(e).t }, 0),
            rightLateness = Windows.Lateness(RowTime, 0),
        ) { a, b -> a to b }
        val out = collect(cell.outlet)
        val ownedTag = tag(1)
        cell.left.call.propagate(SetDelta(adds = mapOf<Any, Set<Timestamp>>(owned to setOf(ownedTag))))
        cell.right.call.propagate(adds(Row("a", 6) to tag(2)))
        val m1 = out.single().adds.getValue(owned to Row("a", 6)).single()

        cell.waterline.call.propagate(WaterlineDelta(10))

        // the right row is evicted and the pair exits (its right support left) with its tag
        out.drop(1) shouldBe listOf(SetDelta(dels = mapOf<Pair<Any, Row>, Set<Timestamp>>((owned to Row("a", 6)) to setOf(m1))))
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

    private class RowSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Row>>>())
    }

    @Test
    fun `gated - the waterline evicts immediately while buffered data waves stay buffered`() {
        // one join key per window (k ignored) so left rows 'l' and right rows 'r' match
        val cell = joined(gated = true, keyFn = { r: Row -> window(r.t) })
        val seen = recordWaves(cell.outlet)
        val lateL = collect(cell.lateLeft)
        val leftSrc = RowSource()
        val rightSrc = RowSource()
        @Suppress("UNCHECKED_CAST")
        leftSrc.outlet.linkTo(cell.left as LinkFrom<Propagate<SetDelta<Row>>>)
        @Suppress("UNCHECKED_CAST")
        rightSrc.outlet.linkTo(cell.right as LinkFrom<Propagate<SetDelta<Row>>>)
        val s = UUID(8, 8)

        // wave 1 on both arms: the pair (l5, r6) is minted at completeness
        underWave(s, 1) { leftSrc.outlet.call.propagate(adds(Row("l", 5) to tag(1))) }
        cell.bufferedWaves shouldBe 1
        underWave(s, 1) { rightSrc.outlet.call.propagate(adds(Row("r", 6) to tag(2))) }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe setOf(Row("l", 5) to Row("r", 6))
        val m1 = seen.single().first.adds.values.single().single()

        // wave 2 arrives on the left only: held
        underWave(s, 2) { leftSrc.outlet.call.propagate(adds(Row("l", 15) to tag(3))) }
        cell.bufferedWaves shouldBe 1

        // the waterline is not gated: eviction leaves at once, under its own wave
        val w = UUID(9, 9)
        underWave(w, 1) { cell.waterline.call.propagate(WaterlineDelta(10)) }
        seen.size shouldBe 2
        seen[1].first shouldBe SetDelta(dels = mapOf((Row("l", 5) to Row("r", 6)) to setOf(m1)))
        seen[1].second shouldBe Timestamp(w, 1)
        cell.bufferedWaves shouldBe 1

        // wave 3's left add is late-split at arrival: forwarded now, and the buffered remainder is empty
        underWave(s, 3) { leftSrc.outlet.call.propagate(adds(Row("l", 4) to tag(4))) }
        lateL shouldBe listOf(adds(Row("l", 4) to tag(4)))
        cell.bufferedWaves shouldBe 2

        // the right arm completes waves 2 and 3: (l15, r16) enters; l4 is never resurrected
        underWave(s, 2) { rightSrc.outlet.call.propagate(adds(Row("r", 16) to tag(5))) }
        underWave(s, 3) { rightSrc.outlet.absorbAck() }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe setOf(Row("l", 15) to Row("r", 16))
        leftRows(cell) shouldBe setOf(Row("l", 15))
        cell.droppedBelowFloorLeft shouldBe 1L
    }

    @Test
    fun `gated - a row admitted before a floor rise but still buffered is evicted, not landed, at flush`() {
        // [24-WL-16]/[24-WL-19]/[24-WL-10]: the ungated cell under the same arrival order admits
        // l12 at floor 10, evicts it at the rise to 15, and never pairs it with r16 — the gated
        // cell's settled state must be the same, not hold a sub-floor row (and its pair) until
        // some later rise.
        val cell = joined(gated = true, keyFn = { r: Row -> window(r.t) })
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
        underWave(s, 1) { leftSrc.outlet.call.propagate(adds(Row("l", 12) to tag(1))) }
        cell.bufferedWaves shouldBe 1
        lateL.shouldBeEmpty()

        // the floor passes l12 while its wave is still buffered
        underWave(w, 2) { cell.waterline.call.propagate(WaterlineDelta(15)) }

        // the right arm completes the wave: l12 must not land, so no (l12, r16) pair is minted
        underWave(s, 1) { rightSrc.outlet.call.propagate(adds(Row("r", 16) to tag(2))) }
        cell.bufferedWaves shouldBe 0
        tagFold(seen.map { it.first }) shouldBe emptySet()
        ledger(cell).size shouldBe 0
        leftRows(cell) shouldBe emptySet()
        rightRows(cell) shouldBe setOf(Row("r", 16))
        indexes(cell).first shouldBe emptyMap<Any, Any>()
        // admitted at arrival, so evicted rather than late: never forwarded, never counted as dropped
        lateL.shouldBeEmpty()
        cell.droppedBelowFloorLeft shouldBe 0L
    }

    private class AnySource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Any>>>())
    }

    @Test
    fun `gated - an Owned add a floor rise passed while buffered lands live at flush and the next rise refuses it`() {
        // [24-WL-17] / 23 §Taps: the flush-time drop of passed adds must not silently drop an exclusive.
        // The ungated cell under the same arrival order admits the Owned row at floor 10, refuses its
        // eviction at the rise to 15 (it stays live), then pairs it with r16 — the gated cell lands it
        // at flush and mints the same pair; the refusal diagnostic comes with the next rise.
        val owned = Owned(Row("a", 12))
        val rowOf = IdentityHashMap<Any, Row>().apply { put(owned, Row("a", 12)) }
        val asRow: (Any) -> Row = { e -> e as? Row ?: rowOf.getValue(e) }
        val ref = CellRef(UUID.randomUUID())
        val cell = JoinSetCell<Any, Row, Any, Pair<Any, Row>>(
            ref = ref,
            leftKey = { e -> window(asRow(e).t) },
            rightKey = { r -> window(r.t) },
            emitOnFrontier = true,
            leftLateness = Windows.Lateness({ e: Any -> asRow(e).t }, 0),
            rightLateness = Windows.Lateness(RowTime, 0),
        ) { a, b -> a to b }
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

        // flush: the exclusive is kept — it lands live and pairs with r16, never dropped, never late
        underWave(s, 1) { rightSrc.outlet.call.propagate(adds(Row("r", 16) to tag(2))) }
        cell.bufferedWaves shouldBe 0
        leftRows(cell) shouldBe setOf<Any>(owned)
        tagFold(out) shouldBe setOf<Pair<Any, Row>>(owned to Row("r", 16))
        lateL.shouldBeEmpty()
        cell.droppedBelowFloorLeft shouldBe 0L

        // the next rise re-evaluates it like any passed row and refuses it per row (r16 is not passed)
        underWave(w, 3) { cell.waterline.call.propagate(WaterlineDelta(16)) }
        val refusal = cell.refusedRows().single()
        refusal.cellRef shouldBe ref
        refusal.unit shouldBe "row"
        cell.refusedEvictions shouldBe 1L
        leftRows(cell) shouldBe setOf<Any>(owned)
        tagFold(out) shouldBe setOf<Pair<Any, Row>>(owned to Row("r", 16))

        // the cell never consumed the exclusive
        owned.take() shouldBe Row("a", 12)
    }

    // ------------------------------------------ [24-WL-19] structural, rows

    @Test
    fun `advancing the floor window by window keeps the minted ledger bounded by the rows above it`() {
        val cell = joined()
        val out = collect(cell.outlet)
        var n = 0L
        for (w in 0 until 100) {
            val base = w * 10L
            cell.left.call.propagate(SetDelta(adds = (0 until 10).associate { Row("a", base + it) to setOf(tag(++n)) }))
            cell.right.call.propagate(SetDelta(adds = (0 until 10).associate { Row("a", base + it) to setOf(tag(++n)) }))
        }
        ledger(cell).size shouldBe 100 * 100

        for (w in 1..100) {
            val floor = w * 10L
            cell.waterline.call.propagate(WaterlineDelta(floor))
            withClue("floor $floor") {
                @Suppress("UNCHECKED_CAST")
                val pairs = ledger(cell).keys as Set<Pair<Row, Row>>
                val aboveBoth = pairs.count { (a, b) -> a.t >= floor && b.t >= floor }
                pairs.size shouldBeLessThanOrEqual aboveBoth
                pairs.size shouldBe (100 - w) * 100
                leftRows(cell).count { (it as Row).t < floor } shouldBe 0
                rightRows(cell).count { (it as Row).t < floor } shouldBe 0
            }
        }
        ledger(cell).size shouldBe 0
        tagFold(out) shouldBe emptySet()
        indexes(cell).first shouldBe emptyMap<Any, Any>()
        indexes(cell).second shouldBe emptyMap<Any, Any>()
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
    fun `B17 - the grown seam still refuses a Replicable host and leaves its state untouched`() {
        // DELETION TRIGGER: remove when the floor is tied to Replication.stableFrontier and [24-WL-18] is lifted.
        val host = ReplicaHost()
        host.state.apply(SetDelta(adds = mapOf("a" to setOf(tag(1)))))

        val ex = shouldThrow<IllegalStateException> { WaterlineEviction.evict(host, host.state) { true } }

        ex.message!! shouldContain "Replicable"
        ex.message!! shouldContain "[24-WL-18]"
        ex.message!! shouldContain "stableFrontier"
        ex.message!! shouldContain host.ref.toString()
        host.state.asDelta() shouldBe SetDelta(adds = mapOf("a" to setOf(tag(1))))
    }

    @Test
    fun `the seam's window message is unchanged and its row message names the row unit`() {
        val ref = CellRef(UUID.randomUUID())
        ExclusiveEvictionRefused(ref, 0L, 2).message!! shouldContain "passed window 0 refused ([24-WL-17])"
        ExclusiveEvictionRefused(ref, Row("a", 5), 1, unit = "row").message!! shouldContain
            "passed row ${Row("a", 5)} refused ([24-WL-17])"
    }

    // ------------------------------------------------------ 3vd7k-D7 snapshot

    @Test
    fun `a post-eviction snapshot restores floor, contents and indexes, and a legacy snapshot restores with no floor`() {
        val ref = CellRef(UUID.randomUUID())
        val cell = joined(ref)
        val out = collect(cell.outlet)
        b14Setup(cell)
        cell.left.call.propagate(adds(Row("a", 15) to tag(3)))
        cell.waterline.call.propagate(WaterlineDelta(10))
        parts(cell).size shouldBe 4

        val restored = joined(ref)
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
        tagFold(restoredOut) shouldBe setOf(Row("a", 15) to Row("a", 16))

        // legacy: the pre-lateness three-element form
        val legacy = ArrayList((cell.snapshot() as List<Serializable?>).take(3))
        val fromLegacy = joined(ref)
        fromLegacy.restore(roundTrip(legacy))
        fromLegacy.floor() shouldBe null
        leftRows(fromLegacy) shouldBe leftRows(cell)
        parts(fromLegacy).size shouldBe 4
    }
}
