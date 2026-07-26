package civictech.cell.data

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.replication.Assignment
import civictech.cell.replication.Interest
import civictech.cell.replication.InstanceSet
import civictech.cell.replication.Replication
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PN-6 (spec 40/42 §Interest-scoped instance sets, 20/24 §Partitioned state):
 * one logical id, an **instance set** of interest-scoped [ShardCell]s, and one
 * assignment lattice ([InstanceSet]) driving both the gossip linker and the
 * router. The three claims of the substrate:
 *
 * 1. **Batch-oracle convergence + one link per overlapping pair.** Across the
 *    three interest regimes (all-Total ⇒ replication, disjoint ⇒ partitioning,
 *    overlapping ⇒ sharded replication) the scatter-gather union over the
 *    instance set equals the batch oracle, and the gossip linker
 *    ([Replication.maybeLink], now `keyOf`-generalized — the *one* linker) forms
 *    exactly one link per overlapping instance pair, which is what
 *    [InstanceSet.overlapCount] reports.
 * 2. **Journaled crash+replay preserves a shed.** An interest reassignment
 *    applied as a journaled, ref-addressed [Assignment] invocation to a shard's
 *    `assignInlet` lands in the shard host's WAL; a crash + replay re-applies the
 *    shed even for a NON-checkpointed shard reconstructed with its original wide
 *    interest — closing PN-4's residual (the shed was an unjournaled in-process
 *    narrow). Control (a): the same shed as a direct in-process `assign` call is
 *    invisible to the WAL and resurrects on replay.
 * 3. **The lattice merge is load-bearing** (control b) and **the control plane
 *    is load-bearing** (control c): a non-commutative assignment merge forks by
 *    delivery order, and dropping control-plane frames while data flows
 *    half-applies a flip so the board forks.
 */
class InstanceSetSubstrateTest {

    private fun key(e: String) = e.first().toString()
    private fun amount(e: String) = e.drop(1).toLong()

    // ---- the assignment lattice max-register + serialization (the substrate) ----

    @Test
    fun `InstanceSet is an epoch-max register — newer epoch adopted, older filtered, round-trips`() {
        val set = InstanceSet(CellRef(UUID.randomUUID()))
        val r = CellRef(UUID.randomUUID(), 0L)
        val wide = Interest.Slots(setOf(0, 1, 2), 3)
        val narrow = Interest.Slots(setOf(0, 1), 3)

        set.assign(r, wide, epoch = 0L) shouldBe true
        set.assign(r, narrow, epoch = 1L) shouldBe true // newer epoch adopted
        set.interestOf(r) shouldBe narrow
        // an OLDER epoch cannot resurrect the shed wide range (admission rule)
        set.assign(r, wide, epoch = 0L) shouldBe false
        set.interestOf(r) shouldBe narrow
        set.epochOf(r) shouldBe 1L

        // snapshot/restore round-trip (the Replicable lattice is Stateful)
        val restored = InstanceSet(set.ref)
        restored.restore(set.snapshot())
        restored.entries() shouldBe set.entries()
    }

    // ---- claim 1: batch-oracle convergence + links == overlap count ----

    /** Interest-scoped [ShardCell] replicas of one logical id, wired by the one (keyOf-generalized) linker. */
    private inner class GossipMesh(seed: Long, interests: List<Interest>) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val hosts = List(interests.size) { ManagedHost(scheduler = controller.scheduler(), registry = registry) }
        // the ONE linker, generalized with the group keyOf (PN-6): the element is
        // "a3" but the interest is scoped over the group key "a".
        val replication = Replication(registry, keyOf = { key(it as String) })
        val logicalId: UUID = UUID.randomUUID()
        val shards: List<ShardCell<String>>
        val refs: Set<CellRef>
        private val src = UUID.randomUUID()
        private var ctr = 0L

        init {
            shards = List(interests.size) { i -> ShardCell(CellRef(logicalId, i.toLong()), { key(it) }, interests[i]) }
            shards.forEachIndexed { i, s -> registry.setInterest(s.ref, interests[i]) }
            shards.forEachIndexed { i, s -> replication.replicate(s, hosts[i]) }
            refs = shards.mapTo(mutableSetOf()) { it.ref }
            controller.runToIdle()
        }

        /** Write [e] to shard [i] (its interest must admit it); gossip carries it to overlapping peers. */
        fun write(i: Int, e: String) {
            val t = Timestamp(src, ++ctr)
            shards[i].routeInlet.call.propagate(RoutedCommand(0L, SetDelta(adds = mapOf(e to setOf(t)))))
        }

