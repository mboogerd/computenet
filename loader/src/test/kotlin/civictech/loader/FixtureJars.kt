package civictech.loader

import civictech.nature.ContractModule
import civictech.nature.ModuleRegistration
import java.io.File
import java.util.jar.JarFile

/**
 * Shared access to the fixture jars and the register/unregister bracket every
 * load-path test needs.
 *
 * Two things live here rather than in each test's own companion object:
 *
 * - **Jar lookup.** Fixture jar paths arrive as system properties wired on the
 *   `:loader` `test` task in loader/build.gradle.kts; a test cannot otherwise
 *   know where a sibling project's `jar` task wrote. (The isolation tests of
 *   feature computenet-051.1 each carry their own private copy of this helper;
 *   they are left alone — the point of putting it here is that the load-path
 *   suite, which is still growing, shares one.)
 * - **[withLoadedModule].** `ContractRegistry`, `ProtocolRegistry` and
 *   `ProxyRegistry` are process-global objects, so a test that registers a
 *   module and does not unregister it leaks descriptors into every test that
 *   runs after it in the same JVM. The bracket is mandatory, not tidy-up.
 */
internal object FixtureJars {

    fun jarFor(property: String): File {
        val path = System.getProperty(property)
            ?: error(
                "System property '$property' is not set. It must be wired in " +
                    "loader/build.gradle.kts on the :loader `test` task."
            )
        return File(path).also {
            check(it.isFile) { "$property points at ${it.absolutePath}, which is not a file" }
        }
    }

    val validBasic: File get() = jarFor("loader.fixture.validBasic")
    val noAttrs: File get() = jarFor("loader.fixture.noAttrs")
    val emptyModule: File get() = jarFor("loader.fixture.emptyModule")
    val utilA: File get() = jarFor("loader.fixture.utilA")
    val utilB: File get() = jarFor("loader.fixture.utilB")
    val smuggler: File get() = jarFor("loader.fixture.smuggler")
    val throwingProvider: File get() = jarFor("loader.fixture.throwingProvider")
    val missingSharedType: File get() = jarFor("loader.fixture.missingSharedType")
    val doctoredNature: File get() = jarFor("loader.fixture.doctoredNature")
    val collidingContract: File get() = jarFor("loader.fixture.collidingContract")
    val wireDelta: File get() = jarFor("loader.fixture.wireDelta")
    val flow: File get() = jarFor("loader.fixture.flow")

    /** A [ModuleLoader] accepting exactly the directory [jars] sit in, and nothing else. */
    fun loaderAccepting(vararg jars: File, observe: (ModuleLoadRecord) -> Unit = {}): ModuleLoader =
        ModuleLoader(
            acceptedLocations = jars.map { it.toPath().toAbsolutePath().normalize().parent }.toSet(),
            observe = observe,
        )

    /**
     * Load [jar], run [body] against the handle, then unregister the module's
     * descriptors and close its classloader — whatever [body] did.
     */
    fun <T> withLoadedModule(jar: File, loader: ModuleLoader = loaderAccepting(jar), body: (ModuleHandle) -> T): T {
        val handle = loader.load(jar)
        try {
            return body(handle)
        } finally {
            ModuleRegistration.unregister(handle.id)
            handle.classLoader.close()
        }
    }

    /**
     * The [ContractModule] the jar's own `META-INF/services` entry names,
     * instantiated through [classLoader].
     *
     * Used to compare what the registry holds against what the *jar* carries,
     * without going through [ModuleLoader] a second time — the point of B1 is
     * that the loader transported the generated table rather than rebuilding
     * something equal-looking.
     */
    fun contractModuleIn(jar: File, classLoader: ClassLoader): ContractModule {
        val fqn = JarFile(jar).use { open ->
            val entry = open.getJarEntry("META-INF/services/civictech.nature.ContractModule")
                ?: error("${jar.name} has no ContractModule services entry")
            open.getInputStream(entry).bufferedReader().readText()
                .lineSequence().map { it.trim() }.first { it.isNotEmpty() }
        }
        return Class.forName(fqn, true, classLoader).getDeclaredConstructor().newInstance() as ContractModule
    }
}
