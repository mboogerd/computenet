package civictech.demo.beadsmirror

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A throwaway `bd --sandbox init` workspace for tests, per epic computenet-dqj
 * §4: every test in this module reads/writes a fresh scratch workspace, NEVER
 * the live `.beads` of this repository.
 *
 * **The workspace is COPIED from a per-JVM template, not initialised per test**
 * (computenet-s5hx). BEFORE that optimisation, `bd --sandbox init` cost 5.17s
 * on the machine that motivated the change (bd 1.1.2 / dolt 2.2.3,
 * darwin/arm64, 2026-08-19) and 83 tests in this module built one each in an
 * `@BeforeEach` — ~430s, 38% of the module's then-18m40s runtime, spent
 * before any assertion ran. That per-test-init figure is a historical
 * measurement of the pre-optimisation regime, kept here as the reason the
 * copy exists — **it is not today's cost** and should not be read as one.
 *
 * AFTER the optimisation (i.e. today, with this file as committed), copying
 * the ~2.4MB (72-77 file) pristine workspace costs ~0.08-0.11s per copy
 * (measured 2026-08-24, `NL-MGD6FQJW91`, darwin/arm64, bd 1.1.2 / dolt 2.2.3,
 * via `cp -R` of a live `bd --sandbox init` template as a proxy for
 * [copyTemplateInto]'s `File.copyRecursively`), against ~5.0s for a fresh
 * `bd --sandbox init` measured the same way. Both of those are **quiesced**
 * per-operation figures — one operation at a time on an otherwise idle host.
 * Under the 4-way parallel test run described below the same operations cost
 * roughly 2.5x more in situ (22.0s across 86 copies is ~0.26s per copy;
 * 37.7s across 5 inits is ~7.5s per init). The ratio between them — which is
 * what motivates the copy — is what survives either way. The module's actual runtime
 * today, measured with `./gradlew :demo:beadsmirror:test --rerun` on the
 * same host/date (two full runs, both confirmed executed rather than
 * UP-TO-DATE/FROM-CACHE, both 53 files / 325 tests), is **6m30s-6m42s
 * wall-clock, not 18m40s.** A direct instrumentation of [templateFor] and
 * [copyTemplateInto] in that same run (temporary counters, removed before
 * commit) recorded, across the 4 parallel test JVM forks Gradle used: 5
 * template inits totaling 37.7s of `bd --sandbox init` time and 86 workspace
 * copies totaling 22.0s of copy time — combined ~59.6s of workspace-setup
 * time, in a pass whose wall clock was 6m38s (398s). **Do not divide 59.6 by
 * 398**: the 59.6s is summed across 4 concurrent forks, so the denominator
 * that matches it is the sum of fork-seconds, ~4 x 398s = ~1592, giving 3.7%.
 * Against the single worst-case fork's own critical path it is ~5%. So the
 * share is **roughly 3-5% of the module's current runtime**, a range because
 * the two defensible denominators disagree — not the 38% the pre-optimisation
 * figure above describes.
 * Nothing is mocked and no fidelity is lost: the copy is a real bd workspace
 * over a real embedded Dolt database, and every test that spawned `bd`/`dolt`
 * subprocesses still spawns them. Only the *creation* of the database is
 * amortised — one `bd --sandbox init` per JVM per [bdEnv], in [templateFor].
 *
 * **The copy is REHOMED, which is not optional** — see [copyTemplateInto]. The
 * embedded Dolt database directory keeps the name it was created under, while
 * [doltRootFor] derives the expected name from the *workspace directory's own
 * basename*, so a plain copy to a fresh temp directory resolves [doltRoot] to a
 * path that does not exist. Landing every copy at one constant basename would
 * make that agree, but it would also make [sanitizedDoltDatabaseName] — and
 * therefore [civictech.demo.beadsmirror.projector.DotMinter.sourceId], the dot
 * provenance the two-node tests depend on — identical for every workspace in the
 * JVM, silently merging two nodes' dot sources into one. So each copy keeps a
 * unique basename and the database directory is renamed (and
 * `.beads/metadata.json`'s `dolt_database` rewritten) to match it.
 * `BdScratchWorkspaceTest` covers exactly that interaction.
 *
 * The workspace's Dolt database root — where `dolt` (and [civictech.demo.beadsmirror.dolt.DoltSql])
 * must run — lives at `<ws>/.beads/embeddeddolt/<name>/`, where `<name>` is the
 * scratch directory's basename with every character that is not a letter,
 * digit or underscore replaced by an underscore ([doltRootFor], promoted to
 * main-source code by computenet-dqj.4.2 so [civictech.demo.beadsmirror.BeadsMirrorApp]
 * shares this exact rule instead of re-deriving it). The epic's breakdown
 * probe observed only the dot case (`mktemp -d`'s `tmp.XXXXXXXX` becomes
 * `tmp_XXXXXXXX`); this task's own probe against a hyphenated prefix
 * (`Files.createTempDirectory("beadsmirror-bd-scratch-")`) found bd's
 * *database* name (unlike its issue-prefix, which keeps hyphens) sanitises
 * hyphens the same way dots are — `beadsmirror-bd-scratch-1234.abcd` becomes
 * `beadsmirror_bd_scratch_1234_abcd` — so the rule generalises to "any
 * non-alphanumeric, non-underscore character", not "dots only".
 */
class BdScratchWorkspace private constructor(val root: Path, private val bdEnv: Map<String, String>) : AutoCloseable {

    /** The Dolt database root for this workspace — NOT the `.dolt/` directory beneath it. */
    val doltRoot: Path = doltRootFor(root)

    /** Runs a `bd` mutation (cwd = this workspace). Throws if `bd` exits non-zero. */
    fun run(vararg bdArgs: String): String {
        val builder = ProcessBuilder(listOf("bd") + bdArgs)
            .directory(root.toFile())
            .redirectErrorStream(true)
        builder.environment().putAll(bdEnv)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "bd ${bdArgs.joinToString(" ")} exited $exitCode in $root:\n$output"
        }
        return output
    }

    /**
     * Like [run], but **never throws** on a non-zero exit — it returns the
     * exit code alongside the combined output.
     *
     * Added by task computenet-98u.2.2 for one purpose:
     * [civictech.demo.beadsmirror.e2e.ReadyDifferentialHarness]'s divergence
     * evidence capture. When a comparison disagrees, the harness runs
     * `bd show <id> --json` for every id in the symmetric difference — and an
     * id can legitimately be one the schedule has just `bd delete`d, for which
     * `bd show` exits non-zero. [run]'s fail-loud `check` would then replace
     * the divergence report (the actual finding) with a "bd show exited 1"
     * failure from the evidence gatherer, which is precisely backwards. The
     * refusal text IS evidence here, so it is captured rather than thrown.
     *
     * Every mutation path keeps using [run]: a `bd` mutation that fails is a
     * broken schedule and must still fail loudly.
     */
    fun runAllowingFailure(vararg bdArgs: String): BdInvocation {
        val builder = ProcessBuilder(listOf("bd") + bdArgs)
            .directory(root.toFile())
            .redirectErrorStream(true)
        builder.environment().putAll(bdEnv)
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return BdInvocation("bd ${bdArgs.joinToString(" ")}", process.waitFor(), output)
    }

    /**
     * Squashes this workspace's Dolt history into a single commit
     * (`bd flatten --force`) — the real history-compaction operation tests
     * use to produce a checkpoint hash that genuinely falls out of
     * `dolt_log`, rather than synthesizing the condition at the reader seam.
     * Verified live (computenet-dqj.3.2 probe): flattens in well under a
     * second against a scratch workspace, pre-flatten commit hashes are gone
     * from `dolt_log` afterward, and issue content survives.
     */
    fun flatten(): String = run("flatten", "--force")

    /** Runs `bd dolt push` (cwd = this workspace). */
    fun push(): String = run("dolt", "push")

    /** Runs `bd dolt pull` (cwd = this workspace). */
    fun pull(): String = run("dolt", "pull")

    override fun close() {
        root.toFile().deleteRecursively()
    }

    /** This workspace's `.beads/metadata.json` "project_id" field. */
    internal fun projectId(): String {
        val metadata = Json.parseToJsonElement(metadataFile.readText()) as JsonObject
        return metadata.getValue("project_id").jsonPrimitive.content
    }

    /** Rewrites `.beads/metadata.json`'s "project_id" field to [projectId], preserving every other key. */
    internal fun rewriteProjectId(projectId: String) {
        rewriteMetadata(root, "project_id", projectId)
    }

    /** This workspace's `.beads/metadata.json` "dolt_database" field — the embedded database's directory name. */
    internal fun doltDatabaseName(): String {
        val metadata = Json.parseToJsonElement(metadataFile.readText()) as JsonObject
        return metadata.getValue("dolt_database").jsonPrimitive.content
    }

    private val metadataFile: Path get() = root.resolve(".beads").resolve("metadata.json")

    companion object {
        /**
         * The environment that makes `bd` resolve **no owner at all**, so
         * every issue created in the workspace carries `owner = ''`.
         *
         * `bd` takes an issue's `owner` from git's configured `user.email`
         * (probed live 2026-08-16 against `bd` 1.1.2: a scratch workspace
         * created under a `HOME` holding `[user] email = ci@example.com`
         * produced `"owner":"ci@example.com"`, and one with no git config at
         * all produced an `issues.owner` column of `''` and an export row
         * with no `owner` key). Pointing both git config files at `/dev/null`
         * is the smallest way to reproduce that second case on a machine that
         * does have a git identity — `bd` shells out to `git`, so it honours
         * git's own `GIT_CONFIG_GLOBAL`/`GIT_CONFIG_SYSTEM` overrides.
         *
         * This is exactly the shape of a stock GitHub Actions runner, which
         * has no global git identity — the condition behind computenet-1anx,
         * where 8 of this module's real-workspace tests failed on
         * `ubuntu-latest` and passed on every developer machine.
         */
        private val OWNERLESS_ENV: Map<String, String> = mapOf(
            "GIT_CONFIG_GLOBAL" to "/dev/null",
            "GIT_CONFIG_SYSTEM" to "/dev/null",
        )

        /**
         * The template's directory basename. Short and alphanumeric on purpose:
         * `bd` derives the workspace's *issue prefix* from it at `init` time and
         * that prefix is baked into the database, so every copy mints ids like
         * `beadsmirror-a1b`. Ids stay workspace-locally unique (the suffix is
         * random, not content-derived — probed 2026-08-19: the same title in
         * three copies minted `ws-jqn`, `ws-bu8`, `ws-17u`), and no test asserts
         * on the prefix's value; the tests that need two workspaces to mint
         * ids under one identity pass `--id ... --force` explicitly
         * ([civictech.demo.beadsmirror.e2e.ScheduleStep.Create]).
         */
        private const val TEMPLATE_NAME = "beadsmirror"

        /**
         * One pristine `bd --sandbox init` workspace per JVM per `bd`
         * environment, created on first use and deleted at JVM exit.
         *
         * Keyed by the environment rather than shared, because `bd` resolves an
         * issue's `owner` from git config and [OWNERLESS_ENV] exists precisely
         * to change what `init` and the workspace see: an ownerless workspace
         * grown from a template initialised under this machine's git identity
         * would be a subtly different artifact from what the CI-shaped tests
         * mean to exercise. Two inits per JVM is 10s at worst, against the ~430s
         * the per-test inits cost.
         */
        private val templates = java.util.concurrent.ConcurrentHashMap<Map<String, String>, Path>()

        private fun templateFor(bdEnv: Map<String, String>): Path = templates.computeIfAbsent(bdEnv) { env ->
            val parent = Files.createTempDirectory("beadsmirror-bd-template-")
            Runtime.getRuntime().addShutdownHook(Thread { parent.toFile().deleteRecursively() })
            val dir = Files.createDirectory(parent.resolve(TEMPLATE_NAME))
            BdScratchWorkspace(dir, env).run("--sandbox", "init")
            dir
        }

        /**
         * Copies [template] into [target] and rehomes the embedded Dolt database
         * onto [target]'s own basename, so [doltRootFor] resolves.
         *
         * Both halves are load-bearing and neither is convention: the database
         * directory is physically renamed (`dolt`'s database name *is* its
         * directory name — verified live: a renamed copy answers `bd create`,
         * `bd ready --json` and `dolt sql` normally), and
         * `.beads/metadata.json`'s `dolt_database` is rewritten to agree, which
         * is where `bd` itself reads the name from.
         */
        private fun copyTemplateInto(template: Path, target: Path) {
            template.toFile().copyRecursively(target.toFile(), overwrite = true)

            val embedded = target.resolve(".beads").resolve("embeddeddolt")
            val templateName = sanitizedDoltDatabaseName(template)
            val targetName = sanitizedDoltDatabaseName(target)
            if (templateName != targetName) {
                Files.move(embedded.resolve(templateName), embedded.resolve(targetName))
                rewriteMetadata(target, "dolt_database", targetName)
            }
        }

        /** Rewrites one string [field] of `<workspace>/.beads/metadata.json`, preserving every other key. */
        private fun rewriteMetadata(workspace: Path, field: String, value: String) {
            val file = workspace.resolve(".beads").resolve("metadata.json")
            val metadata = Json.parseToJsonElement(file.readText()) as JsonObject
            val patched = JsonObject(metadata.toMutableMap().apply { put(field, JsonPrimitive(value)) })
            file.writeText(Json.encodeToString(JsonObject.serializer(), patched))
        }

        /** Creates a fresh scratch directory holding a copy of the pristine `bd --sandbox init` template. */
        fun create(): BdScratchWorkspace = create(emptyMap())

        /**
         * Like [create], but every `bd` invocation runs under [OWNERLESS_ENV],
         * so the workspace's issues have an empty `owner` column and `bd
         * export` omits the `owner` key entirely.
         */
        fun createOwnerless(): BdScratchWorkspace = create(OWNERLESS_ENV)

        private fun create(bdEnv: Map<String, String>): BdScratchWorkspace {
            val dir = Files.createTempDirectory("beadsmirror-bd-scratch-")
            copyTemplateInto(templateFor(bdEnv), dir)
            return BdScratchWorkspace(dir, bdEnv)
        }

        /**
         * A pusher/puller pair of scratch workspaces wired through one throwaway
         * `file://` bare Dolt remote, so a test can drive a real `bd dolt
         * push`/`bd dolt pull` sync between them (task computenet-7em.4.2).
         *
         * The recipe below is exactly what was verified live against `bd` 1.1.2
         * on 2026-08-17, in this order, with no step skippable:
         *
         * 1. The pusher does an ordinary [create], then `bd dolt remote add
         *    origin file://<remoteDir>` and `bd dolt push` — against an empty
         *    pre-created directory, `dolt push` materialises the remote itself.
         * 2. A second **independent** `bd --sandbox init` cannot pull that
         *    remote: `bd dolt pull` refuses with "no common ancestor" (divergent
         *    histories) and its own hint text recommends `bd bootstrap`. So the
         *    puller is never grown from its own `init` database — that database
         *    is deleted and replaced.
         * 3. The puller runs [create] too (for its own `.beads/metadata.json`
         *    and config scaffolding), adds the same remote, then deletes its own
         *    `.beads/embeddeddolt` and `.beads/dolt` and runs `bd bootstrap
         *    --yes`, which clones the pusher's database in its place.
         * 4. Post-bootstrap, the puller's `.beads/metadata.json` still carries
         *    the `project_id` its own `init` minted, which no longer matches the
         *    cloned database's `_project_id` (the pusher's) — every further `bd`
         *    mutation in the puller fails with "workspace identity mismatch
         *    detected". `bd doctor --fix` does **not** repair this in embedded
         *    mode (verified: it prints "not yet supported in embedded mode" and
         *    the mismatch persists). The fix, verified: rewrite the puller's
         *    `project_id` to the pusher's — the pusher minted the database, so
         *    its own metadata value IS the database's value.
         *
         * After this, `push()`/`pull()` on the pair are ordinary `bd dolt
         * push`/`bd dolt pull` and need no further identity handling — the
         * mismatch is strictly a bootstrap-time, one-shot fixup.
         *
         * [bdEnv] is applied to **both** members, never independently — a pair
         * split across [OWNERLESS_ENV] and the default would let one side mint
         * owners the other cannot represent, which defeats the whole point of
         * a shared-shape pair. Use [createOwnerlessSyncedPair] for the CI-like,
         * no-git-identity variant.
         */
        fun createSyncedPair(): BdSyncedWorkspacePair = createSyncedPair(emptyMap())

        /** Like [createSyncedPair], but both members run under [OWNERLESS_ENV]. */
        fun createOwnerlessSyncedPair(): BdSyncedWorkspacePair = createSyncedPair(OWNERLESS_ENV)

        private fun createSyncedPair(bdEnv: Map<String, String>): BdSyncedWorkspacePair {
            val remoteDir = Files.createTempDirectory("beadsmirror-bd-scratch-remote-")

            val pusher = create(bdEnv)
            pusher.run("dolt", "remote", "add", "origin", "file://$remoteDir")
            pusher.push()

            val puller = create(bdEnv)
            puller.run("dolt", "remote", "add", "origin", "file://$remoteDir")
            puller.root.resolve(".beads").resolve("embeddeddolt").toFile().deleteRecursively()
            puller.root.resolve(".beads").resolve("dolt").toFile().deleteRecursively()
            puller.run("bootstrap", "--yes")
            puller.rewriteProjectId(pusher.projectId())

            return BdSyncedWorkspacePair(pusher, puller, remoteDir)
        }
    }
}

/**
 * One completed `bd` invocation that was allowed to fail — see
 * [BdScratchWorkspace.runAllowingFailure]. [command] is the rendered
 * invocation, so a captured refusal names the question as well as the answer.
 */
data class BdInvocation(val command: String, val exitCode: Int, val output: String) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * A [pusher]/[puller] pair sharing one throwaway `file://` bare Dolt remote —
 * see [BdScratchWorkspace.createSyncedPair] for the bootstrap/identity-patch
 * recipe that produces [puller]. [push] and [pull] drive real `bd dolt
 * push`/`bd dolt pull`; [close] tears down both workspaces and the shared
 * remote directory.
 */
class BdSyncedWorkspacePair internal constructor(
    val pusher: BdScratchWorkspace,
    val puller: BdScratchWorkspace,
    private val remoteDir: Path,
) : AutoCloseable {

    /** Runs `bd dolt push` on [pusher]. */
    fun push(): String = pusher.push()

    /** Runs `bd dolt pull` on [puller]. */
    fun pull(): String = puller.pull()

    override fun close() {
        pusher.close()
        puller.close()
        remoteDir.toFile().deleteRecursively()
    }
}
