package civictech.wire

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The guard on `wire/build.gradle.kts`'s `-D` forwarding block (computenet-piur).
 *
 * ## What was unguarded, and why the existing tests could not catch it
 *
 * Gradle's `Test` task does not inherit the daemon's system properties, so every
 * `-Dwire.*` knob a `:wire` test reads has to be listed explicitly in
 * `wire/build.gradle.kts`. Forgetting one is silent by construction: the knob
 * simply does nothing, and the probe runs at its committed default while wearing
 * the label of the size that was asked for. That has now happened twice —
 * `wire.burst.iterations` (computenet-dqy.45: `-Dwire.burst.iterations=2` still
 * ran 10) and `wire.stress.injectFailureAt` (computenet-dqy.63).
 *
 * Nothing in the repo failed when that list was emptied. MEASURED 2026-08-14 on
 * `fed13f7` with the whole `tasks.withType<Test>` block deleted:
 * `./gradlew :wire:test --tests '*WsAnnouncementStressTest*' --rerun
 * -Dwire.stress.injectFailureAt=1` reported all three tests `PASSED`,
 * `BUILD SUCCESSFUL`, `:wire:test` executing (not `FROM-CACHE`/`UP-TO-DATE`).
 *
 * Both prior guards miss this seam, each for its own structural reason:
 *
 *  - [WsAnnouncementStressTest]'s end-to-end injection test sets the properties
 *    with `System.setProperty` **inside the already-running test JVM**, so the
 *    value never travels daemon -> fork and the forwarding is never exercised.
 *  - `.github/workflows/announcement-probe.yml`'s poison preflight *does* cross
 *    the seam, properly, by re-running Gradle with a poisoned `-D` — but it is
 *    `workflow_dispatch`-only and deliberately not a required check, so a
 *    regression stays invisible until a human dispatches a probe, and its
 *    symptom is a wrong number rather than a red build.
 *
 * ## What this test checks, and what it deliberately does not
 *
 * `wire/build.gradle.kts` publishes the forwarded list itself into the test JVM
 * as `wire.forwardedKeys`, from the same `List` the forwarding loop iterates —
 * there is no second literal that could drift. This test reads that published
 * set and requires it to cover every `wire.*` system property key the `:wire`
 * test sources actually read. So:
 *
 *  - emptying, shortening, or deleting the block reddens `build-test-fast`;
 *  - adding a new `-Dwire.*` knob to a test without listing it reddens it too,
 *    which is precisely the regression dqy.45 and dqy.63 both were.
 *
 * It is a *coupling* check, not a proof that Gradle's `systemProperty` works: it
 * cannot fail if `systemProperty(key, value)` itself stopped forwarding. That
 * end-to-end proof needs a nested Gradle invocation with a `-D` on its command
 * line, which is what `announcement-probe.yml`'s preflight is and what makes it
 * worth ~40s x 4 that the required lane should not pay (computenet-dqy.15/.16
 * spent real effort taking work *off* `build-test-fast`). The two guards are
 * complementary: this one covers the list, which is what has actually regressed;
 * the preflight covers the mechanism, which has not.
 *
 * ## Why it skips outside Gradle
 *
 * `scripts/flake-loop/SuiteLoop.java` drives the JUnit Platform Launcher over
 * the compiled classpath with no Gradle task in between, so there is no
 * forwarding to check there and `wire.forwardedKeys` is legitimately absent.
 * The skip is keyed on `org.gradle.test.worker`, which only a Gradle test fork
 * sets — under Gradle an absent `wire.forwardedKeys` is a FAILURE, never a skip,
 * because that is exactly what deleting the block looks like.
 */
class WireSystemPropertyForwardingTest {

    @Test
    fun `every wire system property the tests read is forwarded by wire build gradle kts`() {
        assumeTrue(
            System.getProperty("org.gradle.test.worker") != null,
            "not running in a Gradle test fork (SuiteLoop drives the Launcher directly); " +
                "there is no Test-task forwarding to check here",
        )

        val published = System.getProperty(CHANNEL_KEY)
        assertNotNull(
            published,
            "wire/build.gradle.kts did not publish `wire.forwardedKeys` into this test JVM. " +
                "That is what a deleted or gutted `tasks.withType<Test>` forwarding block looks " +
                "like, and it means every `-Dwire.*` knob is now silently ignored under " +
                "`./gradlew :wire:test`.",
        )
        val forwarded = published.split(",").map(String::trim).filter(String::isNotEmpty).toSet()

        val sources = testSourceFiles()
        // Vacuity guard: a scan that finds no sources, or no keys, would pass this
        // test while checking nothing at all.
        assertTrue(
            sources.size >= 2,
            "expected to scan the :wire test sources, found ${sources.size} file(s) under " +
                "${testSourceRoot()} — the layout moved and this guard would otherwise pass vacuously",
        )
        // `wire.forwardedKeys` is this guard's own channel, read a few lines above.
        // It is set unconditionally by the build rather than forwarded from the
        // daemon, so it is not one of the keys that has to appear in the list.
        val read = sources.flatMap { readWireKeys(it) }.toSet() - CHANNEL_KEY
        assertTrue(
            read.size >= 2,
            "found no `System.getProperty(\"wire.…\")` reads in the :wire test sources; either " +
                "the knobs were removed (delete this guard with them) or KEY_READ no longer matches " +
                "how they are read",
        )

        val missing = read - forwarded
        assertEquals(
            emptySet(), missing,
            "these `wire.*` system properties are read by the :wire test sources but are NOT " +
                "forwarded by wire/build.gradle.kts, so passing them on the Gradle command line " +
                "does nothing and does so silently: $missing. Add them to " +
                "`forwardedSystemProperties` in wire/build.gradle.kts. (published=$forwarded, " +
                "read=$read)",
        )
    }

    private companion object {
        /** The property `wire/build.gradle.kts` publishes its forwarded list through. */
        const val CHANNEL_KEY = "wire.forwardedKeys"

        /**
         * Matches `System.getProperty("wire.<key>")`, the single form every knob
         * in this source set is read through today. A knob read some other way
         * would go unnoticed — the `read.size >= 2` assertion above is what keeps
         * a wholesale change to that form from turning this test vacuous rather
         * than red.
         */
        val KEY_READ = Regex("""System\.getProperty\(\s*"(wire\.[A-Za-z0-9_.]+)"""")

        fun readWireKeys(file: Path): List<String> =
            KEY_READ.findAll(file.readText()).map { it.groupValues[1] }.toList()

        /**
         * Gradle's `Test` task runs with the project directory as its working
         * directory, so `src/test/kotlin` resolves directly. The walk up covers a
         * runner that chose the repository root instead, and the caller asserts on
         * the file count so a wrong answer here is red rather than vacuous.
         */
        fun testSourceRoot(): Path {
            var dir: Path? = Paths.get("").toAbsolutePath()
            while (dir != null) {
                for (candidate in listOf(dir.resolve("src/test/kotlin"), dir.resolve("wire/src/test/kotlin"))) {
                    if (candidate.isDirectory()) return candidate
                }
                dir = dir.parent
            }
            return Paths.get("src/test/kotlin").toAbsolutePath()
        }

        fun testSourceFiles(): List<Path> {
            val root = testSourceRoot()
            if (!root.isDirectory()) return emptyList()
            return Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
            }
        }
    }
}
