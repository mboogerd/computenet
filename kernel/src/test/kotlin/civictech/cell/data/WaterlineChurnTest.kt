package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.ReBaselineEmitting
import civictech.cell.Stateful
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.SupervisionPolicy
import civictech.cell.link.Link
import civictech.cell.link.LinkResult
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.SimWorld
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

// -------------------------------------------------------------------------
// Shared helpers — top-level `private` so computenet-6gkou.2 (B12, the seeded
// churn run) can reuse them in this file (6gkou.1-D6).

/** Identity event time; a named `Serializable` function value, as `[24-WL-01]` asks. */
private object LongTime : (Long) -> Long, Serializable {
    override fun invoke(e: Long): Long = e
    private fun readResolve(): Any = LongTime
}

private val lateness = Windows.Lateness<Long>(LongTime, 5)

private fun adds(vararg events: Long): SetDelta<Long> =
    SetDelta(adds = events.associate { it to setOf(Timestamp(UUID.randomUUID(), 1L)) })

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

/** A raw event-time source: its outlet mints its own `sourceId` and stamps itself as `sourcePort`. */
private class ChurnSource(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())
    fun send(vararg events: Long) = outlet.call.propagate(adds(*events))
    val sourceId: UUID get() = outlet.waveState().sourceId
}

/** A real in-process [Link] from [source] to [cell]'s inlet — its `unlink()` fires `EdgeClose`. */
@Suppress("UNCHECKED_CAST")
private fun link(source: ChurnSource, cell: WaterlineCell<Long>): Link =
    (source.outlet.linkTo(cell.inlet as LinkFrom<Propagate<SetDelta<Long>>>) as LinkResult.Connected).link

private fun unlink(link: Link) = link.unlink()

/** Deliver [events] from [source] (a fresh wave under its own `sourceId`); returns that `sourceId`. */
private fun send(source: ChurnSource, vararg events: Long): UUID {
    source.send(*events)
    return source.sourceId
}

private class ChurnObserver<T>(clazz: Class<Propagate<T>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet(clazz))
    val received = mutableListOf<T>()
    init { inlet.onEach { received += it } }
}

@Suppress("UNCHECKED_CAST")
private val waterlineApi = Propagate::class.java as Class<Propagate<WaterlineDelta>>

/** A pass-through relay: forwards under the arriving wave, so the `sourceId` survives and `sourcePort` becomes this relay's outlet. */
private class ChurnRelay(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<Long>>>))
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())

    init {
        inlet.serve(object : Propagate<SetDelta<Long>> {
            override fun propagate(value: SetDelta<Long>) = outlet.call.propagate(value)
        })
    }
}

/** Proxy shape for poisoning a hosted [ChurnProducer] (RestartReBaselineTest's fixture). */
interface ChurnProducerProxy {
    val inlet: Use<Consumer<Long>>
}

/**
 * A RESTART-able event-time producer (RestartReBaselineTest's `TaggedProducerCell`,
 * emitting `SetDelta<Long>`): a negative input is poison. Its re-baseline
 * re-emits the restored state — empty after a RESTART to the spawn checkpoint.
 */
private class ChurnProducer(override val ref: CellRef = CellRef(UUID.randomUUID())) :
    Cell, Stateful, ReBaselineEmitting {
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())

    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Long>>))

    private val adds = mutableMapOf<Long, MutableSet<Timestamp>>()
    private var counter = 0L

    init {
        inlet.serve(object : Consumer<Long> {
            override fun provide(input: Long) {
                if (input < 0) throw IllegalStateException("poison: $input")
                outlet.originate {
                    val tag = Timestamp(outlet.waveState().sourceId, ++counter)
                    adds.getOrPut(input) { mutableSetOf() } += tag
                    propagate(SetDelta(adds = mapOf(input to setOf(tag))))
                }
            }
        })
    }

    override fun snapshot(): Serializable = HashMap(adds.mapValues { HashSet(it.value) })

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        adds.clear()
        (state as Map<Long, Set<Timestamp>>).forEach { (e, tags) -> adds[e] = tags.toMutableSet() }
    }

    override fun reBaseline(supersedes: Set<UUID>, supersede: Boolean) {
        val delta = SetDelta(adds = adds.mapValues { it.value.toSet() })
        outlet.reBaseline(supersedes, supersede) { propagate(delta) }
    }
}

