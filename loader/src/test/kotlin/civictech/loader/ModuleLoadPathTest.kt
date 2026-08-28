package civictech.loader

import civictech.gen.wire.ProxyRegistry
import civictech.nature.ContractRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The load path of feature computenet-051.3, against real KSP-built fixture jars:
 * scenario **B1**'s load/register half, plus `[JAR1-DISC-04]`, `[JAR1-DISC-05]`,
 * `[JAR1-SEC-02]` and `[JAR1-SEC-04]`.
 *
 * The failure-discipline suite — B11, B12, ERR-03 atomicity, ERR-05
 * `openLoaders`, and B2's doctored-`NatureVector` tripwire — is task
 * **computenet-051.3.3**, sequenced after this one and sharing this directory.
 * The failure *handling* it tests is implemented in
 * `civictech.loader.ModuleLoader` here.
 *
 * Registries are process-global objects, so every test that registers goes
 * through [FixtureJars.withLoadedModule], which unregisters and closes in a
 * `finally`.
 */
class ModuleLoadPathTest {

    private companion object {
        const val GREETING_API = "civictech.loader.fixture.validbasic.GreetingApi"
        const val GREETING_CELL = "civictech.loader.fixture.validbasic.GreetingCell"

        /**
         * `:loader:fixtures:empty-module`'s declared version, verbatim from its
         * build file. Deliberately not a version any scheme would produce — see
         * `[JAR1-DISC-04]`.
         */
        const val EMPTY_MODULE_VERSION = "not a version, recorded verbatim"
    }

    // ------------------------------------------------------------------
    // B1 (load/register half) — [JAR1-DISC-02][JAR1-DISC-03][JAR1-REG-07]
    // ------------------------------------------------------------------

    @Test
    fun `loading the well-formed fixture reaches REGISTERED and publishes the jar's own generated descriptors`() {
        FixtureJars.withLoadedModule(FixtureJars.validBasic) { handle ->
            withClue("a successful load ends in REGISTERED: $handle") {
                handle.state shouldBe ModuleState.REGISTERED
            }
            handle.id.id shouldBe "fixture.valid-basic"
            handle.version shouldBe "1.0.0"

            val contractClass = handle.classLoader.loadClass(GREETING_API)
            val cellClass = handle.classLoader.loadClass(GREETING_CELL)

            // What the JAR carries, read independently of the loader: the module's
            // own generated table, instantiated straight from the services entry.
            val fromJar = FixtureJars.contractModuleIn(FixtureJars.validBasic, handle.classLoader)
            val expected = fromJar.contracts.single { it.fqn == GREETING_API }

            val registered = ContractRegistry.descriptor(contractClass)
            withClue("the contract the module contributed must be resolvable by its Class") {
                registered shouldNotBe null
            }
            // Structural equality against the generated table's own entry: a load
            // path that rebuilt a descriptor from annotations could match here by
            // accident, which is exactly why B2 (task computenet-051.3.3) exists —
            // but one that failed to transport the table at all fails here.
            withClue("the registered descriptor must BE the generated one the jar carries") {
                registered shouldBe expected
            }

            withClue("the module's cell descriptor must resolve too") {
                ContractRegistry.cellDescriptor(cellClass) shouldNotBe null
            }
            ContractRegistry.cellDescriptor(cellClass)!!.fqn shouldBe GREETING_CELL

            withClue("and its generated proxy constructor must be registered, not a JDK fallback") {
                ProxyRegistry.factory(contractClass) shouldNotBe null
            }

            withClue("the handle records what it contributed") {
                handle.contractIds shouldContain expected.contractId
                handle.cellFqns shouldContain GREETING_CELL
                handle.proxiedClasses shouldContain contractClass
            }
        }
    }

