package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** x0oag-D9 — keyed families are graph spawn parameters, not a new step verb. */
class GraphSpecKeyedFamilyTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private data class FamilySnapshot(
        val aliceRef: CellRef,
        val aliceMembers: Set<String>,
        val bobMembers: Set<String>,
        val aliceTags: Pair<Map<String, Set<Timestamp>>, Map<String, Set<Timestamp>>>,
        val bobTags: Pair<Map<String, Set<Timestamp>>, Map<String, Set<Timestamp>>>,
    )

    private fun setFactory() = KeyedCellFactory { _, ref -> SetCell<String>(ref) }

    @Suppress("UNCHECKED_CAST")
    private fun anyFamily(family: KeyedCells<*>): KeyedCells<Any> = family as KeyedCells<Any>

    private fun opsFor(registry: LocationRegistry, cell: Cell): SetOps<String> =
        (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

    @Suppress("UNCHECKED_CAST")
    private fun tags(cell: Cell): Pair<Map<String, Set<Timestamp>>, Map<String, Set<Timestamp>>> {
        val snapshot = (cell as SetCell<String>).snapshot() as Map<String, Any>
        return snapshot["adds"] as Map<String, Set<Timestamp>> to
            (snapshot["dels"] as Map<String, Set<Timestamp>>)
    }

    private fun membership(cell: Cell): Set<String> = (cell as SetCell<String>).membership()

    private fun familySpec(): GraphSpec = GraphSpec(
        listOf(
            SpawnStep(
                handle = "writers",
                factory = setFactory(),
                family = KeyedFamily(namespace = "demo-writer", journalId = "d"),
            ),
        ),
    )

    private fun runFamily(root: File, declared: Boolean): FamilySnapshot {
        val controller1 = SimulationController(seed = 1)
        val registry1 = LocationRegistry()
        val host1 = ManagedHost(
            scheduler = controller1.scheduler(),
            registry = registry1,
            journal = KeyedCells.hostJournal(root),
        )
        val spec = familySpec()
        val family1 = if (declared) {
            val applied = spec.apply(ApplyContext(host1, journalDirs = mapOf("d" to root)))
            applied.refs shouldBe emptyMap()
            applied.families.keys shouldBe setOf("writers")
            anyFamily(applied.families.getValue("writers"))
        } else {
            anyFamily(KeyedCells<String>(host1, root, "demo-writer", { _, ref -> SetCell<String>(ref) }))
        }

        val alice1 = family1.getOrSpawn("alice")
        val bob1 = family1.getOrSpawn("bob")
        opsFor(registry1, alice1).add("milk")
        opsFor(registry1, alice1).add("eggs")
        opsFor(registry1, alice1).remove("milk")
        opsFor(registry1, bob1).add("bread")
        controller1.runToIdle()

        val controller2 = SimulationController(seed = 2)
        val registry2 = LocationRegistry()
        val host2 = ManagedHost(
            scheduler = controller2.scheduler(),
            registry = registry2,
            journal = KeyedCells.hostJournal(root),
        )
        val family2 = if (declared) {
            anyFamily(spec.apply(ApplyContext(host2, journalDirs = mapOf("d" to root))).families.getValue("writers"))
        } else {
            anyFamily(KeyedCells<String>(host2, root, "demo-writer", { _, ref -> SetCell<String>(ref) }))
        }
        family2.recover()
        controller2.runToIdle()

        val alice2 = family2.getOrSpawn("alice")
        val bob2 = family2.getOrSpawn("bob")
        family2.keys() shouldBe setOf("alice", "bob")
        return FamilySnapshot(
            aliceRef = alice2.ref,
            aliceMembers = membership(alice2),
            bobMembers = membership(bob2),
            aliceTags = tags(alice2),
            bobTags = tags(bob2),
        )
    }

    @Test
    fun `declared family matches imperative family through crash and recover`(@TempDir dir: File) {
        val declared = runFamily(dir.resolve("declared"), declared = true)
        val imperative = runFamily(dir.resolve("imperative"), declared = false)

        declared shouldBe imperative
        declared.aliceMembers shouldBe setOf("eggs")
        declared.bobMembers shouldBe setOf("bread")
        declared.aliceRef shouldBe CellRef(java.util.UUID.nameUUIDFromBytes("demo-writer:alice".toByteArray()))
    }

    @Test
    fun `Longs codec round trips keys and deterministic refs after recover`(@TempDir dir: File) {
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "writers",
                    factory = KeyedCellFactory { _, ref -> SetCell<Long>(ref) },
                    family = KeyedFamily("long-writer", KeyCodec.Longs, "d"),
                ),
            ),
        )
        val controller1 = SimulationController(seed = 3)
        val host1 = ManagedHost(
            scheduler = controller1.scheduler(),
            journal = KeyedCells.hostJournal(dir),
        )
        val family1 = anyFamily(spec.apply(ApplyContext(host1, journalDirs = mapOf("d" to dir))).families.getValue("writers"))
        val ref = family1.getOrSpawn(42L).ref

        val controller2 = SimulationController(seed = 4)
        val host2 = ManagedHost(
            scheduler = controller2.scheduler(),
            journal = KeyedCells.hostJournal(dir),
        )
        val family2 = anyFamily(spec.apply(ApplyContext(host2, journalDirs = mapOf("d" to dir))).families.getValue("writers"))
        family2.keys() shouldBe setOf(42L)
        family2.recover()
        controller2.runToIdle()
        family2.getOrSpawn(42L).ref shouldBe ref
    }

    @Test
    fun `family parameter refuses unsupported paths and missing journal directories`(@TempDir dir: File) {
        val spec = familySpec()

        val useFailure = shouldThrow<IllegalStateException> {
            spec.applyTo(ManagedHost().managementInlet)
        }
        useFailure.message!! shouldContain "writers"
        useFailure.message!! shouldContain "family"

        val report = spec.applyRemote(ManagedHost().managementInlet)
        val rejection = report.results.getValue("writers") as StepResult.Rejected
        rejection.reason shouldContain "family"

        val missing = shouldThrow<IllegalStateException> {
            spec.apply(ApplyContext(ManagedHost(), journalDirs = emptyMap()))
        }
        missing.message!! shouldContain "writers"
        missing.message!! shouldContain "family.journalId"
        missing.message!! shouldContain "d"

        val plainFactory = shouldThrow<IllegalArgumentException> {
            SpawnStep(
                handle = "bad",
                factory = CellFactory { ref -> SetCell<String>(ref) },
                family = KeyedFamily("bad"),
            )
        }
        plainFactory.message!! shouldContain "bad"
        plainFactory.message!! shouldContain "KeyedCellFactory"

        val linkFailure = shouldThrow<IllegalStateException> {
            GraphSpec(listOf(spec.steps.single(), ConnectStep("writers", "outlet", "writers", "inlet")))
                .apply(ApplyContext(ManagedHost(), journalDirs = mapOf("d" to dir)))
        }
        linkFailure.message!! shouldContain "writers"
        linkFailure.message!! shouldContain "single port"
    }

    @Test
    fun `context builder constructs family and Use builder refuses it`(@TempDir dir: File) {
        val host = ManagedHost(journal = KeyedCells.hostJournal(dir))
        val (family, spec) = graphOf(ApplyContext(host, journalDirs = mapOf("d" to dir))) {
            family("writers", "demo-writer", journalId = "d", factory = setFactory())
        }

        family.keys() shouldBe emptySet()
        (spec.steps.single() as SpawnStep).family shouldBe KeyedFamily("demo-writer", journalId = "d")

        val failure = shouldThrow<IllegalStateException> {
            graph(ManagedHost().managementInlet) {
                family("writers", "demo-writer", factory = setFactory())
            }
        }
        failure.message!! shouldContain "writers"
        failure.message!! shouldContain "family"
    }
}