// -------------------------------------------------------------------------
// computenet-6gkou.2 (B12, the seeded churn run): a joiner-aware oracle,
// independent of both the cell and any control model (D3).
//
// The churn kinds a slot can go through.
private enum class Kind { CONTRIBUTE, IDLE, UNLINK, RELINK, RESTART_EMPTY, RESTART_ADD, RETIRE }

/** One drawn step of the churn script: which slot, which kind, and (for CONTRIBUTE / RESTART_ADD) which value. */
private class ChurnStep(val kind: Kind, val slot: Int, val t: Long? = null)

/**
 * Generates the churn script for a seed and, independently of whichever
 * subject later executes it, tracks the reference oracle: per-slot cursor,
 * the contributing and protected sets, and the running-max floor (D3). A
 * fresh instance reconstructed with the same `(numSlots, seed)` reproduces
 * the identical script — [step] draws only from its own `Random(seed)` and
 * its own prior state, never from an executor — so the same script can drive
 * the real cell and, separately, a control subject (D4).
 *
 * Per step: [Kind.CONTRIBUTE] and [Kind.RESTART_ADD] evaluate once after
 * setting the slot's cursor; [Kind.UNLINK] and [Kind.RETIRE] evaluate once
 * after removing the slot (the `retireAll` route — a standalone detached
 * evaluation); [Kind.RESTART_EMPTY] evaluates once after removing the slot
 * with no addition (still through `onDelta`, one wave, nothing to add).
 * Every retirement route ([Kind.UNLINK], [Kind.RESTART_EMPTY], [Kind.RETIRE],
 * and [Kind.RESTART_ADD]'s retire half) resets the slot's cursor to `null`,
 * mirroring `forget()`: it drops the maximum entirely, not just contributing
 * membership, so a later re-admission's or fresh epoch's first contribution
 * is unconstrained like a brand-new source's — never a continuation of the
 * retired epoch's cursor, and never held to it even if the new value is lower.
 */
private class ChurnPlanner(private val numSlots: Int, seed: Long) {
    private val rnd = Random(seed)
    private val cursor = arrayOfNulls<Long>(numSlots)
    private val linked = BooleanArray(numSlots) { true }
    private val contributing = BooleanArray(numSlots) { false }
    private var protectedSlots = emptySet<Int>()

    /** `60 + rnd.nextInt(61)` steps, drawn first so it does not perturb the rest of the script. */
    val steps = 60 + rnd.nextInt(61)

    var floor: Long? = null
        private set

    private fun highest(): Long =
        (0 until numSlots).filter { contributing[it] }.maxOfOrNull { cursor[it]!! } ?: 0L

    /** First time (no cursor yet for this slot's current epoch): unconstrained; else fallback 1-in-4, else advance. */
    private fun nextValue(slot: Int): Long {
        val h = highest()
        val c = cursor[slot]
        return if (c == null) {
            if (rnd.nextBoolean()) h + rnd.nextInt(10) else rnd.nextLong(maxOf(h + 1, 1))
        } else if (rnd.nextInt(4) == 0) {
            c - rnd.nextInt(20)
        } else {
            c + rnd.nextInt(10)
        }
    }

    private fun evaluate() {
        val live = (0 until numSlots).filter { contributing[it] }
        if (live.isEmpty()) return
        val candidate = live.minOf { cursor[it]!! } - lateness.lateness
        val prev = floor
        if (prev == null || candidate > prev) {
            floor = candidate
            protectedSlots = live.toSet()
        }
    }

    /** The oracle's protected-set bound (max − lateness of every protected slot), or `null` while none is protected. */
    fun protectedBound(): Long? =
        protectedSlots.takeIf { it.isNotEmpty() }?.minOf { cursor[it]!! - lateness.lateness }

    fun contributingSlots(): Set<Int> = (0 until numSlots).filter { contributing[it] }.toSet()

    fun debugState(): String =
        "cursor=${cursor.toList()} linked=${linked.toList()} contributing=${contributing.toList()} protected=$protectedSlots"

