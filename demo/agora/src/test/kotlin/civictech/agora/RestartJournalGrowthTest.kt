package civictech.agora

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.cell.durability.scanJournalFile
import civictech.cell.host.DecodedJournalRecord
import civictech.cell.host.JournalRecords
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * computenet-xy7w4.4 (feature computenet-xy7w4 rule R-F, design xy7w4-D5): the
 * end-to-end proof computenet-vcrc7 asked for — a small agora graph, seeded
 * once, then restarted repeatedly with no new traffic, leaves the journal's
 * record count unchanged and the recovered credences equal to the
 * pre-restart ones.
 *
 * observed at main e787f6f5 (before computenet-xy7w4.1's D1/D2 fix landed):
 * `ManagedHost.recoverFrom` replayed derived frames with emission on, and
 * only the synchronous staging loop ran with `recovering = true`, so the
 * later delivery of a replayed frame re-journaled its same-host
 * re-emissions as if they were live — every restart re-journaled the whole
 * derived history (vcrc7 measured 15x over 4 restarts on an agora-style
 * graph). Against that kernel this test's [baseline]-vs-restart record-count
 * assertion reds (kept in the bead's implementation comment as the
 * red-before-green evidence); computenet-xy7w4.1 is a dependency of this
 * task specifically so the fix is present here.
 *
 * `AgoraApp` deliberately keeps its host private (xy7w4-D5's acceptance:
 * this task may add exactly the `host.checkpoint(journal)` call to
 * `AgoraApp.kt` and touch nothing else), so this test observes the app only
 * through its public surface (`service`, `start`, `stop`) — the same idiom
 * [AgoraServerTest] and [DurabilityTest] use. There is no fence for LIVE
 * traffic settling equivalent to `Recovery.awaitApplied()` (that fence
 * covers replay only), so [awaitStableCredences] polls `service.graph()`
 * until it reads the same map several times running. That is sound here
 * because the seeded graph is a single non-cyclic edge with no further
 * stimulus once seeding stops — nothing keeps its credences changing, unlike
 * the cyclic fixpoint convergence this repo's `CycleQuiescenceTest`-style
 * detectors have to guard against under load (computenet-dqy.24 in
 * `DurabilityTest`'s KDoc). The restarts themselves need no such polling:
 * `AgoraApp.init` runs `recoverFrom(journal).awaitApplied(...)` (a real
 * fence) and then the startup `host.checkpoint(journal)` this task adds
 * blocks on the management band, so both have completed — and the journal
 * file is already in its post-checkpoint state — by the time the
 * constructor returns.
 */
class RestartJournalGrowthTest {

    private fun awaitStableCredences(
        service: AgoraService,
        requiredStableReads: Int = 5,
        pollMs: Long = 30,
        deadlineMs: Long = 10_000,
    ): Map<CellRef, Double> {
        val deadline = System.currentTimeMillis() + deadlineMs
        var last: Map<CellRef, Double>? = null
        var stable = 0
        while (System.currentTimeMillis() < deadline) {
            val current = service.graph().associate { it.ref to it.credence }
            if (current == last) {
                stable++
                if (stable >= requiredStableReads) return current
            } else {
                stable = 0
            }
            last = current
            Thread.sleep(pollMs)
        }
        error("credences never stabilized within ${deadlineMs}ms: last read $last")
    }

    private fun frameCount(journalFile: File): Int =
        scanJournalFile(journalFile).records
            .map { JournalRecords.decode(it) }
            .count { it is DecodedJournalRecord.Frame }

    @Test
    fun `repeated restarts with no traffic leave the journal unchanged and credences equal`() {
        val dir = createTempDirectory("agora-restart-growth").toFile()
        val journalFile = File(dir, "host.journal")

        // Seed a small, acyclic graph and let it settle, then crash it (no
        // shutdown fence available on the live traffic path — see class KDoc).
        val app = AgoraApp(port = 0, journalDir = dir).start()
        val a = app.service.createClaim("A")
        val b = app.service.createClaim("B")
        app.service.createEdge(a, b, Polarity.ATTACK)
        app.service.setStance(a, "u1", 0.9)
        app.service.setStance(b, "u2", 0.4)
        val before = awaitStableCredences(app.service)
        app.stop()

        // Restart 3 times with no new traffic. Each restart's constructor
        // already ran recoverFrom(journal).awaitApplied(...) and the startup
        // checkpoint synchronously, so the journal file and the recovered
        // state are both settled the moment the constructor returns.
        var baseline: Int? = null
        repeat(3) { i ->
            val restarted = AgoraApp(port = 0, journalDir = dir)
            val after = restarted.service.graph().associate { it.ref to it.credence }
            val recordCount = scanJournalFile(journalFile).records.size
            val frames = frameCount(journalFile)
            restarted.start().stop()

            assertEquals(
                before, after,
                "restart ${i + 1}: recovered credences differ from the pre-restart values",
            )
            assertEquals(
                0, frames,
                "restart ${i + 1}: a no-traffic restart's startup checkpoint should leave no " +
                    "Frame records (the checkpoint compacts the replayed tail)",
            )
            val expected = baseline
            if (expected == null) {
                baseline = recordCount
            } else {
                assertEquals(
                    expected, recordCount,
                    "restart ${i + 1}: journal record count grew across a no-traffic restart " +
                        "(replay is re-journaling its own re-emissions — computenet-vcrc7)",
                )
            }
        }
    }
}