    @Test
    fun `a loaded module is listed by the loader that loaded it`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        withClue("a fresh loader has loaded nothing") { loader.loaded().shouldBeEmpty() }
        FixtureJars.withLoadedModule(FixtureJars.validBasic, loader) { handle ->
            loader.loaded() shouldBe listOf(handle)
        }
    }

    // ------------------------------------------------------------------
    // [JAR1-DISC-04] — the version string is recorded verbatim
    // ------------------------------------------------------------------

    @Test
    fun `the declared version is recorded verbatim and exposed as an uninterpreted String`() {
        FixtureJars.withLoadedModule(FixtureJars.emptyModule) { handle ->
            withClue("no parsing, no normalization, no trimming beyond the manifest's own") {
                handle.version shouldBe EMPTY_MODULE_VERSION
            }
            // The requirement is as much about what is ABSENT as about the value:
            // nothing on the handle interprets, orders or compares this string.
            // `version` is a plain String property and there is no version type,
            // no comparator, and no `isNewerThan` for a caller to reach for.
            withClue("and it is a String, not a parsed version object") {
                (handle.version as Any) shouldBe EMPTY_MODULE_VERSION
            }
        }
    }

    // ------------------------------------------------------------------
    // [JAR1-DISC-05] — attributes but no ContractModule service
    // ------------------------------------------------------------------

    @Test
    fun `a module declaring the attributes but exposing no ContractModule loads contributing zero descriptors`() {
        FixtureJars.withLoadedModule(FixtureJars.emptyModule) { handle ->
            handle.state shouldBe ModuleState.REGISTERED
            handle.id.id shouldBe "fixture.empty-module"
            withClue("DISC-05: zero descriptors is a successful load, not a failure") {
                handle.contractIds.shouldBeEmpty()
                handle.protocolIds.shouldBeEmpty()
                handle.cellFqns.shouldBeEmpty()
                handle.proxiedClasses.shouldBeEmpty()
            }
        }
    }

    // ------------------------------------------------------------------
    // [JAR1-SEC-02] — accepted locations only, and no scan
    // ------------------------------------------------------------------

    @Test
    fun `a jar outside the accepted locations is refused before any classloader is created`() {
        val before = ModuleClassLoader.openLoaders.toSet()
        // Accepts empty-module's directory; valid-basic sits in its own.
        val loader = FixtureJars.loaderAccepting(FixtureJars.emptyModule)

        val thrown = shouldThrow<ModuleLocationRefusedException> { loader.load(FixtureJars.validBasic) }

        withClue("the refusal names the jar and the accepted set: ${thrown.message}") {
            thrown.message.orEmpty().contains(FixtureJars.validBasic.absolutePath) shouldBe true
        }
        withClue("[JAR1-ERR-01]/[JAR1-ERR-05]: a refused location creates no classloader at all") {
            ModuleClassLoader.openLoaders.toSet() shouldBe before
        }
        loader.loaded().shouldBeEmpty()
    }

    @Test
    fun `a loader with no accepted locations accepts nothing`() {
        val loader = ModuleLoader(acceptedLocations = emptySet())
        shouldThrow<ModuleLocationRefusedException> { loader.load(FixtureJars.validBasic) }
        withClue("an empty accepted set must not degrade into 'accept anything'") {
            loader.acceptedLocations.shouldBeEmpty()
        }
    }

    @Test
    fun `an exact jar path is an accepted location, and its sibling is not`() {
        val exact: Path = FixtureJars.validBasic.toPath().toAbsolutePath().normalize()
        val loader = ModuleLoader(acceptedLocations = setOf(exact))

        // The sibling proves the entry is the JAR, not its directory: a directory
        // reading would let no-attrs through, since it sits in the same build tree
        // only one level away.
        shouldThrow<ModuleLocationRefusedException> { loader.load(FixtureJars.noAttrs) }

        val handle = loader.load(FixtureJars.validBasic)
        try {
            handle.state shouldBe ModuleState.REGISTERED
        } finally {
            civictech.nature.ModuleRegistration.unregister(handle.id)
            handle.classLoader.close()
        }
    }

    // ------------------------------------------------------------------
    // [JAR1-SEC-04] — the load record
    // ------------------------------------------------------------------

    @Test
    fun `a successful load emits a record carrying id, verbatim version, jar location and contributed ids`() {
        val records = mutableListOf<ModuleLoadRecord>()
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic) { records += it }

        FixtureJars.withLoadedModule(FixtureJars.validBasic, loader) { handle ->
            withClue("exactly one record per successful load") { records.size shouldBe 1 }
            val record = records.single()

            record.id shouldBe handle.id
            record.version shouldBe "1.0.0"
            record.jar shouldBe FixtureJars.validBasic
            withClue("the contributed ids must actually list what was contributed: $record") {
                record.contributedDescriptorIds shouldBe handle.contributedDescriptorIds()
            }
        }
    }

    @Test
    fun `the load record's contributed ids name every contract, cell and proxied class`() {
        val records = mutableListOf<ModuleLoadRecord>()
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic) { records += it }

        FixtureJars.withLoadedModule(FixtureJars.validBasic, loader) { handle ->
            val ids = records.single().contributedDescriptorIds
            handle.contractIds.forEach { ids shouldContain "contract:$it" }
            handle.cellFqns.forEach { ids shouldContain "cell:$it" }
            handle.proxiedClasses.forEach { ids shouldContain "proxy:${it.name}" }
            withClue("a module that contributes something must not log an empty record") {
                ids.isNotEmpty() shouldBe true
            }
        }
    }

    @Test
    fun `a refused load emits no record`() {
        val records = mutableListOf<ModuleLoadRecord>()
        val loader = ModuleLoader(acceptedLocations = emptySet(), observe = { records += it })
        shouldThrow<ModuleLocationRefusedException> { loader.load(FixtureJars.validBasic) }
        records.shouldBeEmpty()
    }

    // ------------------------------------------------------------------
    // [JAR1-DISC-01] / [JAR1-ERR-02] — detection is the manifest, nothing else
    // ------------------------------------------------------------------

    @Test
    fun `a well-formed ksp jar without the module attributes is refused as not-a-module`() {
        val before = ModuleClassLoader.openLoaders.toSet()
        val loader = FixtureJars.loaderAccepting(FixtureJars.noAttrs)

        val thrown = shouldThrow<NotAModuleException> { loader.load(FixtureJars.noAttrs) }

        withClue("the diagnostic must name both required attributes: ${thrown.message}") {
            thrown.message.orEmpty().contains(ModuleManifest.MODULE_ID) shouldBe true
            thrown.message.orEmpty().contains(ModuleManifest.MODULE_VERSION) shouldBe true
        }
        withClue("detection precedes classloading, so nothing was opened") {
            ModuleClassLoader.openLoaders.toSet() shouldBe before
        }
    }
}