    /** Advance the script by one step: draws a slot and an eligible kind, updates the reference model, returns both. */
    fun step(): ChurnStep {
        val slot = rnd.nextInt(numSlots)
        val kind = if (!linked[slot]) {
            Kind.RELINK
        } else {
            val eligible = mutableListOf(Kind.CONTRIBUTE, Kind.IDLE, Kind.UNLINK, Kind.RESTART_EMPTY, Kind.RESTART_ADD)
            if (contributing[slot]) eligible += Kind.RETIRE
            eligible[rnd.nextInt(eligible.size)]
        }
        var t: Long? = null
        when (kind) {
            Kind.RELINK -> linked[slot] = true
            Kind.CONTRIBUTE -> {
                t = nextValue(slot)
                cursor[slot] = maxOf(cursor[slot] ?: t, t) // the cell's maxima never falls ([24-WL-01])
                contributing[slot] = true
                evaluate()
            }
            Kind.IDLE -> {}
            Kind.UNLINK -> {
                linked[slot] = false
                contributing[slot] = false
                protectedSlots = protectedSlots - slot
                cursor[slot] = null // forget() clears the maximum entirely, not just contributing membership
                evaluate()
            }
            Kind.RESTART_EMPTY -> {
                contributing[slot] = false
                protectedSlots = protectedSlots - slot
                cursor[slot] = null
                evaluate()
            }
            Kind.RESTART_ADD -> {
                contributing[slot] = false
                protectedSlots = protectedSlots - slot
                cursor[slot] = null // the fresh epoch has no cursor yet: nextValue reads this as first-time
                t = nextValue(slot)
                cursor[slot] = t
                contributing[slot] = true
                evaluate()
            }
            Kind.RETIRE -> {
                contributing[slot] = false
                protectedSlots = protectedSlots - slot
                cursor[slot] = null // forget() clears the maximum entirely: re-admission starts from scratch
                evaluate()
            }
        }
        return ChurnStep(kind, slot, t)
    }
}

/** A lightweight subject for the D4 controls: floor by slot index, no real cell, no links. */
private interface ChurnSubject {
    fun contribute(slot: Int, t: Long)

    /** [kind] is UNLINK / RESTART_EMPTY / RESTART_ADD's retire half / RETIRE — see each subject's own handling. */
    fun retire(slot: Int, kind: Kind)
    fun floor(): Long?
    val emitted: List<Long>
}

/** The `[24-WL-03]` control: a floor that follows the min down (unconditional forget, non-monotone assignment). */
private fun fallingFloorControl(): ChurnSubject = object : ChurnSubject {
    val maxima = mutableMapOf<Int, Long>()
    var f: Long? = null
    override val emitted = mutableListOf<Long>()
    override fun contribute(slot: Int, t: Long) {
        maxima[slot] = maxOf(maxima[slot] ?: t, t)
        recompute()
    }

    override fun retire(slot: Int, kind: Kind) {
        maxima.remove(slot)
        recompute()
    }

    private fun recompute() {
        if (maxima.isEmpty()) return
        val cand = maxima.values.min() - lateness.lateness
        if (cand != f) {
            f = cand
            emitted += cand
        }
    }

    override fun floor() = f
}

/** The `[24-WL-12]` control: every retirement route forgets the slot except `EdgeClose`, which is a no-op bug. */
private fun neverRetireOnUnlinkControl(): ChurnSubject = object : ChurnSubject {
    val maxima = mutableMapOf<Int, Long>()
    var f: Long? = null
    override val emitted = mutableListOf<Long>()
    override fun contribute(slot: Int, t: Long) {
        maxima[slot] = maxOf(maxima[slot] ?: t, t)
        raise()
    }

    override fun retire(slot: Int, kind: Kind) {
        if (kind != Kind.UNLINK) maxima.remove(slot) // the bug: an EdgeClose never drops the slot's maximum
        raise()
    }

    private fun raise() {
        if (maxima.isEmpty()) return
        val cand = maxima.values.min() - lateness.lateness
        val prev = f
        if (prev == null || cand > prev) {
            f = cand
            emitted += cand
        }
    }

    override fun floor() = f
}

