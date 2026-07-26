package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.LocationRegistry
import civictech.cell.link.Interest
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * CP-D2 (spec 40/42 §Interest-scoped instance sets): the gossip linker
 * ([Replication.maybeLink]) consults each instance's [Interest]. Two
 * instances link only where their interests overlap, and every emission is
 * filtered to the *target's* interest before it rides the link. The three
 * regimes are three settings of this one knob:
 *
 * - **disjoint** slot-interest ⇒ partitioning: no cross-shard links, so the
 *   scatter-gather union over the instance set is conflict-free and loses
 *   nothing;
 * - **overlapping partial** interest ⇒ a link forms but an out-of-interest
 *   delta is filtered and never crosses;
 * - **total** interest (the default) ⇒ full replication, byte-identical to
 *   pre-interest gossip — every instance converges to the union.
 */
class InterestScopedGossipTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Mesh(seed: Long, count: Int) {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val hosts = List(count) { ManagedHost(scheduler = controller.scheduler(), registry = registry) }
        val replication = Replication(registry)
        val logicalId: UUID = UUID.randomUUID()
        lateinit var replicas: List<SetCell<String>>

        /** Assign interests (before replicate, so the linker sees them) then spawn the mesh. */
        fun start(interests: List<Interest>) {
            replicas = List(hosts.size) { i -> SetCell<String>(CellRef(logicalId, i.toLong())) }
            replicas.forEachIndexed { i, r -> registry.setInterest(r.ref, interests[i]) }
            replicas.forEachIndexed { i, r -> replication.replicate(r, hosts[i]) }
            controller.runToIdle()
        }

        fun writer(i: Int): SetOps<String> =
            (HostedCellProxy.create(replicas[i].ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        fun quiesce() = controller.runToIdle()
        fun membership(i: Int) = replicas[i].membership()
    }

    private val TOTAL = 3
    private fun slot(e: String) = Interest.Slots.slotOf(e, TOTAL)

    // a small universe with elements landing across all three slots
    private val universe = listOf("apple", "banana", "cherry", "date", "elder", "fig", "grape", "kiwi")

    @Test
    fun `disjoint slot-interest — writes land only on the admitting shard, union loses nothing`() {
        val mesh = Mesh(seed = 1, count = TOTAL)
        // shard i owns exactly slot i — pairwise disjoint, so no cross-shard link forms
        mesh.start(List(TOTAL) { i -> Interest.Slots(setOf(i), TOTAL) })

        // route each element to the shard whose interest admits its slot (the test plays the router)
        val written = universe.toSet()
        written.forEach { e -> mesh.writer(slot(e)).add(e) }
        mesh.quiesce()

        // each shard holds EXACTLY its own slice — nothing bled across a link
        (0 until TOTAL).forEach { i ->
            mesh.membership(i) shouldBe written.filterTo(mutableSetOf()) { slot(it) == i }
        }
        // scatter-gather union over the instance set == every write (no loss, no double-count)
        (0 until TOTAL).flatMapTo(mutableSetOf()) { mesh.membership(it) } shouldBe written
    }

    @Test
    fun `overlapping partial interest — a link forms but an out-of-interest delta never rides it`() {
        // two instances; A wants slots {0,1}, B wants {1,2} — overlap on slot 1 ⇒ a link forms
        val mesh = Mesh(seed = 2, count = 2)
        mesh.start(listOf(Interest.Slots(setOf(0, 1), TOTAL), Interest.Slots(setOf(1, 2), TOTAL)))

        val inSlot0 = universe.first { slot(it) == 0 } // A wants, B does not
        val inSlot1 = universe.first { slot(it) == 1 } // both want
        val inSlot2 = universe.first { slot(it) == 2 } // B wants, A does not

        mesh.writer(0).add(inSlot0) // written to A
        mesh.writer(0).add(inSlot1) // written to A
        mesh.quiesce()

        // the shared-slot element rode the link to B; the out-of-interest one was filtered out
        mesh.membership(1) shouldBe setOf(inSlot1)
        (inSlot0 in mesh.membership(1)) shouldBe false

        // symmetrically, B's slot-2 write is filtered before it can reach A
        mesh.writer(1).add(inSlot2)
        mesh.quiesce()
        (inSlot2 in mesh.membership(0)) shouldBe false
        mesh.membership(0) shouldBe setOf(inSlot0, inSlot1)
    }

    @Test
    fun `control — total interest reproduces full replication — every instance converges to the union`() {
        val mesh = Mesh(seed = 3, count = TOTAL)
        // default (unset) interest is Total; assert the linker behaves as pre-interest gossip
        mesh.start(List(TOTAL) { Interest.Total })

        // write different elements to different instances; gossip must converge all three
        universe.forEachIndexed { idx, e -> mesh.writer(idx % TOTAL).add(e) }
        mesh.quiesce()

        val memberships = (0 until TOTAL).map { mesh.membership(it) }
        memberships.toSet().size shouldBe 1 // all identical
        memberships[0] shouldBe universe.toSet() // and equal to the full union
    }
}
