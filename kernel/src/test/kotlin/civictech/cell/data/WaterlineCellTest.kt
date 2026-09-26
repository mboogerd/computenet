package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.TagFrontier
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.Random
import java.util.UUID

/**
 * KE4.2 (computenet-sjqat.2): [WaterlineCell]'s min-over-sources floor,
 * effective-only emission under the triggering wave, absorb-ack, catch-up,
 * [civictech.cell.Stateful] and `retire` — spec 24 `[24-WL-02]` `[24-WL-03]`
 * `[24-WL-04]` `[24-WL-15]`, 22 §Interaction "Waterline floor (24)".
 */
class WaterlineCellTest {

    /** Identity event time; a named `Serializable` function value, as `[24-WL-01]` asks. */
    private object LongTime : (Long) -> Long, Serializable {
        override fun invoke(e: Long): Long = e
        private fun readResolve(): Any = LongTime
    }

    private val lateness = Windows.Lateness<Long>(LongTime, 5)

    private val a = UUID(1, 1)
    private val b = UUID(1, 2)
    private val c = UUID(1, 3)

    /** Every emission, with the timestamp of the context it was delivered under. */
    private class Recorded {
        val seen = mutableListOf<Pair<WaterlineDelta, Timestamp?>>()
        val floors get() = seen.map { it.first.floor }
    }

    private fun record(cell: WaterlineCell<Long>): Recorded {
        val rec = Recorded()
        cell.outlet.subscribe(Use.fixed(object : Propagate<WaterlineDelta> {
            override fun propagate(value: WaterlineDelta) {
                rec.seen += value to CurrentContext.get()?.timestamp
            }
        }, PortRef.generate()))
        return rec
    }

    private fun adds(vararg events: Long): SetDelta<Long> =
        SetDelta(adds = events.associate { it to setOf(Timestamp(UUID.randomUUID(), 1L)) })

    /** Deliver [events] under a wave context stamped `(src, counter)`; returns that timestamp. */
    private fun send(cell: WaterlineCell<Long>, src: UUID, counter: Long, vararg events: Long): Timestamp {
        val ts = Timestamp(src, counter)
        CurrentContext.with(MessageContext(ts, PortRef.generate())) {
            cell.inlet.call.propagate(adds(*events))
        }
        return ts
    }

    // ---------------------------------------------------------------------

    /**
     * The feature DESIGN's worked example, adapted (bead comment on
     * computenet-sjqat.2): the cell has no a-priori source set, so A's first
     * t=10 is a contribution from the only contributing source and raises the
     * identity to 5 (`[24-WL-02]`: the identity holds only "before any source
     * has contributed"). B's t=8 then gives min(10,8)-5 = 3 < 5 — monotone
     * (`[24-WL-03]`), so nothing; A t=20 gives 3 — nothing; B t=30 gives
     * min(20,30)-5 = 15 — one emission, riding B's wave (lxo-D1).
     */
    @Test
    fun `worked example - min over sources, effective-only, riding the triggering wave`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        cell.floor() shouldBe null

        val tsA1 = send(cell, a, 1, 10)
        rec.seen shouldBe listOf(WaterlineDelta(5) to tsA1)
        cell.floor() shouldBe 5L

        send(cell, b, 1, 8)
        rec.seen.size shouldBe 1
        cell.floor() shouldBe 5L
        cell.maxima() shouldBe mapOf(a to 10L, b to 8L)

        send(cell, a, 2, 20)
        rec.seen.size shouldBe 1

