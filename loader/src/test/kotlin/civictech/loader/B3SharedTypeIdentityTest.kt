package civictech.loader

import civictech.nature.ContractModule
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Scenario B3 of feature computenet-051.1 — **shared type identity**
 * `[JAR1-ISO-02][JAR1-ISO-03][JAR1-ISO-06]`, plus the direct unit assertions for
 * `[JAR1-ISO-01]` (parent identity), `[JAR1-ISO-05]` (parallel-capable) and `close()`.
 *
 * Two module jars are opened through two independent [ModuleClassLoader]s, and the `Class`
 * objects for host-owned types resolved through each are asserted **reference-identical**
 * to each other and to this test's own. That is the whole of ISO-06: if it does not hold,
 * a payload handed from one module to the other is a `ClassCastException` between two
 * types that print the same name, which is the single worst failure mode a jar loader has.
 *
 * The *link and invocation* half of B3 — actually passing a value across — is feature
 * computenet-051.5; this task asserts `Class` identity directly, per the feature's design.
 *
 * The ISO-01/ISO-05/`close()` assertions live in this file rather than one of their own
 * because the task names them without naming a file and they are the same object's
 * construction contract; keeping them beside B3 avoids a fourth file asserting three lines.
 */
class B3SharedTypeIdentityTest {

    @Test
    fun `a host-owned kernel type resolves to one Class object through every module loader`() {
        withLoaders(validBasicJar, utilAJar) { a, b ->
            // civictech.cell. is a shared prefix [JAR1-ISO-03]; neither jar contains this
            // class, but that is not why the assertion holds — the smuggler fixture proves
            // a jar *may* contain it, and ISO-02 says it would still not be defined locally.
            val host = civictech.cell.Cell::class.java

            val viaA = a.loadClass(host.name)
            val viaB = b.loadClass(host.name)

            withClue("civictech.cell.Cell resolved through the two module loaders must be one Class") {
                (viaA === viaB) shouldBe true
            }
            withClue("and must be the host's own Class, not a third copy") {
                (viaA === host) shouldBe true
            }
        }
    }

    @Test
    fun `the nature ContractModule interface resolves to one Class object through every module loader`() {
        withLoaders(validBasicJar, utilAJar) { a, b ->
            // The registry type the whole loading story is built on: a module registers
            // ITS ContractModule into the HOST's ContractRegistry, so two copies of this
            // interface would make registration impossible [JAR1-ISO-02][JAR1-ISO-06].
            val host = ContractModule::class.java

            (a.loadClass(host.name) === host) shouldBe true
            (b.loadClass(host.name) === host) shouldBe true
        }
    }

    @Test
    fun `a class defined inside a module jar implements the hosts own kernel interface`() {
        // The identity assertions above are about names the loaders were *asked* for. This
        // one is about a name the JVM resolves on the module's behalf while defining a
        // module-local class: GreetingCell's supertype reference to civictech.cell.Cell is
        // resolved through its defining loader, so if delegation were child-first for a
        // shared prefix this would produce a Cell that is not the host's.
        withLoaders(validBasicJar, utilAJar) { a, _ ->
            val cell = a.loadClass("civictech.loader.fixture.validbasic.GreetingCell")

            withClue("GreetingCell must be defined by the module loader, not inherited from the parent") {
                cell.classLoader shouldBe a
            }
            withClue("its interfaces resolved through the module loader") {
                cell.interfaces.toList() shouldContain civictech.cell.Cell::class.java
            }
        }
    }

    @Test
    fun `a modules own generated ContractTable is defined locally despite its shared-looking package`() {
        // civictech.gen.wire.generated. is the one exemption from the shared prefixes
        // (ModuleClassLoader.MODULE_PRIVATE_SHARED_SUBPREFIXES): ContractProcessor emits
        // each module's tables there, into that module's own jar. If it were treated as
        // shared, this resolution would fail outright — the host has no such class — and
        // discovery (feature computenet-051.3) could never find a module's registration.
        withLoaders(validBasicJar, utilAJar) { a, _ ->
            val tableFqn = serviceEntryOf(validBasicJar)
            tableFqn shouldStartWith "civictech.gen.wire.generated."

            val table = a.loadClass(tableFqn)
            withClue("$tableFqn must be defined by the module loader that owns the jar carrying it") {
                table.classLoader shouldBe a
            }
            withClue("and it must still implement the HOST's ContractModule, or registration is impossible") {
                ContractModule::class.java.isAssignableFrom(table) shouldBe true
            }
        }
    }

