package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.InMemoryJournal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/** 12qyp-D5 — durable inputs are spawn parameters, not topology events. */
class GraphSpecDurableInputParameterTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun ops(registry: LocationRegistry, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

    @Test
    fun `declared input is exposed and recovers its cursor and batch`() {
        val ref = CellRef(UUID.nameUUIDFromBytes("durable-input".toByteArray()), 1)
        val journal = InMemoryJournal()
        val firstController = SimulationController(seed = 1)
        val firstRegistry = LocationRegistry()
        lateinit var firstContext: ApplyContext
        val firstHost = ManagedHost(
            scheduler = firstController.scheduler(),
            registry = firstRegistry,
            journalFor = { cellRef -> firstContext.journalFor(cellRef) },
        )
        firstContext = ApplyContext(firstHost, journals = mapOf("main" to journal))
        val firstCells = mutableMapOf<CellRef, SetCell<String>>()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "records",
                    factory = CellFactory { chosen -> SetCell<String>(chosen).also { firstCells[chosen] = it } },
                    identity = IdentityBinding.Exact(ref),
                    journalId = "main",
                    inputs = setOf("spend"),
                ),
            ),
        )

        val applied = spec.apply(firstContext)
        val input = applied.inputs.getValue("records").getValue("spend")
        input.committed() shouldBe null
        input.commit {
            ops(firstRegistry, ref).add("coffee")
            "cursor-1"
        } shouldBe "cursor-1"
        firstController.runToIdle()
        firstCells.getValue(ref).membership() shouldBe setOf("coffee")

        val secondController = SimulationController(seed = 2)
        val secondRegistry = LocationRegistry()
        lateinit var secondContext: ApplyContext
        val secondHost = ManagedHost(
            scheduler = secondController.scheduler(),
            registry = secondRegistry,
            journalFor = { cellRef -> secondContext.journalFor(cellRef) },
        )
        secondContext = ApplyContext(secondHost, journals = mapOf("main" to journal))
        val secondCells = mutableMapOf<CellRef, SetCell<String>>()
        val recoveredSpec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "records",
                    factory = CellFactory { chosen -> SetCell<String>(chosen).also { secondCells[chosen] = it } },
                    identity = IdentityBinding.Exact(ref),
                    journalId = "main",
                    inputs = setOf("spend"),
                ),
            ),
        )
        val recovered = recoveredSpec.apply(secondContext)
        val recoveredInput = recovered.inputs.getValue("records").getValue("spend")
        recoveredInput.committed() shouldBe null
        val recovery = secondHost.recoverFrom(journal)
        secondController.runToIdle()
        recovery.isApplied shouldBe true
        recoveredInput.committed() shouldBe "cursor-1"
        secondCells.getValue(ref).membership() shouldBe setOf("coffee")
    }

    @Test
    fun `inputs require a journal and cannot be declared on a family`() {
        val missingJournal = shouldThrow<IllegalArgumentException> {
            SpawnStep(
                "records",
                CellFactory { SetCell<String>(it) },
                inputs = setOf("spend"),
            )
        }
        missingJournal.message!! shouldContain "records"
        missingJournal.message!! shouldContain "inputs"

        val family = shouldThrow<IllegalArgumentException> {
            SpawnStep(
                handle = "records",
                factory = KeyedCellFactory { _, ref -> SetCell<String>(ref) },
                journalId = "main",
                family = KeyedFamily("records", journalId = "main"),
                inputs = setOf("spend"),
            )
        }
        family.message!! shouldContain "records"
        family.message!! shouldContain "inputs"
    }

    @Test
    fun `Use and remote application refuse declared inputs`() {
        val ref = CellRef(UUID.randomUUID(), 1)
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "records",
                    factory = CellFactory { SetCell<String>(it) },
                    identity = IdentityBinding.Exact(ref),
                    journalId = "main",
                    inputs = setOf("spend"),
                ),
            ),
        )
        val host = ManagedHost()

        val localFailure = shouldThrow<IllegalStateException> { spec.applyTo(host.managementInlet) }
        localFailure.message!! shouldContain "records"
        localFailure.message!! shouldContain "inputs"

        val remote = spec.applyRemote(host.managementInlet)
        val rejection = remote.results.getValue("records") as StepResult.Rejected
        rejection.reason shouldContain "records"
        rejection.reason shouldContain "inputs"
        host.lookup<SetCell<String>>(ref) shouldBe null
    }

    @Test
    fun `context graph builder records and exposes declared input`() {
        val journal = InMemoryJournal()
        lateinit var context: ApplyContext
        val host = ManagedHost(journalFor = { ref -> context.journalFor(ref) })
        context = ApplyContext(host, journals = mapOf("main" to journal))

        val (handle, built) = graphOf(context) {
            spawn("records", journalId = "main", inputs = setOf("spend")) { ref -> SetCell<String>(ref) }
        }

        val step = built.steps.single() as SpawnStep
        step.inputs shouldBe setOf("spend")
        host.durableInput(handle.ref, "spend").committed() shouldBe null
    }
}
