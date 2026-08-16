package civictech.demo.beadsmirror.feed

import civictech.demo.beadsmirror.BdScratchWorkspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * computenet-dqj.1.3: the feed reader resumes from a persisted commit-hash
 * checkpoint, raises a typed condition on history truncation, and can be
 * driven by a poller that stops cleanly.
 *
 * Two halves, same split as computenet-dqj.1.2's [DoltCommitFeedTest]:
 * - [AgainstAScratchWorkspace] drives real `bd`/`dolt` — the only way to know
 *   resume against a real commit graph, and truncation against a checkpoint a
 *   real `dolt_log` genuinely does not contain, are right. Guarded, so CI
 *   (which installs neither binary) runs it green-but-skipped.
 * - [OverSyntheticRows] drives [DoltFeedPoller]'s batching/checkpoint-ordering
 *   and truncation-conversion rules through [DiffQuery] over hand-built rows,
 *   and [FeedCheckpoint] directly against a temp directory. Runs everywhere,
 *   CI included, no `bd`/`dolt` involved.
 *
 * Every workspace here is a throwaway `bd --sandbox init` directory; nothing
 * touches this repository's live `.beads` (epic computenet-dqj §4).
 */
class CheckpointResumeTest {

    @Nested
    inner class AgainstAScratchWorkspace {

        private lateinit var workspace: BdScratchWorkspace
        private lateinit var runDir: Path

        @BeforeEach
        fun setUp() {
            assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
            assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
            workspace = BdScratchWorkspace.create()
            runDir = Files.createTempDirectory("beadsmirror-checkpoint-run-")
        }

        @AfterEach
        fun tearDown() {
            if (::workspace.isInitialized) workspace.close()
            if (::runDir.isInitialized) runDir.toFile().deleteRecursively()
        }

        /** Feature design example: checkpointed at c2 of 4, restarted, emits exactly c3 and c4. */
        @Test
        fun `resumes from a persisted checkpoint and emits exactly the unseen commits`() {
            val a = workspace.createIssue("Issue A")
            workspace.createIssue("Issue B")
            // `bd --sandbox init` itself writes several commits before any issue exists (schema,
            // config), so dolt_log's length is not "one entry per bd mutation" — the reader only
            // ever cares about the LATEST commit hash as a resume point, never a count.
            val afterAAndB = DoltCommitFeed(workspace.doltRoot).history().last()

            val checkpoint = FeedCheckpoint(runDir)
            checkpoint.write(afterAAndB)

            val c = workspace.createIssue("Issue C")
            val d = workspace.createIssue("Issue D")

            val batches = mutableListOf<ChangeRecord>()
            val poller = DoltFeedPoller(
                feed = DoltCommitFeed(workspace.doltRoot),
                checkpoint = checkpoint,
                interval = Duration.ofMillis(50),
                onBatch = { batches += it },
            )

            poller.pollOnce()

            batches.map { it.issueId } shouldContainExactly listOf(c, d)
            batches.none { it.issueId == a } shouldBe true
            // None re-emitted, none skipped: the checkpoint has moved to the last emitted commit.
            checkpoint.read() shouldBe DoltCommitFeed(workspace.doltRoot).history().last()
        }

        @Test
        fun `polling again with no new commits emits nothing and leaves the checkpoint untouched`() {
            workspace.createIssue("Issue A")
            val checkpoint = FeedCheckpoint(runDir)
            val batches = mutableListOf<ChangeRecord>()
            val poller = DoltFeedPoller(
                feed = DoltCommitFeed(workspace.doltRoot),
                checkpoint = checkpoint,
                interval = Duration.ofMillis(50),
                onBatch = { batches += it },
            )
            poller.pollOnce()
            batches.size shouldBe 1
            val afterFirstPoll = checkpoint.read()

            poller.pollOnce()

            batches.size shouldBe 1
            checkpoint.read() shouldBe afterFirstPoll
        }

        /** Feature design example: a checkpoint the real dolt_log genuinely does not contain. */
        @Test
        fun `raises the typed condition and emits nothing when the checkpoint commit is absent from dolt_log`() {
            workspace.createIssue("Issue A")
            val checkpoint = FeedCheckpoint(runDir)
            // Simulate a compacted/truncated history the cheap way (feature design §5):
            // a checkpoint naming a hash dolt_log genuinely does not contain — here, a
            // syntactically plausible commit hash that was never written to this workspace.
            val goneHash = "0".repeat(32)
            checkpoint.write(goneHash)
            val batches = mutableListOf<ChangeRecord>()
            val poller = DoltFeedPoller(
                feed = DoltCommitFeed(workspace.doltRoot),
                checkpoint = checkpoint,
                interval = Duration.ofMillis(50),
                onBatch = { batches += it },
            )

            val failure = shouldThrow<FeedConditionException> { poller.pollOnce() }

            failure.condition shouldBe FeedCondition.CheckpointGone(goneHash)
            batches shouldBe emptyList()
            // The gap is never silently bridged: the checkpoint file is untouched.
            checkpoint.read() shouldBe goneHash
        }

        /**
         * computenet-dqj.3.2's end-to-end requirement: at least one missing-checkpoint
         * detection is exercised through a REAL history compaction, not a synthesized
         * unknown hash. `bd flatten --force` squashes the workspace's whole Dolt history
         * into one commit (verified live: well under a second against a scratch
         * workspace), so the checkpoint captured before flattening is genuinely absent
         * from `dolt_log` afterward — not merely a hash dolt_log never contained.
         */
        @Test
        fun `a real bd flatten drops the checkpoint from history and the poller raises CheckpointGone`() {
            workspace.createIssue("Issue A")
            val checkpoint = FeedCheckpoint(runDir)
            val batches = mutableListOf<ChangeRecord>()
            val poller = DoltFeedPoller(
                feed = DoltCommitFeed(workspace.doltRoot),
                checkpoint = checkpoint,
                interval = Duration.ofMillis(50),
                onBatch = { batches += it },
            )
            poller.pollOnce()
            batches.size shouldBe 1
            val persistedCheckpoint = checkpoint.read()!!

            workspace.createIssue("Issue B")
            workspace.flatten()
            // The pre-flatten checkpoint hash is genuinely gone: dolt_log after a
            // flatten holds only the new synthetic root and whatever commits came
            // after it (`bd flatten` itself commits the squash).
            DoltCommitFeed(workspace.doltRoot).history() shouldNotContain persistedCheckpoint

            val failure = shouldThrow<FeedConditionException> { poller.pollOnce() }

            failure.condition shouldBe FeedCondition.CheckpointGone(persistedCheckpoint)
            // Nothing was emitted for this tick, and the checkpoint file is untouched —
            // the gap is never silently bridged.
            batches.size shouldBe 1
            checkpoint.read() shouldBe persistedCheckpoint
        }

        @Test
        fun `the poller stops cleanly and releases its polling thread`() {
            workspace.createIssue("Issue A")
            val checkpoint = FeedCheckpoint(runDir)
            val batchCount = AtomicInteger(0)
            val poller = DoltFeedPoller(
                feed = DoltCommitFeed(workspace.doltRoot),
                checkpoint = checkpoint,
                interval = Duration.ofMillis(20),
                onBatch = { batchCount.incrementAndGet() },
            )

            poller.start()
            awaitBounded(Duration.ofSeconds(5)) { batchCount.get() >= 1 }
            poller.stop() // must return once the loop thread has fully joined

            val countAtStop = batchCount.get()
            (countAtStop >= 1) shouldBe true
            failure(poller) shouldBe null
            // No further ticks land after stop() has returned.
            Thread.sleep(100)
            batchCount.get() shouldBe countAtStop
        }

        private fun failure(poller: DoltFeedPoller): Throwable? = poller.failure

        private fun awaitBounded(timeout: Duration, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (!condition()) {
                check(System.nanoTime() < deadline) { "condition not met within $timeout" }
                Thread.sleep(10)
            }
        }

        private fun commandAvailable(vararg command: String): Boolean = try {
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        } catch (e: Exception) {
            false
        }

        private fun BdScratchWorkspace.createIssue(title: String): String {
            val output = run("create", title, "--json")
            // bd --json prints the created issue (or a single-element list of one).
            return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(output)!!.groupValues[1]
        }
    }

