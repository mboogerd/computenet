package civictech.loader

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.gen.wire.ProxyRegistry
import civictech.nature.ContractRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import java.util.jar.JarFile

/**
 * Feature computenet-051.4, task computenet-051.4.4 — scenario **B9**'s reload
 * half and the post-close guarantees: `[JAR1-UNL-04]` **as amended 2026-08-28**
 * (computenet-ykzx) and `[JAR1-UNL-06]`.
 *
 * The removal half of B9 — "exactly this module's entries stop resolving while
 * another module's still do" — is the predecessor task's and lives in
 * [ModuleUnloadTest]; this file does not repeat it.
 *
 * ## What UNL-04 asserts here, and what the red run actually showed
 *
 * The amended criterion states that on JDK 21 a *closed* [java.net.URLClassLoader]
 * will still hand back a `Class` for an as-yet-undefined entry of its own jar
 * (`URLClassPath` caches the jar index at first definition; `close()` releases
 * the handle without invalidating it). **That behaviour did not reproduce here.**
 * Measured 2026-08-28 on JDK 21.0.5 (Amazon Corretto — the vendor this machine's
 * `jvmToolchain(21)` resolves to; the vendor is unpinned, so another machine's
 * toolchain 21 may differ),
 * against the *unmodified* `loadClass`, over every class entry of `valid-basic`:
 *
 * - after `open` → define `GreetingCell` → `close()`, every one of the four
 *   as-yet-undefined entries (`ContractTable_…`, `ProxyTable_…`,
 *   `GreetingApi_Proxy_…`, `GreetingApi_Proxy_…$Companion`) threw
 *   `ClassNotFoundException`; the only names that came back were `GreetingCell`
 *   and `GreetingApi`, both of which the JVM had already **defined** (the
 *   `findLoadedClass` short-circuit, not a fresh definition);
 * - after a full `ModuleLoader.load` → `unload`, the entries that came back were
 *   again exactly those `ServiceLoader` discovery had already defined, and the
 *   one genuinely-undefined entry left (`…$Companion`) threw.
 *
 * So the platform hole the amendment describes was not observable on this JDK,
 * and the honest red/green here is narrower than the criterion anticipated:
 * against the unmodified `loadClass` this test failed on the *diagnostic* —
 * `AssertionError: "civictech.gen.wire.generated.ContractTable_b79b7c5e16f69b55"
 * should include substring "valid-basic.jar"`, from the
 * `the refusal must name the loader, its jar and that it is closed` clue, because
 * the JDK's own `ClassNotFoundException` message is the bare class name — and
 * passes with the closed-flag check in [ModuleClassLoader.loadClass].
 *
 * The check is still worth its few lines: it makes "no new load is routed to a
 * closed loader" hold **by construction** across every JDK and every entry rather
 * than resting on a `URLClassPath` behaviour this run could not even reproduce,
 * and it turns an unload-after-the-fact into a named refusal instead of a
 * "class not found" that reads like a missing dependency. What it did **not** do
 * on this JDK is change any outcome from success to failure.
 */
class B9UnloadReloadTest {

    private companion object {
        const val GREETING_CELL = "civictech.loader.fixture.validbasic.GreetingCell"
        const val GREETING_API = "civictech.loader.fixture.validbasic.GreetingApi"

        /** The single class named by the jar's generated `ContractModule` services entry. */
        fun serviceEntryOf(jar: File): String =
            JarFile(jar).use { open ->
                val entry = open.getJarEntry("META-INF/services/civictech.nature.ContractModule")
                    ?: error("${jar.name} has no ContractModule services entry")
                open.getInputStream(entry).bufferedReader().readText()
                    .lineSequence().map { it.trim() }.first { it.isNotEmpty() }
            }
    }

    // ------------------------------------------------------------------
    // [JAR1-UNL-06] — reload
    // ------------------------------------------------------------------

