package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [KeyedCells] durability (spec 24, M10.4): the `demo/shopping` crash/restart
 * scenario, with the per-key bookkeeping owned by the framework instead of
 * hand-rolled in the app. A family of SetCell writers grows lazily; routed
 * (journaled) ops flow through them; the host is dropped (`kill -9` — only the
 * journal dir survives); a fresh host + [KeyedCells] on the SAME dir [recover]s
 * and the family comes back byte-identical — same membership AND same tags,
 * because deterministic refs plus pre-spawn-before-replay re-mint exactly the
 * tags the writers already produced.
 */
class KeyedCellsRecoveryTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private fun setFactory(): (String, civictech.cell.CellRef) -> Cell = { _, ref -> SetCell<String>(ref) }

    /** Routed (journaled) inlet ops for a spawned writer, via the registry proxy. */
    private fun opsFor(registry: LocationRegistry, cell: Cell): SetOps<String> =
        (HostedCellProxy.create(cell.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

    /** A comparable snapshot of a SetCell's OR-set tags (adds/dels), for tag-identity assertions. */
    @Suppress("UNCHECKED_CAST")
    private fun tags(cell: Cell): Pair<Map<String, Set<Timestamp>>, Map<String, Set<Timestamp>>> {
        val snap = (cell as SetCell<String>).snapshot() as Map<String, Any>
        return (snap["adds"] as Map<String, Set<Timestamp>>) to (snap["dels"] as Map<String, Set<Timestamp>>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun membership(cell: Cell): Set<String> = (cell as SetCell<String>).membership()

    @Test
    fun `crash then recover restores identical membership and tags`(@TempDir dir: File) {
        // — session 1: grow the family, run routed ops, journal them —
        val run1 = SimulationController(1)
        val reg1 = LocationRegistry()
        val host1 = ManagedHost(scheduler = run1.scheduler(), registry = reg1, journal = KeyedCells.hostJournal(dir))
        val keyed1 = KeyedCells<String>(host1, dir, "writer", setFactory())

        val alice1 = keyed1.getOrSpawn("alice")
        val bob1 = keyed1.getOrSpawn("bob")
        opsFor(reg1, alice1).add("milk")
        opsFor(reg1, alice1).add("eggs")
        opsFor(reg1, alice1).remove("milk") // added then removed: must stay removed on replay
        opsFor(reg1, bob1).add("bread")
        run1.runToIdle()

        val preAliceMembers = membership(alice1)
        val preBobMembers = membership(bob1)
        val preAliceTags = tags(alice1)
        val preBobTags = tags(bob1)
        preAliceMembers shouldBe setOf("eggs")
        preBobMembers shouldBe setOf("bread")

        // — kill -9: drop host, registry, queues, controller — only [dir] survives —
        val run2 = SimulationController(1)
        val reg2 = LocationRegistry()
        val host2 = ManagedHost(scheduler = run2.scheduler(), registry = reg2, journal = KeyedCells.hostJournal(dir))
        val keyed2 = KeyedCells<String>(host2, dir, "writer", setFactory())
        keyed2.recover() // pre-spawn every known key, THEN replay the WAL — in that order
        run2.runToIdle()

        // keys() is complete straight from the durable record, before any getOrSpawn
        keyed2.keys() shouldBe setOf("alice", "bob")

        val alice2 = keyed2.getOrSpawn("alice") // idempotent: the pre-spawned, replayed instance
        val bob2 = keyed2.getOrSpawn("bob")
        membership(alice2) shouldBe preAliceMembers
        membership(bob2) shouldBe preBobMembers
        // the headline: no tag re-mint — recovered tags are byte-identical to pre-crash
        tags(alice2) shouldBe preAliceTags
        tags(bob2) shouldBe preBobTags
    }

    @Test
    fun `recover pre-spawns before replay - wrong order loses the ops`(@TempDir dir: File) {
        // journal a removed element (the resurrection hazard) plus a survivor
        val run1 = SimulationController(1)
        val reg1 = LocationRegistry()
        val host1 = ManagedHost(scheduler = run1.scheduler(), registry = reg1, journal = KeyedCells.hostJournal(dir))
        val keyed1 = KeyedCells<String>(host1, dir, "writer", setFactory())
        val alice1 = keyed1.getOrSpawn("alice")
        opsFor(reg1, alice1).add("milk")
        opsFor(reg1, alice1).remove("milk")
        opsFor(reg1, alice1).add("eggs")
        run1.runToIdle()

        // correct order (recover = pre-spawn THEN replay): eggs present, milk stays removed
        val run2 = SimulationController(1)
        val reg2 = LocationRegistry()
        val host2 = ManagedHost(scheduler = run2.scheduler(), registry = reg2, journal = KeyedCells.hostJournal(dir))
        val keyed2 = KeyedCells<String>(host2, dir, "writer", setFactory())
        keyed2.recover()
        run2.runToIdle()
        membership(keyed2.getOrSpawn("alice")) shouldBe setOf("eggs")

        // WRONG order (replay BEFORE the cell exists): every frame dead-letters onto
        // an un-spawned ref, so the state is lost — the exact divergence recover()'s
        // ordering prevents. Proves the pre-spawn-before-replay rule earns its keep.
        val run3 = SimulationController(1)
        val reg3 = LocationRegistry()
        val host3 = ManagedHost(scheduler = run3.scheduler(), registry = reg3, journal = KeyedCells.hostJournal(dir))
        host3.recoverFrom(KeyedCells.hostJournal(dir)!!) // replay with no cells pre-spawned
        run3.runToIdle()
        val keyed3 = KeyedCells<String>(host3, dir, "writer", setFactory())
        membership(keyed3.getOrSpawn("alice")) shouldBe emptySet() // diverged: eggs lost
    }

    @Test
    fun `getOrSpawn is idempotent - same cell, single durable record, no live-ref guard`(@TempDir dir: File) {
        val run = SimulationController()
        val host = ManagedHost(scheduler = run.scheduler(), registry = LocationRegistry(), journal = KeyedCells.hostJournal(dir))
        val keyed = KeyedCells<String>(host, dir, "writer", setFactory())

        val first = keyed.getOrSpawn("x")
        val second = keyed.getOrSpawn("x") // must not re-spawn (would trip the live-ref guard) or re-record
        second shouldBeSameInstanceAs first
        second.ref shouldBe first.ref
        keyed.keys() shouldBe setOf("x")
        File(dir, KeyedCells.KEYS_FILE).readLines().filter { it.isNotBlank() } shouldBe listOf("x")
    }

    @Test
    fun `ephemeral mode works in memory and touches zero files`(@TempDir dir: File) {
        val run = SimulationController()
        val reg = LocationRegistry()
        // journalDir null on BOTH the host and the family: fully ephemeral
        val host = ManagedHost(scheduler = run.scheduler(), registry = reg, journal = null)
        val keyed = KeyedCells<String>(host, null, "writer", setFactory())

        val cell = keyed.getOrSpawn("a")
        opsFor(reg, cell).add("x")
        run.runToIdle()
        membership(cell) shouldBe setOf("x")
        keyed.keys() shouldBe setOf("a")
        keyed.recover() // no journal dir: a no-op, never throws

        dir.list()!!.toList() shouldBe emptyList() // not one file created
    }
}
