package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.CountCell
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.partition.RoutedCommand
import civictech.cell.partition.ShardCell
import civictech.cell.port.Use
import civictech.cell.replication.Replication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/** x0oag-D2/D3/D4/D7 — replication is a spawn parameter, not a graph verb. */
class GraphSpecReplicationParameterTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private data class ReplicatedRun(
        val memberships: List<Set<String>>,
        val links: Set<Pair<civictech.cell.port.PortRef, civictech.cell.port.PortRef>>,
    )

    private fun replicatedRun(declared: Boolean): ReplicatedRun {
        val controller = SimulationController(seed = 17)
        val registry = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        val logicalId = UUID.nameUUIDFromBytes("graph-replication".toByteArray())
        val refs = listOf(CellRef(logicalId, 1), CellRef(logicalId, 2))
        val cells = mutableMapOf<CellRef, SetCell<String>>()

        if (declared) {
            refs.zip(listOf(hostA, hostB)).forEachIndexed { index, (ref, host) ->
                val spec = GraphSpec(
                    listOf(
                        SpawnStep(
                            handle = "shared-${index + 1}",
                            factory = CellFactory { chosen -> SetCell<String>(chosen).also { cells[chosen] = it } },
                            identity = IdentityBinding.Exact(ref),
                            replicated = true,
                        ),
                    ),
                )
                val applied = spec.apply(ApplyContext(host, replication))
                applied.refs.values.single() shouldBe ref
            }
        } else {
            refs.zip(listOf(hostA, hostB)).forEach { (ref, host) ->
                SetCell<String>(ref).also {
                    cells[ref] = it
                    replication.replicate(it, host)
                }
            }
        }
        controller.runToIdle()

        val routed = HostedCellProxy.create(refs[0], registry, SetInletProxy::class.java) as SetInletProxy
        routed.inlet.call.add("x")
        controller.runToIdle()

        val dataRefs = refs.toSet()
        val dataLinks = registry.all()
            .filter { it.from.cell in dataRefs && it.to.cell in dataRefs }
            .mapTo(mutableSetOf()) { it.from to it.to }
        return ReplicatedRun(refs.map { cells.getValue(it).membership() }, dataLinks)
    }

    @Test
    fun `declared replicated spawns converge and form the imperative link set`() {
        val declared = replicatedRun(declared = true)
        val imperative = replicatedRun(declared = false)

        declared.memberships shouldBe listOf(setOf("x"), setOf("x"))
        declared shouldBe imperative
    }

    private fun shardSpecs(): List<InstanceSpec> = (0 until 3).flatMap { shard ->
        val interest = Interest.Ranges(listOf(Interest.Ranges.Range(shard.toLong(), shard + 1L)))
        (0 until 2).map { replica ->
            InstanceSpec(
                interest = interest,
                instanceId = shard * 2 + replica,
                journalId = "j-$shard-$replica",
                replicated = true,
            )
        }
    }

    private fun shardedRun(declared: Boolean, seed: Long): Map<Int, Set<Int>> {
        val controller = SimulationController(seed)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry, keyOf = { it })
        val logicalId = UUID.nameUUIDFromBytes("graph-shards-$seed".toByteArray())
        val specs = shardSpecs()
        val cells = mutableMapOf<Int, ShardCell<Int>>()
        val factory = InstanceFactory { ref, spec ->
            // The formation assignment is construction-time data: publish it
            // before Replication forms this instance's interest-scoped links.
            registry.setInterest(ref, spec.interest)
            ShardCell<Int>(ref, { it }, spec.interest).also { cells[spec.instanceId] = it }
        }

        if (declared) {
            GraphSpec(listOf(InstanceSetStep("orders", logicalId, factory, specs)))
                .apply(ApplyContext(host, replication))
        } else {
            specs.forEach { spec ->
                val ref = CellRef(logicalId, spec.instanceId.toLong())
                val cell = factory.build(ref, spec)
                replication.replicate(cell as ShardCell<Int>, host)
            }
        }
        controller.runToIdle()

        val source = UUID.nameUUIDFromBytes("graph-shard-source-$seed".toByteArray())
        repeat(3) { shard ->
            val tag = Timestamp(source, shard + 1L)
            cells.getValue(shard * 2).routeInlet.call.propagate(
                RoutedCommand(0L, SetDelta(adds = mapOf(shard to setOf(tag)))),
            )
        }
        controller.runToIdle()
        return cells.mapValues { it.value.membership() }
    }

    @Test
    fun `replicated instance set lowers every shard replica through the same mesh`() {
        val declared = shardedRun(declared = true, seed = 29)
        val imperative = shardedRun(declared = false, seed = 29)

        declared shouldBe imperative
        repeat(3) { shard ->
            declared.getValue(shard * 2) shouldBe setOf(shard)
            declared.getValue(shard * 2 + 1) shouldBe setOf(shard)
        }
    }

    @Test
    fun `missing replication refuses before any step is applied`() {
        val host = ManagedHost()
        val first = CellRef(UUID.randomUUID(), 1)
        val replicated = CellRef(UUID.randomUUID(), 2)
        val spec = GraphSpec(
            listOf(
                SpawnStep("first", CellFactory { SetCell<String>(it) }, IdentityBinding.Exact(first)),
                SpawnStep(
                    "shared",
                    CellFactory { SetCell<String>(it) },
                    IdentityBinding.Exact(replicated),
                    replicated = true,
                ),
            ),
        )

        val failure = shouldThrow<IllegalStateException> { spec.apply(ApplyContext(host)) }
        failure.message!! shouldContain "shared"
        failure.message!! shouldContain "replicated"
        host.lookup<SetCell<String>>(first) shouldBe null
        host.lookup<SetCell<String>>(replicated) shouldBe null
    }

    @Test
    fun `non Replicable spawn refuses before any step is applied`() {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val first = CellRef(UUID.randomUUID(), 1)
        val invalid = CellRef(UUID.randomUUID(), 2)
        val spec = GraphSpec(
            listOf(
                SpawnStep("first", CellFactory { SetCell<String>(it) }, IdentityBinding.Exact(first)),
                SpawnStep(
                    "count",
                    CellFactory { CountCell<String>(it) },
                    IdentityBinding.Exact(invalid),
                    replicated = true,
                ),
            ),
        )

        val failure = shouldThrow<IllegalStateException> {
            spec.apply(ApplyContext(host, Replication(registry)))
        }
        failure.message!! shouldContain "count"
        failure.message!! shouldContain "replicated"
        host.lookup<SetCell<String>>(first) shouldBe null
        host.lookup<CountCell<String>>(invalid) shouldBe null
    }

    @Test
    fun `Use apply and builder refuse replicated while remote reports rejection through progress`() {
        val host = ManagedHost()
        val ref = CellRef(UUID.randomUUID(), 1)
        val step = SpawnStep(
            "shared",
            CellFactory { SetCell<String>(it) },
            IdentityBinding.Exact(ref),
            replicated = true,
        )
        val spec = GraphSpec(listOf(step))

        val localFailure = shouldThrow<IllegalStateException> { spec.applyTo(host.managementInlet) }
        localFailure.message!! shouldContain "shared"
        localFailure.message!! shouldContain "replicated"
        localFailure.message!! shouldContain "apply(ApplyContext)"

        val builderFailure = shouldThrow<IllegalStateException> {
            graph(host.managementInlet) {
                spawn("builder-shared", replicated = true) { chosen -> SetCell<String>(chosen) }
            }
        }
        builderFailure.message!! shouldContain "builder-shared"
        builderFailure.message!! shouldContain "replicated"

        val events = mutableListOf<StepEvent>()
        val report = spec.applyRemote(host.managementInlet, ApplyProgress(events::add))
        val rejection = report.results.getValue("shared") as StepResult.Rejected
        rejection.reason shouldContain "replicated"
        events shouldBe listOf(StepEvent(0, "shared", rejection))
        host.lookup<SetCell<String>>(ref) shouldBe null
    }

    @Test
    fun `context graph and graphOf record and apply replicated spawns`() {
        val controller = SimulationController(seed = 41)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val context = ApplyContext(host, Replication(registry))

        val spec = graph(context) {
            spawn("a", replicated = true) { ref -> SetCell<String>(ref) }
        }
        val (handle, specOf) = graphOf(context) {
            spawn("b", replicated = true) { ref -> SetCell<String>(ref) }
        }
        controller.runToIdle()

        (spec.steps.single() as SpawnStep).replicated shouldBe true
        (specOf.steps.single() as SpawnStep).replicated shouldBe true
        handle.cell.membership() shouldBe emptySet()
    }
}
