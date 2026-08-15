package civictech.demo.beadsmirror

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * A throwaway `bd --sandbox init` workspace for tests, per epic computenet-dqj
 * §4: every test in this module reads/writes a fresh scratch workspace, NEVER
 * the live `.beads` of this repository.
 *
 * The workspace's Dolt database root — where `dolt` (and [civictech.demo.beadsmirror.dolt.DoltSql])
 * must run — lives at `<ws>/.beads/embeddeddolt/<name>/`, where `<name>` is the
 * scratch directory's basename with every character that is not a letter,
 * digit or underscore replaced by an underscore. The epic's breakdown probe
 * observed only the dot case (`mktemp -d`'s `tmp.XXXXXXXX` becomes
 * `tmp_XXXXXXXX`); this task's own probe against a hyphenated prefix
 * (`Files.createTempDirectory("beadsmirror-bd-scratch-")`) found bd's
 * *database* name (unlike its issue-prefix, which keeps hyphens) sanitises
 * hyphens the same way dots are — `beadsmirror-bd-scratch-1234.abcd` becomes
 * `beadsmirror_bd_scratch_1234_abcd` — so the rule generalises to "any
 * non-alphanumeric, non-underscore character", not "dots only".
 */
class BdScratchWorkspace private constructor(val root: Path) : AutoCloseable {

    /** The Dolt database root for this workspace — NOT the `.dolt/` directory beneath it. */
    val doltRoot: Path = root.resolve(".beads").resolve("embeddeddolt").resolve(sanitizedName(root))

    /** Runs a `bd` mutation (cwd = this workspace). Throws if `bd` exits non-zero. */
    fun run(vararg bdArgs: String): String {
        val process = ProcessBuilder(listOf("bd") + bdArgs)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "bd ${bdArgs.joinToString(" ")} exited $exitCode in $root:\n$output"
        }
        return output
    }

    override fun close() {
        root.toFile().deleteRecursively()
    }

    companion object {
        /** Creates a fresh scratch directory and runs `bd --sandbox init` in it. */
        fun create(): BdScratchWorkspace {
            val dir = Files.createTempDirectory("beadsmirror-bd-scratch-")
            val workspace = BdScratchWorkspace(dir)
            workspace.run("--sandbox", "init")
            return workspace
        }

        private fun sanitizedName(root: Path): String = root.name.replace(Regex("[^A-Za-z0-9_]"), "_")
    }
}