    @Nested
    inner class OverSyntheticRows {

        @Test
        fun `a poll against a persisted checkpoint emits only the commits after it`() {
            val feed = DoltCommitFeed(
                DiffQuery { sql ->
                    when (sql) {
                        DoltCommitFeed.LOG_QUERY -> listOf("c2", "c1").map { mapOf("commit_hash" to JsonPrimitive(it)) }
                        DoltCommitFeed.ISSUE_QUERY -> listOf(
                            row("diff_type" to "added", "to_commit" to "c1", "to_id" to "a"),
                            row("diff_type" to "added", "to_commit" to "c2", "to_id" to "b"),
                        )
                        DoltCommitFeed.EDGE_QUERY -> emptyList()
                        else -> error("unexpected query: $sql")
                    }
                },
            )

            withTempRunDir { runDir ->
                val checkpoint = FeedCheckpoint(runDir)
                checkpoint.write("c1")
                val batches = mutableListOf<ChangeRecord>()
                val poller = DoltFeedPoller(feed, checkpoint, Duration.ofMillis(10), onBatch = { batches += it })

                poller.pollOnce()

                batches.map { it.issueId } shouldContainExactly listOf("b")
            }
        }

        @Test
        fun `the checkpoint is persisted only after the batch has been handed to the consumer`() {
            val feed = DoltCommitFeed(
                DiffQuery { sql ->
                    when (sql) {
                        DoltCommitFeed.LOG_QUERY -> listOf("c1").map { mapOf("commit_hash" to JsonPrimitive(it)) }
                        DoltCommitFeed.ISSUE_QUERY -> listOf(row("diff_type" to "added", "to_commit" to "c1", "to_id" to "a"))
                        DoltCommitFeed.EDGE_QUERY -> emptyList()
                        else -> error("unexpected query: $sql")
                    }
                },
            )

            withTempRunDir { runDir ->
                val checkpoint = FeedCheckpoint(runDir)
                val checkpointDuringCallback = AtomicReference<String?>("unset")
                val poller = DoltFeedPoller(
                    feed,
                    checkpoint,
                    Duration.ofMillis(10),
                    onBatch = { checkpointDuringCallback.set(checkpoint.read()) },
                )

                poller.pollOnce()

                // Nothing was persisted yet while the consumer had the batch...
                checkpointDuringCallback.get() shouldBe null
                // ...but it is persisted once pollOnce has returned.
                checkpoint.read() shouldBe "c1"
            }
        }

        @Test
        fun `a checkpoint absent from the feed's history raises CheckpointGone via the default onCondition`() {
            val feed = DoltCommitFeed(
                DiffQuery { sql ->
                    when (sql) {
                        DoltCommitFeed.LOG_QUERY -> listOf("c1").map { mapOf("commit_hash" to JsonPrimitive(it)) }
                        DoltCommitFeed.ISSUE_QUERY -> emptyList()
                        DoltCommitFeed.EDGE_QUERY -> emptyList()
                        else -> error("unexpected query: $sql")
                    }
                },
            )

            withTempRunDir { runDir ->
                val checkpoint = FeedCheckpoint(runDir)
                checkpoint.write("not-a-real-commit")
                val poller = DoltFeedPoller(feed, checkpoint, Duration.ofMillis(10), onBatch = { error("must not be called") })

                val failure = shouldThrow<FeedConditionException> { poller.pollOnce() }

                failure.condition shouldBe FeedCondition.CheckpointGone("not-a-real-commit")
            }
        }

        /**
         * computenet-dqj.3.2: closes the false-positive path the dqj.1 review
         * flagged — [DoltFeedPoller] must catch exactly
         * [CheckpointNotInHistoryException], not the broader
         * [IllegalArgumentException] it extends. A plain [IllegalArgumentException]
         * raised anywhere under [DoltCommitFeed.readFrom] (here, from the
         * [DiffQuery] fake itself) must propagate out of [DoltFeedPoller.pollOnce]
         * unconverted — it must NOT surface as [FeedCondition.CheckpointGone] via
         * [onCondition].
         */
        @Test
        fun `a plain IllegalArgumentException from the query propagates, not converted to CheckpointGone`() {
            val feed = DoltCommitFeed(
                DiffQuery { sql ->
                    when (sql) {
                        DoltCommitFeed.LOG_QUERY -> listOf("c1").map { mapOf("commit_hash" to JsonPrimitive(it)) }
                        DoltCommitFeed.ISSUE_QUERY -> throw IllegalArgumentException("boom: not a truncation")
                        DoltCommitFeed.EDGE_QUERY -> emptyList()
                        else -> error("unexpected query: $sql")
                    }
                },
            )

            withTempRunDir { runDir ->
                val checkpoint = FeedCheckpoint(runDir)
                // No checkpoint written: readFrom(null) starts at genesis, so any
                // IllegalArgumentException reaching pollOnce here can only have come
                // from the query fake, not from a history-truncation precondition.
                val conditions = mutableListOf<FeedCondition>()
                val poller = DoltFeedPoller(
                    feed,
                    checkpoint,
                    Duration.ofMillis(10),
                    onBatch = { error("must not be called") },
                    onCondition = { conditions += it },
                )

                val failure = shouldThrow<IllegalArgumentException> { poller.pollOnce() }

                (failure is CheckpointNotInHistoryException) shouldBe false
                failure.message shouldBe "boom: not a truncation"
                conditions shouldBe emptyList()
            }
        }

        @Test
        fun `a custom onCondition callback is used instead of throwing, and the loop keeps running`() {
            val feed = DoltCommitFeed(
                DiffQuery { sql ->
                    when (sql) {
                        DoltCommitFeed.LOG_QUERY -> listOf("c1").map { mapOf("commit_hash" to JsonPrimitive(it)) }
                        DoltCommitFeed.ISSUE_QUERY -> emptyList()
                        DoltCommitFeed.EDGE_QUERY -> emptyList()
                        else -> error("unexpected query: $sql")
                    }
                },
            )

            withTempRunDir { runDir ->
                val checkpoint = FeedCheckpoint(runDir)
                checkpoint.write("not-a-real-commit")
                val conditions = mutableListOf<FeedCondition>()
                val poller = DoltFeedPoller(
                    feed,
                    checkpoint,
                    Duration.ofMillis(10),
                    onBatch = { error("must not be called") },
                    onCondition = { conditions += it },
                )

                poller.pollOnce()
                poller.pollOnce()

                conditions shouldContainExactly listOf(
                    FeedCondition.CheckpointGone("not-a-real-commit"),
                    FeedCondition.CheckpointGone("not-a-real-commit"),
                )
            }
        }

        @Test
        fun `a truncation condition raised inside the background loop stops it cleanly and is observable via failure`() {
            val feed = DoltCommitFeed(
                DiffQuery { sql ->
                    when (sql) {
                        DoltCommitFeed.LOG_QUERY -> listOf("c1").map { mapOf("commit_hash" to JsonPrimitive(it)) }
                        DoltCommitFeed.ISSUE_QUERY -> emptyList()
                        DoltCommitFeed.EDGE_QUERY -> emptyList()
                        else -> error("unexpected query: $sql")
                    }
                },
            )

            withTempRunDir { runDir ->
                val checkpoint = FeedCheckpoint(runDir)
                checkpoint.write("not-a-real-commit")
                // Default onCondition (throws FeedConditionException): unlike pollOnce(), which
                // lets the caller catch it directly, the background loop has nowhere to propagate
                // an uncaught exception to except the `failure` property — this is the path that
                // matters for a real start()-driven poller, not just the synchronous pollOnce()
                // calls the other tests here drive.
                val poller = DoltFeedPoller(
                    feed,
                    checkpoint,
                    Duration.ofMillis(10),
                    onBatch = { error("must not be called") },
                )

                poller.start()
                awaitBoundedSynthetic(Duration.ofSeconds(5)) { poller.failure != null }
                // stop() must return promptly even though the loop already exited on its own —
                // interrupt()/join() on an already-terminated thread must not hang.
                poller.stop()

                val failure = poller.failure
                (failure is FeedConditionException) shouldBe true
                (failure as FeedConditionException).condition shouldBe FeedCondition.CheckpointGone("not-a-real-commit")
            }
        }

        private fun awaitBoundedSynthetic(timeout: Duration, condition: () -> Boolean) {
            val deadline = System.nanoTime() + timeout.toNanos()
            while (!condition()) {
                check(System.nanoTime() < deadline) { "condition not met within $timeout" }
                Thread.sleep(10)
            }
        }

        @Test
        fun `a checkpoint round-trips through a fresh instance, simulating a restart`(@TempDir runDir: Path) {
            val first = FeedCheckpoint(runDir)
            first.write("abc123")

            val second = FeedCheckpoint(runDir)

            second.read() shouldBe "abc123"
        }

        @Test
        fun `an unwritten checkpoint reads as null, meaning genesis`(@TempDir runDir: Path) {
            FeedCheckpoint(runDir).read() shouldBe null
        }

        @Test
        fun `writing a checkpoint twice leaves only the latest value, never a torn file`(@TempDir runDir: Path) {
            val checkpoint = FeedCheckpoint(runDir)
            checkpoint.write("first")
            checkpoint.write("second")

            checkpoint.read() shouldBe "second"
            Files.list(runDir).use { entries -> entries.count() } shouldBe 1L // no stray temp files left behind
        }

        @Test
        fun `the poller rejects a negative interval`(@TempDir runDir: Path) {
            shouldThrow<IllegalArgumentException> {
                DoltFeedPoller(
                    DoltCommitFeed(DiffQuery { emptyList() }),
                    FeedCheckpoint(runDir),
                    Duration.ofMillis(-1),
                    onBatch = {},
                )
            }
        }

        private fun withTempRunDir(block: (Path) -> Unit) {
            val dir = Files.createTempDirectory("beadsmirror-checkpoint-synthetic-")
            try {
                block(dir)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        private fun row(vararg columns: Pair<String, String>): Map<String, JsonElement> =
            columns.associate { (key, value) -> key to JsonPrimitive(value) }
    }
}
