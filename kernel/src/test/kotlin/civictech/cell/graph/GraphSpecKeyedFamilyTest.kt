package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.InMemoryJournal
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
        val journal1 = KeyedCells.hostJournal(root)!!
        val host1 = ManagedHost(
            scheduler = controller1.scheduler(),
            registry = registry1,
            journal = journal1,
        )
        val spec = familySpec()
        val family1 = if (declared) {
            val applied = spec.apply(ApplyContext(host1, journalDirs = mapOf("d" to root), topology = journal1))
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
        val journal2 = KeyedCells.hostJournal(root)!!
        val host2 = ManagedHost(
            scheduler = controller2.scheduler(),
            registry = registry2,
            journal = journal2,
        )
        val family2 = if (declared) {
            val context = ApplyContext(host2, journalDirs = mapOf("d" to root), topology = journal2)
            context.recover(journal2)
            anyFamily(checkNotNull(context.familyFor("writers")))
        } else {
            anyFamily(KeyedCells<String>(host2, root, "demo-writer", { _, ref -> SetCell<String>(ref) }))
        }
        if (!declared) family2.recover()
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
        val declaredDir = dir.resolve("declared")
        val imperativeDir = dir.resolve("imperative")
        val declared = runFamily(declaredDir, declared = true)
        val imperative = runFamily(imperativeDir, declared = false)

        declared shouldBe imperative
        declared.aliceMembers shouldBe setOf("eggs")
        declared.bobMembers shouldBe setOf("bread")
        declared.aliceRef shouldBe CellRef(java.util.UUID.nameUUIDFromBytes("demo-writer:alice".toByteArray()))
        declaredDir.list()!!.toSet() shouldBe setOf(KeyedCells.HOST_JOURNAL)
        imperativeDir.list()!!.toSet() shouldBe setOf(KeyedCells.HOST_JOURNAL)
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
        val journal1 = KeyedCells.hostJournal(dir)!!
        val host1 = ManagedHost(
            scheduler = controller1.scheduler(),
            journal = journal1,
        )
        val family1 = anyFamily(
            spec.apply(ApplyContext(host1, journalDirs = mapOf("d" to dir), topology = journal1))
                .families.getValue("writers"),
        )
        val ref = family1.getOrSpawn(42L).ref

        val controller2 = SimulationController(seed = 4)
        val journal2 = KeyedCells.hostJournal(dir)!!
        val host2 = ManagedHost(
            scheduler = controller2.scheduler(),
            journal = journal2,
        )
        val context2 = ApplyContext(host2, journalDirs = mapOf("d" to dir), topology = journal2)
        context2.recover(journal2)
        controller2.runToIdle()
        val family2 = anyFamily(checkNotNull(context2.familyFor("writers")))
        family2.keys() shouldBe setOf(42L)
        family2.getOrSpawn(42L).ref shouldBe ref
        dir.list()!!.toSet() shouldBe setOf(KeyedCells.HOST_JOURNAL)
    }

    @Test
    fun `a family spec survives Java serialization with both built-in codecs`(@TempDir dir: File) {
        listOf(KeyCodec.Strings to "alice", KeyCodec.Longs to 42L).forEach { (codec, key) ->
            val spec = GraphSpec(
                listOf(
                    SpawnStep(
                        handle = "writers",
                        factory = KeyedCellFactory { _, ref -> SetCell<String>(ref) },
                        family = KeyedFamily("ser-writer", codec, "d"),
                    ),
                ),
            )
            val bytes = java.io.ByteArrayOutputStream()
                .also { java.io.ObjectOutputStream(it).use { out -> out.writeObject(spec) } }
                .toByteArray()
            val revived = java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)).readObject() as GraphSpec

            val journal = InMemoryJournal()
            val family = anyFamily(
                revived.apply(
                    ApplyContext(ManagedHost(journal = journal), journalDirs = mapOf("d" to dir), topology = journal),
                ).families.getValue("writers"),
            )
            val ref = family.getOrSpawn(key).ref
            ref shouldBe CellRef(java.util.UUID.nameUUIDFromBytes("ser-writer:$key".toByteArray()))

            val recoveredContext = ApplyContext(
                ManagedHost(journal = journal),
                journalDirs = mapOf("d" to dir),
                topology = journal,
            )
            recoveredContext.recover(journal).awaitApplied(30_000)
            anyFamily(checkNotNull(recoveredContext.familyFor("writers"))).keys() shouldBe setOf(key)
        }
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