        val tsB2 = send(cell, b, 2, 30)
        rec.seen shouldBe listOf(WaterlineDelta(5) to tsA1, WaterlineDelta(15) to tsB2)
        cell.floor() shouldBe 15L
        cell.maxima() shouldBe mapOf(a to 20L, b to 30L)
    }

    /** `[KE4-26]`: nothing contributed, nothing emitted, floor is the identity (null). */
    @Test
    fun `before any contribution the floor is null and nothing is emitted`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        CurrentContext.with(MessageContext(Timestamp(a, 1), PortRef.generate())) {
            cell.inlet.call.propagate(SetDelta()) // a wave with no adds
        }
        cell.floor() shouldBe null
        cell.maxima() shouldBe emptyMap()
        rec.seen shouldBe emptyList()
    }

    /** sjqat-D1: a null context carries no wave position and contributes nothing. */
    @Test
    fun `a delivery under no context contributes nothing`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        send(cell, a, 1, 10)
        CurrentContext.with(null) { cell.inlet.call.propagate(adds(100)) }
        cell.maxima() shouldBe mapOf(a to 10L)
        rec.floors shouldBe listOf(5L)
    }

    /** sjqat-D1: a catch-up baseline carries no wave position and contributes nothing. */
    @Test
    fun `a baseline-marked delivery contributes nothing`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        send(cell, a, 1, 10)
        CurrentContext.with(MessageContext(Timestamp(b, 7), PortRef.generate(), baseline = TagFrontier(emptyMap()))) {
            cell.inlet.call.propagate(adds(100, 8))
        }
        cell.maxima() shouldBe mapOf(a to 10L)
        rec.floors shouldBe listOf(5L)
    }

    /** `[KE4-21]` producer half: an already-covered redelivery is a no-op. */
    @Test
    fun `redelivering covered event times emits nothing`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        send(cell, a, 1, 10)
        send(cell, b, 1, 30)
        rec.floors shouldBe listOf(5L)
        send(cell, a, 1, 10) // redelivery
        send(cell, b, 2, 30, 12) // covered by b's 30
        rec.floors shouldBe listOf(5L)
        cell.maxima() shouldBe mapOf(a to 10L, b to 30L)
    }

    /**
     * Monotonicity beats the min (`[24-WL-03]`): a source that first contributes
     * below the current floor lowers min-over-sources but never the floor. This
     * is the one prefix on which `floor() <= min(maxima) - lateness` does not
     * hold — see [seededRun]'s joiner rule.
     */
    @Test
    fun `a late source joining low never lowers the floor`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        send(cell, a, 1, 40)
        send(cell, c, 1, 2)
        cell.floor() shouldBe 35L
        cell.maxima() shouldBe mapOf(a to 40L, c to 2L)
        rec.floors shouldBe listOf(35L)
    }

    // ---------------------------------------------------------------------
    // B8 ([KE4-05], [KE4-33])

    /**
     * One seeded 3-source interleaving, asserting on every prefix that emitted
     * floors strictly increase, the last equals `floor()`, and `floor()` is
     * `<= min(maxima) - lateness`.
     *
     * Joiner rule: a source's *first* event time is drawn at or above the
     * highest maximum seen so far. Without it the last invariant is false by
     * design — a low late joiner lowers the min but not the (monotone) floor,
     * pinned separately by the test above — and it would fail the min variant
     * too, so the control would prove nothing. Later events are unconstrained
     * relative to other sources: they mostly rise but may fall back.
     */
    private fun seededRun(seed: Long, combine: (Collection<Long>) -> Long) {
        val cell = WaterlineCell(CellRef(UUID.randomUUID()), lateness, combine)
        val rec = record(cell)
        val rnd = Random(seed)
        val sources = listOf(a, b, c)
        val counters = LongArray(3)
        val cursor = LongArray(3)
        val steps = 60 + rnd.nextInt(61)
        for (step in 0 until steps) {
            val i = rnd.nextInt(3)
            val src = sources[i]
            val t = if (counters[i] == 0L) {
                (cell.maxima().values.maxOrNull() ?: 0L) + rnd.nextInt(10)
            } else if (rnd.nextInt(4) == 0) {
                cursor[i] - rnd.nextInt(20) // falls back
            } else {
                cursor[i] + rnd.nextInt(10)
            }
            cursor[i] = maxOf(cursor[i], t)
            counters[i]++
            send(cell, src, counters[i], t)

            val floors = rec.floors
            val where = "seed=$seed step=$step src=$i t=$t floors=$floors maxima=${cell.maxima()}"
            for (k in 1 until floors.size) {
                if (floors[k] <= floors[k - 1]) throw AssertionError("not strictly increasing: $where")
            }
            if (floors.lastOrNull() != cell.floor()) throw AssertionError("last emission != floor(): $where")
            val f = cell.floor() ?: continue
            val bound = cell.maxima().values.min() - lateness.lateness
            if (f > bound) throw AssertionError("floor $f > min(maxima)-lateness $bound: $where")
        }
    }

    @Test
    fun `B8 - seeded 3-source interleavings keep a monotone floor at or below min over sources`() {
        for (seed in 0L until 100L) seededRun(seed) { it.min() }
    }

    /** The `[KE4-33]` control: max-over-sources lets a fast source run the floor past a slow one. */
    @Test
    fun `B8 control - max over sources fails the bound`() {
        val err = shouldThrow<AssertionError> {
            for (seed in 0L until 100L) seededRun(seed) { it.max() }
        }
        println("B8 control first failure: ${err.message}")
    }

    // ---------------------------------------------------------------------
    // Absorb-ack diamond ([KE4-16], CP-A3) — shapes copied from OperatorAbsorbAckTest.

    private class RawLongSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())
        fun send(vararg events: Long) =
            outlet.call.propagate(SetDelta(adds = events.associate { it to setOf(Timestamp(UUID.randomUUID(), 1L)) }))
    }

    /** The always-real sibling arm: a distinct negative marker per wave. */
    private class AlwaysEmitWaterline(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<Long>>>))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<WaterlineDelta>>())
        private var n = 0L
        init {
            inlet.onEach { outlet.call.propagate(WaterlineDelta(-1000L - n++)) }
        }
    }

    private class Observer<T>(clazz: Class<Propagate<T>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))
        val received = mutableListOf<T>()
        init { inlet.onEach { received += it } }
    }

    @Suppress("UNCHECKED_CAST")
    private val waterlineApi = Propagate::class.java as Class<Propagate<WaterlineDelta>>

    @Test
    fun `a wave that does not move the floor is absorb-acked so a downstream glitch-free join settles it`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())

        val source = RawLongSource()
        val opArm = WaterlineCell(lateness = lateness)
        val passArm = AlwaysEmitWaterline()
        val gf = GlitchFreeCell(waterlineApi)
        val observer = Observer(waterlineApi)
        listOf(source, opArm, passArm, gf, observer).forEach { host.managementInlet.call.spawn(it) }

        source.outlet.linkTo(opArm.inlet as LinkFrom<Propagate<SetDelta<Long>>>)
        source.outlet.linkTo(passArm.inlet as LinkFrom<Propagate<SetDelta<Long>>>)
        opArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<WaterlineDelta>>)
        passArm.outlet.linkTo(gf.inlet as LinkFrom<Propagate<WaterlineDelta>>)
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        source.send(10)
        controller.runToIdle()
        observer.received shouldBe listOf(WaterlineDelta(5), WaterlineDelta(-1000))

        // t=7 is below the source's maximum: the floor does not move, the
        // waterline arm absorbs; only its ack lets the join settle the wave.
        source.send(7)
        controller.runToIdle()
        opArm.floor() shouldBe 5L
        observer.received shouldBe listOf(WaterlineDelta(5), WaterlineDelta(-1000), WaterlineDelta(-1001))
    }

    // ---------------------------------------------------------------------
    // Catch-up ([KE4-24])

    private class WaterlineCollector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<WaterlineDelta>>))
        val received = mutableListOf<WaterlineDelta>()
        init {
            inlet.serve(object : Propagate<WaterlineDelta> {
                override fun propagate(value: WaterlineDelta) { received += value }
            })
        }
    }

    private fun link(cell: WaterlineCell<Long>, collector: WaterlineCollector) {
        (cell.outlet.linkTo(collector.inlet as LinkFrom<Propagate<WaterlineDelta>>) is LinkResult.Connected) shouldBe true
    }

    @Test
    fun `a consumer linking after the floor is set receives exactly the floor`() {
        val cell = WaterlineCell(lateness = lateness)
        send(cell, a, 1, 10)
        send(cell, b, 1, 20)
        send(cell, a, 2, 20)
        cell.floor() shouldBe 15L
        val late = WaterlineCollector()
        link(cell, late)
        late.received shouldBe listOf(WaterlineDelta(15))
    }

    @Test
    fun `a consumer linking before any contribution receives nothing`() {
        val cell = WaterlineCell(lateness = lateness)
        val early = WaterlineCollector()
        link(cell, early)
        early.received shouldBe emptyList()
    }

    // ---------------------------------------------------------------------

    @Test
    fun `snapshot and restore preserve floor and maxima`() {
        val cell = WaterlineCell(lateness = lateness)
        send(cell, a, 1, 10)
        send(cell, b, 1, 30)
        send(cell, c, 1, 12)

        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(cell.snapshot()) } }
        val state = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as Serializable }

        val restored = WaterlineCell(lateness = lateness)
        restored.restore(state)
        restored.floor() shouldBe cell.floor()
        restored.maxima() shouldBe cell.maxima()

        val rec = record(restored)
        send(restored, a, 2, 9) // covered
        rec.seen shouldBe emptyList()
    }

    @Test
    fun `restore of a never-contributed cell keeps the identity`() {
        val restored = WaterlineCell(lateness = lateness)
        restored.restore(WaterlineCell(lateness = lateness).snapshot())
        restored.floor() shouldBe null
        restored.maxima() shouldBe emptyMap()
    }

    /** `[24-WL-15]` declaration: retiring the slowest source raises the floor by one emission. */
    @Test
    fun `retiring the slowest source raises the floor by exactly one emission`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        send(cell, a, 1, 30)
        send(cell, b, 1, 40)
        send(cell, c, 1, 50)
        send(cell, a, 2, 31)
        rec.floors shouldBe listOf(25L, 26L)

        cell.retire(a)
        rec.floors shouldBe listOf(25L, 26L, 35L)
        cell.floor() shouldBe 35L
        cell.maxima() shouldBe mapOf(b to 40L, c to 50L)

        cell.retire(UUID(9, 9)) // unknown: no-op
        rec.floors.size shouldBe 3
    }
}