/** Drives [subject] through the seed's script (D4): the same generator, applied to a subject rather than a real cell. */
private fun runAgainstSubject(seed: Long, numSlots: Int, subject: ChurnSubject) {
    val planner = ChurnPlanner(numSlots, seed)
    for (step in 0 until planner.steps) {
        val st = planner.step()
        when (st.kind) {
            Kind.CONTRIBUTE -> subject.contribute(st.slot, st.t!!)
            Kind.RESTART_ADD -> {
                subject.retire(st.slot, st.kind)
                subject.contribute(st.slot, st.t!!)
            }
            Kind.UNLINK, Kind.RESTART_EMPTY, Kind.RETIRE -> subject.retire(st.slot, st.kind)
            Kind.IDLE, Kind.RELINK -> {}
        }
        val floors = subject.emitted
        val where = "seed=$seed step=$step kind=${st.kind} slot=${st.slot} floors=$floors oracle=${planner.floor}"
        for (k in 1 until floors.size) {
            if (floors[k] <= floors[k - 1]) throw AssertionError("not strictly increasing: $where")
        }
        if (floors.lastOrNull() != subject.floor()) throw AssertionError("last emission != floor(): $where")
        if (subject.floor() != planner.floor) throw AssertionError("floor ${subject.floor()} != oracle ${planner.floor}: $where")
        val bound = planner.protectedBound()
        if (bound != null && planner.floor!! > bound) throw AssertionError("floor above protected bound $bound: $where")
    }
}

/**
 * Drives a real, freshly-linked [WaterlineCell] through the seed's script
 * (D2), checking every prefix against [ChurnPlanner]'s oracle (D3): emitted
 * floors strictly increase, the last equals `floor()`, `floor()` equals the
 * oracle, `floor()` never exceeds the protected bound, and `maxima().keys`
 * equals the oracle's contributing set (mapped from slot to the real
 * `sourceId` currently representing that slot's live epoch, tracked
 * alongside the planner since the oracle itself is id-free).
 */
private fun runAgainstCell(seed: Long, numSlots: Int, kindCounts: MutableMap<Kind, Int>) {
    val planner = ChurnPlanner(numSlots, seed)
    val cell = WaterlineCell(lateness = lateness)
    val rec = record(cell)
    val sources = List(numSlots) { ChurnSource() }
    val links = arrayOfNulls<Link>(numSlots)
    val realId = arrayOfNulls<UUID>(numSlots)
    sources.indices.forEach { links[it] = link(sources[it], cell) }

    for (step in 0 until planner.steps) {
        val st = planner.step()
        kindCounts[st.kind] = (kindCounts[st.kind] ?: 0) + 1
        val src = sources[st.slot]
        when (st.kind) {
            Kind.RELINK -> links[st.slot] = link(src, cell)
            Kind.CONTRIBUTE -> realId[st.slot] = send(src, st.t!!)
            Kind.IDLE -> {}
            Kind.UNLINK -> {
                unlink(links[st.slot]!!)
                links[st.slot] = null
                realId[st.slot] = null
            }
            Kind.RESTART_EMPTY -> {
                val old = src.sourceId
                src.outlet.mintFreshEpoch()
                src.outlet.reBaseline(setOf(old), true) { propagate(adds()) }
                realId[st.slot] = null
            }
            Kind.RESTART_ADD -> {
                val old = src.sourceId
                src.outlet.mintFreshEpoch()
                src.outlet.reBaseline(setOf(old), true) { propagate(adds(st.t!!)) }
                realId[st.slot] = src.sourceId
            }
            Kind.RETIRE -> {
                cell.retire(src.sourceId)
                realId[st.slot] = null
            }
        }

        val floors = rec.floors
        val where = "seed=$seed step=$step kind=${st.kind} slot=${st.slot} floors=$floors oracle=${planner.floor}" +
            " maxima=${cell.maxima()} realId=${realId.toList()} planner=${planner.debugState()}"
        for (k in 1 until floors.size) {
            if (floors[k] <= floors[k - 1]) throw AssertionError("not strictly increasing: $where")
        }
        if (floors.lastOrNull() != cell.floor()) throw AssertionError("last emission != floor(): $where")
        if (cell.floor() != planner.floor) throw AssertionError("floor ${cell.floor()} != oracle ${planner.floor}: $where")
        val bound = planner.protectedBound()
        if (bound != null && planner.floor!! > bound) throw AssertionError("floor above protected bound $bound: $where")
        val expectedIds = planner.contributingSlots().map { realId[it]!! }.toSet()
        if (cell.maxima().keys != expectedIds) {
            throw AssertionError("maxima ${cell.maxima()} keys != oracle contributing $expectedIds: $where")
        }
    }
}

/**
 * KE4.4 task 1 (computenet-6gkou.1): [WaterlineCell] under source churn —
 * retirement on `EdgeClose` (`[24-WL-12]`, B10), on a superseding
 * `ReBaselineNotice` (`[24-WL-13]`, B11), detached retirement emissions, the
 * `[24-WL-20]` re-admission on the `EdgeClose` route, and link bookkeeping
 * across snapshot/restore. Lateness 5 throughout.
 *
 * The cell knows no source set in advance, so B10's sources contribute
 * slowest-first (C, B, A): A's t=30 first would raise the floor to 25 alone,
 * and C's later t=10 would join below it (`[24-WL-20]`).
 */