    @Test
    fun `every loader is parented to the loader of ContractModule`() {
        // [JAR1-ISO-01], asserted against the requirement's own wording rather than
        // against "the app classloader": in a host that is itself loaded in a container
        // those are different loaders, and the registry types are what matter.
        ModuleClassLoader.open(validBasicJar).use { loader ->
            loader.parent shouldBe ContractModule::class.java.classLoader
            loader.parent shouldNotBe null
        }
    }

    @Test
    fun `every loader is registered parallel-capable`() {
        // [JAR1-ISO-05]. ClassLoader.getClassLoadingLock returns `this` for a loader that
        // is NOT registered parallel-capable, and a distinct per-name lock object for one
        // that is — so this discriminates the registration behaviourally, without reading
        // a private JDK field.
        ModuleClassLoader.open(validBasicJar).use { loader ->
            val one = loader.classLoadingLockFor("com.example.One")
            val two = loader.classLoadingLockFor("com.example.Two")

            withClue("a non-parallel-capable loader returns itself as the lock for every name") {
                (one === loader) shouldBe false
            }
            withClue("distinct names must take distinct locks") { (one === two) shouldBe false }
            withClue("the same name must take the same lock") {
                (one === loader.classLoadingLockFor("com.example.One")) shouldBe true
            }
        }
    }

    @Test
    fun `close releases the jar and the loader defines nothing further`() {
        val loader = ModuleClassLoader.open(validBasicJar)
        loader.loadClass("civictech.loader.fixture.validbasic.GreetingCell")

        loader.isClosed shouldBe false
        ModuleClassLoader.openLoaders shouldContain loader

        loader.close()

        loader.isClosed shouldBe true
        withClue("a closed loader must no longer count as open") {
            ModuleClassLoader.openLoaders.contains(loader) shouldBe false
        }
        // `URLClassLoader.close` semantics, asserted at the *resource* level. An earlier
        // version of this comment claimed that, on JDK 21, `loadClass` of an as-yet-undefined
        // entry could still succeed after `close()` because `URLClassPath` caches the jar's
        // index at first definition. That claim does NOT reproduce: computenet-0ick records
        // two independent 2026-08-28 measurements (a ComputeNet-level probe and a from-scratch
        // plain-`URLClassLoader` rebuild against Amazon Corretto 21.0.5 and Microsoft 21.0.11)
        // in which loadClass of a genuinely undefined entry threw ClassNotFoundException after
        // close in every case, including with the index pre-warmed by getResource/getResources/
        // a cached jar: URLConnection before close. Both measurements were taken on
        // darwin/arm64; Linux was NOT measured, so this records a non-reproduction on that
        // platform rather than a claim that no JDK anywhere behaves as the old text said.
        // What IS measured and asserted here is narrower: `findResource` goes null immediately
        // once `close()` returns.
        //
        // Separately, and stronger than any platform behaviour either way: ModuleClassLoader's
        // own `loadClass` (see its KDoc, ModuleClassLoader.kt, § "The closed check") refuses
        // every name that is not already defined once `closed` is set, throwing a
        // ClassNotFoundException that names the loader, its jar, and the fact that it is
        // closed — added by computenet-051.4.4, and asserted in B9UnloadReloadTest. So "no new class
        // is defined through a closed ModuleClassLoader" holds by construction, independent of
        // whatever `URLClassPath` does with its cached index; this test does not need to pin
        // that guarantee to the platform's behaviour at all.
        withClue("a closed loader must no longer serve resources from the released jar") {
            loader.findResource("civictech/loader/fixture/validbasic/GreetingApi.class") shouldBe null
        }
        withClue("close must be idempotent — an unload path may close a loader twice") {
            loader.close()
            loader.isClosed shouldBe true
        }
    }

    private fun withLoaders(first: File, second: File, body: (ModuleClassLoader, ModuleClassLoader) -> Unit) {
        ModuleClassLoader.open(first).use { a ->
            ModuleClassLoader.open(second).use { b -> body(a, b) }
        }
    }

    private companion object {
        /**
         * Fixture jar paths arrive as system properties wired on the `:loader` test task in
         * loader/build.gradle.kts; a test cannot otherwise know where a sibling project's
         * `jar` task wrote. Read through a helper so a missing property fails with the
         * reason rather than a NullPointerException three frames away.
         */
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

        val validBasicJar: File get() = fixtureJar("loader.fixture.validBasic")
        val utilAJar: File get() = fixtureJar("loader.fixture.utilA")

        /** The single class named by the jar's generated `ContractModule` services entry. */
        fun serviceEntryOf(jar: File): String =
            JarFile(jar).use { open ->
                val entry = open.getJarEntry("META-INF/services/civictech.nature.ContractModule")
                    ?: error("${jar.name} has no ContractModule services entry")
                open.getInputStream(entry).bufferedReader().readText()
                    .lineSequence().map { it.trim() }.first { it.isNotEmpty() }
            }
    }
}
