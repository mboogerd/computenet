package civictech.demo.beadsmirror.dolt

import civictech.demo.beadsmirror.BdScratchWorkspace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.1.1 acceptance: reads one created issue's row back from a
 * [BdScratchWorkspace] via `dolt sql -r json`, treats an empty `{}` result as
 * zero rows, and surfaces a query failure as a typed [DoltSqlException]
 * carrying the query and cause. Never touches the live `.beads` (epic
 * computenet-dqj §4) — every test here runs against its own throwaway
 * `bd --sandbox init` workspace.
 *
 * EVERY test in this class spawns `bd` and `dolt`, so all of them guard with
 * JUnit assumptions: `.github/workflows/ci.yml` installs neither binary, and
 * this class is therefore green-but-SKIPPED in CI and a real gate only on a
 * developer machine. Unlike [civictech.demo.beadsmirror.feed.DoltCommitFeedTest]
 * and [civictech.demo.beadsmirror.feed.CheckpointResumeTest], which each pair
 * their live half with a synthetic half that does run in CI, this class has no
 * such half — a green CI run is no evidence at all about `DoltSql`.
 */
class DoltSqlTest {

    private lateinit var workspace: BdScratchWorkspace

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
        workspace = BdScratchWorkspace.create()
    }

    @AfterEach
    fun tearDown() {
        if (::workspace.isInitialized) {
            workspace.close()
        }
    }

    @Test
    fun `reads one created issue's row back from dolt_diff_issues`() {
        workspace.run("create", "Test issue", "-p", "1")

        val sql = DoltSql(workspace.doltRoot)
        val rows = sql.query("select to_id, diff_type, to_title from dolt_diff_issues")

        rows.size shouldBe 1
        rows[0].getValue("diff_type").jsonPrimitive.content shouldBe "added"
        rows[0].getValue("to_title").jsonPrimitive.content shouldBe "Test issue"
        rows[0].getValue("to_id").jsonPrimitive.content.isNotBlank() shouldBe true
    }

    @Test
    fun `an empty dolt sql result is zero rows, not an error`() {
        val sql = DoltSql(workspace.doltRoot)

        val rows = sql.query("select to_id from dolt_diff_issues where to_id = 'does-not-exist'")

        rows shouldBe emptyList()
    }

    @Test
    fun `a failing query raises a typed exception carrying the query and cause`() {
        val sql = DoltSql(workspace.doltRoot)

        val exception = shouldThrow<DoltSqlException> {
            sql.query("select * from table_that_does_not_exist")
        }

        exception.query shouldBe "select * from table_that_does_not_exist"
        exception.message!!.contains("table_that_does_not_exist") shouldBe true
    }

    private fun commandAvailable(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
