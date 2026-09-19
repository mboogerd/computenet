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

    /** App 1's run over [dir]: load + half the stream + one `removeKnows`, then dropped. */
    private class Crashed(val n: Int, val a: Long, val b: Long, val date: Long, val snapshot: BatchModel.Relations)

    /**
     * Runs "process 1" on its own [SimulationController]: loads the seed-42
     * slice, applies N = half the update stream, removes one live `knows`
     * edge, settles, snapshots, asserts [SOC1-DUR-01]'s on-disk layout, and
     * then drops the app — no `stop()`, no checkpoint.
     */
    private fun crashAfterPrefix(dir: File): Crashed {
        val c1 = SimulationController(42)
        val app1 = SocialApp(port = 0, journalDir = dir, scheduler = c1.scheduler())
        SocialLoader.load(SOURCE, app1.graph)
        val stream1 = UpdateStream(SOURCE, app1.graph)
        val n = stream1.remaining / 2
        assertTrue(n >= 1, "the seed-42 stream must have at least two events, had ${stream1.remaining}")
        stream1.step(n)
        c1.runToIdle()

        val (a, edge) = BatchModel.observe(app1.graph).knows.entries
            .first { it.value.isNotEmpty() }
            .let { (a, edges) -> a to edges.first() }
        app1.graph.removeKnows(a, edge.otherId, edge.creationDate)
        c1.runToIdle()

        val snap1 = BatchModel.observe(app1.graph)
        assertTrue(snap1.personIds.isNotEmpty(), "the prefix produced persons to recover")
        assertFalse(snap1.knows[a].orEmpty().any { it.otherId == edge.otherId }, "app 1 applied the removal")

        // [SOC1-DUR-01]: one root WAL, one keys log per family, all non-empty.
        val wal = File(dir, KeyedCells.HOST_JOURNAL)
        assertTrue(wal.isFile && wal.length() > 0, "WAL $wal exists and is non-empty")
        for (family in listOf("person", "authored", "forum", "message")) {
            val keys = File(File(dir, family), KeyedCells.KEYS_FILE)
            assertTrue(keys.isFile && keys.length() > 0, "keys log $keys exists and is non-empty")
        }
        return Crashed(n, a, edge.otherId, edge.creationDate, snap1)
    }

    /** Asserts [recovered] equals [expected] relation by relation, so a failure names the relation. */
    private fun assertRelations(expected: BatchModel.Relations, recovered: BatchModel.Relations, label: String) {
        expected.byName().zip(recovered.byName()).forEach { (e, r) ->
            assertEquals(e.second, r.second, "relation=${e.first}: recovered != $label")
        }
        assertEquals(expected, recovered)
    }

    private fun assertNotResurrected(crashed: Crashed, recovered: BatchModel.Relations) {
        assertFalse(recovered.knows[crashed.a].orEmpty().any { it.otherId == crashed.b }, "removed edge ${crashed.a}->${crashed.b} resurrected")
        assertFalse(recovered.knows[crashed.b].orEmpty().any { it.otherId == crashed.a }, "removed edge ${crashed.b}->${crashed.a} resurrected")
    }

    @Test
    fun `B16 an in-process restart equals the pre-drop snapshot and a fresh replay of the same prefix`(@TempDir dir: File) {
        val crashed = crashAfterPrefix(dir)

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
        SocialLoader.load(SOURCE, app3.graph)
        UpdateStream(SOURCE, app3.graph).step(crashed.n)
        app3.graph.removeKnows(crashed.a, crashed.b, crashed.date)
        c3.runToIdle()
        val snap3 = BatchModel.observe(app3.graph)

        assertEquals(crashed.snapshot.personIds, app2.graph.personIds(), "recovered personIds")
        assertRelations(crashed.snapshot, snap2, "pre-drop snapshot")
        assertRelations(snap3, snap2, "fresh replay of the prefix")
        assertNotResurrected(crashed, snap2)
    }

    /**
     * The production path: app 2 on the default `VirtualThreadScheduler`,
     * whose drain thread runs WHILE [SocialRecovery.stage] replays. [SocialApp.start]
     * must drain the staged replay behind its quiescence fence and complete
     * recovery before it binds a port, so the graph is settled the moment
     * `start()` returns — read here with no polling.
     *
     * This is also the case that discriminates `stage()`'s order. On a
     * `SimulationController` both halves only enqueue (spawn at management
     * priority 0, replayed frames at data priority 20) and nothing runs until
     * the test steps, so swapping them is invisible there; here the live drain
     * thread dispatches replayed frames as they are submitted, and a frame that
     * reaches a not-yet-spawned cell is dead-lettered.
     */
    @Test
    fun `B16 on the production scheduler start drains the replay before it binds and serves the recovered graph`(@TempDir dir: File) {
        val crashed = crashAfterPrefix(dir)

        val app2 = SocialApp(port = 0, journalDir = dir)
        assertFailsWith<IllegalStateException> { app2.boundPort }
        app2.start()
        try {
            assertTrue(app2.boundPort > 0, "bound after recovery completed")
            val snap2 = BatchModel.observe(app2.graph)
            assertRelations(crashed.snapshot, snap2, "pre-drop snapshot")
            assertNotResurrected(crashed, snap2)
        } finally {
            app2.stop()
        }
    }

    private companion object {
        val SOURCE = SnbGenerator(42, 0.05)
    }
}
