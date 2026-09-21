package civictech.demo.beadsmirror.feed

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bug computenet-btt30, over synthetic rows (no `bd`/`dolt`; runs everywhere):
 * [DoltFeedPoller] advances its checkpoint past commits that carry no record,
 * and never past a commit it has not read.
 *
 * `dolt_log` answers are newest-first, as the real table's are.
 */
class RecordlessCommitCheckpointTest {

    @Test
    fun `an empty read advances the checkpoint to the observed head`(@TempDir runDir: Path) {
        val feed = feed(log = { listOf("c2", "c1") }, issueRows = emptyList())
        val checkpoint = FeedCheckpoint(runDir).apply { write("c1") }
        val batches = mutableListOf<List<ChangeRecord>>()

        DoltFeedPoller(feed, checkpoint, Duration.ofMillis(10), onBatch = { batches += it }).pollOnce()

        batches shouldBe emptyList()
        checkpoint.read() shouldBe "c2"
    }

    @Test
    fun `record-less commits trailing a batch are passed over too`(@TempDir runDir: Path) {
        val feed = feed(
            log = { listOf("c3", "c2", "c1") },
            issueRows = listOf(row("diff_type" to "added", "to_commit" to "c2", "to_id" to "a")),
        )
        val checkpoint = FeedCheckpoint(runDir).apply { write("c1") }
        val batches = mutableListOf<ChangeRecord>()

        DoltFeedPoller(feed, checkpoint, Duration.ofMillis(10), onBatch = { batches += it }).pollOnce()

        batches.map { it.issueId } shouldContainExactly listOf("a")
        checkpoint.read() shouldBe "c3"
    }

    @Test
    fun `a tick already at head does not read the feed`(@TempDir runDir: Path) {
        val feed = DoltCommitFeed(
            DiffQuery { sql ->
                when (sql) {
                    DoltCommitFeed.LOG_QUERY -> log("c2", "c1")
                    else -> error("an idle tick must not query the diff tables: $sql")
                }
            },
        )
        val checkpoint = FeedCheckpoint(runDir).apply { write("c2") }

        DoltFeedPoller(feed, checkpoint, Duration.ofMillis(10), onBatch = { error("must not be called") }).pollOnce()

        checkpoint.read() shouldBe "c2"
    }

    /**
     * The soundness rule: only a head observed BEFORE the feed read may be
     * persisted. Here c3 — which carries a record — lands after the tick's
     * feed read. A tick that re-read the head afterwards would persist c3 and
     * the record would never be delivered; this one stops at c2 and hands c3's
     * record over on the next tick.
     */
    @Test
    fun `a commit landing after the tick's reads is delivered next tick, never skipped`(@TempDir runDir: Path) {
        val logReads = AtomicInteger(0)
        val feed = feed(
            log = { if (logReads.incrementAndGet() <= 2) listOf("c2", "c1") else listOf("c3", "c2", "c1") },
            issueRows = listOf(row("diff_type" to "added", "to_commit" to "c3", "to_id" to "late")),
        )
        val checkpoint = FeedCheckpoint(runDir).apply { write("c1") }
        val batches = mutableListOf<ChangeRecord>()
        val poller = DoltFeedPoller(feed, checkpoint, Duration.ofMillis(10), onBatch = { batches += it })

        poller.pollOnce()
        batches shouldBe emptyList()
        checkpoint.read() shouldBe "c2"

        poller.pollOnce()
        batches.map { it.issueId } shouldContainExactly listOf("late")
        checkpoint.read() shouldBe "c3"
    }

    private fun feed(log: () -> List<String>, issueRows: List<Map<String, JsonElement>>) = DoltCommitFeed(
        DiffQuery { sql ->
            when {
                sql == DoltCommitFeed.LOG_QUERY -> log().map { mapOf("commit_hash" to JsonPrimitive(it)) }
                sql.startsWith(DoltCommitFeed.ISSUE_QUERY) -> issueRows
                sql.startsWith(DoltCommitFeed.EDGE_QUERY) -> emptyList()
                else -> error("unexpected query: $sql")
            }
        },
    )

    private fun log(vararg newestFirst: String) = newestFirst.map { mapOf("commit_hash" to JsonPrimitive(it)) }

    private fun row(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
        pairs.associate { (k, v) -> k to JsonPrimitive(v) }
}
