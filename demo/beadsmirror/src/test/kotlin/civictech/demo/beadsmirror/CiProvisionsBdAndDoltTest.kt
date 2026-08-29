package civictech.demo.beadsmirror

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every workflow job that runs a repo-wide Gradle `build`/`check` runs this
 * module's suites, and this module's suites need `bd` and `dolt` on PATH.
 *
 * This is the regression guard for computenet-mgxe. `main` was red for twelve
 * days (2026-08-18 .. 2026-08-29, GitHub issue #304) because
 * `post-merge.yml` and `cache-seed.yml` mirror `ci.yml`'s fast and serial
 * lanes command-for-command but never copied its "Install bd and dolt" step.
 * `ci.yml`'s own copy carried a comment arguing a composite action was not
 * worth "a second file to keep in step" — true while both copies sat in one
 * file next to the assertion step that compares them, false the moment the
 * lanes were mirrored into two more files where nothing compares anything.
 *
 * The failure had two faces, and the quiet one is the worse:
 *  - the fast lane went RED, because `BdSyncedWorkspacePairTest` and
 *    `PullRebaselineTest` drive `bd` without an `assumeTrue` guard;
 *  - the serial lane went GREEN with `TwoJvmMirrorTest` SKIPPED — a lane
 *    passing having asserted nothing about the mirror, which is exactly what
 *    computenet-7em.5 exists to prevent.
 *
 * So the rule is checked here rather than by a third copy of an assertion
 * step: a job that invokes an unqualified `check` or `build` task must
 * reference the shared install action. A job whose Gradle tasks are all
 * module-qualified (`:concord:test`, `:kernel:test`) does not run this
 * module and is exempt.
 */
class CiProvisionsBdAndDoltTest {

    @Test
    fun `every workflow job running a repo-wide gradle build provisions bd and dolt`() {
        val workflows = repoRoot().resolve(".github/workflows")
        assertTrue(workflows.isDirectory(), "expected $workflows to exist — has the workflow layout moved?")

        val files = Files.list(workflows).use { s ->
            s.filter { it.name.endsWith(".yml") || it.name.endsWith(".yaml") }.toList()
        }.sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no workflow files found under $workflows")

        val offenders = files.flatMap { file ->
            jobsOf(file.readText())
                .filter { (_, body) -> body.lines().any(::isRepoWideGradleRun) }
                .filterNot { (_, body) -> body.contains(INSTALL_ACTION) }
                .map { (job, _) -> "${file.name}:$job" }
        }

        assertAll(
            offenders.map { where ->
                {
                    fail(
                        "$where runs a repo-wide Gradle build (so it runs :demo:beadsmirror's suites) " +
                            "but never uses $INSTALL_ACTION. Without it `bd` is absent: the fast lane fails " +
                            "outright and the serial lane passes with the mirror e2e SKIPPED (computenet-mgxe).",
                    )
                }
            },
        )
    }

    private companion object {
        const val INSTALL_ACTION = "./.github/actions/install-bd-dolt"

        /** Job ids are the only two-space-indented keys under a workflow's `jobs:`. */
        val JOB_HEADER = Regex("""^ {2}([A-Za-z0-9_-]+):\s*$""")

        /**
         * A Gradle task naming no module — `check`, `build` — is repo-wide, so it
         * reaches `:demo:beadsmirror:test`. `:concord:test` and friends do not.
         */
        fun isRepoWideGradleRun(line: String): Boolean {
            val gradle = line.substringAfter("./gradlew ", missingDelimiterValue = "")
            if (gradle.isBlank()) return false
            return gradle.split(" ")
                .filterNot { it.isBlank() || it.startsWith("-") }
                .any { it == "check" || it == "build" }
        }

        fun jobsOf(yaml: String): Map<String, String> {
            val jobs = linkedMapOf<String, StringBuilder>()
            var current: StringBuilder? = null
            var inJobs = false
            for (line in yaml.lines()) {
                if (line.startsWith("jobs:")) { inJobs = true; current = null; continue }
                if (inJobs && line.isNotBlank() && !line.startsWith(" ")) { inJobs = false; current = null }
                if (!inJobs) continue
                val header = JOB_HEADER.find(line)
                if (header != null) {
                    current = StringBuilder().also { jobs[header.groupValues[1]] = it }
                } else {
                    current?.appendLine(line)
                }
            }
            return jobs.mapValues { it.value.toString() }
        }

        fun repoRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull { it.resolve(".github/workflows").isDirectory() }
            ?: fail("could not locate the repository root from ${Path.of("").toAbsolutePath()}")
    }
}
