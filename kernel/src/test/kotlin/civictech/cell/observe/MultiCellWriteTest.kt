package civictech.cell.observe

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.op.UnionSetCell
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.HostLiveView
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.PlannedAction
import civictech.cell.graph.RefusalCode
import civictech.cell.graph.SpawnStep
import civictech.cell.graph.StepCheck
import civictech.cell.graph.StepResult
import civictech.cell.graph.TypedRef
import civictech.cell.graph.Verdict
import civictech.cell.graph.WriteStep
import civictech.cell.graph.graph
import civictech.cell.graph.lookup
import civictech.cell.graph.precheck
import civictech.cell.host.DeclaredWrite
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Collections
import java.util.UUID

/** Exit criterion for the declared multi-cell write boundary (axcyk-D11). */
class MultiCellWriteTest {

    private data class WaveRecord(val cell: String, val timestamp: Timestamp)

    private class Rig(seed: Long) {
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry())
        val a = SetCell<String>()
        val b = SetCell<String>()
        val c = SetCell<String>()
        val union = UnionSetCell<String>()
        val proxies: List<SetOps<String>>

        init {
            val management = host.managementInlet.call
            listOf(a, b, c, union).forEach(management::spawn)
            listOf(a, b, c).forEach { management.connect(it.ref, "outlet", union.ref, "inlet") }
            // Obtained outside DeclaredWrite.invoke: the boundary is ambient
            // context, not a special proxy or a test-side drive wrapper.
            proxies = listOf(a, b, c).map { cell ->
                host.lookup(TypedRef<SetApi<String>>(cell.ref))!!.inlet.call
            }
        }

        fun declareObservation(): Pair<Observation, DeclaredWrite> {
            val observation = host.observation {
                set("all", union.ref)
                write("addThree", setOf(a.ref, b.ref, c.ref))
            }
            return observation to observation.write("addThree")
        }

        fun probe(records: MutableList<WaveRecord>) {
            listOf("a" to a, "b" to b, "c" to c).forEach { (name, cell) ->
                cell.outlet.observe(PortRef.generate()) { context ->
                    if (context.baseline == null) records += WaveRecord(name, context.timestamp)
                }
            }
        }

