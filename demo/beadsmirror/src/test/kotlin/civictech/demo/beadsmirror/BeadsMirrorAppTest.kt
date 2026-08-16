package civictech.demo.beadsmirror

import civictech.testkit.HttpProbe
import civictech.testkit.awaitUntil
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * computenet-dqj.4.2: [BeadsMirrorApp] wires [civictech.demo.beadsmirror.feed.DoltCommitFeed] ->
 * [civictech.demo.beadsmirror.projector.MirrorProjector] -> [civictech.demo.beadsmirror.http.MirrorRoutes]
 * against a `--workspace` path, and refuses to run against the repository's
 * own live `.beads` (feature rule 4, epic computenet-dqj §4).
 *
 * Two halves:
 * - [Refusal] exercises [refuseIfLiveBeads] as pure path logic — no `bd`,
 *   `dolt`, process, or socket involved — so it runs everywhere, CI included.
 * - [AgainstAScratchWorkspace] drives real `bd` mutations against a real
 *   [BdScratchWorkspace] and reads them back over HTTP after
 *   [civictech.demo.beadsmirror.feed.DoltFeedPoller] has had a chance to
 *   poll — the only way to know the whole wiring, not just its parts, is
 *   right. Guarded so CI (which installs neither binary) runs it
 *   green-but-skipped.
 */
class BeadsMirrorAppTest {

    @Nested
    inner class FlagParsing {

        /** Ticket's decided direction: "--workspace <path> (also --workspace=<path>)". */
        @Test
        fun `extractFlag reads the space-separated form and strips both tokens`() {
            val (value, rest) = arrayOf("--workspace", "/tmp/ws", "port").extractFlag("--workspace")
            value shouldBe "/tmp/ws"
            rest shouldBe arrayOf("port")
        }

        @Test
        fun `extractFlag reads the inline equals form and strips the single token`() {
            val (value, rest) = arrayOf("--workspace=/tmp/ws", "port").extractFlag("--workspace")
            value shouldBe "/tmp/ws"
            rest shouldBe arrayOf("port")
        }

        @Test
        fun `extractFlag returns null when the flag is absent`() {
            val (value, rest) = arrayOf("port").extractFlag("--workspace")
            value shouldBe null
            rest shouldBe arrayOf("port")
        }
    }

