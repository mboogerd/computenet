package civictech.loader

import civictech.nature.ContractRegistry
import civictech.nature.ProtocolRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Scenario B5 of feature computenet-051.1 — **smuggled shared-prefix class**
 * `[JAR1-ISO-08]`, with the close-on-failure discipline (ERR-05) on this path.
 *
 * `:loader:fixtures:smuggler` bundles a class literally named `civictech.cell.Cell`. Under
 * a child-first loader that name would shadow the kernel's own interface inside the module
 * — the module would appear to work and every value it exchanged with the host would be a
 * `ClassCastException` between two `civictech.cell.Cell`s. So the jar is refused outright,
 * at open, before any class is defined.
 *
 * Three things are asserted, because "rejected" alone is cheap: the diagnostic **names the
 * offending class** (a refusal that does not is unactionable), **no classloader is left
 * open** (a refusal that leaks a loader leaks a file handle on every attempt), and the
 * **registries are unchanged** (this feature registers nothing at all, so "unchanged" is
 * literally identical — the check exists so that the day registration lands, a partially
 * registered smuggler fails here).
 */
class B5SmuggledSharedPrefixTest {

    private companion object {
        const val SMUGGLED_FQN = "civictech.cell.Cell"

        fun fixtureJar(property: String): File {
            val path = System.getProperty(property)
                ?: error(
                    "System property '$property' is not set. It must be wired in " +
                        "loader/build.gradle.kts on the :loader `test` task."
                )
            return File(path).also {
                check(it.isFile) { "$property points at ${it.absolutePath}, which is not a file" }
            }
        }

        val smugglerJar: File get() = fixtureJar("loader.fixture.smuggler")
        val validBasicJar: File get() = fixtureJar("loader.fixture.validBasic")
    }

    @Test
    fun `loading a jar that smuggles a shared-prefix class fails with a diagnostic naming it`() {
        val thrown = shouldThrow<SmuggledSharedPrefixException> { ModuleClassLoader.open(smugglerJar) }

        withClue("the refusal must name the offending class structurally") {
            thrown.offendingClasses shouldContain SMUGGLED_FQN
        }
        val message = thrown.message.orEmpty()
        withClue("and in the message a human reads: $message") {
            message.contains(SMUGGLED_FQN) shouldBe true
        }
        withClue("the jar it refused must be identifiable from the diagnostic: $message") {
            message.contains(smugglerJar.absolutePath) shouldBe true
        }
    }

    @Test
    fun `the refused load leaves no classloader open`() {
        val before = ModuleClassLoader.openLoaders.toSet()

        shouldThrow<SmuggledSharedPrefixException> { ModuleClassLoader.open(smugglerJar) }

        withClue("a refused open must close whatever it constructed [JAR1-ISO-08, ERR-05]") {
            ModuleClassLoader.openLoaders.toSet() shouldBe before
        }
        withClue("and repeating the refusal must not accumulate loaders either") {
            repeat(3) { shouldThrow<SmuggledSharedPrefixException> { ModuleClassLoader.open(smugglerJar) } }
            ModuleClassLoader.openLoaders.toSet() shouldBe before
        }
    }

    @Test
    fun `the refused load leaves the registries unchanged`() {
        // This feature registers nothing — discovery and registration are feature
        // computenet-051.3 — so "unchanged" is identity of the snapshots. Snapshotting is
        // still the right shape: it is what will catch a partially-registered smuggler once
        // registration exists, and it costs nothing now.
        val contractsBefore = ContractRegistry.contracts.map { it.contractId }.toSet()
        val cellsBefore = ContractRegistry.cells.map { it.fqn }.toSet()
        val protocolsBefore = ProtocolRegistry.protocols.map { it.protocolId }.toSet()

        shouldThrow<SmuggledSharedPrefixException> { ModuleClassLoader.open(smugglerJar) }

        ContractRegistry.contracts.map { it.contractId }.toSet() shouldBe contractsBefore
        ContractRegistry.cells.map { it.fqn }.toSet() shouldBe cellsBefore
        ProtocolRegistry.protocols.map { it.protocolId }.toSet() shouldBe protocolsBefore
    }

    @Test
    fun `the smuggled name still resolves to the hosts class for a well-formed module`() {
        // Non-vacuity from the other side: the refusal above is about the *jar*, not about
        // the name being unloadable. A well-formed module asked for the same name gets the
        // host's class, which is what makes the smuggler's copy a smuggle rather than a
        // legitimate private dependency.
        ModuleClassLoader.open(validBasicJar).use { loader ->
            (loader.loadClass(SMUGGLED_FQN) === civictech.cell.Cell::class.java) shouldBe true
        }
    }

    @Test
    fun `the shared prefix decision is a declared set, not a probe of what the parent can load`() {
        // Epic risk 051-R2: "delegate anything the parent can load" is explicitly rejected.
        // These assertions pin the property that distinguishes the two — a name inside a
        // declared prefix is shared whether or not the host has such a class, and a name
        // outside every prefix is private even though the host certainly can load it.
        withClue("inside a declared prefix, absent from the host") {
            ModuleClassLoader.isShared("civictech.cell.NoSuchTypeExistsAnywhere") shouldBe true
        }
        withClue("outside every declared prefix, present on the host classpath") {
            ModuleClassLoader.isShared("org.junit.jupiter.api.Test") shouldBe false
        }
        withClue("prefix matching is on the literal package prefix, dots included") {
            ModuleClassLoader.isShared("civictech.cellar.Vintage") shouldBe false
        }
        withClue("the generated per-module package is the one carve-out") {
            ModuleClassLoader.isShared("civictech.gen.wire.generated.ContractTable_0") shouldBe false
            ModuleClassLoader.isShared("civictech.gen.wire.ProxyRegistry") shouldBe true
        }
    }
}
