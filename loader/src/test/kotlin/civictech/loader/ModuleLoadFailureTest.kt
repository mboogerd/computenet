package civictech.loader

import civictech.nature.ContractRegistry
import civictech.nature.ModuleId
import civictech.nature.ModuleRegistration
import civictech.nature.Monotonicity
import civictech.nature.NatureVector
import civictech.nature.RegistrationRefusedException
import civictech.nature.StableHash
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Task computenet-051.3.3: the load path's failure discipline and the B2
 * anti-reflection tripwire, against real KSP-built fixture jars — never
 * hand-written `META-INF/services` files.
 *
 * `ModuleLoadPathTest` (task computenet-051.3.2) already covers B1's
 * load/register half, `[JAR1-DISC-04]`, `[JAR1-DISC-05]`, `[JAR1-SEC-02]`,
 * `[JAR1-SEC-04]`, and one ERR-02 case (`noAttrs`). This file adds the
 * remaining scenarios the feature's breakdown assigns to this task:
 *
 * - **B11** — a non-jar file inside an accepted location [JAR1-ERR-01].
 * - **B12** — a module compiled against a shared type the host no longer
 *   supplies [JAR1-ERR-04].
 * - **ERR-03 atomicity** — an un-instantiable `ServiceLoader` provider fails
 *   the whole load, including the same jar's otherwise-valid `ContractModule`.
 * - **ERR-05** — `ModuleClassLoader.openLoaders` shows no leaked loader after
 *   every failure that opened one.
 * - **B2** — the registered `PortDescriptor.natures` is whatever the jar's
 *   `ContractModule` table carries, including a value no source annotation
 *   would produce. Any implementation that re-derives descriptors from
 *   annotations/bytecode instead of transporting the generated table
 *   unmodified [JAR1-DISC-03] fails this test.
 *
 * Every failure test asserts three things: the diagnostic content, the
 * registries unchanged for the fixture's would-be contributions (checked by
 * `contractId`/cell fqn, computed independently via [StableHash] rather than
 * by loading the fixture's own classes — the whole point is to check the
 * registry *without* trusting a classloader the failed load may have already
 * closed), and [ModuleClassLoader.openLoaders] showing no leaked loader.
 */
class ModuleLoadFailureTest {

    private companion object {
        const val PINGBACK_API = "civictech.loader.fixture.throwingprovider.PingBackApi"
        const val PINGBACK_CELL = "civictech.loader.fixture.throwingprovider.PingBackCell"

        const val MISSING_SHARED_TYPE_API = "civictech.loader.fixture.missingsharedtype.MissingSharedTypeApi"
        const val MISSING_SHARED_TYPE_CELL = "civictech.loader.fixture.missingsharedtype.MissingSharedTypeCell"

        const val DOCTORED_CELL = "civictech.loader.fixture.doctorednature.DoctoredCell"

        const val GREETING_API = "civictech.loader.fixture.validbasic.GreetingApi"
        val COLLIDING_CONTRACT_MODULE = ModuleId("fixture.colliding-contract")
    }

    // ------------------------------------------------------------------
    // B11 — malformed input [JAR1-ERR-01]
    // ------------------------------------------------------------------

