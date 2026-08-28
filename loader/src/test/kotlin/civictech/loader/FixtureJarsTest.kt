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
         *
         * `:loader:fixtures:empty-module` is also absent: plain `kotlin-jvm`, no KSP,
         * no `@Contract`/`Cell` — DISC-05's whole point is that it carries no
         * `ContractModule` services entry at all.
         *
         * `:loader:fixtures:doctored-nature` is also absent, for the opposite reason:
         * it IS ksp-cell-built and DOES carry a generated `ContractTable_<hash>`, but
         * its jar's services entry is a build-script-generated REPLACEMENT naming
         * `DoctoredContractModule`, not the generated table — asserted separately
         * below, in `doctored-nature fixture jar names DoctoredContractModule in its
         * ContractModule services entry`, precisely because it would fail the
         * "names a class starting with the generated package prefix" assertion this
         * test makes for every other ksp-built fixture.
         */
        val KSP_FIXTURES = mapOf(
            "loader.fixture.validBasic" to "valid-basic",
            "loader.fixture.noAttrs" to "no-attrs",
            "loader.fixture.utilA" to "util-a",
            "loader.fixture.utilB" to "util-b",
            "loader.fixture.throwingProvider" to "throwing-provider",
            "loader.fixture.missingSharedType" to "missing-shared-type",
            "loader.fixture.collidingContract" to "colliding-contract",
            // computenet-051.5.2: fixture (j) — carries no @Contract of its own, but IS
            // ksp-cell-built and DOES carry two local Cell subclasses (FlowSetCell,
            // FlowPromotionCandidateCell), which is enough for ContractProcessor to emit
            // a real ContractTable_<hash> (cells.isNotEmpty()), so it belongs here like
            // every other real generated-table fixture.
            "loader.fixture.flow" to "flow",
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
        // KSP_FIXTURES(8, since computenet-051.5.2 added "flow") + smuggler + empty-module
        // + removed-api + doctored-nature + wire-delta.
        // computenet-051.6.4: wire-delta (fixture (h) of the feature's fixture note) is
        // plain kotlin-jvm like empty-module — no @Contract/Cell, so it is not in
        // KSP_FIXTURES — but it still adds one more `fixtures/*/src` directory, so this
        // count's `+ 4` becomes `+ 5`. Outside that task's own file claim
        // (loader/fixtures/, B13ModuleWireSerializersTest.kt, FixtureJars.kt,
        // loader/build.gradle.kts, settings.gradle.kts); touched anyway because this
        // literal count is otherwise the only thing standing between fixture (h)
        // landing and `:loader:test` going red on an unrelated, mechanical vacuity
        // check — reported alongside this edit rather than silently expanding scope.
        withClue("found no fixture src/ tree under ${fixtures.absolutePath}") {
            srcRoots.size shouldBe KSP_FIXTURES.size + 5
        }

        // Scoped to the two entries `ContractProcessor` itself emits (ContractModule,
        // ProxyModule — computenet-051 risk 051-R7's concern), NOT every
        // META-INF/services/ file: `:loader:fixtures:throwing-provider` carries a
        // DELIBERATELY hand-written civictech.cell.wire.WireSerializers entry (see
        // that module's ThrowingProvider.kt), which ContractProcessor never emits in
        // the first place, so it is not a stand-in for generator output and this
        // check must not flag it.
        val checkedIn = srcRoots.flatMap { root ->
            root.walkTopDown().filter { it.isFile }
                .filter {
                    val path = it.absolutePath.replace(File.separatorChar, '/')
                    path.endsWith("/META-INF/services/civictech.nature.ContractModule") ||
                        path.endsWith("/META-INF/services/civictech.gen.wire.ProxyModule")
                }
                .toList()
        }
        withClue(
            "checked-in service registrations found: ${checkedIn.map { it.absolutePath }}. The " +
                "fixture jars' ContractModule/ProxyModule entries must be ContractProcessor output " +
                "(epic computenet-051 risk 051-R7), and a source-tree copy would be shaded " +
                "into the jar and pass the entry check above without the generator running."
        ) { checkedIn.shouldBeEmpty() }
    }

    @Test
    fun `doctored-nature fixture jar names DoctoredContractModule in its ContractModule services entry`() {
        // B2's premise: the ContractModule services entry is a build-script-generated
        // REPLACEMENT (see loader/fixtures/doctored-nature/build.gradle.kts's `jar`
        // `doLast`), not the generated ContractTable_<hash> every other ksp-built
        // fixture's entry names — which is exactly why this fixture is excluded from
        // KSP_FIXTURES and gets its own, narrower assertion here.
        JarFile(jarAt("loader.fixture.doctoredNature")).use { jar ->
            val entry = jar.getJarEntry(SERVICES_ENTRY)
            withClue("doctored-nature's jar has no $SERVICES_ENTRY") { entry shouldNotBe null }

            val named = jar.getInputStream(entry).bufferedReader().readText()
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            withClue("doctored-nature's $SERVICES_ENTRY names ${named.size} classes, expected exactly 1") {
                named.size shouldBe 1
            }
            withClue("doctored-nature's services entry names ${named.singleOrNull()}") {
                named.single() shouldBe "civictech.loader.fixture.doctorednature.DoctoredContractModule"
            }

            val classEntry = named.single().replace('.', '/') + ".class"
            withClue(
                "doctored-nature's services entry names ${named.single()}, but $classEntry is not in the jar"
            ) { jar.getJarEntry(classEntry) shouldNotBe null }
        }
    }
}
