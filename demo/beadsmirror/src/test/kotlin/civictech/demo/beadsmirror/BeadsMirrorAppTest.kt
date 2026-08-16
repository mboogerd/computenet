package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.baseline.PollLoopDied
import civictech.demo.beadsmirror.baseline.RebaselineReason
import civictech.demo.beadsmirror.dolt.DoltSqlException
import civictech.demo.beadsmirror.feed.DoltCommitFeed
import civictech.demo.beadsmirror.projector.MirrorEdge
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

        /**
         * computenet-dqj.3.3 extends what this pins: `start` now runs a
         * first-start re-baseline — a `bd export` **subprocess against the
         * workspace** — when no checkpoint exists, which is precisely what
         * must not happen against the live tracker. The refusal is `start`'s
         * first statement, so a refused workspace never reaches it; the
         * distinguishing evidence is the exception *type*, since an export
         * that did run here would fail as a `BdExportException` instead.
         */
        @Test
        fun `BeadsMirrorApp start refuses before touching the feed, checkpoint, a socket, or bd export`() {
            val repoRoot = Files.createTempDirectory("beadsmirror-fake-repo-")
            try {
                Files.createDirectories(repoRoot.resolve(".beads"))

                // No dolt database exists under repoRoot/.beads/embeddeddolt/... at
                // all, and no bd/dolt binary needs to be on PATH: if start() got
                // past the refusal it would fail some other, noisier way (a
                // missing-database DoltSql error, a bd export failure, or a hang
                // starting the poller).
                // A LiveBeadsWorkspaceException specifically is the proof the
                // refusal ran first.
                shouldThrow<LiveBeadsWorkspaceException> {
                    BeadsMirrorApp.start(
                        BeadsMirrorConfig(workspace = repoRoot, repoSearchRoot = repoRoot),
                    )
                }
                // Nothing of the mirror's own was created inside the refused
                // workspace either — the default run dir is <workspace>/.beadsmirror.
                Files.exists(repoRoot.resolve(".beadsmirror")) shouldBe false
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

        /**
         * Every [MirrorEvent] the app under test produced. Synchronized because
         * a [RebaselineReason.CheckpointGone] rebuild reports from the poller
         * thread while the test reads from its own.
         */
        private val events: MutableList<MirrorEvent> = java.util.Collections.synchronizedList(mutableListOf())

        @BeforeEach
        fun setUp() {
            assumeTrue(commandAvailable("bd", "--version"), "bd is not on PATH — skipping")
            assumeTrue(commandAvailable("dolt", "version"), "dolt is not on PATH — skipping")
            events.clear()
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

        // -------------------------------------------------------------
        // computenet-dqj.3.3 — re-baseline, both trigger paths
        // -------------------------------------------------------------

        /**
         * Feature rule 3: with no persisted checkpoint the app baselines from
         * `bd export` **before consuming the feed**, rather than walking the
         * commit graph from genesis. The baseline runs inside `start`, so
         * asserting on the returned app is asserting on state that existed
         * before the poller thread was ever created — with a 30s poll interval,
         * no batch can have landed by the time these assertions run either.
         */
        @Test
        fun `a start with no checkpoint baselines from bd export before consuming the feed`() {
            val ids = (1..4).map { workspace.createIssue("Issue $it") }
            workspace.run("dep", "add", ids[1], ids[0], "--type", "blocks")
            val head = DoltCommitFeed(workspace.doltRoot).history().last()

            app = startApp(pollInterval = Duration.ofSeconds(30))

            val event = events.single() as MirrorEvent.Rebaselined
            event.reason shouldBe RebaselineReason.FirstStart
            event.headCommit shouldBe head
            event.issueCount shouldBe 4
            app!!.state.rebaselineCount shouldBe 1
            app!!.state.current.view().keys shouldBe ids.toSet()
            app!!.state.current.edgeView() shouldBe setOf(MirrorEdge(ids[1], ids[0], "blocks"))
            Files.readString(runDir.resolve("checkpoint")).trim() shouldBe head
        }

        /**
         * Feature example 1, end to end against a real compaction: the mirror
         * is checkpointed, `bd flatten --force` squashes that checkpoint out of
         * `dolt_log`, and the next poll rebuilds instead of skipping ahead or
         * wedging. Then rule 2's resume half — a mutation made *after* the
         * rebuild arrives incrementally, with no further rebuild.
         *
         * The 5s poll interval is what makes the gap real rather than raced:
         * the close/create/flatten sequence (~2s of `bd`) completes well inside
         * one interval, so the poller meets a workspace whose history has
         * already been squashed, not a half-applied one.
         */
        @Test
        fun `a flattened-away checkpoint rebuilds from export, then resumes incrementally`() {
            val idA = workspace.createIssue("Issue A")
            app = startApp(pollInterval = Duration.ofSeconds(5))
            probe = HttpProbe("http://localhost:${app!!.boundPort}")
            events.single() // the FirstStart baseline, and nothing else yet

            workspace.run("close", idA)
            val idC = workspace.createIssue("Issue C")
            workspace.flatten()
            val flattenedHead = DoltCommitFeed(workspace.doltRoot).history().last()

            awaitUntil("the mirror re-baselines past the flattened-away checkpoint") { events.size == 2 }
            app!!.pollerFailure shouldBe null

            val rebuild = events[1] as MirrorEvent.Rebaselined
            (rebuild.reason is RebaselineReason.CheckpointGone) shouldBe true
            rebuild.headCommit shouldBe flattenedHead
            Files.readString(runDir.resolve("checkpoint")).trim() shouldBe flattenedHead

            // A's post-gap status and the post-gap issue C are both there.
            val a = Json.parseToJsonElement(probe!!.get("/beads/issues/$idA").body()).jsonObject
            a["status"]?.jsonPrimitive?.content shouldBe "closed"
            probe!!.get("/beads/issues/$idC").statusCode() shouldBe 200

            // Rule 2's resume half: the next mutation arrives through the
            // ordinary incremental path — no third re-baseline.
            val idD = workspace.createIssue("Issue D")
            awaitUntil("issue $idD arrives incrementally after the re-baseline") {
                probe!!.get("/beads/issues/$idD").statusCode() == 200
            }
            events.size shouldBe 2
            app!!.state.rebaselineCount shouldBe 2
        }

        /**
         * Feature example 3 (rule 4): an issue that existed pre-gap and is
         * absent from the export must not survive the swap — neither as a
         * `view()` entry nor as a dangling dependency edge. The edge half is
         * the sharper assertion: edges live in a different cell from the issue
         * fields, so a rebuild that replaced only the map would leave B's
         * dependency behind.
         */
        @Test
        fun `an issue deleted before the compaction does not survive the re-baseline`() {
            val idA = workspace.createIssue("Issue A")
            val idB = workspace.createIssue("Issue B")
            workspace.run("dep", "add", idB, idA, "--type", "blocks")

            app = startApp(pollInterval = Duration.ofSeconds(5))
            app!!.state.current.view().keys shouldBe setOf(idA, idB)
            app!!.state.current.edgeView() shouldBe setOf(MirrorEdge(idB, idA, "blocks"))

            workspace.run("delete", idB, "--force")
            workspace.flatten()

            awaitUntil("the mirror re-baselines past the flattened-away checkpoint") { events.size == 2 }
            app!!.pollerFailure shouldBe null

            app!!.state.current.view().keys shouldBe setOf(idA)
            app!!.state.current.edgeView().none { it.issueId == idB || it.dependsOnIssueId == idB } shouldBe true
            app!!.state.current.edgeView() shouldBe emptySet<MirrorEdge>()
        }

        // -------------------------------------------------------------
        // computenet-dqj.12 — a dead poll loop must be audible and visible
        // -------------------------------------------------------------

        /**
         * computenet-dqj.12: when a poll tick raises anything other than the
         * truncation condition the loop exits for good, and before this item
         * the process printed nothing while every route went on answering 200
         * from the frozen fold — the epic reviewer measured exactly that
         * against a real workspace (`GET /beads/issues/<id>` served
         * `status=in_progress` after `bd` had closed the issue).
         *
         * The failure is injected at the feed's own seam rather than
         * simulated: the workspace's `.dolt` directory is moved aside under
         * the running poller, so the next tick's `dolt sql` subprocess still
         * starts (its working directory still exists) but exits non-zero, and
         * [civictech.demo.beadsmirror.dolt.DoltSqlException] propagates out of
         * `pollOnce`. Deliberately NOT the external-dependency row that the
         * reviewer tripped over (computenet-dqj.11) — that one is being
         * repaired on a sibling branch, and a reproduction built on it would
         * stop reproducing the moment it lands.
         *
         * `.dolt` is then restored so `bd` can go on mutating the workspace.
         * The poller thread has already exited permanently at that point, so
         * the mutation made afterwards is one the mirror provably can never
         * see: the fold under test is frozen, not merely slow.
         */
        @Test
        fun `a dead poll loop is reported to the operator and the frozen fold stops answering 200`() {
            val idA = workspace.createIssue("Issue A")
            app = startApp(pollInterval = Duration.ofMillis(50))
            probe = HttpProbe("http://localhost:${app!!.boundPort}")

            awaitUntil("issue $idA appears on the route") {
                probe!!.get("/beads/issues/$idA").statusCode() == 200
            }
            val frozenCheckpoint = Files.readString(runDir.resolve("checkpoint")).trim()

            val doltDir = workspace.doltRoot.resolve(".dolt")
            val parked = workspace.doltRoot.resolve(".dolt-parked")
            Files.move(doltDir, parked)
            awaitUntil("the poll loop dies on the broken feed") { app!!.pollerFailure != null }
            Files.move(parked, doltDir)

            // A real mutation the mirror can never catch up with.
            workspace.run("update", idA, "--status", "in_progress")

            val response = probe!!.get("/beads/issues/$idA")
            response.statusCode() shouldBe 503
            val body = Json.parseToJsonElement(response.body()).jsonObject
            body["mirror"]?.jsonPrimitive?.content shouldBe "frozen"
            body["frozen_at_checkpoint"]?.jsonPrimitive?.content shouldBe frozenCheckpoint
            (body["failure"]?.jsonPrimitive?.content?.contains("DoltSqlException") == true) shouldBe true
            // The stale fold is still served, plainly labelled as stale: it
            // still says `open`, which bd no longer does.
            body["stale"]?.jsonObject?.get("status")?.jsonPrimitive?.content shouldBe "open"

            // The list route admits it too, not only the single-issue one.
            probe!!.get("/beads/issues").statusCode() shouldBe 503

            // ...and the operator got a written record of it, naming the
            // throwable and the checkpoint the feed stopped at.
            val died = events.filterIsInstance<PollLoopDied>().single()
            died.checkpoint shouldBe frozenCheckpoint
            (died.failure is DoltSqlException) shouldBe true
        }

        /** [BeadsMirrorApp.start] against this test's scratch workspace, reporting into [events]. */
        private fun startApp(pollInterval: Duration): BeadsMirrorApp = BeadsMirrorApp.start(
            BeadsMirrorConfig(
                workspace = workspace.root,
                pollInterval = pollInterval,
                runDir = runDir,
                repoSearchRoot = isolatedSearchRoot,
                onEvent = events::add,
            ),
        )

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
