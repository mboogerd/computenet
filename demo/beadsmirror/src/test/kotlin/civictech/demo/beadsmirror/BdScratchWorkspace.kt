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
        val metadata = Json.parseToJsonElement(metadataFile.readText()) as JsonObject
        val patched = JsonObject(metadata.toMutableMap().apply { put("project_id", JsonPrimitive(projectId)) })
        metadataFile.writeText(Json.encodeToString(JsonObject.serializer(), patched))
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

        /** Creates a fresh scratch directory and runs `bd --sandbox init` in it. */
        fun create(): BdScratchWorkspace = create(emptyMap())

        /**
         * Like [create], but every `bd` invocation runs under [OWNERLESS_ENV],
         * so the workspace's issues have an empty `owner` column and `bd
         * export` omits the `owner` key entirely.
         */
        fun createOwnerless(): BdScratchWorkspace = create(OWNERLESS_ENV)

        private fun create(bdEnv: Map<String, String>): BdScratchWorkspace {
            val dir = Files.createTempDirectory("beadsmirror-bd-scratch-")
            val workspace = BdScratchWorkspace(dir, bdEnv)
            workspace.run("--sandbox", "init")
            return workspace
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
