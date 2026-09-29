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
    fun `a tick already at head does not read the feed, and costs exactly one dolt_log query`(
        @TempDir runDir: Path,
    ) {
        val logQueries = AtomicInteger(0)
        val feed = DoltCommitFeed(
            DiffQuery { sql ->
                when (sql) {
                    DoltCommitFeed.LOG_QUERY -> {
                        logQueries.incrementAndGet()
                        log("c2", "c1")
                    }
                    else -> error("an idle tick must not query the diff tables: $sql")
                }
            },
        )
        val checkpoint = FeedCheckpoint(runDir).apply { write("c2") }

        DoltFeedPoller(feed, checkpoint, Duration.ofMillis(10), onBatch = { error("must not be called") }).pollOnce()

        logQueries.get() shouldBe 1
        checkpoint.read() shouldBe "c2"
    }

    /**
     * The soundness rule: only a head from the read's own `dolt_log` may be
     * persisted. Here c3 — which carries a record — lands after the tick's
     * single `dolt_log` read (computenet-yspa5: one read per tick, not two).
     * The tick stops at c2 and hands c3's record over on the next tick.
     */
    @Test
    fun `a commit landing after the tick's read is delivered next tick, never skipped`(@TempDir runDir: Path) {
        val logReads = AtomicInteger(0)
        val feed = feed(
            log = { if (logReads.incrementAndGet() <= 1) listOf("c2", "c1") else listOf("c3", "c2", "c1") },
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

    /**
     * The single-read shape's replacement for the two-read ordering test this
     * used to be (computenet-yspa5, residual of computenet-btt30): with only
     * one `dolt_log` call per tick, there is no longer a window between "read
     * the head" and "read the feed" for a commit to land in — [DoltFeedPoller]
     * now calls [DoltCommitFeed.readFromWithHead], which reads `dolt_log`
     * exactly once and hands back both the records and the head that log's
     * tail names, so the two can never disagree. This pins that there really
     * is one `dolt_log` call per active tick, not two: a regression back to a
     * separate `history()` read before the feed read — the shape that made
     * the "landing between the two reads" race possible in the first place —
     * would double this count.
     */
    @Test
    fun `an active tick issues exactly one dolt_log query`(@TempDir runDir: Path) {
        val logQueries = AtomicInteger(0)
        val feed = DoltCommitFeed(
            DiffQuery { sql ->
                when {
                    sql == DoltCommitFeed.LOG_QUERY -> {
                        logQueries.incrementAndGet()
                        log("c2", "c1")
                    }
                    sql.startsWith(DoltCommitFeed.ISSUE_QUERY) ->
                        listOf(row("diff_type" to "added", "to_commit" to "c2", "to_id" to "a"))
                    sql.startsWith(DoltCommitFeed.EDGE_QUERY) -> emptyList()
                    else -> error("unexpected query: $sql")
                }
            },
        )
        val checkpoint = FeedCheckpoint(runDir).apply { write("c1") }
        val batches = mutableListOf<ChangeRecord>()

        DoltFeedPoller(feed, checkpoint, Duration.ofMillis(10), onBatch = { batches += it }).pollOnce()

        logQueries.get() shouldBe 1
        batches.map { it.issueId } shouldContainExactly listOf("a")
        checkpoint.read() shouldBe "c2"
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
