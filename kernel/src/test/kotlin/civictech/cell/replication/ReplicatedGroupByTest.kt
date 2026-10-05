package civictech.cell.replication

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.Aggregators
import civictech.cell.data.Gossiping
import civictech.cell.data.Replicable
import civictech.cell.data.Windows
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.GroupByCell
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.nature.manifestOf
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import civictech.nature.ContractRegistry
import civictech.nature.Manifest
import civictech.nature.MergeClass
import civictech.nature.NatureAxis
import civictech.testkit.forEachSeed
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.Random
import java.util.UUID

/** 7afo4-D6/D7/D8 — GroupBy membership gossip without making its MapDelta outlet mergeable. */
class ReplicatedGroupByTest {

    interface GroupByInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    private data class Emission(
        val delta: MapDelta<String, Long>,
        val context: MessageContext?,
    )

    private fun keyOf(element: String): String = element.first().toString()
    private fun amountOf(element: String): Long = element.drop(1).toLong()

    private fun groupBy(ref: CellRef): GroupByCell<String, String, Long, Long> =
        GroupByCell(ref, ::keyOf, Aggregators.sumOf(::amountOf))

    private fun spawn(peer: Peer, ref: CellRef): GroupByCell<String, String, Long, Long> {
        var created: GroupByCell<String, String, Long, Long>? = null
        GraphSpec(
            listOf(
                SpawnStep(
                    handle = "group-by-${ref.instanceId}",
                    factory = CellFactory { chosen -> groupBy(chosen).also { created = it } },
                    identity = IdentityBinding.Exact(ref),
                    replicated = true,
                ),
            ),
        ).apply(ApplyContext(peer.host, peer.replication))
        return checkNotNull(created)
    }

    private fun record(cell: GroupByCell<String, String, Long, Long>): MutableList<Emission> {
        val emissions = mutableListOf<Emission>()
        cell.outlet.subscribe(
            Use.fixed(
                Propagate<MapDelta<String, Long>> { emissions += Emission(it, CurrentContext.get()) },
                PortRef.generate(),
            ),
        )
        return emissions
    }

    private fun fold(emissions: List<Emission>): Map<String, Long> = buildMap {
        emissions.forEach { emission ->
            emission.delta.removals.forEach(::remove)
            putAll(emission.delta.puts)
        }
    }

    private fun inlet(peer: Peer, ref: CellRef): Propagate<SetDelta<String>> =
        (HostedCellProxy.create(ref, peer.registry, GroupByInletProxy::class.java) as GroupByInletProxy).inlet.call

    @Test
    fun `replicated GroupByCells converge to equal per-key aggregates after writes on either peer`() {
        forEachSeed(0L until 50L) { seed ->
            val controller = SimulationController(seed)
            val peers = List(if (seed == 0L) 3 else 2) { Peer(controller) }
            // Seed zero is deliberately a three-peer chain: peer 2 learns peer 0's
            // membership only through peer 1's effective-delta echo.
            val links = peers.zipWithNext().map { (left, right) -> Peering.loopback(left.side, right.side) }
            val logicalId = UUID.nameUUIDFromBytes("replicated-group-by-$seed".toByteArray())
            val cells = peers.mapIndexed { index, peer -> spawn(peer, CellRef(logicalId, index.toLong())) }
            val streams = cells.map(::record)
            val inputs = peers.mapIndexed { index, peer -> inlet(peer, cells[index].ref) }
            controller.runToIdle()

            val tagSource = UUID.nameUUIDFromBytes("replicated-group-by-tags-$seed".toByteArray())
            var counter = 1L
            val live = linkedMapOf<String, Timestamp>()

            // A deterministic remote write makes the gossip path non-vacuous on
            // every seed and makes the third peer depend on the middle peer's echo.
            val firstTag = Timestamp(tagSource, counter++)
            live["a3"] = firstTag
            inputs[0].propagate(SetDelta(adds = mapOf("a3" to setOf(firstTag))))
            controller.runToIdle()
            streams.drop(1).forEachIndexed { index, stream ->
                withClue("peer ${index + 1} emitted for peer 0's write on seed $seed") {
                    stream.any { it.delta.puts["a"] == 3L } shouldBe true
                }
            }

            val random = Random(seed)
            val domain = listOf("a1", "a2", "a3", "b4", "b7", "c5", "d9", "e2")
            repeat(40) {
                val element = domain[random.nextInt(domain.size)]
                val target = random.nextInt(inputs.size)
                val liveTag = live[element]
                if (liveTag == null || random.nextBoolean()) {
                    if (liveTag == null) {
                        val tag = Timestamp(tagSource, counter++)
                        live[element] = tag
                        inputs[target].propagate(SetDelta(adds = mapOf(element to setOf(tag))))
                    }
                } else {
                    live.remove(element)
                    inputs[target].propagate(SetDelta(dels = mapOf(element to setOf(liveTag))))
                }
                repeat(random.nextInt(3)) { controller.step() }
            }
            controller.runToIdle()

            val expected = live.keys.groupBy(::keyOf).mapValues { (_, elements) -> elements.sumOf(::amountOf) }
            cells.forEachIndexed { index, cell ->
                withClue("peer $index membership on seed $seed") {
                    cell.contents().adds.keys shouldBe live.keys
                }
            }
            streams.forEachIndexed { index, stream ->
                withClue("peer $index aggregate on seed $seed (${links.size} bridge links)") {
                    fold(stream) shouldBe expected
                }
            }
        }
    }

