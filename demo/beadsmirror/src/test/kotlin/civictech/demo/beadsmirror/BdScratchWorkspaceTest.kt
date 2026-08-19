package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.dolt.DoltSql
import civictech.demo.beadsmirror.projector.DotMinter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.isDirectory

/**
 * computenet-s5hx: [BdScratchWorkspace] obtains its workspace by copying a
 * per-JVM pristine template instead of running `bd --sandbox init` per test,
 * and this class covers the one interaction that makes that non-obvious — the
 * embedded Dolt database's directory name.
 *
 * The database directory keeps the name it was created under, while
 * [doltRootFor] derives the expected name from the workspace directory's own
 * basename. A copy therefore has to be *rehomed*, and the two failure modes are
 * opposite: leave the database under the template's name and [doltRoot] points
 * at nothing; land every copy at the template's basename instead and every
 * workspace in the JVM shares one [sanitizedDoltDatabaseName], which is
 * [DotMinter]'s `workspaceIdentity` — two mirror nodes would then mint dots
 * under one source id and their OR-map merge would silently conflate them. Both
 * are asserted here rather than left to the convention that the copy "happens
 * to" land somewhere workable.
 *
 * Every test spawns real `bd`/`dolt` subprocesses (the point of the fixture is
 * that they still do), so all of them guard with JUnit assumptions the same way
 * the module's other live tests do: CI installs neither binary.
 */
class BdScratchWorkspaceTest {

    @BeforeEach
    fun setUp() {
        assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
        assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
    }

    @Test
    fun `a created workspace's dolt root is rehomed onto its own basename`() {
        BdScratchWorkspace.create().use { workspace ->
            val expectedName = sanitizedDoltDatabaseName(workspace.root)

            workspace.doltRoot shouldBe workspace.root.resolve(".beads").resolve("embeddeddolt").resolve(expectedName)
            workspace.doltRoot.isDirectory() shouldBe true
            workspace.doltRoot.resolve(".dolt").isDirectory() shouldBe true

            // `bd` reads the database's directory name from here, so the
            // physical rename alone is not enough.
            workspace.doltDatabaseName() shouldBe expectedName

            // Nothing is left behind under the template's own name.
            workspace.root.resolve(".beads").resolve("embeddeddolt").toFile().list()!!.toSet() shouldBe
                setOf(".lock", expectedName)
        }
    }

    @Test
    fun `two workspaces get distinct basenames, so their dot source ids differ`() {
        BdScratchWorkspace.create().use { first ->
            BdScratchWorkspace.create().use { second ->
                first.root.fileName shouldNotBe second.root.fileName

                val firstIdentity = sanitizedDoltDatabaseName(first.root)
                val secondIdentity = sanitizedDoltDatabaseName(second.root)
                firstIdentity shouldNotBe secondIdentity
                DotMinter(firstIdentity).sourceId shouldNotBe DotMinter(secondIdentity).sourceId

                // And each resolves to its own live database, not the other's.
                first.doltRoot.isDirectory() shouldBe true
                second.doltRoot.isDirectory() shouldBe true
                first.doltRoot shouldNotBe second.doltRoot
            }
        }
    }

    @Test
    fun `a copied workspace answers real bd mutations and dolt reads independently`() {
        BdScratchWorkspace.create().use { first ->
            BdScratchWorkspace.create().use { second ->
                first.run("create", "only in the first workspace")

                val firstTitles = titlesIn(first)
                val secondTitles = titlesIn(second)

                firstTitles shouldBe listOf("only in the first workspace")
                secondTitles shouldBe emptyList()
            }
        }
    }

    private fun titlesIn(workspace: BdScratchWorkspace): List<String> =
        DoltSql(workspace.doltRoot).query("select title from issues order by title")
            .map { it.getValue("title").jsonPrimitive.content }

    private fun commandAvailable(vararg command: String): Boolean = try {
        ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (_: Exception) {
        false
    }
}
