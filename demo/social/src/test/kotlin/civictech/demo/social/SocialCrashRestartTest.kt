package civictech.demo.social

import civictech.cell.host.KeyedCells
import civictech.cell.host.SimulationController
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crash-restart of a `--journal` [SocialApp] (SOC1 F7, feature
 * `computenet-v10ou`): B16 here; B17 (a truncated `keys` log refuses to
 * serve) belongs to the same feature's refusal task. Rules: [SOC1-DUR-01]
 * (every family journals through the shared root WAL beside its own `keys`
 * log) and [SOC1-DUR-03] (a restart reproduces the graph a fresh process gets
 * from the same event prefix, with no removed element resurrected).
 *
 * **In-process, by decision 07k-D7.** Each "process" is a [SocialApp] on its
 * own [SimulationController] over one `@TempDir`; the crash is simply ceasing
 * to reference app 1 — no `stop()`, no checkpoint, nothing flushed beyond what
 * the WAL already holds. A multi-JVM (`JvmPeer`) variant is a non-goal: every
 * WAL append and every `keys` line is fsync'd at write time, so what survives
 * a real process death is exactly what app 2 reads here, and the property
 * under test — replay over pre-spawned keys equals the live graph — does not
 * depend on the process boundary.
 */
class SocialCrashRestartTest {

    @Test
    fun `B16 an in-process restart equals the pre-drop snapshot and a fresh replay of the same prefix`(@TempDir dir: File) {
        val source = SnbGenerator(42, 0.05)

        // --- app 1: load + N events + one removal, then drop ------------------
        val c1 = SimulationController(42)
        val app1 = SocialApp(port = 0, journalDir = dir, scheduler = c1.scheduler())
        SocialLoader.load(source, app1.graph)
        val stream1 = UpdateStream(source, app1.graph)
        val n = stream1.remaining / 2
        assertTrue(n >= 1, "the seed-42 stream must have at least two events, had ${stream1.remaining}")
        stream1.step(n)
        c1.runToIdle()

        val removed = BatchModel.observe(app1.graph).knows.entries
            .first { it.value.isNotEmpty() }
            .let { (a, edges) -> Triple(a, edges.first().otherId, edges.first().creationDate) }
        val (a, b, date) = removed
        app1.graph.removeKnows(a, b, date)
        c1.runToIdle()

        val snap1 = BatchModel.observe(app1.graph)
        assertFalse(snap1.knows[a].orEmpty().any { it.otherId == b }, "app 1 applied the removal of $a-$b")

        // [SOC1-DUR-01]: one root WAL, one keys log per family, all non-empty.
        val wal = File(dir, KeyedCells.HOST_JOURNAL)
        assertTrue(wal.isFile && wal.length() > 0, "WAL $wal exists and is non-empty")
        for (family in listOf("person", "authored", "forum", "message")) {
            val keys = File(File(dir, family), KeyedCells.KEYS_FILE)
            assertTrue(keys.isFile && keys.length() > 0, "keys log $keys exists and is non-empty")
        }
        // Drop app 1: no stop(), no checkpoint — it is simply never referenced again.

        // --- app 2: recover from the same directory -------------------------
        val c2 = SimulationController(42)
        val app2 = SocialApp(port = 0, journalDir = dir, scheduler = c2.scheduler())
        c2.runToIdle()
        app2.completeRecovery()
        app2.completeRecovery() // idempotent
        // Never started: no DemoShell was built, so no port is bound (v10ou-D3).
        assertFailsWith<IllegalStateException> { app2.boundPort }
        val snap2 = BatchModel.observe(app2.graph)

        // --- app 3: a fresh, ephemeral app fed the same prefix + removal ----
        val c3 = SimulationController(42)
        val app3 = SocialApp(port = 0, scheduler = c3.scheduler())
        SocialLoader.load(source, app3.graph)
        UpdateStream(source, app3.graph).step(n)
        app3.graph.removeKnows(a, b, date)
        c3.runToIdle()
        val snap3 = BatchModel.observe(app3.graph)

        assertEquals(snap1.personIds, app2.graph.personIds(), "recovered personIds")
        assertTrue(snap1.personIds.isNotEmpty(), "the prefix produced persons to recover")
        snap2.byName().zip(snap1.byName()).forEach { (recovered, before) ->
            assertEquals(before.second, recovered.second, "relation=${before.first}: recovered != pre-drop snapshot")
        }
        snap2.byName().zip(snap3.byName()).forEach { (recovered, fresh) ->
            assertEquals(fresh.second, recovered.second, "relation=${fresh.first}: recovered != fresh replay of the prefix")
        }
        assertEquals(snap1, snap2)
        assertEquals(snap3, snap2)

        // No resurrection: the removed edge is absent on both endpoints.
        assertFalse(snap2.knows[a].orEmpty().any { it.otherId == b }, "removed edge $a->$b resurrected")
        assertFalse(snap2.knows[b].orEmpty().any { it.otherId == a }, "removed edge $b->$a resurrected")
    }
}
