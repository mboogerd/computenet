package civictech.loader

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Epic computenet-051 risk 051-R7: the loader's fixture jars must be produced by the REAL
 * `ksp-cell` KSP pipeline, never hand-assembled with a checked-in `META-INF/services`
 * file. A hand-written services entry would make every later isolation, discovery and
 * registration test in this epic a test of the fixture author's typing rather than of the
 * generator's output — and it would keep passing after `ContractProcessor` stopped
 * emitting the entry at all.
 *
 * "Generator output" is not directly observable from a jar, so it is pinned from both
 * sides:
 *
 * - the entry **exists in the built jar** and names a class that is also in that jar
 *   (`ContractProcessor` writes `META-INF/services/civictech.nature.ContractModule`
 *   containing the FQN of the `ContractTable_<hash>` class it generated in the same pass —
 *   gen/src/main/kotlin/civictech/gen/wire/ContractProcessor.kt), and
 * - **no such file exists anywhere in a fixture's source tree**, so the entry in the jar
 *   cannot have been copied from one.
 *
 * Together those two exclude the hand-assembled alternative: the file is in the artifact
 * and is in no source tree, so the build produced it.
 *
 * Jar paths arrive as system properties wired in `loader/build.gradle.kts`; the tests
 * cannot otherwise know where a sibling project's `jar` task wrote.
 */
class FixtureJarsTest {

    private companion object {
        /** The generated `ServiceLoader` registration `ContractProcessor` emits per module. */
        const val SERVICES_ENTRY = "META-INF/services/civictech.nature.ContractModule"

        /** Package `ContractProcessor` generates its `ContractTable_<hash>` into. */
        const val GENERATED_PACKAGE_PREFIX = "civictech.gen.wire.generated."

        /**
         * The `ksp-cell`-built fixtures, by the system property carrying each one's jar.
         * `:loader:fixtures:smuggler` is deliberately absent: it applies plain
         * `kotlin-jvm` and carries no contract, so it has no services entry to check.
         */
        val KSP_FIXTURES = mapOf(
            "loader.fixture.validBasic" to "valid-basic",
            "loader.fixture.utilA" to "util-a",
            "loader.fixture.utilB" to "util-b",
        )
    }

    private fun jarAt(property: String): File {
        val path = System.getProperty(property)
            ?: error(
                "System property '$property' is not set. It must be wired in " +
                    "loader/build.gradle.kts on the :loader `test` task so this test can " +
                    "locate the fixture jar."
            )
        return File(path).also {
            withClue("$property points at ${it.absolutePath}, which is not a file") { it.isFile shouldBe true }
        }
    }

    @Test
    fun `each ksp-built fixture jar carries a generated ContractModule services entry naming a class it contains`() {
        KSP_FIXTURES.forEach { (property, module) ->
            JarFile(jarAt(property)).use { jar ->
                val entry = jar.getJarEntry(SERVICES_ENTRY)
                withClue(
                    "$module's jar has no $SERVICES_ENTRY. Either the module declares no " +
                        "@Contract/Cell for ContractProcessor to see, or it lost the " +
                        "buildsrc.convention.ksp-cell plugin — check " +
                        "loader/fixtures/$module/build.gradle.kts."
                ) { entry shouldNotBe null }

                val named = jar.getInputStream(entry).bufferedReader().readText()
                    .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

                withClue("$module's $SERVICES_ENTRY names ${named.size} classes, expected exactly 1") {
                    named.size shouldBe 1
                }

                // The generated package is the generator's, not the fixture's: a
                // hand-written entry would have no reason to land there.
                withClue("$module's services entry names ${named.single()}") {
                    named.single() shouldStartWith GENERATED_PACKAGE_PREFIX
                }

                val classEntry = named.single().replace('.', '/') + ".class"
                withClue(
                    "$module's services entry names ${named.single()}, but $classEntry is not " +
                        "in the jar — the registration points at a class that does not exist, " +
                        "which is exactly what a stale checked-in entry looks like"
                ) { jar.getJarEntry(classEntry) shouldNotBe null }
            }
        }
    }

    @Test
    fun `the smuggler fixture jar contains a class named civictech cell Cell`() {
        // Fixture (e)'s whole content is this one entry; the loader's rejection of it
        // (JAR1-ISO-08) is a sibling task's assertion, but the jar has to actually carry
        // the smuggled name or that assertion would pass vacuously.
        JarFile(jarAt("loader.fixture.smuggler")).use { jar ->
            withClue("the smuggler fixture jar does not contain civictech/cell/Cell.class") {
                jar.getJarEntry("civictech/cell/Cell.class") shouldNotBe null
            }
        }
    }

    @Test
    fun `no fixture source tree contains a hand-written META-INF services file`() {
        // Working directory is the project directory for a Gradle Test task, i.e. loader/.
        val fixtures = File("fixtures")
        withClue("expected ${fixtures.absolutePath} to be the fixture module root") {
            fixtures.isDirectory shouldBe true
        }

        val srcRoots = fixtures.listFiles().orEmpty().map { File(it, "src") }.filter { it.isDirectory }
        // Non-vacuity: a renamed fixtures directory or layout would otherwise make this
        // check pass by finding nothing to look at.
        withClue("found no fixture src/ tree under ${fixtures.absolutePath}") {
            srcRoots.size shouldBe KSP_FIXTURES.size + 1 // + :loader:fixtures:smuggler
        }

        val checkedIn = srcRoots.flatMap { root ->
            root.walkTopDown().filter { it.isFile }
                .filter { it.absolutePath.replace(File.separatorChar, '/').contains("/META-INF/services/") }
                .toList()
        }
        withClue(
            "checked-in service registrations found: ${checkedIn.map { it.absolutePath }}. The " +
                "fixture jars' ContractModule entries must be ContractProcessor output " +
                "(epic computenet-051 risk 051-R7), and a source-tree copy would be shaded " +
                "into the jar and pass the entry check above without the generator running."
        ) { checkedIn.shouldBeEmpty() }
    }
}
