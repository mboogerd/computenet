package civictech.demo.beadsmirror.e2e

import civictech.demo.beadsmirror.feed.DoltCommitFeed
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Bug computenet-btt30: a `bd` commit that changes no `issues` or
 * `dependencies` row must not leave [TwoNodeRig.Node.quiesce] waiting out its
 * whole budget.
 *
 * Before the fix, [civictech.demo.beadsmirror.feed.DoltFeedPoller.pollOnce]
 * returned early on an empty read and never moved the checkpoint, so a test
 * whose LAST mutation was record-less timed out in `quiesce()` with
 * "checkpoint is 1 commit(s) behind head", `poll=DID NOT ADVANCE`.
 *
 * **The record-less commit is `bd comments add`, not the bead's no-op
 * `bd update --priority 2`.** That one is record-less only when bd's
 * `updated_at` lands in the create's own second — 6 of 8 tries on the
 * reviewer's probe — so a test built on it would pin the bug only some of the
 * time. `bd comments add` touched only the `comments` table on 5 of 5 probes
 * (bd 1.1.2, 2026-09-21, including one issued immediately after the create),
 * and `comments` is outside the fold by design (see
 * [civictech.demo.beadsmirror.writeback.WriteBackPlan]). The test does not rely
 * on that probe: it asserts through the feed itself that the commit carries no
 * record before it asserts anything about `quiesce`.
 *
 * Guarded like the other rig tests: green-but-skipped where `bd`/`dolt` are
 * not on PATH.
 */
class IssueLessCommitQuiesceTest {

    private var rig: TwoNodeRig? = null

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        rig = TwoNodeRig.create("btt30-issueless")
    }

    @AfterEach
    fun tearDown() {
        rig?.close()
    }

    @Test
    fun `a record-less last commit does not stall quiesce, and the checkpoint reaches head`() {
        val rig = checkNotNull(rig)
        val node = rig.startListener()
        val id = rig.createIssue(node, "btt30 issue-less probe")
        node.quiesce()
        val feed = DoltCommitFeed(node.workspace.doltRoot)
        val beforeComment = feed.history().last()
        node.progress().checkpoint shouldBe beforeComment

        rig.mutate(node, "comments", "add", id, "a comment touches no issue row")

        val head = feed.history().last()
        head shouldNotBe beforeComment
        // The premise, read through the same reader the poller uses: the new
        // commit carries no record at all.
        feed.readFrom(beforeComment) shouldBe emptyList()

        node.quiesce()

        node.progress().checkpoint shouldBe head
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
}