    @Test
    fun `a garbage file that is not a readable jar fails with a diagnostic and creates no classloader`() {
        val dir = Files.createTempDirectory("loader-b11-malformed")
        val garbage = dir.resolve("not-a-jar.jar").toFile()
        garbage.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))

        val before = ModuleClassLoader.openLoaders.toSet()
        val loader = ModuleLoader(acceptedLocations = setOf(dir))

        val thrown = shouldThrow<ModuleLoadException> { loader.load(garbage) }

        withClue("the diagnostic names the offending jar: ${thrown.message}") {
            thrown.message.orEmpty().contains(garbage.absolutePath) shouldBe true
        }
        withClue("[JAR1-ERR-01]: an unreadable jar creates no classloader") {
            ModuleClassLoader.openLoaders.toSet() shouldBe before
        }
        loader.loaded().shouldBeEmpty()
    }

    // ------------------------------------------------------------------
    // B12 — incompatible module [JAR1-ERR-04]
    // ------------------------------------------------------------------

    @Test
    fun `a module compiled against a shared type absent from the host fails at load time naming the missing type`() {
        val before = ModuleClassLoader.openLoaders.toSet()
        val loader = FixtureJars.loaderAccepting(FixtureJars.missingSharedType)

        // Assert the failure happens IN load() itself, not deferred to a later
        // spawn — there is no spawn API in this feature, so "load() throws" is
        // the whole assertion.
        val thrown = shouldThrow<ModuleLoadException> { loader.load(FixtureJars.missingSharedType) }

        withClue("the diagnostic names the missing shared type: ${thrown.message}") {
            // NoClassDefFoundError's own message carries the JVM's slash-separated
            // binary name, not the dotted fqn form.
            thrown.message.orEmpty().contains("civictech/nature/removed/RemovedBase") shouldBe true
        }
        withClue("nothing was registered for the fixture's contract") {
            ContractRegistry.contract(StableHash.of(MISSING_SHARED_TYPE_API)) shouldBe null
        }
        withClue("nothing was registered for the fixture's cell") {
            ContractRegistry.cellContributorsOf(MISSING_SHARED_TYPE_CELL).shouldBeEmpty()
        }
        withClue("[JAR1-ERR-05]: the classloader opened for the attempt is closed") {
            ModuleClassLoader.openLoaders.toSet() shouldBe before
        }
        loader.loaded().shouldBeEmpty()
    }

    // ------------------------------------------------------------------
    // ERR-03 — un-instantiable ServiceLoader provider, and atomicity
    // ------------------------------------------------------------------

    @Test
    fun `a provider that cannot be instantiated fails the load naming it, and registers none of the jar's descriptors`() {
        val before = ModuleClassLoader.openLoaders.toSet()
        val loader = FixtureJars.loaderAccepting(FixtureJars.throwingProvider)

        val thrown = shouldThrow<ModuleLoadException> { loader.load(FixtureJars.throwingProvider) }

        withClue("the diagnostic names the throwing provider class: ${thrown.message}") {
            thrown.message.orEmpty().contains(
                "civictech.loader.fixture.throwingprovider.ThrowingWireSerializers"
            ) shouldBe true
        }
        withClue("atomicity: the jar's own perfectly valid contract must NOT be registered") {
            ContractRegistry.contract(StableHash.of(PINGBACK_API)) shouldBe null
        }
        withClue("atomicity: the jar's own perfectly valid cell must NOT be registered") {
            ContractRegistry.cellContributorsOf(PINGBACK_CELL).shouldBeEmpty()
        }
        withClue("[JAR1-ERR-05]: the classloader opened for the attempt is closed") {
            ModuleClassLoader.openLoaders.toSet() shouldBe before
        }
        loader.loaded().shouldBeEmpty()
    }

    // ------------------------------------------------------------------
    // ERR-05, registration-refusal arm — computenet-9fqe
    // ------------------------------------------------------------------

    @Test
    fun `a module whose contract collides with an already-registered contractId is refused, registers nothing of its own, and leaks no classloader`() {
        FixtureJars.withLoadedModule(FixtureJars.validBasic) { baseline ->
            withClue("precondition: valid-basic registered under its own contractId") {
                baseline.state shouldBe ModuleState.REGISTERED
            }

            val before = ModuleClassLoader.openLoaders.toSet()
            val loader = FixtureJars.loaderAccepting(FixtureJars.collidingContract)

            // ModuleRegistration.register throws RegistrationRefusedException directly
            // (an IllegalArgumentException, not a ModuleLoadException); ModuleLoader.load's
            // outer catch(t: Throwable) closes the loader and rethrows it unwrapped, so
            // the caller-visible type here IS RegistrationRefusedException.
            val thrown = shouldThrow<RegistrationRefusedException> {
                loader.load(FixtureJars.collidingContract)
            }

            withClue("the diagnostic names the colliding contractId's fqn: ${thrown.message}") {
                thrown.message.orEmpty().contains(GREETING_API) shouldBe true
            }
            withClue("the diagnostic names it as a CONTRACT_ID collision: ${thrown.message}") {
                thrown.message.orEmpty().contains("CONTRACT_ID") shouldBe true
            }
            val registered = ContractRegistry.contract(StableHash.of(GREETING_API))
            withClue("atomicity: valid-basic's own contract is still registered") {
                registered shouldNotBe null
            }
            withClue(
                "atomicity: the registered descriptor is still valid-basic's single-arg " +
                    "greet(String), not the colliding fixture's greet(String, Boolean) — " +
                    "${registered?.methods}"
            ) {
                registered!!.methods.size shouldBe 1
            }
            withClue("the refused module contributed nothing under the colliding contractId") {
                ContractRegistry.contributorsOf(
                    StableHash.of(GREETING_API)
                ).contains(COLLIDING_CONTRACT_MODULE) shouldBe false
            }
            withClue("[JAR1-ERR-05]: the classloader opened for the refused attempt is closed") {
                ModuleClassLoader.openLoaders.toSet() shouldBe before
            }
            loader.loaded().shouldBeEmpty()
        }
    }

    // ------------------------------------------------------------------
    // computenet-j1mm — a host callback that throws AFTER registration committed
    // ------------------------------------------------------------------

    /**
     * Load [FixtureJars.validBasic] with the given post-commit callbacks and
     * assert, whatever the load threw, that the committed module is intact:
     * listed in [ModuleLoader.loaded], REGISTERED, its contract in the registry,
     * and its classloader still open and still able to define its own classes.
     *
     * That is the invariant computenet-j1mm is about — "REGISTERED implies the
     * classloader is open" — stated once so both callbacks are checked against
     * exactly the same predicate.
     */
    private fun assertCommitSurvives(
        observe: (ModuleLoadRecord) -> Unit = {},
        onWireSerializers: (ModuleHandle, List<civictech.cell.wire.WireSerializers>) -> Unit = { _, _ -> },
    ) {
        val jar = FixtureJars.validBasic
        val loader = ModuleLoader(
            acceptedLocations = setOf(jar.toPath().toAbsolutePath().normalize().parent),
            observe = observe,
            onWireSerializers = onWireSerializers,
        )

        val thrown = shouldThrow<IllegalStateException> { loader.load(jar) }
        withClue("the host callback's own throwable reaches the caller unwrapped: ${thrown.message}") {
            thrown.message shouldBe "host callback exploded"
        }

        val handle = loader.loaded().singleOrNull()
        try {
            withClue("registration committed, so the handle must still be listed by loaded()") {
                handle shouldNotBe null
            }
            withClue("the committed handle is REGISTERED") {
                handle!!.state shouldBe ModuleState.REGISTERED
            }
            withClue("its contract is published in the registry") {
                ContractRegistry.contract(StableHash.of(GREETING_API)) shouldNotBe null
            }
            withClue("a REGISTERED module's classloader must still be open") {
                ModuleClassLoader.openLoaders shouldContain handle!!.classLoader
            }
            withClue("and must still be able to define the classes behind those descriptors") {
                handle!!.classLoader.loadClass(GREETING_API) shouldNotBe null
            }
        } finally {
            handle?.let {
                ModuleRegistration.unregister(it.id)
                it.classLoader.close()
            }
        }
    }

    @Test
    fun `an observe callback that throws after registration leaves the module registered with its classloader open`() {
        assertCommitSurvives(observe = { throw IllegalStateException("host callback exploded") })
    }

    @Test
    fun `an onWireSerializers seam that throws after registration leaves the module registered with its classloader open`() {
        assertCommitSurvives(onWireSerializers = { _, _ -> throw IllegalStateException("host callback exploded") })
    }

    @Test
    fun `a throwing onWireSerializers seam does not suppress the JAR1-SEC-04 observation of a committed load`() {
        val observed = mutableListOf<ModuleLoadRecord>()
        assertCommitSurvives(
            observe = { observed += it },
            onWireSerializers = { _, _ -> throw IllegalStateException("host callback exploded") },
        )
        withClue("[JAR1-SEC-04]: the load succeeded, so it is reported even though the wire seam threw") {
            observed.map { it.id } shouldBe listOf(ModuleId("fixture.valid-basic"))
        }
    }

    /**
     * The KDoc's step 7 states that when *both* post-commit callbacks throw, the
     * first throwable is rethrown and the second is attached to it with
     * [Throwable.addSuppressed]. Neither of the tests above reaches that arm —
     * each leaves the other callback well-behaved — so it is checked here, the
     * same way [ModuleUnloadTest] checks [JAR1-UNL-07]'s suppressed restore
     * failure. The expected strings are the literals thrown below; nothing here
     * recomputes them from production code.
     */
    @Test
    fun `when both post-commit callbacks throw, the first is rethrown and the second rides suppressed`() {
        val jar = FixtureJars.validBasic
        val loader = ModuleLoader(
            acceptedLocations = setOf(jar.toPath().toAbsolutePath().normalize().parent),
            observe = { throw IllegalStateException("observe exploded") },
            onWireSerializers = { _, _ -> throw IllegalStateException("onWireSerializers exploded") },
        )

        val thrown = shouldThrow<IllegalStateException> { loader.load(jar) }
        val handle = loader.loaded().singleOrNull()
        try {
            withClue("onWireSerializers runs first, so its throwable is the one the caller sees") {
                thrown.message shouldBe "onWireSerializers exploded"
            }
            withClue("the observation still ran, and its failure is not lost") {
                thrown.suppressed.map { it.message } shouldBe listOf("observe exploded")
            }
            withClue("and the commit survives both") {
                handle shouldNotBe null
                handle!!.state shouldBe ModuleState.REGISTERED
                ModuleClassLoader.openLoaders shouldContain handle.classLoader
            }
        } finally {
            handle?.let {
                ModuleRegistration.unregister(it.id)
                it.classLoader.close()
            }
        }
    }

    /**
     * computenet-iifu: a host whose two post-commit callbacks throw the exact
     * SAME [Throwable] instance (a cached/singleton exception, or a shared stub
     * in a test double) must not turn into `IllegalArgumentException`("Self-suppression
     * not permitted") — the host's own throwable must reach the caller unwrapped,
     * same as the two-distinct-instances case above.
     *
     * This is a lock-in test, not a bug reproduction: it PASSES against the code
     * as it stood before this task (verified by running it before adding any
     * production change). `t.addSuppressed(u)` in Kotlin resolves to
     * `kotlin.ExceptionsKt.addSuppressed`, not `java.lang.Throwable.addSuppressed`
     * — decompiling `ModuleLoader.class`'s `load()` shows
     * `invokestatic kotlin/ExceptionsKt.addSuppressed`, whose own bytecode does
     * `if_acmpeq` on the two references and returns without delegating to the JDK
     * method (which is the one that throws) when they are identical. So the
     * `IllegalArgumentException("Self-suppression not permitted")` the bead
     * describes cannot be produced by this call in Kotlin, regardless of instance
     * identity — see the KDoc on [ModuleLoader.load]'s post-commit section for
     * the full citation. No production change was made for this case; this test
     * exists to keep that guarantee from silently regressing (e.g. if the call
     * site were ever rewritten to force the Java method via an explicit cast).
     */
    @Test
    fun `when both post-commit callbacks throw the same throwable instance, the caller receives it unwrapped`() {
        val jar = FixtureJars.validBasic
        val shared = IllegalStateException("shared host failure")
        val loader = ModuleLoader(
            acceptedLocations = setOf(jar.toPath().toAbsolutePath().normalize().parent),
            observe = { throw shared },
            onWireSerializers = { _, _ -> throw shared },
        )

        val thrown = shouldThrow<IllegalStateException> { loader.load(jar) }
        val handle = loader.loaded().singleOrNull()
        try {
            withClue("the host's own throwable reaches the caller unwrapped: ${thrown.message}") {
                thrown shouldBe shared
            }
            withClue("no self-suppression: addSuppressed(this) was never attempted for the identical instance") {
                thrown.suppressed.toList().shouldBeEmpty()
            }
            withClue("and the commit survives both") {
                handle shouldNotBe null
                handle!!.state shouldBe ModuleState.REGISTERED
                ModuleClassLoader.openLoaders shouldContain handle.classLoader
            }
        } finally {
            handle?.let {
                ModuleRegistration.unregister(it.id)
                it.classLoader.close()
            }
        }
    }

    // ------------------------------------------------------------------
    // B2 — the anti-reflection tripwire [JAR1-DISC-03]
    // ------------------------------------------------------------------

    @Test
    fun `the registered PortDescriptor carries the doctored NatureVector the jar's ContractModule table carries`() {
        FixtureJars.withLoadedModule(FixtureJars.doctoredNature) { handle ->
            withClue("the doctored fixture loads successfully") {
                handle.state shouldBe ModuleState.REGISTERED
            }

            val cellClass = handle.classLoader.loadClass(DOCTORED_CELL)
            val descriptor = ContractRegistry.cellDescriptor(cellClass)
            withClue("the doctored fixture's cell descriptor must resolve") {
                descriptor shouldNotBe null
            }

            val port = descriptor!!.ports.singleOrNull { it.name == "trigger" }
            withClue("the doctored port must be present: ${descriptor.ports}") {
                port shouldNotBe null
            }

            // Restated literally rather than imported from the fixture's
            // DoctoredContractModule, so this test cannot pass vacuously by
            // comparing the doctored value against itself.
            val expectedDoctoredNatures = NatureVector.of(Monotonicity.MONOTONE)
            withClue("the registered natures must equal the jar's doctored value, not a re-derived one") {
                port!!.natures shouldBe expectedDoctoredNatures
            }
            withClue("and the doctored value must not be the KSP-default — otherwise this test would pass vacuously") {
                port!!.natures shouldNotBe NatureVector.DEFAULT
            }
        }
    }
}