        fun addSameValue(value: String) {
            proxies.forEach { it.add(value) }
        }
    }

    @Test
    fun `declared write stamps every contributing outlet once and releases one aligned frame per invocation`() {
        for (seed in 0L until 20L) {
            val rig = Rig(seed)
            val records = Collections.synchronizedList(mutableListOf<WaveRecord>())
            val frames = Collections.synchronizedList(mutableListOf<ObservationFrame>())
            rig.probe(records)
            val (observation, write) = rig.declareObservation()
            observation.onChange { frames += it }
            observation.group("all").drainBarrier().await(1_000) shouldBe AlignedDrainResult.Drained
            frames.clear()

            val expectedActor = UUID.nameUUIDFromBytes(
                "computenet:declared-write:${rig.host.ref.id}:addThree".toByteArray(UTF_8),
            )
            write.actorId shouldBe expectedActor

            val value = "value-$seed"
            write.invoke { rig.addSameValue(value) }
            rig.controller.runToIdle()
            observation.group("all").drainBarrier().await(1_000) shouldBe AlignedDrainResult.Drained

            withClue("seed=$seed records=$records frames=$frames") {
                records.map { it.cell } shouldContainExactly listOf("a", "b", "c")
                records.map { it.timestamp }.toSet() shouldBe setOf(Timestamp(write.actorId, write.position))
                frames.size shouldBe 1
                frames.single().views.getValue("all") shouldBe setOf(value)
                frames.single().groups.getValue("all").frontier[write.actorId] shouldBe write.position
            }
            observation.close()
        }
    }

    @Test
    fun `the same three writes without a declaration originate three source lanes`() {
        for (seed in 0L until 20L) {
            val rig = Rig(seed)
            val records = mutableListOf<WaveRecord>()
            rig.probe(records)

            rig.addSameValue("value-$seed")
            rig.controller.runToIdle()

            withClue("seed=$seed records=$records") {
                records.map { it.cell } shouldContainExactly listOf("a", "b", "c")
                records.map { it.timestamp.sourceId }.toSet().size shouldBe 3
            }
        }
    }

    @Test
    fun `host declaration is idempotent and validates its hosted scope`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val management = host.managementInlet.call
        val a = SetCell<String>()
        val b = SetCell<String>()
        val c = SetCell<String>()
        listOf(a, b, c).forEach(management::spawn)

        val first = management.declareWrite("addThree", setOf(a.ref, b.ref, c.ref))
        val repeated = management.declareWrite("addThree", setOf(c.ref, a.ref, b.ref))
        assertSame(first, repeated)
        assertSame(first, management.declaredWrite("addThree"))

        assertThrows<IllegalStateException> {
            management.declareWrite("addThree", setOf(a.ref, b.ref))
        }
        assertThrows<IllegalArgumentException> {
            management.declareWrite("foreign", setOf(a.ref, CellRef(UUID.randomUUID())))
        }
    }

    @Test
    fun `a conflicting WriteStep refuses the whole GraphSpec before spawning`() {
        val host = ManagedHost(scheduler = SimulationController(seed = 96).scheduler())
        val existing = SetCell<String>()
        host.managementInlet.call.spawn(existing)
        host.managementInlet.call.declareWrite("update", setOf(existing.ref))
        val spawnedRef = CellRef(UUID.randomUUID())
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "spawned",
                    factory = CellFactory { ref -> SetCell<String>(ref) },
                    identity = IdentityBinding.Exact(spawnedRef),
                ),
                WriteStep("update", listOf("spawned")),
            ),
        )
        val context = ApplyContext(host)

        val failure = assertThrows<IllegalStateException> { spec.apply(context) }

        failure.message shouldContain "update"
        context.live().handles shouldBe emptyMap()
        host.lookup(TypedRef<SetApi<String>>(spawnedRef)) shouldBe null
    }

    @Test
    fun `WriteStep applies locally prechecks handles and is refused remotely`() {
        val sourceController = SimulationController(seed = 91)
        val source = ManagedHost(scheduler = sourceController.scheduler())
        val spec = graph(source.managementInlet) {
            val a = spawn("a") { ref -> SetCell<String>(ref) }
            val b = spawn("b") { ref -> SetCell<String>(ref) }
            val c = spawn("c") { ref -> SetCell<String>(ref) }
            write("addThree", listOf(a, b, c))
        }
        spec.steps.last() shouldBe WriteStep("addThree", listOf("a", "b", "c"))
        source.managementInlet.call.declaredWrite("addThree")!!.cells.size shouldBe 3

        val replay = ManagedHost(scheduler = SimulationController(seed = 92).scheduler())
        val replayRefs = spec.applyTo(replay.managementInlet)
        replay.managementInlet.call.declaredWrite("addThree")!!.cells shouldBe
            setOf(replayRefs.getValue("a"), replayRefs.getValue("b"), replayRefs.getValue("c"))

        val contextual = ManagedHost(scheduler = SimulationController(seed = 93).scheduler())
        val applied = spec.apply(ApplyContext(contextual))
        contextual.managementInlet.call.declaredWrite("addThree")!!.cells shouldBe
            setOf(applied.refs.getValue("a"), applied.refs.getValue("b"), applied.refs.getValue("c"))

        val registry = LocationRegistry()
        val precheckHost = ManagedHost(
            scheduler = SimulationController(seed = 94).scheduler(),
            registry = registry,
        )
        val invalid = GraphSpec(spec.steps.dropLast(1) + WriteStep("badWrite", listOf("a", "missing")))
        val plan = invalid.precheck(live = HostLiveView(precheckHost, registry))
        val refusal = plan.verdict.shouldBeInstanceOf<Verdict.NotAppliable>().refusals.single()
        refusal.action shouldBe PlannedAction.WRITE
        refusal.result.shouldBeInstanceOf<StepCheck.Refused>().code shouldBe RefusalCode.UNRESOLVED_HANDLE

        val remote = ManagedHost(scheduler = SimulationController(seed = 95).scheduler())
        val rejected = spec.applyRemote(remote.managementInlet).results.getValue("addThree")
            .shouldBeInstanceOf<StepResult.Rejected>()
        rejected.reason shouldContain "not supported by applyRemote"
    }
}
