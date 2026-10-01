package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.DurabilityClass
import civictech.cell.durability.FileJournal
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.Interest
import civictech.cell.port.Use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID

/** x0oag-D6 — a graph journalId is bound before the host evaluates journalFor. */
class GraphSpecDurableParameterTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun ops(registry: LocationRegistry, ref: CellRef): SetOps<String> =
        (HostedCellProxy.create(ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

    private fun spec(ref: CellRef, cells: MutableMap<CellRef, SetCell<String>>): GraphSpec = GraphSpec(
        listOf(
            SpawnStep(
                handle = "s",
                factory = CellFactory { chosen -> SetCell<String>(chosen).also { cells[chosen] = it } },
                identity = IdentityBinding.Exact(ref),
                journalId = "j",
            ),
        ),
    )

    private data class RunResult(val membership: Set<String>, val stats: ManagedHost.DurabilityAccounting)

    private fun declaredRun(file: File): RunResult {
        val ref = CellRef(UUID.nameUUIDFromBytes("declared-durable".toByteArray()), 1)
        val journal = FileJournal(file)
        val controller = SimulationController(seed = 11)
        val registry = LocationRegistry()
        lateinit var context: ApplyContext
        val host = ManagedHost(
            scheduler = controller.scheduler(),
            registry = registry,
            journalFor = { cellRef -> context.journalFor(cellRef) },
        )
        context = ApplyContext(host, journals = mapOf("j" to journal))
        val cells = mutableMapOf<CellRef, SetCell<String>>()

        spec(ref, cells).apply(context)
        controller.runToIdle()
        ops(registry, ref).add("apple")
        ops(registry, ref).add("banana")
        controller.runToIdle()
        val beforeCrash = cells.getValue(ref).membership()
        val stats = host.durabilityAccounting()

        val recoveredJournal = FileJournal(file)
        val recoveredController = SimulationController(seed = 12)
        val recoveredRegistry = LocationRegistry()
        lateinit var recoveredContext: ApplyContext
        val recoveredHost = ManagedHost(
            scheduler = recoveredController.scheduler(),
            registry = recoveredRegistry,
            journalFor = { cellRef -> recoveredContext.journalFor(cellRef) },
        )
        recoveredContext = ApplyContext(recoveredHost, journals = mapOf("j" to recoveredJournal))
        val recoveredCells = mutableMapOf<CellRef, SetCell<String>>()
        spec(ref, recoveredCells).apply(recoveredContext)
        recoveredController.runToIdle()
        recoveredHost.recoverFrom(recoveredJournal)
        recoveredController.runToIdle()

        recoveredCells.getValue(ref).membership() shouldBe beforeCrash
        recoveredHost.durabilityAccounting().journaledSpawns[DurabilityClass.SYNCHRONOUS] shouldBe
            stats.journaledSpawns[DurabilityClass.SYNCHRONOUS]
        return RunResult(recoveredCells.getValue(ref).membership(), stats)
    }

    private fun imperativeRun(file: File): RunResult {
        val ref = CellRef(UUID.nameUUIDFromBytes("imperative-durable".toByteArray()), 1)
        val journal = FileJournal(file)
        val controller = SimulationController(seed = 21)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, journal = journal)
        val cell = SetCell<String>(ref)
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()
        ops(registry, ref).add("apple")
        ops(registry, ref).add("banana")
        controller.runToIdle()
        val beforeCrash = cell.membership()
        val stats = host.durabilityAccounting()

        val recoveredJournal = FileJournal(file)
        val recoveredController = SimulationController(seed = 22)
        val recoveredRegistry = LocationRegistry()
        val recoveredHost = ManagedHost(
            scheduler = recoveredController.scheduler(),
            registry = recoveredRegistry,
            journal = recoveredJournal,
        )
        val recovered = SetCell<String>(ref)
        recoveredHost.managementInlet.call.spawn(recovered)
        recoveredController.runToIdle()
        recoveredHost.recoverFrom(recoveredJournal)
        recoveredController.runToIdle()

        recovered.membership() shouldBe beforeCrash
        return RunResult(recovered.membership(), stats)
    }

    @Test
    fun `declared journalId recovers the same membership and accounting as imperative journal spawn`(@TempDir dir: File) {
        val declared = declaredRun(dir.resolve("declared.journal"))
        val imperative = imperativeRun(dir.resolve("imperative.journal"))

        declared.membership shouldBe setOf("apple", "banana")
        declared shouldBe imperative
    }

    @Test
    fun `InstanceSpec journalId survives lowering onto SpawnStep`() {
        val logicalId = UUID.nameUUIDFromBytes("durable-instance-set".toByteArray())
        val lowered = InstanceSetStep(
            handle = "items",
            logicalId = logicalId,
            factory = InstanceFactory { ref, _ -> SetCell<String>(ref) },
            instances = listOf(InstanceSpec(Interest.Total, instanceId = 0, journalId = "j")),
        ).lower().filterIsInstance<SpawnStep>()

        lowered.single().journalId shouldBe "j"
    }

    @Test
    fun `missing journalId refuses before any spawn and names the handle and id`() {
        val host = ManagedHost()
        val first = CellRef(UUID.randomUUID(), 1)
        val missing = CellRef(UUID.randomUUID(), 2)
        val spec = GraphSpec(
            listOf(
                SpawnStep("first", CellFactory { SetCell<String>(it) }, IdentityBinding.Exact(first)),
                SpawnStep(
                    "s",
                    CellFactory { SetCell<String>(it) },
                    IdentityBinding.Exact(missing),
                    journalId = "missing",
                ),
            ),
        )

        val failure = shouldThrow<IllegalStateException> { spec.apply(ApplyContext(host)) }
        failure.message!! shouldContain "'s'"
        failure.message!! shouldContain "journalId"
        failure.message!! shouldContain "missing"
        host.lookup<SetCell<String>>(first) shouldBe null
        host.lookup<SetCell<String>>(missing) shouldBe null
    }

    @Test
    fun `Use and remote application reject journalId while context builder applies it`(@TempDir dir: File) {
        val ref = CellRef(UUID.randomUUID(), 1)
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    "s",
                    CellFactory { SetCell<String>(it) },
                    IdentityBinding.Exact(ref),
                    journalId = "j",
                ),
            ),
        )
        val host = ManagedHost()
        val localFailure = shouldThrow<IllegalStateException> { spec.applyTo(host.managementInlet) }
        localFailure.message!! shouldContain "s"
        localFailure.message!! shouldContain "journalId"

        val remote = spec.applyRemote(host.managementInlet)
        val rejection = remote.results.getValue("s") as StepResult.Rejected
        rejection.reason shouldContain "journalId"
        host.lookup<SetCell<String>>(ref) shouldBe null

        lateinit var context: ApplyContext
        val journal = FileJournal(dir.resolve("builder.journal"))
        val controller = SimulationController(seed = 31)
        val contextHost = ManagedHost(
            scheduler = controller.scheduler(),
            journalFor = { cellRef -> context.journalFor(cellRef) },
        )
        context = ApplyContext(contextHost, journals = mapOf("j" to journal))
        val (_, builtSpec) = graphOf(context) {
            spawn("built", journalId = "j") { SetCell<String>(it) }
        }
        (builtSpec.steps.single() as SpawnStep).journalId shouldBe "j"
        contextHost.durabilityAccounting().journaledSpawns[DurabilityClass.SYNCHRONOUS] shouldBe 1L
    }
}