    @Nested
    inner class Refusal {

        @Test
        fun `refuses a workspace whose dot-beads IS the repository's own`() {
            val repoRoot = Files.createTempDirectory("beadsmirror-fake-repo-")
            try {
                Files.createDirectories(repoRoot.resolve(".beads"))

                val error = shouldThrow<LiveBeadsWorkspaceException> {
                    refuseIfLiveBeads(workspace = repoRoot, searchFrom = repoRoot)
                }
                error.message?.contains(".beads") shouldBe true
            } finally {
                repoRoot.toFile().deleteRecursively()
            }
        }

        @Test
        fun `refuses a workspace canonically equal to the repository root via a dot-dot detour`() {
            val repoRoot = Files.createTempDirectory("beadsmirror-fake-repo-")
            try {
                Files.createDirectories(repoRoot.resolve(".beads"))
                val nested = Files.createDirectories(repoRoot.resolve("nested"))
                val detour = nested.resolve("..") // canonicalizes back to repoRoot

                shouldThrow<LiveBeadsWorkspaceException> {
                    refuseIfLiveBeads(workspace = detour, searchFrom = repoRoot)
                }
            } finally {
                repoRoot.toFile().deleteRecursively()
            }
        }

        @Test
        fun `refuses when searchFrom is a descendant of the repository root, not the root itself`() {
            val repoRoot = Files.createTempDirectory("beadsmirror-fake-repo-")
            try {
                Files.createDirectories(repoRoot.resolve(".beads"))
                val cwd = Files.createDirectories(repoRoot.resolve("some/nested/cwd"))

                shouldThrow<LiveBeadsWorkspaceException> {
                    refuseIfLiveBeads(workspace = repoRoot, searchFrom = cwd)
                }
            } finally {
                repoRoot.toFile().deleteRecursively()
            }
        }

        @Test
        fun `does not refuse a scratch workspace unrelated to the repository root`() {
            val repoRoot = Files.createTempDirectory("beadsmirror-fake-repo-")
            val scratch = Files.createTempDirectory("beadsmirror-fake-scratch-")
            try {
                Files.createDirectories(repoRoot.resolve(".beads"))

                shouldNotThrowAny {
                    refuseIfLiveBeads(workspace = scratch, searchFrom = repoRoot)
                }
            } finally {
                repoRoot.toFile().deleteRecursively()
                scratch.toFile().deleteRecursively()
            }
        }

        /**
         * Review repair (computenet-dqj.4): the refusal must not depend on the
         * process's working directory. Measured before the fix — the installed
         * `beadsmirror` run from `/tmp` against `--workspace=<the computenet
         * checkout>` started normally and wrote `.beadsmirror/checkpoint` into
         * the live checkout. `searchFrom` here is a temp directory with no
         * `.beads` above it, so only the code-source candidate can refuse.
         */
        @Test
        fun `refuses this checkout's own dot-beads even when searchFrom is outside any repository`() {
            val thisCheckout = checkoutRootFromCwd()
            assumeTrue(thisCheckout != null, "no .beads above the test's cwd — nothing to refuse against")
            val elsewhere = Files.createTempDirectory("beadsmirror-elsewhere-")
            try {
                shouldThrow<LiveBeadsWorkspaceException> {
                    refuseIfLiveBeads(workspace = thisCheckout!!, searchFrom = elsewhere)
                }
            } finally {
                elsewhere.toFile().deleteRecursively()
            }
        }

        /** A scratch workspace is still fine when only the code-source candidate is in play. */
        @Test
        fun `does not refuse a scratch workspace when searchFrom is outside any repository`() {
            val scratch = Files.createTempDirectory("beadsmirror-fake-scratch-")
            val elsewhere = Files.createTempDirectory("beadsmirror-elsewhere-")
            try {
                Files.createDirectories(scratch.resolve(".beads"))

                shouldNotThrowAny {
                    refuseIfLiveBeads(workspace = scratch, searchFrom = elsewhere)
                }
            } finally {
                scratch.toFile().deleteRecursively()
                elsewhere.toFile().deleteRecursively()
            }
        }

        /**
         * Review repair (computenet-dqj.4), the worktree half: a `/work` session
         * builds and runs this app inside a linked worktree, whose own `.beads`
         * holds only checked-in config while `bd`'s Dolt database lives in the
         * main checkout. Measured before the fix — the installed binary run from
         * `/tmp` against `--workspace=<main checkout>` started and wrote
         * `.beadsmirror/checkpoint` there. Driven at [mainCheckoutOf] because the
         * code-source end of the walk cannot be injected, and a plain clone (CI)
         * has no linked worktree to observe.
         */
        @Test
        fun `mainCheckoutOf resolves a linked worktree's dot-git file to the main checkout`() {
            val main = Files.createTempDirectory("beadsmirror-fake-main-")
            val worktree = Files.createTempDirectory("beadsmirror-fake-worktree-")
            try {
                Files.createDirectories(main.resolve(".git/worktrees/wt"))
                Files.writeString(worktree.resolve(".git"), "gitdir: $main/.git/worktrees/wt\n")

                mainCheckoutOf(worktree)?.toRealPath() shouldBe main.toRealPath()
            } finally {
                main.toFile().deleteRecursively()
                worktree.toFile().deleteRecursively()
            }
        }

        @Test
        fun `mainCheckoutOf returns null for a normal checkout whose dot-git is a directory`() {
            val clone = Files.createTempDirectory("beadsmirror-fake-clone-")
            try {
                Files.createDirectories(clone.resolve(".git"))
                mainCheckoutOf(clone) shouldBe null
            } finally {
                clone.toFile().deleteRecursively()
            }
        }

        /** The checkout containing the test's working directory, found the way `bd` finds one. */
        private fun checkoutRootFromCwd(): Path? {
            var dir: Path? = Path.of("").toAbsolutePath().normalize()
            while (dir != null) {
                if (Files.isDirectory(dir.resolve(".beads"))) return dir
                dir = dir.parent
            }
            return null
        }

        @Test
        fun `does not refuse when no ancestor of searchFrom has a dot-beads at all`() {
            val noRepo = Files.createTempDirectory("beadsmirror-no-repo-")
            val scratch = Files.createTempDirectory("beadsmirror-fake-scratch-")
            try {
                shouldNotThrowAny {
                    refuseIfLiveBeads(workspace = scratch, searchFrom = noRepo)
                }
            } finally {
                noRepo.toFile().deleteRecursively()
                scratch.toFile().deleteRecursively()
            }
        }

        @Test
        fun `BeadsMirrorApp start refuses before touching the feed, checkpoint, or a socket`() {
            val repoRoot = Files.createTempDirectory("beadsmirror-fake-repo-")
            try {
                Files.createDirectories(repoRoot.resolve(".beads"))

                // No dolt database exists under repoRoot/.beads/embeddeddolt/... at
                // all, and no bd/dolt binary needs to be on PATH: if start() got
                // past the refusal it would fail some other, noisier way (a
                // missing-database DoltSql error, or a hang starting the poller).
                // A LiveBeadsWorkspaceException specifically is the proof the
                // refusal ran first.
                shouldThrow<LiveBeadsWorkspaceException> {
                    BeadsMirrorApp.start(
                        BeadsMirrorConfig(workspace = repoRoot, repoSearchRoot = repoRoot),
                    )
                }
            } finally {
                repoRoot.toFile().deleteRecursively()
            }
        }
    }