class WaterlineChurnTest {

    /** B10's setup: C=10, B=20, A=30 ⇒ floor 5, one emission. */
    private class B10 {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val a = ChurnSource()
        val b = ChurnSource()
        val c = ChurnSource()
        val linkA = link(a, cell)
        val linkB = link(b, cell)
        val linkC = link(c, cell)
        val idC = send(c, 10)
        val idB = send(b, 20)
        val idA = send(a, 30)
    }

    @Test
    fun `B10 - EdgeClose retires every source the link carried, one detached emission`() {
        val f = B10()
        f.rec.floors shouldBe listOf(5L)
        f.cell.maxima() shouldBe mapOf(f.idA to 30L, f.idB to 20L, f.idC to 10L)

        unlink(f.linkC)
        f.rec.floors shouldBe listOf(5L, 15L) // min(30, 20) - 5
        f.cell.floor() shouldBe 15L
        f.cell.maxima() shouldBe mapOf(f.idA to 30L, f.idB to 20L)
        // 6gkou.1-D3: a fresh wave minted by the waterline's own outlet, not any source's
        val ts = f.rec.seen.last().second!!
        ts.sourceId shouldBe f.cell.outlet.waveState().sourceId
        (ts.sourceId in setOf(f.idA, f.idB, f.idC)) shouldBe false

        send(f.a, 40) // B gates at 15
        f.rec.floors shouldBe listOf(5L, 15L)
    }

    @Test
    fun `B10 - an EdgeClose of a link whose source was never the minimum emits nothing`() {
        val f = B10()
        unlink(f.linkA) // candidate min(20, 10) - 5 = 5: no rise
        f.rec.floors shouldBe listOf(5L)
        f.cell.maxima() shouldBe mapOf(f.idB to 20L, f.idC to 10L)
    }

    @Test
    fun `B10 - one EdgeClose retires both epochs a link carried, in one emission`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val a = ChurnSource()
        val b = ChurnSource()
        val linkA = link(a, cell)
        link(b, cell)
        val a1 = send(a, 30)
        a.outlet.mintFreshEpoch()
        val a2 = send(a, 40)
        val idB = send(b, 50)
        (a1 == a2) shouldBe false
        cell.maxima() shouldBe mapOf(a1 to 30L, a2 to 40L, idB to 50L)
        rec.floors shouldBe listOf(25L)