    @Test
    fun `the same jar loads again after unload, with Class objects distinct from the first load's`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)

        val first = loader.load(FixtureJars.validBasic)
        // "Used", per the scenario: the cell class is resolved through the module's
        // own loader and instantiated, so the first load's Class objects genuinely
        // exist and are not merely names.
        val firstCellClass = first.classLoader.loadClass(GREETING_CELL)
        val firstApiClass = first.classLoader.loadClass(GREETING_API)
        firstCellClass.getDeclaredConstructor(CellRef::class.java)
            .newInstance(CellRef(UUID.randomUUID())) as Cell

        loader.unload(first)
        first.state shouldBe ModuleState.CLOSED

        val second = loader.load(FixtureJars.validBasic)
        try {
            withClue("UNL-06: the reload must produce a REGISTERED handle this loader lists") {
                second.state shouldBe ModuleState.REGISTERED
                loader.loaded() shouldContain second
                loader.loaded().contains(first) shouldBe false
            }

            val secondCellClass = second.classLoader.loadClass(GREETING_CELL)
            val secondApiClass = second.classLoader.loadClass(GREETING_API)

            withClue("a reload defines NEW classes: a revived handle would be UNL-06's failure mode") {
                (secondCellClass === firstCellClass) shouldBe false
                (secondApiClass === firstApiClass) shouldBe false
                secondCellClass.name shouldBe firstCellClass.name
            }
            withClue("and they are defined by the NEW loader") {
                (secondCellClass.classLoader === second.classLoader) shouldBe true
                (secondCellClass.classLoader === first.classLoader) shouldBe false
            }
            withClue("the reloaded module's contributions resolve again") {
                ContractRegistry.descriptor(secondApiClass) shouldNotBe null
                ProxyRegistry.factory(secondApiClass) shouldNotBe null
            }
        } finally {
            if (second.state == ModuleState.REGISTERED) loader.unload(second)
        }
    }

    // ------------------------------------------------------------------
    // [JAR1-UNL-04] as amended — post-close guarantees
    // ------------------------------------------------------------------

    @Test
    fun `after unload the module's loader is closed, serves no resource, and is not an open loader`() {
        val loader = FixtureJars.loaderAccepting(FixtureJars.validBasic)
        val handle = loader.load(FixtureJars.validBasic)
        val moduleLoader = handle.classLoader

        ModuleClassLoader.openLoaders shouldContain moduleLoader

        loader.unload(handle)

        withClue("UNL-04: the classloader is closed") { moduleLoader.isClosed shouldBe true }
        withClue("and removed from the loader's own live-loader set") {
            ModuleClassLoader.openLoaders.contains(moduleLoader) shouldBe false
        }
        withClue("the jar handle is released: no path resolves as a resource any more") {
            moduleLoader.findResource("civictech/loader/fixture/validbasic/GreetingCell.class") shouldBe null
            moduleLoader.findResource("civictech/loader/fixture/validbasic/GreetingApi.class") shouldBe null
            moduleLoader.findResource("META-INF/MANIFEST.MF") shouldBe null
        }
        withClue("and this loader routes no new load to it — the handle is gone from loaded()") {
            loader.loaded().contains(handle) shouldBe false
        }
    }

    @Test
    fun `a closed loader defines no further class from its released jar`() {
        // RED/GREEN, per the amended [JAR1-UNL-04] — but see this class's KDoc for
        // what the red run actually showed. Against the UNMODIFIED
        // ModuleClassLoader.loadClass a ClassNotFoundException WAS already thrown
        // on JDK 21.0.5 (the cached-jar-index behaviour the amendment describes did
        // not reproduce); what failed was the diagnostic assertion below, since the
        // JDK's own message is the bare class name. The sequence is still the one
        // the amendment prescribes: define ONE class (warming the index), close,
        // then ask for a DIFFERENT, as-yet-undefined entry.
        val undefinedEntry = serviceEntryOf(FixtureJars.validBasic)
        val moduleLoader = ModuleClassLoader.open(FixtureJars.validBasic)

        val defined = moduleLoader.loadClass(GREETING_CELL)
        withClue("precondition: the entry we will ask for after close must not be defined yet") {
            (defined.name == undefinedEntry) shouldBe false
        }

        moduleLoader.close()

        val refusal = shouldThrow<ClassNotFoundException> { moduleLoader.loadClass(undefinedEntry) }
        withClue("the refusal must name the loader, its jar and that it is closed") {
            val message = refusal.message ?: ""
            message shouldContain undefinedEntry
            message shouldContain FixtureJars.validBasic.name
            message shouldContain "closed"
        }

        withClue("but an ALREADY-defined class stays returnable: an instance that outlived the unload") {
            // already references its Class, so refusing the lookup would add failure
            // without adding safety.
            (moduleLoader.loadClass(GREETING_CELL) === defined) shouldBe true
        }
        withClue("a shared type this loader already initiated stays returnable, like any defined class") {
            // Defining GreetingCell resolved its `civictech.cell.Cell` supertype
            // through this loader, which makes it an initiating loader of Cell — so
            // step 1 answers, exactly as for GreetingCell itself.
            moduleLoader.loadClass(Cell::class.java.name) shouldBe Cell::class.java
        }
        withClue("but a shared name it never initiated is refused too: closed means closed") {
            // Delegating this to the parent would be harmless, but "a closed loader
            // is not a route to load anything new" is the simpler contract, and a
            // caller that needs a host type has the host's own loader to ask.
            val shared = shouldThrow<ClassNotFoundException> {
                moduleLoader.loadClass("civictech.cell.host.ManagedHost")
            }
            (shared.message ?: "") shouldContain "closed"
        }
    }
}