    @Nested
    inner class AgainstAScratchWorkspace {

        private lateinit var workspace: BdScratchWorkspace
        private lateinit var runDir: Path
        private lateinit var isolatedSearchRoot: Path
        private var app: BeadsMirrorApp? = null
        private var probe: HttpProbe? = null

        @BeforeEach
        fun setUp() {
            assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
            assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
            workspace = BdScratchWorkspace.create()
            runDir = Files.createTempDirectory("beadsmirror-app-run-")
            // An ancestor tree with no .beads of its own, so refuseIfLiveBeads's
            // walk-up finds nothing and the app starts normally — distinct from
            // `workspace.root`, which IS the workspace this app mirrors.
            isolatedSearchRoot = Files.createTempDirectory("beadsmirror-app-searchroot-")
        }

        @AfterEach
        fun tearDown() {
            probe?.close()
            app?.stop()
            if (::workspace.isInitialized) workspace.close()
            if (::runDir.isInitialized) runDir.toFile().deleteRecursively()
            if (::isolatedSearchRoot.isInitialized) isolatedSearchRoot.toFile().deleteRecursively()
        }

        /** Feature design example 3: main started against --workspace, mutations show up after a poll. */
        @Test
        fun `issues created and updated in the workspace appear on the route after the poll interval`() {
            val idA = workspace.createIssue("Issue A")

            app = BeadsMirrorApp.start(
                BeadsMirrorConfig(
                    workspace = workspace.root,
                    pollInterval = Duration.ofMillis(50),
                    runDir = runDir,
                    repoSearchRoot = isolatedSearchRoot,
                ),
            )
            probe = HttpProbe("http://localhost:${app!!.boundPort}")

            awaitUntil("issue $idA appears on the route") {
                probe!!.get("/beads/issues/$idA").statusCode() == 200
            }
            val created = Json.parseToJsonElement(probe!!.get("/beads/issues/$idA").body()).jsonObject
            created["title"]?.jsonPrimitive?.content shouldBe "Issue A"

            workspace.run("update", idA, "--status", "in_progress")

            awaitUntil("issue $idA's status reflects the update") {
                val body = Json.parseToJsonElement(probe!!.get("/beads/issues/$idA").body()).jsonObject
                body["status"]?.jsonPrimitive?.content == "in_progress"
            }
        }

        /** Feature design example 3, dependency-edge half: `bd dep add B A` -> GET B carries the edge. */
        @Test
        fun `a dependency added in the workspace appears on the dependent issue's route`() {
            val idA = workspace.createIssue("Issue A")
            val idB = workspace.createIssue("Issue B")

            app = BeadsMirrorApp.start(
                BeadsMirrorConfig(
                    workspace = workspace.root,
                    pollInterval = Duration.ofMillis(50),
                    runDir = runDir,
                    repoSearchRoot = isolatedSearchRoot,
                ),
            )
            probe = HttpProbe("http://localhost:${app!!.boundPort}")

            awaitUntil("both issues appear on the route") {
                probe!!.get("/beads/issues/$idA").statusCode() == 200 &&
                    probe!!.get("/beads/issues/$idB").statusCode() == 200
            }

            workspace.run("dep", "add", idB, idA)

            awaitUntil("B's dependency on A appears on B's route") {
                val body = Json.parseToJsonElement(probe!!.get("/beads/issues/$idB").body()).jsonObject
                val deps = body["dependencies"]?.jsonArray
                deps != null && deps.any { it.jsonObject["depends_on_issue_id"]?.jsonPrimitive?.content == idA }
            }
        }

        private fun BdScratchWorkspace.createIssue(title: String): String {
            val output = run("create", title, "--json")
            return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(output)!!.groupValues[1]
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
}