        unlink(linkA) // retiring only a1 would give min(40, 50) - 5 = 35
        rec.floors shouldBe listOf(25L, 45L)
        cell.maxima() shouldBe mapOf(idB to 50L)
    }

    /** `[24-WL-20]` on the EdgeClose route: re-admitted below the floor, C leaves it and gates the next rise. */
    @Test
    fun `a source re-linked after EdgeClose joins below the floor and gates the next rise`() {
        val f = B10()
        unlink(f.linkC)
        f.rec.floors shouldBe listOf(5L, 15L)

        link(f.c, f.cell)
        send(f.c, 8) // candidate min(30, 20, 8) - 5 = 3 < 15
        f.cell.floor() shouldBe 15L
        f.rec.floors shouldBe listOf(5L, 15L)
        f.cell.maxima().keys shouldBe setOf(f.idA, f.idB, f.idC)

        send(f.a, 100)
        send(f.b, 100) // C gates: candidate 3
        f.rec.floors shouldBe listOf(5L, 15L)

        send(f.c, 30) // min(100, 100, 30) - 5 = 25: the first rise C takes part in
        f.rec.floors shouldBe listOf(5L, 15L, 25L)
    }

    /**
     * `[24-WL-12]` on a diamond: S reaches the inlet through two relays, so
     * closing one of them leaves S arriving over the other. Retiring S there
     * would raise the floor to A's 50 - 5 = 45, past S's live promise 10 - 5.
     */
    @Test
    fun `B10 - EdgeClose of one diamond arm keeps a source the other arm still carries`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val s = ChurnSource()
        val a = ChurnSource()
        val r1 = ChurnRelay()
        val r2 = ChurnRelay()
        @Suppress("UNCHECKED_CAST")
        s.outlet.linkTo(r1.inlet as LinkFrom<Propagate<SetDelta<Long>>>)
        @Suppress("UNCHECKED_CAST")
        s.outlet.linkTo(r2.inlet as LinkFrom<Propagate<SetDelta<Long>>>)
        @Suppress("UNCHECKED_CAST")
        val arm1 = (r1.outlet.linkTo(cell.inlet as LinkFrom<Propagate<SetDelta<Long>>>) as LinkResult.Connected).link
        @Suppress("UNCHECKED_CAST")
        val arm2 = (r2.outlet.linkTo(cell.inlet as LinkFrom<Propagate<SetDelta<Long>>>) as LinkResult.Connected).link
        link(a, cell)
        val idS = send(s, 10)
        val idA = send(a, 50)
        rec.floors shouldBe listOf(5L)

        unlink(arm1)
        rec.floors shouldBe listOf(5L)
        cell.maxima() shouldBe mapOf(idS to 10L, idA to 50L)

        unlink(arm2) // the last arm carrying S: now it retires
        rec.floors shouldBe listOf(5L, 45L)
        cell.maxima() shouldBe mapOf(idA to 50L)
    }

    /** 6gkou.1-D3: manual `retire` emits detached even when called inside another wave. */
    @Test
    fun `manual retire inside a foreign wave still emits a fresh wave`() {
        val f = B10()
        val foreign = Timestamp(UUID(7, 7), 3)
        CurrentContext.with(civictech.cell.MessageContext(foreign, PortRef.generate())) { f.cell.retire(f.idC) }
        f.rec.floors shouldBe listOf(5L, 15L)
        f.rec.seen.last().second!!.sourceId shouldBe f.cell.outlet.waveState().sourceId
    }

    @Test
    fun `B10 hosted - a GlitchFreeCell downstream delivers the retirement emission`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val a = ChurnSource()
        val b = ChurnSource()
        val c = ChurnSource()
        val cell = WaterlineCell(lateness = lateness)
        val gf = GlitchFreeCell(waterlineApi)
        val observer = ChurnObserver(waterlineApi)
        listOf(a, b, c, cell, gf, observer).forEach { host.managementInlet.call.spawn(it) }

        link(a, cell)
        link(b, cell)
        val linkC = link(c, cell)
        @Suppress("UNCHECKED_CAST")
        cell.outlet.linkTo(gf.inlet as LinkFrom<Propagate<WaterlineDelta>>)
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, PortRef.generate()))
        controller.runToIdle()

        c.send(10)
        controller.runToIdle()
        b.send(20)
        controller.runToIdle()
        a.send(30)
        controller.runToIdle()
        observer.received shouldBe listOf(WaterlineDelta(5))

        unlink(linkC)
        controller.runToIdle()
        cell.floor() shouldBe 15L
        observer.received shouldBe listOf(WaterlineDelta(5), WaterlineDelta(15))
    }

    // ---------------------------------------------------------------------
    // B11 ([24-WL-13])

    /**
     * B11 through a real `SupervisionPolicy.RESTART`. P=50, Q=60 ⇒ floor 45.
     * The RESTART's re-baseline supersedes P with an empty delta: P's stale 50
     * no longer gates, so the candidate is Q's 60 − 5 = 55 and the floor rises,
     * riding the notice's wave. P' then joins at 12, below the floor
     * (`[24-WL-20]`): no regression, nothing emitted, and P' gates.
     */
    @Test
    fun `B11 hosted - a RESTART's superseding re-baseline retires the stale epoch and the fresh one contributes from scratch`() {
        val world = SimWorld(seed = 7)
        val controller = world.controller
        val host = world.host
        val p = ChurnProducer()
        val q = ChurnSource()
        val cell = WaterlineCell(lateness = lateness)
        listOf(p, q, cell).forEach { host.managementInlet.call.spawn(it) }
        @Suppress("UNCHECKED_CAST")
        p.outlet.linkTo(cell.inlet as LinkFrom<Propagate<SetDelta<Long>>>)
        link(q, cell)
        val rec = record(cell)
        host.managementInlet.call.supervise(p.ref, SupervisionPolicy.RESTART)
        val api = (HostedCellProxy.create(p.ref, host, ChurnProducerProxy::class.java) as ChurnProducerProxy).inlet.call
        controller.runToIdle()

        api.provide(50)
        controller.runToIdle()
        val idP = p.outlet.waveState().sourceId
        q.send(60)
        controller.runToIdle()
        cell.floor() shouldBe 45L
        cell.maxima() shouldBe mapOf(idP to 50L, q.sourceId to 60L)
        rec.floors shouldBe listOf(45L)

        api.provide(-1) // poison ⇒ RESTART ⇒ fresh epoch + ReBaselineNotice(supersedes = {P}, supersede = true)
        controller.runToIdle()
        host.generationOf(p.ref) shouldBe 1L
        val idP2 = p.outlet.waveState().sourceId
        (idP2 == idP) shouldBe false
        cell.maxima() shouldBe mapOf(q.sourceId to 60L) // P retired; the empty delta contributed nothing
        rec.floors shouldBe listOf(45L, 55L)
        rec.seen.last().second!!.sourceId shouldBe idP2 // rode the notice's wave

        api.provide(12) // P' joins below the floor: candidate min(12, 60) - 5 = 7
        controller.runToIdle()
        cell.maxima() shouldBe mapOf(idP2 to 12L, q.sourceId to 60L)
        cell.floor() shouldBe 55L
        rec.floors shouldBe listOf(45L, 55L)

        q.send(200) // P' gates
        controller.runToIdle()
        rec.floors shouldBe listOf(45L, 55L)

        api.provide(70) // min(70, 200) - 5 = 65
        controller.runToIdle()
        rec.floors shouldBe listOf(45L, 55L, 65L)
        (idP in cell.maxima()) shouldBe false
    }

    /** B11 direct: the notice retires P and the fresh epoch's non-empty delta contributes in the same fold. */
    @Test
    fun `B11 direct - a superseding re-baseline with adds retires and contributes in one evaluation`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val p = ChurnSource()
        val q = ChurnSource()
        link(p, cell)
        link(q, cell)
        val idP = send(p, 50)
        val idQ = send(q, 60)
        rec.floors shouldBe listOf(45L)

        p.outlet.mintFreshEpoch()
        p.outlet.reBaseline(setOf(idP), true) { propagate(adds(12)) }
        val idP2 = p.sourceId
        cell.maxima() shouldBe mapOf(idP2 to 12L, idQ to 60L)
        cell.floor() shouldBe 45L // min(12, 60) - 5 = 7: [24-WL-20], no regression
        rec.floors shouldBe listOf(45L)
    }

    /** The same fold, raising: without the retirement, P's stale 50 would hold the candidate at 45. */
    @Test
    fun `B11 direct - retirement and contribution combine into one raise riding the notice's wave`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val p = ChurnSource()
        val q = ChurnSource()
        link(p, cell)
        link(q, cell)
        val idP = send(p, 50)
        val idQ = send(q, 60)

        p.outlet.mintFreshEpoch()
        p.outlet.reBaseline(setOf(idP), true) { propagate(adds(58)) }
        val idP2 = p.sourceId
        cell.maxima() shouldBe mapOf(idP2 to 58L, idQ to 60L)
        rec.floors shouldBe listOf(45L, 53L) // one evaluation: min(58, 60) - 5
        rec.seen.last().second!!.sourceId shouldBe idP2
    }

    @Test
    fun `a re-baseline with supersede = false retires nothing`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val p = ChurnSource()
        val q = ChurnSource()
        link(p, cell)
        link(q, cell)
        val idP = send(p, 50)
        val idQ = send(q, 60)

        p.outlet.mintFreshEpoch()
        p.outlet.reBaseline(setOf(idP), false) { propagate(adds(58)) }
        val idP2 = p.sourceId
        cell.maxima() shouldBe mapOf(idP to 50L, idP2 to 58L, idQ to 60L)
        rec.floors shouldBe listOf(45L)
    }

    /** A re-baseline-retired source's link no longer names it: closing that link later retires only the fresh epoch. */
    @Test
    fun `a superseded source is dropped from its link's bookkeeping`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val p = ChurnSource()
        val q = ChurnSource()
        val linkP = link(p, cell)
        link(q, cell)
        val idP = send(p, 50)
        val idQ = send(q, 60)
        p.outlet.mintFreshEpoch()
        p.outlet.reBaseline(setOf(idP), true) { propagate(adds(12)) }
        unlink(linkP)
        cell.maxima() shouldBe mapOf(idQ to 60L)
        rec.floors shouldBe listOf(45L, 55L)
    }

    // ---------------------------------------------------------------------

    @Test
    fun `a restored cell still retires on the close of a link its contributions arrived over`() {
        val f = B10()
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { o -> o.writeObject(f.cell.snapshot()) } }
        val state = ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() as Serializable }

        val restored = WaterlineCell(lateness = lateness)
        restored.restore(state)
        restored.floor() shouldBe 5L
        val rec = record(restored)

        // C's outlet ref is stable (derived from its cell ref), so a close of
        // any link from it names the port the restored bookkeeping keys on.
        unlink(link(f.c, restored))
        restored.maxima() shouldBe mapOf(f.idA to 30L, f.idB to 20L)
        rec.floors shouldBe listOf(15L)
    }

    // ---------------------------------------------------------------------
    // computenet-6gkou.2: B12 ([24-WL-14], [24-WL-15])

    /** B12: an idle *contributing* source freezes the floor at its own promise; manual retire raises it once. */
    @Test
    fun `B12 - an idle contributing source freezes the floor, retire raises it in one emission`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val a = ChurnSource()
        val b = ChurnSource()
        link(a, cell)
        link(b, cell)
        val idB = send(b, 20)
        send(a, 30)
        rec.floors shouldBe listOf(15L) // min(30, 20) - 5

        // B goes idle: A keeps advancing but B still gates, nothing more is emitted
        send(a, 100)
        send(a, 200)
        rec.floors shouldBe listOf(15L)
        cell.floor() shouldBe 15L

        cell.retire(idB)
        rec.floors shouldBe listOf(15L, 195L) // 200 - 5
    }

    /** B12's `[24-WL-14]` half: an idle source that joined *below* the floor freezes it entirely, not just at its own promise. */
    @Test
    fun `B12 - an idle source joined below the floor freezes it entirely, retire raises it in one emission`() {
        val cell = WaterlineCell(lateness = lateness)
        val rec = record(cell)
        val a = ChurnSource()
        val c = ChurnSource()
        link(a, cell)
        link(c, cell)
        send(a, 30)
        rec.floors shouldBe listOf(25L)

        val idC = send(c, 2) // joins below the floor: candidate min(30, 2) - 5 = -3, clamped ([24-WL-20])
        rec.floors shouldBe listOf(25L)
        cell.floor() shouldBe 25L

        // C idle: A keeps advancing but C still gates at its own promise (-3), so no advance at all
        send(a, 300)
        send(a, 400)
        rec.floors shouldBe listOf(25L)
        cell.floor() shouldBe 25L

        cell.retire(idC)
        rec.floors shouldBe listOf(25L, 395L) // 400 - 5
    }

    // ---------------------------------------------------------------------
    // computenet-6gkou.2 (D2/D3): >= 100 seeded churn interleavings against
    // the joiner-aware oracle, mixing contribute, idle, unlink (EdgeClose),
    // re-link + re-admission, restart (empty and non-empty deltas) and manual
    // retire. Each seed drives a fresh cell with 3 or 4 source cells (a
    // function of the seed, so every run of the same seed uses the same
    // slot count without perturbing the drawn script).

    private fun numSlotsFor(seed: Long): Int = if (seed % 2 == 0L) 3 else 4

    @Test
    fun `seeded churn against the joiner-aware oracle covers every kind`() {
        val kindCounts = mutableMapOf<Kind, Int>()
        for (seed in 0L until 100L) {
            runAgainstCell(seed, numSlotsFor(seed), kindCounts)
        }
        println("churn kind counts over 100 seeds: $kindCounts")
        for (kind in Kind.values()) {
            ((kindCounts[kind] ?: 0) > 0) shouldBe true
        }
    }

    /** D4(a): the non-vacuity control — a floor allowed to fall fails the oracle. */
    @Test
    fun `control - a floor allowed to fall fails the joiner-aware oracle`() {
        val err = shouldThrow<AssertionError> {
            for (seed in 0L until 100L) runAgainstSubject(seed, numSlotsFor(seed), fallingFloorControl())
        }
        println("falling-floor control first failure: ${err.message}")
    }

    /** D4(b): the non-vacuity control — a model that never retires on `EdgeClose` fails the oracle. */
    @Test
    fun `control - a model that never retires on unlink fails the joiner-aware oracle`() {
        val err = shouldThrow<AssertionError> {
            for (seed in 0L until 100L) runAgainstSubject(seed, numSlotsFor(seed), neverRetireOnUnlinkControl())
        }
        println("never-retire-on-unlink control first failure: ${err.message}")
    }
}