    @Test
    fun `a replicated GroupByCell emits under its own source`() {
        val controller = SimulationController(71)
        val peers = List(2) { Peer(controller) }
        Peering.loopback(peers[0].side, peers[1].side)
        val logicalId = UUID.nameUUIDFromBytes("replicated-group-by-source".toByteArray())
        val cells = peers.mapIndexed { index, peer -> spawn(peer, CellRef(logicalId, index.toLong())) }
        val streams = cells.map(::record)
        controller.runToIdle()

        val tag = Timestamp(UUID.nameUUIDFromBytes("replicated-group-by-source-tag".toByteArray()), 1)
        inlet(peers[0], cells[0].ref).propagate(SetDelta(adds = mapOf("b6" to setOf(tag))))
        controller.runToIdle()

        val relay = streams[1].single { it.delta.puts["b"] == 6L }
        val remoteSource = (cells[1].outlet as FanOutlet<Propagate<MapDelta<String, Long>>>).waveState().sourceId
        val originSource = (cells[0].outlet as FanOutlet<Propagate<MapDelta<String, Long>>>).waveState().sourceId
        relay.context.shouldNotBeNull().timestamp.sourceId shouldBe remoteSource
        relay.context.timestamp.sourceId shouldNotBe originSource
    }

    private object LongTime : (Long) -> Long, Serializable {
        override fun invoke(value: Long): Long = value
        private fun readResolve(): Any = LongTime
    }

    private fun windowed(ref: CellRef = CellRef(UUID.randomUUID())) = GroupByCell(
        ref = ref,
        keyFn = Windows.tumbling(10),
        aggregator = Aggregators.count<Long>(),
        lateness = Windows.Lateness(LongTime, 2),
        keyTime = { key: Long -> key + 10 },
    )

