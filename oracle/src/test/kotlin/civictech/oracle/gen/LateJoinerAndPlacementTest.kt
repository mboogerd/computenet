package civictech.oracle.gen

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.inlet
import civictech.cell.link.LinkResult
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * The generative halves of `[ORA1-GEN-09]` (late-joiner terminal + mid-script quiesce barrier)
 * and `[ORA1-GEN-10]` (multi-host placement) — computenet-4ru.6.4.
 *
 * Structural and generative only, exactly like [GraphGeneratorTest]/[ScriptGeneratorTest]: no
 * `ReferenceModel.eval` and no fold comparison anywhere here — the differential halves of BS-7
 * and BS-9 belong to the runner feature (computenet-4ru.8). This suite also never applies or
 * links the late terminal itself; [GraphGenerator] and [ScriptGenerator] only ever *emit* the
 * barrier and the late terminal as data (see both classes' KDoc) — the smoke test below applies
 * only the *static* topology (spawns + connects), never the late-joiner extension.
 *
 * [OperatorCatalog] is registered in [BeforeEach] and emptied in [AfterEach], following the
 * sibling generator suites' convention, even though the `ScriptGenerator`-only tests below do
 * not themselves touch it ([ScriptGenerator] reads catalog ids through [SourceKind], never the
 * process-wide registry).
 */
class LateJoinerAndPlacementTest {

    @BeforeEach
    fun register() {
        OperatorCatalog.reset()
        CoreOperators.registerAll()
    }

    @AfterEach
    fun emptyTheProcessWideCatalog() {
        OperatorCatalog.reset()
    }

    // -- [ORA1-GEN-09], graph half: the late terminal --------------------------------------

    @Test
    fun `lateJoiner on emits exactly one late terminal attached to an operator node's outlet`() {
        val generator = GraphGenerator(graphConfig(lateJoiner = true))
        (0L until SEED_SWEEP).forEach { seed ->
            val topology = generator.generate(seed).topology
            val late = topology.terminals.filter { it.late }
            withClue("seed $seed: late terminals $late") { late.size shouldBe 1 }

            val node = topology.nodes.first { it.handle == late.single().handle }
            withClue("seed $seed: late terminal '${node.handle}' names a source, not an operator") {
                node.source shouldBe null
            }
        }
    }

    @Test
    fun `lateJoiner off emits no late terminal`() {
        val generator = GraphGenerator(graphConfig(lateJoiner = false))
        (0L until SEED_SWEEP).forEach { seed ->
            val topology = generator.generate(seed).topology
            withClue("seed $seed") { topology.terminals.none { it.late } shouldBe true }
        }
    }

    // -- [ORA1-GEN-09], script half: the mid-script Barrier ---------------------------------

    @Test
    fun `lateJoiner on emits exactly one strictly interior Barrier`() {
        val topology = barrierTopology()
        (0L until SEED_SWEEP).forEach { seed ->
            val generated = ScriptGenerator(barrierConfig(lateJoiner = true), topology, Random(seed)).generate()
            val steps = generated.script.steps
            val barrierIndices = steps.indices.filter { steps[it] is CaseStep.Barrier }
            withClue("seed $seed: barriers at $barrierIndices of ${steps.size} steps") {
                barrierIndices.size shouldBe 1
            }
            val position = barrierIndices.single()
            withClue("seed $seed: Barrier at $position of ${steps.size} is not strictly interior") {
                (position in 1 until steps.size - 1) shouldBe true
            }
        }
    }

    @Test
    fun `lateJoiner off emits zero Barriers`() {
        val topology = barrierTopology()
        (0L until SEED_SWEEP).forEach { seed ->
            val generated = ScriptGenerator(barrierConfig(lateJoiner = false), topology, Random(seed)).generate()
            withClue("seed $seed") { generated.script.steps.none { it is CaseStep.Barrier } shouldBe true }
        }
    }

    /**
     * The Barrier's insertion must not corrupt the remove audit: every [RemoveRecord.stepIndex]
     * still names the index of a [CaseStep.Op] whose event is a `ScriptEvent.Remove`, even after
     * the splice shifts everything at or past the insertion point.
     */
    @Test
    fun `the remove audit still names Remove-Op steps after the Barrier splice`() {
        val topology = barrierTopology()
        (0L until SEED_SWEEP).forEach { seed ->
            val generated = ScriptGenerator(barrierConfig(lateJoiner = true), topology, Random(seed)).generate()
            val steps = generated.script.steps
            generated.removeAudit.forEach { record ->
                withClue("seed $seed: audit entry $record does not name a Remove/RemoveKey Op") {
                    val step = steps[record.stepIndex]
                    (step is CaseStep.Op && step.event is ScriptEvent.Remove) shouldBe true
                }
            }
        }
    }

    // -- [ORA1-GEN-10]: multi-host placement -------------------------------------------------

    @Test
    fun `hostCount greater than 1 places every handle, uses at least two ordinals, and crosses a host boundary`() {
        listOf(2, 3).forEach { hostCount ->
            val generator = GraphGenerator(graphConfig(hostCount = hostCount))
            (0L until SEED_SWEEP).forEach { seed ->
                val graph = generator.generate(seed)
                val placement = graph.topology.placement

                withClue("hostCount=$hostCount seed=$seed: placement covers every handle") {
                    placement.keys shouldBe graph.topology.nodes.map { it.handle }.toSet()
                }
                withClue("hostCount=$hostCount seed=$seed: an ordinal falls outside 0 until $hostCount") {
                    placement.values.all { it in 0 until hostCount } shouldBe true
                }
                withClue("hostCount=$hostCount seed=$seed: fewer than two ordinals actually used") {
                    placement.values.toSet().size shouldBeGreaterThanOrEqual 2
                }

                val crosses = graph.spec.steps.filterIsInstance<ConnectStep>()
                    .any { placement.getValue(it.from) != placement.getValue(it.to) }
                withClue("hostCount=$hostCount seed=$seed: no ConnectStep crosses a host boundary") {
                    crosses shouldBe true
                }
            }
        }
    }

    @Test
    fun `hostCount 1 places every handle at ordinal 0`() {
        val generator = GraphGenerator(graphConfig(hostCount = 1))
        (0L until SEED_SWEEP).forEach { seed ->
            val placement = generator.generate(seed).topology.placement
            withClue("seed $seed") { placement.values.toSet() shouldBe setOf(0) }
        }
    }

    // -- Determinism -------------------------------------------------------------------------

    @Test
    fun `equal seed and config yields equal late terminal, Barrier position, and placement`() {
        val gConfig = graphConfig(lateJoiner = true, hostCount = 2)
        (0L until 20L).forEach { seed ->
            val left = GraphGenerator(gConfig).generate(seed)
            val right = GraphGenerator(gConfig).generate(seed)
            withClue("seed $seed") {
                left.topology.terminals shouldBe right.topology.terminals
                left.topology.placement shouldBe right.topology.placement
            }
        }

        val topology = barrierTopology()
        val sConfig = barrierConfig(lateJoiner = true)
        (0L until 20L).forEach { seed ->
            val left = ScriptGenerator(sConfig, topology, Random(seed)).generate()
            val right = ScriptGenerator(sConfig, topology, Random(seed)).generate()
            withClue("seed $seed") {
                left.script shouldBe right.script
                left.removeAudit shouldBe right.removeAudit
            }
        }
    }

    // -- Smoke: a hostCount=2 case is genuinely applicable across two hosts -----------------

    /**
     * Proves the emitted [CaseTopology.placement] is applicable, not merely well-formed:
     * spawns each node onto its assigned [ManagedHost] and connects per the spec's
     * [ConnectStep]s (same-host directly, cross-host through the shared [LocationRegistry] —
     * the pattern `GenerativeGraphTest` established for cross-host wiring), runs to idle, and
     * observes zero dead letters. No values are asserted and no script is driven — driving is
     * the runner's (computenet-4ru.8); this is a placement-applicability probe only.
     */
    @Test
    fun `hostCount 2 cases apply across two ManagedHosts sharing one registry with no dead letters`() {
        val generator = GraphGenerator(graphConfig(hostCount = 2))
        var applied = 0

        (0L until SMOKE_CASES).forEach { seed ->
            val graph = generator.generate(seed)
            val controller = SimulationController(seed)
            val registry = LocationRegistry()
            val hosts = listOf(
                ManagedHost(scheduler = controller.scheduler(), registry = registry),
                ManagedHost(scheduler = controller.scheduler(), registry = registry),
            )
            val letters = mutableListOf<DeadLetter>()
            hosts.forEach { host ->
                host.deadLetterOutlet.subscribe(
                    Use.fixed(
                        object : Propagate<DeadLetter> {
                            override fun propagate(value: DeadLetter) {
                                letters += value
                            }
                        },
                        PortRef.generate(),
                    ),
                )
            }

            withClue("seed $seed: ${graph.topology.nodes.map { it.handle to it.catalogId }}") {
                applyAcrossHosts(graph, hosts, registry)
            }
            runToIdle(controller)

            withClue("seed $seed: dead letters ${letters.map { it.toString() }}") {
                letters.shouldBeEmpty()
            }
            applied++
        }

        withClue("the smoke batch must actually have applied its whole batch") {
            applied shouldBeGreaterThanOrEqual SMOKE_CASES.toInt()
        }
    }

    /**
     * Applies [graph] across [hosts] by [CaseTopology.placement]: every [SpawnStep] lands on
     * its assigned host, a same-host [ConnectStep] uses the ordinary two-[CellRef] `connect`,
     * and a cross-host one resolves the target inlet through [registry] (`LocationRegistry.inlet`,
     * the generic named-port write-handle `RoutedInlet.kt` offers precisely so a caller need not
     * hand-write a per-port proxy interface) and links to it via the `Use<*>`-target `connect`
     * overload — the same primitive [civictech.cell.graph.GraphSpec.applyTo] uses for a
     * same-host connect, generalized to a target that is not locally resolvable.
     */
    private fun applyAcrossHosts(
        graph: GeneratedGraph,
        hosts: List<ManagedHost>,
        registry: LocationRegistry,
    ): Map<String, CellRef> {
        val placement = graph.topology.placement
        val refs = mutableMapOf<String, CellRef>()

        graph.spec.steps.filterIsInstance<SpawnStep>().forEach { step ->
            val ref = step.identity.resolve()
            val cell = step.factory.create(ref)
            val host = hosts[placement.getValue(step.handle)]
            refs[step.handle] = host.managementInlet.call.spawn(cell)
        }

        graph.spec.steps.filterIsInstance<ConnectStep>().forEach { step ->
            val fromRef = refs.getValue(step.from)
            val toRef = refs.getValue(step.to)
            val fromOrdinal = placement.getValue(step.from)
            val toOrdinal = placement.getValue(step.to)
            val fromHost = hosts[fromOrdinal]

            if (fromOrdinal == toOrdinal) {
                val result = fromHost.managementInlet.call.connect(fromRef, step.outlet, toRef, step.inlet)
                check(result !is LinkResult.Rejected) {
                    "link ${step.from}.${step.outlet} -> ${step.to}.${step.inlet} rejected: " +
                        (result as LinkResult.Rejected).reason
                }
            } else {
                val target = registry.inlet(toRef, step.inlet, Any::class.java)
                fromHost.managementInlet.call.connect(fromRef, step.outlet, Use.fixed(target, PortRef.generate()))
            }
        }
        return refs
    }

    private fun runToIdle(controller: SimulationController, budget: Int = 200_000): Int {
        var steps = 0
        while (controller.step()) {
            check(++steps < budget) { "no quiescence within $budget steps" }
        }
        return steps
    }

    private companion object {
        /** The acceptance criterion's floor for the late-joiner/placement sweeps. */
        const val SEED_SWEEP = 50L

        /** The smoke test's floor: at least 5 hostCount=2 cases actually applied. */
        const val SMOKE_CASES = 5L

        /**
         * The **set-rooted** slice of the core vocabulary — the same restricted vocabulary
         * [GraphGeneratorTest] and [GraphSpecLinkSweepTest] sweep with. The full catalog
         * vocabulary throws "frontier holds N unconsumed nodes but terminalCount is 1" on
         * roughly 7/50 seeds (computenet-b9x7, filed and owned elsewhere) — restricting to this
         * slice, already proven clean by the sibling suites, keeps this suite from rediscovering
         * that filed defect as if it were its own.
         */
        fun graphConfig(
            lateJoiner: Boolean = false,
            hostCount: Int = 1,
        ) = GeneratorConfig(
            depthRange = 3..5,
            sourceCount = 3,
            vocabulary = listOf(
                CoreOperators.Ids.SET,
                CoreOperators.Ids.KEYED_SET,
                CoreOperators.Ids.FILTER,
                CoreOperators.Ids.FLAT_MAP_SET,
                CoreOperators.Ids.MAP_SET,
                CoreOperators.Ids.COUNT,
                CoreOperators.Ids.UNION,
                CoreOperators.Ids.INTERSECT,
                CoreOperators.Ids.PRESENCE_COUNT,
                CoreOperators.Ids.QUORUM_SET,
            ),
            elementDomainSize = 6,
            scriptLength = 40,
            addRemoveRatio = 0.6,
            unobservedRemoveRatio = 0.25,
            terminalCount = 1,
            lateJoiner = lateJoiner,
            hostCount = hostCount,
        ).validated()

        /** A hand-built two-writer-source topology, in [ScriptGeneratorTest]'s own style. */
        fun barrierTopology(): CaseTopology {
            val nodes = listOf(
                TopologyNode("s0", CoreOperators.Ids.SET, emptyList(), SourceId("s0")),
                TopologyNode("s1", CoreOperators.Ids.SET, emptyList(), SourceId("s1")),
                TopologyNode("agg", CoreOperators.Ids.COUNT, listOf("s0", "s1"), null),
            )
            return CaseTopology(
                nodes = nodes,
                terminals = listOf(TerminalSpec("terminal", "agg")),
                placement = nodes.associate { it.handle to 0 },
            )
        }

        fun barrierConfig(lateJoiner: Boolean) = GeneratorConfig(
            depthRange = 1..2,
            sourceCount = 2,
            vocabulary = listOf(CoreOperators.Ids.SET, CoreOperators.Ids.COUNT),
            elementDomainSize = 12,
            scriptLength = 60,
            addRemoveRatio = 0.5,
            unobservedRemoveRatio = 0.3,
            terminalCount = 1,
            writerCount = 2,
            lateJoiner = lateJoiner,
        )
    }
}
