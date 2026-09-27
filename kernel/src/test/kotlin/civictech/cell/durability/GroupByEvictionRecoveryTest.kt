package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.Aggregators
import civictech.cell.data.Windows
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.data.op.GroupByCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.forEachSeed
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.*

/**
 * B13 (computenet-nt17o.6, KE4.3): a `GroupByCell` that has evicted windows,
 * checkpointed, crashed and recovered from its journal. Owns `[KE4-45]` /
 * `[22-REC-01]` for an evicting group-by, and the recovery half of
 * `[24-WL-05]` (state = integrated output) and `[24-WL-07]` (late-drop).
 *
 * Fixture as `GroupByEvictionTest`: elements are `Long` event times, tumbling
 * windows of 10 keyed by their start, `keyTime` = the window's end, lateness 2,
 * a count per window. The cell is hosted on a journaled [ManagedHost]; a plain
 * [Source] drives both its `inlet` and its `waterline` through the host's
 * intake (a [HostedCellProxy]), so every frame is written to the WAL. The
 * floor is driven directly with [WaterlineDelta]s — `WaterlineCell`'s own
 * recovery is KE4.2's, not this test's.
 *
 * Shape: populate windows 0/10/20 plus a seeded interleaving of adds and
 * floor rises ending at 20 (so windows 0 and 10 are evicted, observed by
 * subscriber A) -> `checkpoint` (the WAL compacts to the snapshot, which holds
 * the post-eviction groups and the floor, nt17o-D4) -> a journal TAIL: an add
 * into window 20, a sub-floor add t=3, and a non-raising `WaterlineDelta(10)`
 * -> CRASH (a new host on the same journal, the cell re-spawned at the same
 * `CellRef`) -> `recoverFrom` (restore + tail replay, the tail re-entering as a
 * baseline, 24 §Durable replay of a mid-graph data cell) -> a late-linked
 * subscriber B catches up -> live traffic resumes.
 *
 * Both halves are exercised: the tail's window-20 add is present in B's fold
 * and the tail's sub-floor add is counted in the recovered cell's
 * `droppedBelowFloor`, neither of which the checkpoint alone could produce.
 *
 * Control: the same session with `recoverFrom` skipped leaves B's fold empty
 * and the floor null — the harness observes recovery rather than a live cell.
 */
class GroupByEvictionRecoveryTest {

    /** Identity event time; a named `Serializable` function value, as `[24-WL-01]` asks. */
    private object LongTime : (Long) -> Long, Serializable {
        override fun invoke(e: Long): Long = e
        private fun readResolve(): Any = LongTime
    }

    private fun tag(n: Long) = Timestamp(UUID(0, n + 1), n + 1)

    private fun add(vararg es: Long) = SetDelta(adds = es.associate { it to setOf(tag(it)) })

    private fun windowed(ref: CellRef) = GroupByCell(
        ref = ref,
        keyFn = Windows.tumbling(10),
        aggregator = Aggregators.count<Long>(),
        lateness = Windows.Lateness(LongTime, 2),
        keyTime = { k: Long -> k + 10 },
    )