        fun quiesce() = controller.runToIdle()
        fun union(): Set<String> = shards.flatMapTo(mutableSetOf()) { it.membership() }
    }

    private fun admittingShard(interests: List<Interest>, e: String): Int? =
        interests.indexOfFirst { it.admits(key(e)) }.takeIf { it >= 0 }

    private fun convergenceCase(interests: List<Interest>, universe: List<String>) {
        val mesh = GossipMesh(seed = 7, interests)
        // an InstanceSet built from the same assignments reports the overlap count
        val oracle = InstanceSet(CellRef(UUID.randomUUID()))
        mesh.shards.forEachIndexed { i, s -> oracle.assign(s.ref, interests[i], 0L) }

        val written = mutableSetOf<String>()
        universe.forEach { e ->
            val target = admittingShard(interests, e) ?: return@forEach
            mesh.write(target, e)
            written += e
        }
        mesh.quiesce()

        // scatter-gather union over the instance set == the batch oracle (the written set)
        mesh.union() shouldBe written
        // the one linker formed exactly one link per overlapping ordered instance pair
        mesh.replication.linkCountAmong(mesh.refs) shouldBe oracle.overlapCount()
    }

    private val universe = listOf("a1", "b2", "c3", "d4", "e5", "f6", "g7", "h8", "k9", "m1", "n2", "p3")

    @Test
    fun `all-Total ⇒ replication — every instance converges to the union, full link mesh`() {
        // 3 Total instances: every ordered pair overlaps ⇒ 6 links; union == written
        convergenceCase(List(3) { Interest.Total }, universe)
    }

    @Test
    fun `disjoint slot-interest ⇒ partitioning — no cross links, union loses nothing`() {
        val total = 3
        convergenceCase(List(total) { i -> Interest.Slots(setOf(i), total) }, universe)
    }

    @Test
    fun `overlapping partial interest ⇒ sharded replication — links == overlap count, union intact`() {
        val total = 4
        // s0={0,1}, s1={1,2}, s2={3}: only s0-s1 overlap ⇒ 2 ordered links; all slots covered
        val interests = listOf(
            Interest.Slots(setOf(0, 1), total),
            Interest.Slots(setOf(1, 2), total),
            Interest.Slots(setOf(3), total),
        )
        convergenceCase(interests, universe)
    }

    // ---- claim 2: journaled crash+replay preserves a shed (+ control a) ----

    private interface RouteProxy {
        val routeInlet: Use<Propagate<RoutedCommand<String>>>
    }

    private interface AssignProxy {
        val assignInlet: Use<Propagate<Assignment>>
    }

    private fun routeVia(host: ManagedHost, ref: CellRef): Propagate<RoutedCommand<String>> =
        (HostedCellProxy.create(ref, host, RouteProxy::class.java) as RouteProxy).routeInlet.call

    private fun assignVia(host: ManagedHost, ref: CellRef): Propagate<Assignment> =
        (HostedCellProxy.create(ref, host, AssignProxy::class.java) as AssignProxy).assignInlet.call

    /**
     * Feed a wide-interest shard some elements over its journaled host, narrow its
     * interest (shedding a slot), then `kill -9` and recover from the WAL alone,
     * reconstructing the shard with its ORIGINAL wide interest. [journaledShed]
     * true ⇒ the narrow rides a journaled [Assignment] invocation; false (control
     * a) ⇒ a direct in-process `assign` call the WAL never sees.
     */
    private fun runShedRecovery(journaledShed: Boolean): Set<String> {
        val total = 3
        val wide = Interest.Slots(setOf(0, 1, 2), total)
        val narrow = Interest.Slots(setOf(0, 1), total) // sheds slot 2
        val ref = CellRef(UUID.randomUUID())
        val journal = InMemoryJournal() // the only thing that survives the crash

        val c1 = SimulationController(seed = 11)
        val host = ManagedHost(scheduler = c1.scheduler(), registry = LocationRegistry(), journal = journal)
        val shard = ShardCell<String>(ref, { key(it) }, wide, epochAware = true)
        host.managementInlet.call.spawn(shard)
        c1.runToIdle()

        val src = UUID.randomUUID()
        var ctr = 0L
        // write one element per slot over the JOURNALED intake (routed frames land in the WAL)
        universe.forEach { e ->
            routeVia(host, ref).propagate(RoutedCommand(0L, SetDelta(adds = mapOf(e to setOf(Timestamp(src, ++ctr))))))
        }
        c1.runToIdle()

        // narrow the interest: sheds every slot-2 element
        if (journaledShed) assignVia(host, ref).propagate(Assignment(narrow, 1L))
        else shard.assign(narrow, 1L) // control a: unjournaled in-process narrow
        c1.runToIdle()

        // kill -9: reconstruct on a fresh host over the SAME journal, with the
        // ORIGINAL WIDE interest, then replay (spec 24 recovery order: spawn → replay)
        val c2 = SimulationController(seed = 22)
        val host2 = ManagedHost(scheduler = c2.scheduler(), registry = LocationRegistry(), journal = journal)
        val recovered = ShardCell<String>(ref, { key(it) }, wide, epochAware = true)
        host2.managementInlet.call.spawn(recovered)
        c2.runToIdle()
        host2.recoverFrom(journal)
        c2.runToIdle()
        return recovered.membership()
    }

    @Test
    fun `journaled assignment makes the shed durable — a non-checkpointed shard replays it`() {
        val recovered = runShedRecovery(journaledShed = true)
        // the shed slot-2 range is GONE after recovery — the journaled Assignment replayed
        val slot2 = universe.filterTo(mutableSetOf()) { Interest.Slots.slotOf(key(it), 3) == 2 }
        recovered.intersect(slot2) shouldBe emptySet<String>()
        // and the retained slots survive intact
        recovered shouldBe universe.filterTo(mutableSetOf()) { Interest.Slots.slotOf(key(it), 3) != 2 }
    }

    @Test
    fun `control a - a direct in-process shed is invisible to the WAL and resurrects on replay`() {
        val recovered = runShedRecovery(journaledShed = false)
        val slot2 = universe.filterTo(mutableSetOf()) { Interest.Slots.slotOf(key(it), 3) == 2 }
        // the shed range came BACK — the unjournaled narrow never replayed (PN-4's residual)
        recovered.intersect(slot2) shouldNotBe emptySet<String>()
    }

    // ---- control b: a non-commutative merge forks by delivery order ----

    @Test
    fun `control b - a non-commutative assignment merge forks by delivery order`() {
        val r = CellRef(UUID.randomUUID(), 0L)
        val i1 = Interest.Slots(setOf(0), 3)
        val i2 = Interest.Slots(setOf(1), 3)
        // two assignments at the SAME epoch, different interest
        val a = Assignment(i1, 5L)
        val b = Assignment(i2, 5L)

        // the join-semilattice merge (default) is order-independent — both orders converge
        val commAB = InstanceSet(r).apply { assign(r, a.interest, a.epoch); assign(r, b.interest, b.epoch) }
        val commBA = InstanceSet(r).apply { assign(r, b.interest, b.epoch); assign(r, a.interest, a.epoch) }
        commAB.interestOf(r) shouldBe commBA.interestOf(r)

        // the non-commutative control merge forks — delivery order decides the winner
        val lwwAB = InstanceSet(r, merge = InstanceSet.Companion::lastWriterWins)
            .apply { assign(r, a.interest, a.epoch); assign(r, b.interest, b.epoch) }
        val lwwBA = InstanceSet(r, merge = InstanceSet.Companion::lastWriterWins)
            .apply { assign(r, b.interest, b.epoch); assign(r, a.interest, a.epoch) }
        (lwwAB.interestOf(r) != lwwBA.interestOf(r)).shouldBeTrue()
    }

    // ---- control c: dropping control-plane frames while data flows forks the board ----

    /** Scatter-gather board: sum per group across shards; a group on two shards double-counts. */
    private fun boardOf(shards: List<ShardCell<String>>): Map<String, Long> {
        val board = mutableMapOf<String, Long>()
        shards.forEach { s ->
            s.membership().groupBy { key(it) }.forEach { (k, es) -> board.merge(k, es.sumOf { amount(it) }, Long::plus) }
        }
        return board
    }

    @Test
    fun `control c - control-plane frames dropped while data flows half-applies a flip and forks the board`() {
        val total = 2
        val s0 = ShardCell<String>(CellRef(UUID.randomUUID(), 0L), { key(it) }, Interest.Slots(setOf(0), total))
        val s1 = ShardCell<String>(CellRef(UUID.randomUUID(), 1L), { key(it) }, Interest.Slots(setOf(1), total))
        val shards = listOf(s0, s1)
        val src = UUID.randomUUID()
        var ctr = 0L
        fun writeTo(s: ShardCell<String>, e: String) =
            s.routeInlet.call.propagate(RoutedCommand(0L, SetDelta(adds = mapOf(e to setOf(Timestamp(src, ++ctr))))))

        // pick one element per slot
        val e0 = universe.first { Interest.Slots.slotOf(key(it), total) == 0 }
        val e1 = universe.first { Interest.Slots.slotOf(key(it), total) == 1 }
        writeTo(s0, e0)
        writeTo(s1, e1)

        // a flip swaps ownership. The control plane (assign) reaches s0 but is
        // DROPPED for s1; data keeps flowing. s0 adopts {1} and sheds e0; s1 keeps
        // {1} (never told to take slot 0). Re-route the moved element e0 — no shard
        // now admits slot 0, so it is lost: the flip half-applied and the board forks.
        s0.assign(Interest.Slots(setOf(1), total), 1L) // control-plane reaches s0
        // (s1's assign is dropped)
        writeTo(s0, e0) // re-routed under the new epoch to whoever admits slot 0 — s0 now rejects it
        writeTo(s1, e0) // s1 also rejects (still {1})

        val batch = mapOf(key(e0) to amount(e0), key(e1) to amount(e1))
        (boardOf(shards) != batch).shouldBeTrue() // forked: e0's group is missing/duplicated
    }
}