    @Test
    fun `a GroupByCell with a lateness declaration is refused replication naming 24-WL-18`() {
        val controller = SimulationController(17)
        val peer = Peer(controller)
        val direct = windowed(CellRef(UUID.randomUUID(), 1))

        shouldThrow<IllegalStateException> { peer.replication.replicate(direct, peer.host) }
            .message.shouldNotBeNull() shouldContain "[24-WL-18]"
        peer.registry.replicasOf(direct.ref.id).shouldBeEmpty()
        peer.host.lookup<GroupByApi<Long, Long, Long>>(direct.ref) shouldBe null

        val graphRef = CellRef(UUID.randomUUID(), 2)
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "late-group-by",
                    factory = CellFactory { chosen -> windowed(chosen) },
                    identity = IdentityBinding.Exact(graphRef),
                    replicated = true,
                ),
            ),
        )
        shouldThrow<IllegalStateException> { spec.apply(ApplyContext(peer.host, peer.replication)) }
            .message.shouldNotBeNull() shouldContain "[24-WL-18]"
        peer.registry.replicasOf(graphRef.id).shouldBeEmpty()
        peer.host.lookup<GroupByApi<Long, Long, Long>>(graphRef) shouldBe null
    }

    @Test
    fun `a lateness GroupByCell still evicts`() {
        val cell = windowed()
        val emissions = mutableListOf<MapDelta<Long, Long>>()
        cell.outlet.subscribe(Use.fixed(Propagate<MapDelta<Long, Long>>(emissions::add), PortRef.generate()))
        val tag = Timestamp(UUID.randomUUID(), 1)

        cell.inlet.call.propagate(SetDelta(adds = mapOf(1L to setOf(tag))))
        cell.waterline.call.propagate(WaterlineDelta(10))

        emissions shouldBe listOf(
            MapDelta(mapOf(0L to 1L), emptySet()),
            MapDelta(emptyMap(), setOf(0L)),
        )
        cell.contents().adds shouldBe emptyMap()
    }

    @Test
    fun `GroupByCell is Gossiping but not Replicable and keeps a non-idempotent inlet`() {
        val cell = groupBy(CellRef(UUID.randomUUID()))
        val polymorphic: Cell = cell
        (polymorphic is Gossiping<*>) shouldBe true
        (polymorphic is Replicable<*>) shouldBe false
        manifestOf(GroupByCell::class.java) shouldContain Manifest.REPLICATED

        val inlet = ContractRegistry.cellDescriptor(GroupByCell::class.java)
            .shouldNotBeNull()
            .ports.first { it.name == "inlet" }
        inlet.natures.level(NatureAxis.MERGE_IDEMPOTENCE) shouldBe MergeClass.NON_IDEMPOTENT
    }

    /**
     * Retained tombstones must ride the membership catch-up. A replica restored
     * from a checkpoint taken before a del still holds the add; the replica that
     * folded the del holds only its tombstone, so a catch-up gated on
     * non-empty adds sends nothing and the restored add survives forever (the
     * peer rejects the add as tombstoned without echoing anything back).
     */
    @Test
    fun `a replica restored from a pre-del checkpoint learns the del through catch-up`() {
        val controller = SimulationController(5)
        val peers = List(2) { Peer(controller) }
        val logicalId = UUID.nameUUIDFromBytes("replicated-group-by-restore".toByteArray())
        val tag = Timestamp(UUID.nameUUIDFromBytes("replicated-group-by-restore-tag".toByteArray()), 1)
        val checkpoint = groupBy(CellRef(logicalId, 1))
            .apply { inlet.call.propagate(SetDelta(adds = mapOf("a3" to setOf(tag)))) }
            .snapshot()

        val survivor = spawn(peers[0], CellRef(logicalId, 0))
        controller.runToIdle()
        inlet(peers[0], survivor.ref).propagate(SetDelta(adds = mapOf("a3" to setOf(tag))))
        controller.runToIdle()
        inlet(peers[0], survivor.ref).propagate(SetDelta(dels = mapOf("a3" to setOf(tag))))
        controller.runToIdle()
        survivor.contents().adds.keys shouldBe emptySet()

        var restored: GroupByCell<String, String, Long, Long>? = null
        GraphSpec(
            listOf(
                SpawnStep(
                    handle = "restored-group-by",
                    factory = CellFactory { chosen -> groupBy(chosen).also { it.restore(checkpoint); restored = it } },
                    identity = IdentityBinding.Exact(CellRef(logicalId, 1)),
                    replicated = true,
                ),
            ),
        ).apply(ApplyContext(peers[1].host, peers[1].replication))
        Peering.loopback(peers[0].side, peers[1].side)
        controller.runToIdle()

        withClue("survivor") { survivor.contents().adds.keys shouldBe emptySet() }
        withClue("restored replica") { checkNotNull(restored).contents().adds.keys shouldBe emptySet() }
    }
}
