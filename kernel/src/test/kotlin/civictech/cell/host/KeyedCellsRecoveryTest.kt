package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.graph.TopoEvent
import civictech.cell.port.Use
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** [KeyedCells] membership is journal topology, ordered before each key's frames. */
class KeyedCellsRecoveryTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun setFactory(): (String, CellRef) -> Cell = { _, ref -> SetCell<String>(ref) }

    private fun opsFor(registry: LocationRegistry, cell: Cell): SetOps<String> =
        (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

    @Suppress("UNCHECKED_CAST")
    private fun tags(cell: Cell): Pair<Map<String, Set<Timestamp>>, Map<String, Set<Timestamp>>> {
        val snap = (cell as SetCell<String>).snapshot() as Map<String, Any>
        return (snap["adds"] as Map<String, Set<Timestamp>>) to (snap["dels"] as Map<String, Set<Timestamp>>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun membership(cell: Cell): Set<String> = (cell as SetCell<String>).membership()

    private fun familyKeys(journal: Journal): List<TopoEvent.FamilyKey> =
        journal.replay()
            .map(JournalRecords::decode)
            .filterIsInstance<DecodedJournalRecord.Topology>()
            .flatMap { it.events }
            .filterIsInstance<TopoEvent.FamilyKey>()

    @Test
    fun `crash then recover restores identical membership and tags`(@TempDir dir: File) {
        val run1 = SimulationController(1)
        val reg1 = LocationRegistry()
        val journal1 = KeyedCells.hostJournal(dir)!!
        val host1 = ManagedHost(scheduler = run1.scheduler(), registry = reg1, journal = journal1)
        val keyed1 = KeyedCells(host1, dir, "writer", setFactory())

        val alice1 = keyed1.getOrSpawn("alice")
        val bob1 = keyed1.getOrSpawn("bob")
        opsFor(reg1, alice1).add("milk")
        opsFor(reg1, alice1).add("eggs")
        opsFor(reg1, alice1).remove("milk")
        opsFor(reg1, bob1).add("bread")
        run1.runToIdle()

        val preAliceMembers = membership(alice1)
        val preBobMembers = membership(bob1)
        val preAliceTags = tags(alice1)
        val preBobTags = tags(bob1)

        val run2 = SimulationController(1)
        val reg2 = LocationRegistry()
        val host2 = ManagedHost(scheduler = run2.scheduler(), registry = reg2, journal = KeyedCells.hostJournal(dir))
        val keyed2 = KeyedCells(host2, dir, "writer", setFactory())
        keyed2.keys() shouldBe emptySet()
        keyed2.recover()
        run2.runToIdle()

        keyed2.keys() shouldBe setOf("alice", "bob")
        val alice2 = keyed2.getOrSpawn("alice")
        val bob2 = keyed2.getOrSpawn("bob")
        membership(alice2) shouldBe preAliceMembers
        membership(bob2) shouldBe preBobMembers
        tags(alice2) shouldBe preAliceTags
        tags(bob2) shouldBe preBobTags
    }

    @Test
    fun `stripping FamilyKey records dead letters the key frames`(@TempDir dir: File) {
        val run1 = SimulationController(1)
        val reg1 = LocationRegistry()
        val journal = KeyedCells.hostJournal(dir)!!
        val host1 = ManagedHost(scheduler = run1.scheduler(), registry = reg1, journal = journal)
        val keyed1 = KeyedCells(host1, dir, "writer", setFactory())
        val alice1 = keyed1.getOrSpawn("alice")
        opsFor(reg1, alice1).add("eggs")
        run1.runToIdle()

        val stripped = InMemoryJournal().also { copy ->
            copy.reset(
                journal.replay().filterNot { record ->
                    val decoded = JournalRecords.decode(record)
                    decoded is DecodedJournalRecord.Topology && decoded.events.any { it is TopoEvent.FamilyKey }
                },
            )
        }
        val run2 = SimulationController(2)
        val host2 = ManagedHost(scheduler = run2.scheduler(), registry = LocationRegistry(), journal = stripped)
        val keyed2 = KeyedCells(host2, null, "writer", setFactory())
        host2.recoverFrom(stripped)
        run2.runToIdle()

        keyed2.keys() shouldBe emptySet()
        host2.supervisionAccounting().deadLetters shouldBe 1
    }

    @Test
    fun `getOrSpawn is idempotent - same cell and one topology record with no side file`(@TempDir dir: File) {
        val run = SimulationController()
        val journal = KeyedCells.hostJournal(dir)!!
        val host = ManagedHost(scheduler = run.scheduler(), registry = LocationRegistry(), journal = journal)
        val keyed = KeyedCells(host, dir, "writer", setFactory())

        val first = keyed.getOrSpawn("x")
        val second = keyed.getOrSpawn("x")
        second shouldBeSameInstanceAs first
        second.ref shouldBe first.ref
        keyed.keys() shouldBe setOf("x")
        File(dir, "keys").exists() shouldBe false
        familyKeys(journal) shouldBe listOf(TopoEvent.FamilyKey("writer", "x"))
    }

    @Test
    fun `contains becomes complete when journal recovery applies its key records`(@TempDir dir: File) {
        val run1 = SimulationController(1)
        val host1 = ManagedHost(scheduler = run1.scheduler(), journal = KeyedCells.hostJournal(dir))
        val keyed1 = KeyedCells(host1, dir, "writer", setFactory())
        keyed1.contains("alice") shouldBe false
        keyed1.getOrSpawn("alice")
        keyed1.contains("alice") shouldBe true

        val run2 = SimulationController(2)
        val host2 = ManagedHost(scheduler = run2.scheduler(), journal = KeyedCells.hostJournal(dir))
        val keyed2 = KeyedCells(host2, dir, "writer", setFactory())
        keyed2.contains("alice") shouldBe false
        keyed2.recover()
        run2.runToIdle()
        keyed2.contains("alice") shouldBe true
        keyed2.keys() shouldBe setOf("alice")
    }

    @Test
    fun `recovery decodes a rendered Long before getOrSpawn`() {
        val journal = InMemoryJournal()
        val host1 = ManagedHost(journal = journal)
        val keyed1 = KeyedCells<Long>(
            host1, null, "long-writer",
            factory = { key, ref -> check(key == 42L); SetCell<String>(ref) },
            render = Long::toString,
            parse = String::toLong,
        )
        val ref = keyed1.getOrSpawn(42L).ref

        val host2 = ManagedHost(journal = journal)
        val keyed2 = KeyedCells<Long>(
            host2, null, "long-writer",
            factory = { key, cellRef -> check(key == 42L); SetCell<String>(cellRef) },
            render = Long::toString,
            parse = String::toLong,
        )
        host2.recoverFrom(journal).awaitApplied(30_000)

        keyed2.keys() shouldBe setOf(42L)
        keyed2.getOrSpawn(42L).ref shouldBe ref
    }

    @Test
    fun `checkpoint topology keeps family membership and state`() {
        val journal = InMemoryJournal()
        val registry1 = LocationRegistry()
        val host1 = ManagedHost(registry = registry1, journal = journal)
        val keyed1 = KeyedCells(host1, null, "writer", setFactory())
        val cell1 = keyed1.getOrSpawn("alice")
        opsFor(registry1, cell1).add("eggs")
        host1.quiescence().await(30_000, "family write")

        host1.checkpoint(journal)
        val first = JournalRecords.decode(journal.replay().first()) as DecodedJournalRecord.Topology
        first.events.filterIsInstance<TopoEvent.FamilyKey>() shouldBe
            listOf(TopoEvent.FamilyKey("writer", "alice"))

        val host2 = ManagedHost(registry = LocationRegistry(), journal = journal)
        val keyed2 = KeyedCells(host2, null, "writer", setFactory())
        host2.recoverFrom(journal).awaitApplied(30_000)
        keyed2.keys() shouldBe setOf("alice")
        membership(keyed2.getOrSpawn("alice")) shouldBe setOf("eggs")
    }

    @Test
    fun `a second family registration refuses the duplicate namespace`() {
        val host = ManagedHost()
        KeyedCells(host, null, "writer", setFactory())
        val failure = shouldThrow<IllegalStateException> {
            KeyedCells(host, null, "writer", setFactory())
        }
        failure.message shouldContain "writer"
    }

    @Test
    fun `ephemeral mode works in memory and touches zero files`(@TempDir dir: File) {
        val run = SimulationController()
        val reg = LocationRegistry()
        val host = ManagedHost(scheduler = run.scheduler(), registry = reg, journal = null)
        val keyed = KeyedCells(host, null, "writer", setFactory())

        val cell = keyed.getOrSpawn("a")
        opsFor(reg, cell).add("x")
        run.runToIdle()
        membership(cell) shouldBe setOf("x")
        keyed.keys() shouldBe setOf("a")
        keyed.recover()

        dir.list()!!.toList() shouldBe emptyList()
    }
}
