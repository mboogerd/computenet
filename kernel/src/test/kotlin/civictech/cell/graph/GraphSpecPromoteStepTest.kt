package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.data.SetCell
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.evolve.EvolutionHandle
import civictech.cell.evolve.ObservationWindow
import civictech.cell.evolve.Promotion
import civictech.cell.evolve.PromotionPolicy
import civictech.cell.evolve.StateMigrating
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkOptions
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.replication.Replication
import civictech.cell.verify.InvariantCell
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GraphSpecPromoteStepTest {

    private companion object {
        const val INVARIANT_NAME = "candidate sum never regresses"

        @Suppress("UNCHECKED_CAST")
        val consumerInt = Consumer::class.java as Class<Consumer<Int>>
        val cells = ConcurrentHashMap<CellRef, Cell>()

        fun <C : Cell> remember(cell: C): C = cell.also { cells[it.ref] = it }
    }

    private class RelayCell(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Consumer<Int>>())

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) = outlet.call.provide(input)
            })
        }
    }

    private class SummerV1(override val ref: CellRef) : Cell, Stateful {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Long>>())
        private var sum = 0L

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    sum += input
                    outlet.call.propagate(sum)
                }
            })
        }

        override fun snapshot(): Serializable = sum

        override fun restore(state: Serializable) {
            sum = state as Long
        }
    }

    private open class SummerV2(override val ref: CellRef) : Cell, Stateful, StateMigrating {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Long>>())
        private var representation = "sum=0"
        private var inputs = 0

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    inputs++
                    representation = "sum=${sum() + input}"
                    outlet.call.propagate(emission(sum(), inputs))
                }
            })
        }

        protected open fun emission(sum: Long, inputs: Int): Long = sum

        private fun sum(): Long = representation.removePrefix("sum=").toLong()

        override fun snapshot(): Serializable = representation

        override fun restore(state: Serializable) {
            representation = state as String
        }

        override fun importFrom(prior: Serializable) {
            representation = "sum=${prior as Long}"
        }
    }

    private class RegressingSummerV2(ref: CellRef) : SummerV2(ref) {
        override fun emission(sum: Long, inputs: Int): Long = if (inputs == 2) sum - 100 else sum
    }

    private class CollectorCell(override val ref: CellRef) : Cell {
        val received = mutableListOf<Long>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Long>>())

        init {
            inlet.serve(Propagate { received += it })
        }
    }

    private class EmptyCell(override val ref: CellRef) : Cell

    private object RelayFactory : TypedCellFactory<RelayCell> {
        override fun create(ref: CellRef): RelayCell = remember(RelayCell(ref))
    }

    private object GateFactory : TypedCellFactory<TrafficLightCell<Consumer<Int>>> {
        override fun create(ref: CellRef): TrafficLightCell<Consumer<Int>> =
            remember(TrafficLightCell(consumerInt, ref))
    }

    private object SummerV1Factory : TypedCellFactory<SummerV1> {
        override fun create(ref: CellRef): SummerV1 = remember(SummerV1(ref))
    }

    private object SummerV2Factory : TypedCellFactory<SummerV2> {
        override fun create(ref: CellRef): SummerV2 = remember(SummerV2(ref))
    }

    private object RegressingSummerV2Factory : TypedCellFactory<RegressingSummerV2> {
        override fun create(ref: CellRef): RegressingSummerV2 = remember(RegressingSummerV2(ref))
    }

    private object CollectorFactory : TypedCellFactory<CollectorCell> {
        override fun create(ref: CellRef): CollectorCell = remember(CollectorCell(ref))
    }

    private object InvariantFactory : TypedCellFactory<InvariantCell<Long, Pair<Long, Long>>> {
        override fun create(ref: CellRef): InvariantCell<Long, Pair<Long, Long>> = remember(
            InvariantCell(
                name = INVARIANT_NAME,
                initial = 0L to 0L,
                fold = { (_, current), value -> current to value },
                check = { (prior, current), _ -> if (current < prior) "$current < $prior" else null },
                ref = ref,
            ),
        )
    }

    private object EmptyFactory : TypedCellFactory<EmptyCell> {
        override fun create(ref: CellRef): EmptyCell = remember(EmptyCell(ref))
    }

    private object ReplicatedFactory : TypedCellFactory<SetCell<String>> {
        override fun create(ref: CellRef): SetCell<String> = remember(SetCell(ref))
    }

    private data class DeclaredRefs(
        val relay: CellRef,
        val gate: CellRef,
        val incumbent: CellRef,
        val collector: CellRef,
        val invariant: CellRef,
    )

    private data class World(
        val controller: SimulationController,
        val host: ManagedHost,
        val context: ApplyContext,
        val journal: Journal,
    )

    private fun world(seed: Long): World {
        val journal = InMemoryJournal()
        val controller = SimulationController(seed)
        lateinit var context: ApplyContext
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            journalFor = { ref -> context.journalFor(ref) },
        )
        context = ApplyContext(host, journals = mapOf("j" to journal), topology = journal)
        return World(controller, host, context, journal)
    }

    private fun refs(): DeclaredRefs {
        val logicalId = UUID.randomUUID()
        return DeclaredRefs(
            relay = CellRef(UUID.randomUUID()),
            gate = CellRef(UUID.randomUUID()),
            incumbent = CellRef(logicalId, 0),
            collector = CellRef(UUID.randomUUID()),
            invariant = CellRef(UUID.randomUUID()),
        )
    }

    private fun policy(gates: List<String> = listOf(INVARIANT_NAME)) = PromotionPolicy(
        gates = gates,
        window = ObservationWindow(3),
        judge = "graph-spec-judge",
    )

    private fun spec(
        refs: DeclaredRefs,
        candidateFactory: CellFactory = SummerV2Factory,
        candidateShadow: Boolean = true,
        connectCandidateInput: Boolean = true,
        gateHandles: List<String> = listOf("invariant"),
        policyGateNames: List<String> = listOf(INVARIANT_NAME),
    ): GraphSpec = GraphSpec(buildList {
        add(SpawnStep("relay", RelayFactory, IdentityBinding.Exact(refs.relay), journalId = "j"))
        add(SpawnStep("gate", GateFactory, IdentityBinding.Exact(refs.gate)))
        add(SpawnStep("incumbent", SummerV1Factory, IdentityBinding.Exact(refs.incumbent), journalId = "j"))
        add(
            SpawnStep(
                "candidate",
                candidateFactory,
                IdentityBinding.NewInstanceOf(refs.incumbent.id),
                journalId = "j",
                shadow = candidateShadow,
            ),
        )
        add(SpawnStep("collector", CollectorFactory, IdentityBinding.Exact(refs.collector)))
        add(SpawnStep("invariant", InvariantFactory, IdentityBinding.Exact(refs.invariant)))
        val staged = LinkOptions(staged = true)
        add(ConnectStep("relay", "outlet", "gate", "dataInlet", staged))
        add(ConnectStep("gate", "dataOutlet", "incumbent", "inlet", staged))
        if (connectCandidateInput) add(ConnectStep("gate", "dataOutlet", "candidate", "inlet", staged))
        add(ConnectStep("incumbent", "outlet", "collector", "inlet", staged))
        add(ConnectStep("candidate", "outlet", "invariant", "inlet", staged))
        add(
            PromoteStep(
                handle = "rollout",
                incumbent = "incumbent",
                candidate = "candidate",
                gate = "gate",
                outletName = "outlet",
                downstream = listOf("collector" to "inlet"),
                policy = policy(policyGateNames),
                gates = gateHandles,
            ),
        )
    })

    private fun setGreen(world: World, applied: AppliedGraph) {
        @Suppress("UNCHECKED_CAST")
        val gate = cells.getValue(applied.refs.getValue("gate")) as TrafficLightCell<Consumer<Int>>
        gate.controlInlet.call.setGreen()
        world.controller.runToIdle()
    }

    private fun feed(world: World, ref: CellRef, vararg inputs: Int) {
        val relay = cells.getValue(ref) as RelayCell
        inputs.forEach { input ->
            relay.inlet.call.provide(input)
            world.controller.runToIdle()
        }
    }

    private fun topologyEvents(journal: Journal): List<TopoEvent> = journal.replay()
        .map(JournalRecords::decode)
        .filterIsInstance<DecodedJournalRecord.Topology>()
        .flatMap { it.events }

    @Test
    fun `apply returns an evolution whose accepted swap is journaled once`() {
        val world = world(1)
        val refs = refs()
        val applied = spec(refs).apply(world.context)
        val candidate = applied.refs.getValue("candidate")
        val handle = applied.evolutions.getValue("rollout")
        setGreen(world, applied)

        feed(world, refs.relay, 1, 2, 3)
        handle.advance() shouldBe EvolutionHandle.State.PROMOTED
        world.controller.runToIdle()

        val live = world.context.live()
        (refs.incumbent !in live.spawns) shouldBe true
        live.spawns.getValue(candidate).shadow shouldBe false
        val promotes = topologyEvents(world.journal).filterIsInstance<TopoEvent.Promote>()
        promotes.size shouldBe 1
        promotes.single().incumbent shouldBe refs.incumbent
        promotes.single().candidate shouldBe candidate
    }

    @Test
    fun `rejection journals the shadow despawn and no promotion`() {
        val world = world(2)
        val refs = refs()
        val applied = spec(refs, candidateFactory = RegressingSummerV2Factory).apply(world.context)
        val candidate = applied.refs.getValue("candidate")
        setGreen(world, applied)

        feed(world, refs.relay, 1, 2, 3)
        applied.evolutions.getValue("rollout").advance() shouldBe EvolutionHandle.State.REJECTED
        world.controller.runToIdle()

        val events = topologyEvents(world.journal)
        events.filterIsInstance<TopoEvent.Promote>().size shouldBe 0
        events.filterIsInstance<TopoEvent.Despawn>().count { it.ref == candidate } shouldBe 1
        (candidate !in world.context.live().spawns) shouldBe true
        (refs.incumbent in world.context.live().spawns) shouldBe true
    }

    @Test
    fun `a candidate not declared shadow is refused before the evolution adds a tap`() {
        val world = world(3)
        val refs = refs()

        val refused = shouldThrow<Promotion.PromotionAborted> {
            spec(refs, candidateShadow = false, connectCandidateInput = false).apply(world.context)
        }

        refused.message!!.shouldContain("shadow")
        val gate = cells.getValue(refs.gate) as TrafficLightCell<*>
        val candidate = world.context.refFor("candidate")
        gate.dataOutlet.linking.links.none { it.to.cell == candidate } shouldBe true
        gate.controlInlet.call.setGreen()
        world.controller.runToIdle()
        feed(world, refs.relay, 4)
        (cells.getValue(refs.collector) as CollectorCell).received shouldBe listOf(4L)
    }

    @Test
    fun `remote and Use paths refuse promote by step and path name`() {
        val host = ManagedHost()
        val step = PromoteStep(
            "rollout", "incumbent", "candidate", "gate", "outlet", emptyList(),
            policy(gates = emptyList()), emptyList(),
        )

        val report = GraphSpec(listOf(step)).applyRemote(host.managementInlet)
        report.allApplied shouldBe false
        (report.results.getValue("rollout") as StepResult.Rejected).reason shouldContain "promote step 'rollout'"

        shouldThrow<IllegalStateException> {
            GraphSpec(listOf(step)).applyTo(host.managementInlet)
        }.message!!.let {
            it.shouldContain("promote step 'rollout'")
            it.shouldContain("applyTo(Use<HostManagementApi>)")
        }

        shouldThrow<IllegalStateException> {
            graph(host.managementInlet) {
                val incumbent = spawn("incumbent", factory = EmptyFactory)
                val candidate = spawn("candidate", factory = EmptyFactory)
                val gate = spawn("gate", factory = EmptyFactory)
                promote("rollout", incumbent, candidate, gate, "outlet", emptyList(), policy(emptyList()), emptyList())
            }
        }.message!!.let {
            it.shouldContain("promote step 'rollout'")
            it.shouldContain("graph(Use<HostManagementApi>)")
        }
    }

    @Test
    fun `cold precheck plans promote as the write plane policy refusal`() {
        val step = PromoteStep(
            "rollout", "incumbent", "candidate", "gate", "outlet", emptyList(),
            policy(gates = emptyList()), emptyList(),
        )
        val live = HostLiveView(ManagedHost(), LocationRegistry())

        val plan = GraphSpec(listOf(step)).precheck(live = live)

        val verdict = plan.verdict.shouldBeInstanceOf<Verdict.NotAppliable>()
        verdict.refusals.size shouldBe 1
        verdict.refusals.single().action shouldBe PlannedAction.PROMOTE
        val refusal = verdict.refusals.single().result.shouldBeInstanceOf<StepCheck.Refused>()
        refusal.code shouldBe RefusalCode.POLICY_DENIAL
        refusal.reason shouldContain "computenet-8joqm"
    }

    @Test
    fun `a replicated incumbent is refused by the single-instance path`() {
        val controller = SimulationController(4)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val context = ApplyContext(host, Replication(registry))
        val logicalId = UUID.randomUUID()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    "incumbent",
                    ReplicatedFactory,
                    IdentityBinding.Exact(CellRef(logicalId, 0)),
                    replicated = true,
                ),
                SpawnStep("candidate", EmptyFactory, IdentityBinding.Exact(CellRef(logicalId, 1)), shadow = true),
                SpawnStep("gate", EmptyFactory),
                PromoteStep(
                    "rollout", "incumbent", "candidate", "gate", "outlet", emptyList(),
                    policy(gates = emptyList()), emptyList(),
                ),
            ),
        )

        shouldThrow<Promotion.PromotionAborted> { spec.apply(context) }
            .message!!.shouldContain("single-instance path")
    }

    @Test
    fun `a named evolution gate that is not an invariant is refused by name`() {
        val world = world(5)
        val refs = refs()

        val refused = shouldThrow<Promotion.PromotionAborted> {
            spec(
                refs,
                gateHandles = listOf("collector"),
                policyGateNames = listOf("collector"),
            ).apply(world.context)
        }

        refused.message!!.shouldContain("collector")
        refused.message!!.shouldContain("InvariantCell")
    }
}
