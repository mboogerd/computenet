package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.link.LinkOptions
import civictech.cell.port.input
import civictech.cell.port.output
import civictech.testkit.SimWorld
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class GraphSpecDespawnStepTest {

    private val sourceRef = CellRef(UUID.randomUUID(), 1)
    private val sinkRef = CellRef(UUID.randomUUID(), 2)

    private class Source(override val ref: CellRef) : Cell {
        val outlet by output<Propagate<Int>>()
    }

    private class Sink(override val ref: CellRef) : Cell {
        val inlet by input<Propagate<Int>>()

        init {
            inlet.serve(Propagate { })
        }
    }

    private fun spec(): GraphSpec = GraphSpec(
        listOf(
            SpawnStep("source", CellFactory(::Source), IdentityBinding.Exact(sourceRef)),
            SpawnStep("sink", CellFactory(::Sink), IdentityBinding.Exact(sinkRef)),
            ConnectStep("source", "outlet", "sink", "inlet", LinkOptions(staged = true)),
            DespawnStep("source"),
        ),
    )

    @Test
    fun `context apply unlinks before despawn and removes the cell link and handle without a dead letter`() {
        val world = SimWorld()
        val context = ApplyContext(world.host)
        val deadLettersBefore = world.host.supervisionAccounting().deadLetters

        val applied = spec().apply(context)
        world.runToIdle()

        applied.refs.keys shouldBe setOf("sink")
        applied.links shouldBe emptyMap()
        context.live().spawns.values.map { it.handle } shouldBe listOf("sink")
        context.live().links shouldBe emptyMap()
        context.handles.keys shouldBe setOf("sink")
        world.registry.localLinks().shouldBeEmpty()
        world.host.lookup<Source>(sourceRef) shouldBe null
        world.host.supervisionAccounting().deadLetters shouldBe deadLettersBefore
    }

    @Test
    fun `applyTo despawns while applyRemote reports a rejected despawn step`() {
        val local = SimWorld()
        val localRefs = spec().applyTo(local.host.managementInlet)
        local.runToIdle()
        localRefs.keys shouldBe setOf("sink")
        local.registry.localLinks().shouldBeEmpty()

        val remote = SimWorld()
        val report = spec().applyRemote(remote.host.managementInlet)
        remote.runToIdle()
        report.results.getValue("despawn source")
            .shouldBeInstanceOf<StepResult.Rejected>()
            .reason shouldContain "not supported by applyRemote"
        report.results.getValue("source").shouldBeInstanceOf<StepResult.Applied>()
    }

    @Test
    fun `unknown despawn handle is refused by apply and precheck with its name`() {
        val world = SimWorld()
        val unknown = GraphSpec(listOf(DespawnStep("missing")))

        shouldThrow<IllegalStateException> {
            unknown.apply(ApplyContext(world.host))
        }.message shouldContain "missing"

        val plan = unknown.precheck(live = HostLiveView(world.host, world.registry))
        val refusal = (plan.verdict as Verdict.NotAppliable).refusals.single()
        refusal.action shouldBe PlannedAction.DESPAWN
        refusal.handle shouldBe "missing"
        (refusal.result as StepCheck.Refused).reason shouldContain "missing"
    }
}
