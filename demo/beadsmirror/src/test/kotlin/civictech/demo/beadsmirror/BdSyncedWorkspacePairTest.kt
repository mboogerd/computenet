package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.baseline.BdExportReader
import civictech.demo.beadsmirror.dolt.DoltSql
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Task computenet-7em.4.2: [BdScratchWorkspace.createSyncedPair] drives a
 * *real* `bd dolt push` / `bd dolt pull` between two scratch workspaces
 * sharing one throwaway `file://` bare remote — the rig the sibling e2e task
 * (computenet-7em.4's re-baseline work) needs to exercise a genuine dolt
 * merge, not a synthesized one.
 *
 * No `assumeTrue`/`@Disabled` toolchain guard here on purpose: CI installs
 * `bd` and `dolt` on both lanes as of computenet-7em.5 (PR #294), and a
 * skipped `:demo:beadsmirror` suite now turns the required check RED rather
 * than green-but-silent.
 */
class BdSyncedWorkspacePairTest {

    private lateinit var pair: BdSyncedWorkspacePair

    @AfterEach
    fun tearDown() {
        if (::pair.isInitialized) {
            pair.close()
        }
    }

    @Test
    fun `a real bd dolt pull lands a 2-parent merge, the union of both sides, and keeps the puller's own pre-pull head`() {
        pair = BdScratchWorkspace.createSyncedPair()

        pair.pusher.run("create", "a1 on the pusher", "-p", "1")
        pair.puller.run("create", "b1 on the puller", "-p", "1")

        val pullerSql = DoltSql(pair.puller.doltRoot)

        // The puller's OWN head, recorded before the pull — this is the fact
        // the sibling task's whole re-baseline design rests on: a real bd
        // sync merges histories rather than fast-forwarding or discarding the
        // puller's local commit. Read fresh from dolt_log, not restated from
        // memory, so a bd/dolt upgrade that stops preserving it fails here.
        val prePullHead =
            pullerSql.query("select commit_hash from dolt_log order by date desc limit 1")
                .single()
                .getValue("commit_hash")
                .jsonPrimitive
                .content

        pair.push()
        pair.pull()

        // Post-pull, the puller's export holds both sides' issues.
        val pulledTitles = BdExportReader(pair.puller.root).read().map { it.json["title"]!!.jsonPrimitive.content }
        pulledTitles shouldContain "a1 on the pusher"
        pulledTitles shouldContain "b1 on the puller"

        // The puller's new head is a real 2-parent merge commit, and the
        // pre-pull head named above is genuinely still present in dolt_log —
        // not merely implied by history-walk defaults, but read back by an
        // explicit query, so a future bd/dolt pull that prunes or squashes
        // local history on merge (a real risk: `bd flatten` already does this
        // deliberately) fails this test loudly instead of the sibling's.
        val head =
            pullerSql.query("select commit_hash, parents from dolt_log order by date desc limit 1").single()
        val parents = head.getValue("parents").jsonPrimitive.content.split(", ")
        parents.size shouldBe 2
        parents shouldContain prePullHead

        val prePullHeadStillInLog =
            pullerSql.query("select commit_hash from dolt_log where commit_hash = '$prePullHead'")
        prePullHeadStillInLog.size shouldBe 1
    }
}
