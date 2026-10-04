package civictech.demo.beadsmirror.e2e

import civictech.cell.host.DecodedJournalRecord
import civictech.demo.beadsmirror.BdScratchWorkspace
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.feed.DoltFeedPoller
import civictech.demo.beadsmirror.feed.FeedCursor
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Regression for an idle workspace whose journal grows only through peer gossip. */
class LiveCheckpointTwoNodeTest {

    @Test
    fun `the elapsed checkpoint boundary follows a completed condition handler`() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")

        BdScratchWorkspace.create().use { workspace ->
            val order = mutableListOf<String>()
            val cursor = object : FeedCursor {
                override fun committed(): String = "absent-from-history"
                override fun commit(head: String, drive: () -> Unit) = error("a missing cursor must not commit")
                override fun pollCompleted() {
                    order += "elapsed-checkpoint"
                }
            }
            DoltFeedPoller(
                feed = DoltCommitFeed(workspace.doltRoot),
                cursor = cursor,
                interval = Duration.ZERO,
                onBatch = { error("a missing cursor must not emit a batch") },
                onCondition = { order += "rebaseline-finished" },
            ).pollOnce()

            order shouldBe listOf("rebaseline-finished", "elapsed-checkpoint")
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `peer-only journal growth checkpoints and the idle node recovers the same fold`() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")

        TwoNodeRig.create(
            name = "live-checkpoint-idle-peer",
            pollInterval = Duration.ofMillis(10),
        ).use { rig ->
            val listener = rig.startListener()
            val dialer = rig.startDialer()
            listener.quiesce()
            dialer.quiesce()
            val idleWorkspaceLog = dialer.logHead()

            val id = rig.createIssue(listener, "peer-only checkpoint load")
            repeat(6) { revision ->
                rig.mutate(listener, "update", id, "--title", "peer revision $revision")
            }
            rig.await("the idle dialer receives the peer's last revision") {
                dialer.view()[id]?.get("title") == "\"peer revision 5\""
            }
            dialer.logHead() shouldBe idleWorkspaceLog

            rig.await("the idle dialer checkpoints its peer-grown journal with a bounded frame tail") {
                dialer.journalCensus().let { census ->
                    census.checkpoints > 0 && census.frameTail <= MAX_FRAME_TAIL
                }
            }
            val census = dialer.journalCensus()
            withClue("idle dialer journal after peer-only growth: $census") {
                (census.checkpoints > 0) shouldBe true
                (census.frameTail <= MAX_FRAME_TAIL) shouldBe true
            }

            val liveView = dialer.view()
            val liveEdges = dialer.edgeView()
            val idleCursor = dialer.committedCheckpoint()
            val restarted = rig.restartDialer()

            restarted.committedCheckpoint() shouldBe idleCursor
            restarted.view() shouldBe liveView
            restarted.edgeView() shouldBe liveEdges
            restarted.logHead() shouldBe idleWorkspaceLog
        }
    }

    private fun TwoNodeRig.Node.journalCensus(): JournalCensus {
        val records = journalRecords()
        val checkpoint = records.indexOfLast { it is DecodedJournalRecord.Checkpoint }
        return JournalCensus(
            checkpoints = records.count { it is DecodedJournalRecord.Checkpoint },
            frameTail = records.drop(checkpoint + 1).count { it is DecodedJournalRecord.Frame },
        )
    }

    private data class JournalCensus(val checkpoints: Int, val frameTail: Int)

    private companion object {
        /** Below the six updates' uncheckpointed frame total, while allowing in-flight carry. */
        const val MAX_FRAME_TAIL = 8

        fun commandAvailable(vararg command: String): Boolean = try {
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