    /** Drives the cell's two inlets; minted per build, so post-crash traffic rides a new source lane. */
    private class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val adds = registerPort("adds", FanOutlet.create<Propagate<SetDelta<Long>>>())
        val floors = registerPort("floors", FanOutlet.create<Propagate<WaterlineDelta>>())
    }

    /** The hosted cell's inlets as the host's intake sees them — every call is a journaled frame. */
    interface WindowedProxy {
        val inlet: Use<Propagate<SetDelta<Long>>>
        val waterline: Use<Propagate<WaterlineDelta>>
    }

    /** A late-linking subscriber: [linkTo]-ed, so it receives the outlet's catch-up (G-22). */
    private class MapCollector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<Long, Long>>>())
        val arrivals = mutableListOf<MapDelta<Long, Long>>()

        init {
            inlet.serve(object : Propagate<MapDelta<Long, Long>> {
                override fun propagate(value: MapDelta<Long, Long>) {
                    arrivals += value
                }
            })
        }
    }

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    private fun fold(deltas: List<MapDelta<Long, Long>>): Map<Long, Long> {
        val out = mutableMapOf<Long, Long>()
        deltas.forEach { d ->
            out.putAll(d.puts)
            d.removals.forEach { out.remove(it) }
        }
        return out
    }

    private class Session(
        val aFoldAtCrash: Map<Long, Long>,
        val aRemovals: Set<Long>,
        val expectedWindow20: Long,
        val recovered: GroupByCell<Long, Long, Long, *>,
        val b: MapCollector,
        val source: Source,
        val controller: SimulationController,
    )

    private fun runSession(seed: Long, recover: Boolean): Session {
        val controller = SimulationController(seed)
        val rnd = Random(seed)
        val journal = InMemoryJournal() // "the disk": the only thing that survives the crash
        val cellRef = CellRef(UUID.randomUUID())

        fun build(): Triple<ManagedHost, GroupByCell<Long, Long, Long, *>, Source> {
            val host = ManagedHost(scheduler = controller.scheduler(), journal = journal)
            val cell = windowed(cellRef)
            host.managementInlet.call.spawn(cell)
            controller.runToIdle()
            val proxy = HostedCellProxy.create(cellRef, host, WindowedProxy::class.java) as WindowedProxy
            val source = Source()
            source.adds.subscribe(Use.fixed(proxy.inlet.call, PortRef.generate()))
            source.floors.subscribe(Use.fixed(proxy.waterline.call, PortRef.generate()))
            return Triple(host, cell, source)
        }

        val (host, cell, source) = build()
        val a = collect(cell.outlet)

        // step 1 (seeded): windows 0/10/20 populated, then a random interleaving of
        // extra adds and one or two floor rises; the last rise is always to 20
        source.adds.call.propagate(add(1, 12, 21))
        controller.runToIdle()
        val extra = (listOf(2L, 5L, 8L, 11L, 14L, 17L, 22L, 24L, 27L).shuffled(rnd)).take(2 + rnd.nextInt(5))
        val rises = if (rnd.nextBoolean()) listOf(10L, 20L) else listOf(20L)
        // adds shuffle around the rises; the rises keep their relative order
        val addOps = extra.map { e -> { source.adds.call.propagate(add(e)) } }.toMutableList()
        val riseOps = rises.map { f -> { source.floors.call.propagate(WaterlineDelta(f)) } }.toMutableList()
        val script = mutableListOf<() -> Unit>()
        while (addOps.isNotEmpty() || riseOps.isNotEmpty()) {
            val pickAdd = riseOps.isEmpty() || (addOps.isNotEmpty() && rnd.nextBoolean())
            script += if (pickAdd) addOps.removeAt(0) else riseOps.removeAt(0)
        }
        script.forEach { op ->
            op()
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        cell.floor() shouldBe 20L
        host.checkpoint(journal)

        // step 2: the journal tail — an add into window 20 (admitted), a sub-floor
        // add t=3 (late-dropped), a non-raising floor (a [24-WL-03] fixpoint)
        source.adds.call.propagate(add(25))
        source.adds.call.propagate(add(3))
        source.floors.call.propagate(WaterlineDelta(10))
        controller.runToIdle()
        val aFoldAtCrash = fold(a)
        val aRemovals = a.flatMap { it.removals }.toSet()
        // window 20: 21, 25, and every extra add at >= 20 (never below a floor of at most 20)
        val expectedWindow20 = 2L + extra.count { it >= 20 }

        // step 3: CRASH — every host, cell, queue and link is discarded; only the journal survives
        val (rHost, rCell, rSource) = build()
        if (recover) rHost.recoverFrom(journal)
        controller.runToIdle()

        // step 4: a late-linked subscriber catches up from the recovered state
        val b = MapCollector()
        rCell.outlet.linkTo(b.inlet as LinkFrom<Propagate<MapDelta<Long, Long>>>)
        controller.runToIdle()

        return Session(aFoldAtCrash, aRemovals, expectedWindow20, rCell, b, rSource, controller)
    }

    @Test
    fun `B13 - recovered after eviction, the late-linked fold equals the pre-crash fold on every seed`() {
        forEachSeed(0L until 100L) { seed ->
            val s = runSession(seed, recover = true)
            val cell = s.recovered

            // A saw windows 0 and 10 evicted as MapDelta removals before the checkpoint
            s.aRemovals shouldContainAll setOf(0L, 10L)
            s.aFoldAtCrash shouldBe mapOf(20L to s.expectedWindow20)

            // [22-REC-01]/[24-WL-05]: B's catch-up fold == A's fold at the crash; the tail's
            // window-20 add is in it, so the tail was replayed, not only the checkpoint restored
            fold(s.b.arrivals) shouldBe s.aFoldAtCrash
            // no evicted window re-admitted: neither a group nor a live element below 20
            fold(s.b.arrivals).keys.none { it < 20 } shouldBe true
            cell.contents().adds.keys.none { it < 20 } shouldBe true
            // nt17o-D4: the floor is restored from the checkpoint and not lowered by the
            // replayed WaterlineDelta(10) (a non-raising floor is a fixpoint)
            cell.floor() shouldBe 20L
            // [24-WL-07] on replay: the recovered cell starts its counter at 0 (a counter,
            // not snapshotted — nt17o-D4) and the checkpoint compacted away every pre-
            // checkpoint drop, so exactly 1 == the tail's replayed t=3 add, dropped again
            // against the restored floor instead of re-admitted into window 0
            cell.droppedBelowFloor shouldBe 1L

            // step 5: live traffic after recovery, on a fresh source lane
            val late = collect(cell.late)
            s.source.floors.call.propagate(WaterlineDelta(30))
            s.controller.runToIdle()
            // window 20 passes at floor 30 and is evicted through the ordinary retraction path
            s.b.arrivals.last() shouldBe MapDelta(emptyMap(), setOf(20L))
            fold(s.b.arrivals).shouldBeEmpty()
            cell.floor() shouldBe 30L

            s.source.adds.call.propagate(add(15, 31))
            s.controller.runToIdle()
            // t=15 < 30 is late-dropped and forwarded verbatim; t=31 is an ordinary add
            late shouldBe listOf(SetDelta(adds = mapOf(15L to setOf(tag(15)))))
            cell.droppedBelowFloor shouldBe 2L
            fold(s.b.arrivals) shouldBe mapOf(30L to 1L)
        }
    }

    @Test
    fun `control - with recovery skipped the late-linked fold is empty`() {
        forEachSeed(0L until 10L) { seed ->
            val s = runSession(seed, recover = false)
            // the pre-crash world had a non-empty fold ...
            s.aFoldAtCrash shouldBe mapOf(20L to s.expectedWindow20)
            // ... which a rebuilt cell never recovers without its journal
            s.b.arrivals.shouldBeEmpty()
            s.recovered.floor() shouldBe null
            s.recovered.droppedBelowFloor shouldBe 0L
        }
    }
}
