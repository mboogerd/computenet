package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.Replicable
import civictech.cell.evolve.Effectful
import civictech.cell.evolve.Shadow
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import civictech.gen.wire.Contract
import civictech.cell.replication.Replication
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

@Contract(effect = true)
interface GraphShadowEffectApi {
    fun fire(value: Int)
}

@Contract
interface GraphShadowPureApi {
    fun update(value: Int)
}

/** An effect boundary plus a pure data inlet: contract-granular suppression is observable. */
private class ContractBoundaryCell(override val ref: CellRef) : Cell {
    val effectInlet = registerPort("effectInlet", FanInlet.create<GraphShadowEffectApi>())
    val pureInlet = registerPort("pureInlet", FanInlet.create<GraphShadowPureApi>())
    var effects = 0
    var state = 0

    init {
        effectInlet.serve(object : GraphShadowEffectApi {
            override fun fire(value: Int) {
                effects += value
            }
        })
        pureInlet.serve(object : GraphShadowPureApi {
            override fun update(value: Int) {
                state += value
            }
        })
    }
}

@Contract(effect = true)
interface GraphReplicatedEffectApi {
    fun fire(value: Int)
}

/** A lone replicated Effectful cell, used to prove suppression runs after replicate(). */
private class ReplicatedEffectCell(override val ref: CellRef) : Cell, Replicable<Int>, Effectful {
    val effectInlet = registerPort("effectInlet", FanInlet.create<GraphReplicatedEffectApi>())
    override val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())
    override val deltaInlet = registerPort("deltaInlet", FanInlet.create<Propagate<Int>>())
    var effects = 0

    init {
        effectInlet.serve(object : GraphReplicatedEffectApi {
            override fun fire(value: Int) {
                effects += value
            }
        })
        deltaInlet.serve(object : Propagate<Int> {
            override fun propagate(value: Int) = Unit
        })
    }
}

/** x0oag-D8 — shadow is a spawn parameter and reuses Shadow's suppression. */
class GraphSpecShadowParameterTest {

    private data class Outcome(val effects: Int, val state: Int)

    private fun declaredBoundary(): Outcome {
        val controller = SimulationController(seed = 41)
        val host = ManagedHost(scheduler = controller.scheduler())
        lateinit var cell: ContractBoundaryCell
        GraphSpec(
            listOf(
                SpawnStep(
                    handle = "candidate",
                    factory = CellFactory { ref -> ContractBoundaryCell(ref).also { cell = it } },
                    shadow = true,
                ),
            ),
        ).apply(ApplyContext(host))
        controller.runToIdle()
        cell.effectInlet.call.fire(3)
        cell.pureInlet.call.update(7)
        return Outcome(cell.effects, cell.state)
    }

    private fun imperativeBoundary(): Outcome {
        val controller = SimulationController(seed = 41)
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = ContractBoundaryCell(CellRef(UUID.randomUUID()))
        Shadow.spawn(host, cell)
        controller.runToIdle()
        cell.effectInlet.call.fire(3)
        cell.pureInlet.call.update(7)
        return Outcome(cell.effects, cell.state)
    }

    @Test
    fun `declared shadow suppresses effect contracts and preserves pure inputs like Shadow spawn`() {
        declaredBoundary() shouldBe Outcome(effects = 0, state = 7)
        declaredBoundary() shouldBe imperativeBoundary()
    }

    @Test
    fun `shadow suppression also runs after replicated spawn`() {
        val controller = SimulationController(seed = 43)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = Replication(registry)
        lateinit var cell: ReplicatedEffectCell
        val ref = CellRef(UUID.randomUUID(), 1)

        GraphSpec(
            listOf(
                SpawnStep(
                    handle = "replicated-candidate",
                    factory = CellFactory { chosen -> ReplicatedEffectCell(chosen).also { cell = it } },
                    identity = IdentityBinding.Exact(ref),
                    replicated = true,
                    shadow = true,
                ),
            ),
        ).apply(ApplyContext(host, replication))
        controller.runToIdle()

        cell.effectInlet.call.fire(9)
        cell.effects shouldBe 0
    }

    @Test
    fun `context builder spawn with shadow suppresses like Shadow spawn`() {
        val controller = SimulationController(seed = 41)
        val host = ManagedHost(scheduler = controller.scheduler())
        val (handle, builtSpec) = graphOf(ApplyContext(host)) {
            spawn("built-candidate", shadow = true) { ContractBoundaryCell(it) }
        }
        controller.runToIdle()
        handle.cell.effectInlet.call.fire(3)
        handle.cell.pureInlet.call.update(7)

        (builtSpec.steps.single() as SpawnStep).shadow shouldBe true
        Outcome(handle.cell.effects, handle.cell.state) shouldBe imperativeBoundary()
    }

    @Test
    fun `Use builders refuse shadow and remote folds it into a rejection`() {
        val ref = CellRef(UUID.randomUUID(), 1)
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "candidate",
                    factory = CellFactory { chosen -> ContractBoundaryCell(chosen) },
                    identity = IdentityBinding.Exact(ref),
                    shadow = true,
                ),
            ),
        )
        val host = ManagedHost()

        val applyToFailure = shouldThrow<IllegalStateException> { spec.applyTo(host.managementInlet) }
        applyToFailure.message!! shouldContain "candidate"
        applyToFailure.message!! shouldContain "shadow"

        shouldThrow<IllegalStateException> {
            graph(host.managementInlet) {
                spawn("builder-candidate", shadow = true) { ContractBoundaryCell(it) }
            }
        }.message!! shouldContain "builder-candidate"

        shouldThrow<IllegalStateException> {
            graphOf(host.managementInlet) {
                spawn("graph-of-candidate", shadow = true) { ContractBoundaryCell(it) }
            }
        }.message!! shouldContain "graph-of-candidate"

        val remote = spec.applyRemote(host.managementInlet)
        val rejection = remote.results.getValue("candidate") as StepResult.Rejected
        rejection.reason shouldContain "shadow"
    }
}
