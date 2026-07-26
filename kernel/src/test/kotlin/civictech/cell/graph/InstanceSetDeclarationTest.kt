package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.replication.Interest
import civictech.cell.replication.InstanceSet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random
import civictech.cell.partition.ShardCell

/**
 * PN-13 (spec 40/42 §Interest-scoped instance sets, 51 §Graph construction DSL).
 * A single [InstanceSetStep] declaration produces a heterogeneous instance set —
 * `instances = f(interestPartition, replicationFactor)` — that ends the
 * hand-wiring of N mechanisms. Three claims:
 *
 * 1. **Replay is order-independent** (the parameters-not-verbs payoff): a
 *    declared set replays from its [GraphSpec] onto fresh hosts with identical
 *    memberships (instanceId → interest) and link sets (the overlap graph),
 *    across 100 seeds of step orderings — the declaration is data, not a
 *    sequence of verbs whose order matters.
 * 2. **Mis-compositions are refused at declaration, naming the axis**:
 *    partitioning a SINGLETON (non-`PARTITIONED`) cell is refused on the
 *    `INSTANCE_SCOPING` axis; a `DURABLE` cell declared journal-less is refused
 *    on the `DURABLE` nature.
 * 3. **Control — the DSL gains parameters, not verbs**: a fully-default
 *    declaration `lower`s to a [GraphSpec] `equals` to the hand-written N ×
 *    [SpawnStep] form.
 */
class InstanceSetDeclarationTest {

    private val totalSlots = 12

    /** The partition function `f(interestPartition, replicationFactor)`: P shards × R replicas. */
    private fun instances(partitions: Int, replicationFactor: Int): List<InstanceSpec> =
        (0 until partitions).flatMap { p ->
            val interest = Interest.Slots.forShard(p, partitions, totalSlots)
            (0 until replicationFactor).map { r ->
                val id = p * replicationFactor + r
                InstanceSpec(interest = interest, instanceId = id, journalId = "j-$id", placement = "host-$p")
            }
        }

    /** A shard base factory that also records each built cell by its declared instanceId. */
    private fun recordingShardBase(sink: MutableMap<Int, ShardCell<String>>): InstanceFactory =
        InstanceFactory { ref, spec ->
            ShardCell<String>(ref, { it }, spec.interest).also { sink[spec.instanceId] = it }
        }

    /** membership = instanceId → assigned interest; the set's declared contents. */
    private fun membershipOf(cells: Map<Int, ShardCell<String>>): Map<Int, Interest> =
        cells.mapValues { it.value.interest }

    /** link set = overlap graph over the live instances (what the gossip linker forms). */
    private fun overlapOf(cells: Map<Int, ShardCell<String>>): Int {
        val set = InstanceSet(CellRef(UUID.randomUUID()))
        cells.forEach { (_, cell) -> set.assign(cell.ref, cell.interest, 0L) }
        return set.overlapCount()
    }

    private fun freshHost(seed: Long): ManagedHost =
        ManagedHost(scheduler = SimulationController(seed).scheduler())

    @Test
    fun `a declared set replays with identical memberships and link sets across 100 step orderings`() {
        val logicalId = UUID.randomUUID()
        val declared = instances(partitions = 3, replicationFactor = 2) // 6 instances, sharded replication

        // reference replay (unshuffled) on a fresh host
        val refCells = mutableMapOf<Int, ShardCell<String>>()
        val refSpec = GraphSpec(listOf(InstanceSetStep("orders", logicalId, recordingShardBase(refCells), declared)))
        refSpec.applyTo(freshHost(seed = 0).managementInlet)
        val refMembership = membershipOf(refCells)
        val refLinks = overlapOf(refCells)

        // the set is genuinely heterogeneous: disjoint across shards, overlapping within a shard
        refMembership.values.toSet().size shouldBe 3 // 3 distinct shard interests
        refLinks shouldBe 3 * (2 * 1) // R*(R-1) ordered links per shard × 3 shards

        // 100 seeds: shuffle the LOWERED step order and replay onto a fresh host each time.
        // Memberships and links are interest-determined ⇒ invariant to spawn order.
        repeat(100) { seed ->
            val cells = mutableMapOf<Int, ShardCell<String>>()
            val lowered = InstanceSetStep("orders", logicalId, recordingShardBase(cells), declared).lower()
            val shuffled = GraphSpec(lowered.shuffled(Random(seed.toLong())))
            shuffled.applyTo(freshHost(seed = seed + 1L).managementInlet)

            membershipOf(cells) shouldBe refMembership
            overlapOf(cells) shouldBe refLinks
        }
    }

    @Test
    fun `mis-composition — partitioning a SINGLETON cell is refused on the INSTANCE_SCOPING axis`() {
        // SetCell is {DURABLE, REPLICATED} — not PARTITIONED; a disjoint (partitioning)
        // assignment over it is the SINGLETON mis-composition.
        val singletonBase = InstanceFactory { ref, _ -> SetCell<String>(ref = ref) }
        val ex = shouldThrow<IllegalArgumentException> {
            InstanceSetStep(
                "s", UUID.randomUUID(), singletonBase,
                listOf(
                    InstanceSpec(Interest.Slots(setOf(0), 2), 0, journalId = "j0"),
                    InstanceSpec(Interest.Slots(setOf(1), 2), 1, journalId = "j1"),
                ),
            ).lower()
        }
        ex.message!! shouldContain "INSTANCE_SCOPING"
    }

    @Test
    fun `mis-composition — a DURABLE cell declared journal-less is refused on the DURABLE nature`() {
        // ShardCell is DURABLE (Stateful); a null journalId is a journal-less host.
        val durableBase = InstanceFactory { ref, spec -> ShardCell<String>(ref, { it }, spec.interest) }
        val ex = shouldThrow<IllegalArgumentException> {
            InstanceSetStep(
                "s", UUID.randomUUID(), durableBase,
                listOf(InstanceSpec(Interest.Total, 0, journalId = null)),
            ).lower()
        }
        ex.message!! shouldContain "DURABLE"
    }

    @Test
    fun `control — a fully-default declaration lowers to a GraphSpec equal to the hand-written one`() {
        val logicalId = UUID.randomUUID()
        val base = InstanceFactory { ref, spec -> ShardCell<String>(ref, { it }, spec.interest) }
        val specs = listOf(
            InstanceSpec(Interest.Slots(setOf(0), 2), 0, journalId = "j0"),
            InstanceSpec(Interest.Slots(setOf(1), 2), 1, journalId = "j1"),
        )

        val lowered = GraphSpec(InstanceSetStep("s", logicalId, base, specs).lower())
        val handWritten = GraphSpec(
            listOf(
                SpawnStep("s-0", InstanceCellFactory(base, specs[0]), IdentityBinding.NewInstanceOf(logicalId)),
                SpawnStep("s-1", InstanceCellFactory(base, specs[1]), IdentityBinding.NewInstanceOf(logicalId)),
            ),
        )

        // parameters, not verbs: the convenience lowers to exactly the primitive steps.
        lowered shouldBe handWritten
    }
}
