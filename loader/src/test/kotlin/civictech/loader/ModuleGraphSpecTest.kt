package civictech.loader

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.delta.SetDelta
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `[JAR1-SPAWN-03]` — GraphSpec citizenship of a module-spawned cell (epic
 * computenet-051, feature computenet-051.5, task computenet-051.5.4); the
 * declaration-side sibling of B1's flow half in [B1ModuleFlowParityTest] and of
 * B14's promotion scenario.
 *
 * The claim: a [CellFactory] minted by [ModuleHandle.cellFactory] is an ordinary
 * citizen of the graph-construction DSL — it can be carried in a [SpawnStep] of
 * a [GraphSpec], survive [GraphSpec.applyTo] against a live [ManagedHost], and
 * the cell it produces is the *module-loaded* class, links to a host-declared
 * cell through a [ConnectStep], and flows after apply.
 *
 * ### Non-vacuousness
 *
 * The factory the spec carries is proven module-loaded *before* it is handed to
 * the spec, by constructing one probe cell from that very object and asserting
 * `javaClass.classLoader === handle.classLoader`. `GraphSpec.applyTo` calls
 * `step.factory.create(ref)` on the same object through the same code path, so
 * the assertion covers what the spec spawns without the test having to wrap (and
 * thereby weaken) the factory the [SpawnStep] holds.
 *
 * The downstream is deliberately a **host-classpath** cell ([GraphSpecSink],
 * declared here on `:loader`'s own test classpath), so the `ConnectStep` crosses
 * the module/host classloader boundary — the same R2 point B1 makes.
 */
class ModuleGraphSpecTest {

    private companion object {
        const val FLOW_SET_CELL = "civictech.loader.fixture.flow.FlowSetCell"
    }

    @Test
    fun `a GraphSpec whose SpawnStep factory is a module cellFactory applies, links to a host cell and flows`() {
        FixtureJars.withLoadedModule(FixtureJars.flow) { handle ->
            val controller = SimulationController(seed = 3051)
            val host = ManagedHost(scheduler = controller.scheduler())

            val moduleFactory = handle.cellFactory(FLOW_SET_CELL)

            // Non-vacuousness, on the exact factory object the SpawnStep below carries:
            // this is the thing `applyTo` will call `create(ref)` on.
            val probe = moduleFactory.create(CellRef(UUID.randomUUID()))
            withClue("the factory the spec carries must resolve through the module's own classloader") {
                probe.javaClass.classLoader shouldBe handle.classLoader
            }
            withClue("...and not through the host's") {
                probe.javaClass.classLoader shouldNotBe ModuleGraphSpecTest::class.java.classLoader
            }
            probe.javaClass.name shouldBe FLOW_SET_CELL

            var sink: GraphSpecSink? = null
            val spec = GraphSpec(
                listOf(
                    SpawnStep(handle = "moduleCell", factory = moduleFactory),
                    SpawnStep(
                        handle = "sink",
                        factory = CellFactory { ref -> GraphSpecSink(ref).also { sink = it } },
                    ),
                    ConnectStep(from = "moduleCell", outlet = "outlet", to = "sink", inlet = "inlet"),
                )
            )

            val refs = spec.applyTo(host.managementInlet)
            controller.runToIdle()

            withClue("every declared step handle must have been bound to a ref by applyTo") {
                refs.keys shouldBe setOf("moduleCell", "sink")
            }
            val moduleRef = refs.getValue("moduleCell")

            // The spawned cell exists on the host: `lookup` answers null for a ref this
            // host does not hold (no LocationRegistry is attached here).
            val hosted = host.lookup<SetApi<String>>(moduleRef)
            withClue("the module cell must be live on the host after applyTo") { hosted.shouldNotBeNull() }
            withClue("an unspawned ref must NOT answer — otherwise the check above proves nothing") {
                host.lookup<SetApi<String>>(CellRef(UUID.randomUUID())) shouldBe null
            }

            // ...and it flows, driven through the host's own proxy for the HOST-declared
            // SetApi/SetOps contract (the module cell satisfies it because
            // ModuleClassLoader parent-delegates the shared `civictech.cell.*` prefix).
            hosted!!.inlet.call.add("spec-applied")
            controller.runToIdle()

            withClue("the ConnectStep's link must carry the module cell's emission to the host sink") {
                sink!!.seen.map { it.adds.keys } shouldBe listOf(setOf("spec-applied"))
            }
            withClue("the emission must have arrived with a wave stamp, like any host-to-host flow") {
                sink!!.stamped shouldBe listOf(true)
            }
        }
    }

    @Test
    fun `an Exact identity binding makes the module-spawned ref the one the spec declared`() {
        FixtureJars.withLoadedModule(FixtureJars.flow) { handle ->
            val controller = SimulationController(seed = 3052)
            val host = ManagedHost(scheduler = controller.scheduler())

            val exact = CellRef(UUID.fromString("00000000-0000-0000-0000-0000000003c1"))
            val spec = GraphSpec(
                listOf(
                    SpawnStep(
                        handle = "moduleCell",
                        factory = handle.cellFactory(FLOW_SET_CELL),
                        identity = IdentityBinding.Exact(exact),
                    )
                )
            )

            val refs = spec.applyTo(host.managementInlet)
            controller.runToIdle()

            withClue("applyTo must have bound the module cell to the ref the spec named") {
                refs.getValue("moduleCell") shouldBe exact
            }
            host.lookup<SetApi<String>>(exact).shouldNotBeNull()
        }
    }

    /** Host-classpath downstream: the far end of the spec's [ConnectStep]. */
    private class GraphSpecSink(override val ref: CellRef) : Cell {
        val seen = mutableListOf<SetDelta<String>>()
        val stamped = mutableListOf<Boolean>()

        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    seen += value
                    stamped += civictech.cell.CurrentContext.get() != null
                }
            })
        }
    }
}
