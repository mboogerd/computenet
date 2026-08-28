package civictech.loader

import civictech.nature.ContractModule
import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * Raised when a module jar is refused because it bundles a class whose name falls inside
 * the loader's shared-prefix space [JAR1-ISO-08].
 *
 * The message names every offending class, because the whole point of the requirement is
 * that a module author can tell *which* type they smuggled. [offendingClasses] carries the
 * same names structurally so a caller does not have to parse prose.
 */
class SmuggledSharedPrefixException internal constructor(
    val jar: File,
    val offendingClasses: List<String>,
) : IllegalStateException(
    "Module jar ${jar.absolutePath} is refused: it bundles ${offendingClasses.size} class(es) " +
        "whose names fall inside the loader's shared-prefix space, which the host owns and a " +
        "module may never redefine: ${offendingClasses.joinToString(", ")}. " +
        "Shared prefixes: ${ModuleClassLoader.SHARED_PREFIXES.joinToString(", ")}; " +
        "exempt sub-prefixes: ${ModuleClassLoader.MODULE_PRIVATE_SHARED_SUBPREFIXES.joinToString(", ")}."
)

/**
 * One classloader per loaded module jar — the isolation half of JAR1 (epic
 * computenet-051, feature computenet-051.1, requirement ids `[JAR1-ISO-01..08]`).
 *
 * Two rules, and nothing else:
 *
 * - Names inside [SHARED_PREFIXES] are **always** delegated parent-first and are never
 *   defined locally, so the host and every loaded module see one `Class` object for every
 *   type they exchange [JAR1-ISO-02][JAR1-ISO-03][JAR1-ISO-06].
 * - Every other name is searched **child-first** — this jar before the parent — so two
 *   modules may carry different builds of the same non-shared fully-qualified name and
 *   neither observes the other's [JAR1-ISO-04][JAR1-ISO-07].
 *
 * Instances are obtained from [open], never constructed directly: [open] performs the
 * eager smuggle scan that [JAR1-ISO-08] requires, and a public constructor would be a way
 * around it.
 *
 * ### Why the prefix set is a declared constant
 *
 * Epic risk 051-R2 explicitly rejects the heuristic "delegate anything the parent can
 * load". That heuristic makes the isolation boundary a function of whatever happens to be
 * on the host classpath — it moves when an unrelated dependency is added, it cannot be
 * reviewed, and it silently converts a module's private dependency into a shared one the
 * first time the host acquires a class of the same name. [SHARED_PREFIXES] is therefore an
 * enumerable set of package-name prefixes, reviewable in this one place.
 */
class ModuleClassLoader private constructor(
    /** The single jar this loader defines classes from. */
    val jar: File,
    parent: ClassLoader?,
) : URLClassLoader("module:${jar.name}", arrayOf(jar.toURI().toURL()), parent) {

    /**
     * The loader every shared-prefix name is delegated to. Held separately from
     * [getParent] because that returns `null` for the bootstrap loader, which is not a
     * value `loadClass` can be called on.
     */
    private val delegate: ClassLoader = parent ?: ClassLoader.getPlatformClassLoader()

    @Volatile
    private var closed: Boolean = false

    /** True once [close] has run. A closed loader defines no further classes. */
    val isClosed: Boolean get() = closed

    /**
     * The standard three steps, with the shared/private split in the middle:
     *
     * 1. already loaded by this loader -> that class;
     * 2. shared prefix -> the parent, unconditionally, without ever consulting this jar
     *    (that is what makes [JAR1-ISO-06]'s identity guarantee hold rather than merely
     *    usually hold);
     * 3. otherwise this jar first, and only on [ClassNotFoundException] the parent.
     *
     * Step 3's fallback is what lets a module use host types it did not bundle — the
     * ordinary case — while step 2 keeps the types the host *owns* out of the module's
     * reach entirely.
     *
     * ### The closed check, and why it sits *after* step 1
     *
     * [JAR1-UNL-04] (as amended 2026-08-28, computenet-ykzx) wants "no new load is
     * routed to a closed loader" to hold **by construction** rather than by whatever
     * the JDK's `URLClassPath` happens to do with its cached jar index after
     * [close]. Once [closed] is set, every name that is not *already defined* is
     * refused with a [ClassNotFoundException] naming this loader, its jar, and the
     * fact that it is closed — a diagnostic a caller can act on, in place of a bare
     * "class not found" that looks like a missing dependency.
     *
     * Already-defined classes stay returnable through step 1 deliberately. An
     * instance that outlived its module's unload already holds a reference to its
     * `Class`; refusing the *lookup* of a class the JVM has by then permanently
     * defined would add a failure mode without adding any safety.
     *
     * Shared-prefix names are refused too once closed. They resolve through the
     * parent and so would be harmless, but "a closed loader is not a route to load
     * anything new" is a simpler contract than one with an exception in it, and a
     * caller that still needs a host type has the host's own loader to ask.
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val already = findLoadedClass(name)
            if (already != null) {
                if (resolve) resolveClass(already)
                return already
            }

            if (closed) {
                throw ClassNotFoundException(
                    "$name cannot be loaded through $this: this ModuleClassLoader for " +
                        "${jar.absolutePath} is closed. Its module was unloaded, so no further " +
                        "class is defined from that jar; classes it had already defined remain " +
                        "resolvable through instances that hold them."
                )
            }

            val loaded = if (isShared(name)) {
                delegate.loadClass(name)
            } else {
                try {
                    findClass(name)
                } catch (_: ClassNotFoundException) {
                    delegate.loadClass(name)
                }
            }

            if (resolve) resolveClass(loaded)
            return loaded
        }

    override fun close() {
        closed = true
        liveLoaders.remove(this)
        super.close()
    }

    /**
     * Test seam for [JAR1-ISO-05]. `getClassLoadingLock` is `protected` on [ClassLoader]
     * and returns a per-name lock only when the loader is registered parallel-capable —
     * otherwise it returns `this`. Exposing it here lets that registration be asserted
     * behaviourally rather than by reading a private JDK field.
     */
    internal fun classLoadingLockFor(name: String): Any = getClassLoadingLock(name)

    override fun toString(): String = "ModuleClassLoader(${jar.name}${if (closed) ", closed" else ""})"

    companion object {
        init {
            // [JAR1-ISO-05]. Must happen before any instance exists, hence a companion
            // `init` rather than anything on the instance path.
            registerAsParallelCapable()
        }

        /**
         * The host-owned name space [JAR1-ISO-03]. Matching is on literal package-name
         * prefix, so every entry ends in a dot and `civictech.cellar.Foo` does not match
         * `civictech.cell.`.
         *
         * - `civictech.nature.` — descriptors and registries: a module's descriptors are
         *   registered into the host's registry objects, so both sides must mean the same
         *   `ContractDescriptor`.
         * - `civictech.gen.wire.` — the generated-code *runtime* surface (`Contract`,
         *   `ProxyRegistry`, `WireSerializers`): a proxy generated into a module and a
         *   proxy generated into the host implement one interface or they cannot be
         *   dispatched through the same registry.
         * - `civictech.cell.` — the kernel model itself. Two `Cell` interfaces means two
         *   incompatible runtimes.
         * - `kotlin.`, `kotlinx.serialization.`, `java.`, `javax.` — the language and
         *   platform runtimes, which are shared by definition.
         */
        val SHARED_PREFIXES: Set<String> = java.util.Collections.unmodifiableSet(
            linkedSetOf(
                "civictech.nature.",
                "civictech.gen.wire.",
                "civictech.cell.",
                "kotlin.",
                "kotlinx.serialization.",
                "java.",
                "javax.",
            )
        )

        /**
         * The one carve-out from [SHARED_PREFIXES], and the reason it exists.
         *
         * `ContractProcessor` emits every module's generated tables and proxies into
         * `civictech.gen.wire.generated` (gen/src/main/kotlin/civictech/gen/wire/
         * ContractProcessor.kt, `GENERATED_PACKAGE`), and each module's `ksp-cell` build
         * puts *its own* copies in *its own* jar — verified 2026-08-28 by listing this
         * epic's fixture jars: `:loader:fixtures:valid-basic` carries
         * `civictech/gen/wire/generated/ContractTable_b79b7c5e16f69b55.class`,
         * `:loader:fixtures:util-a` carries `ContractTable_c417f322cb4ebecd.class`, and so
         * on for every real module.
         *
         * That package therefore sits inside the shared prefix `civictech.gen.wire.` while
         * being, per module, module-private by construction: the classes are named from a
         * hash of the module's own contract and cell FQNs, no two modules generate the
         * same one, and the host has no copy to delegate to. Treating them as shared would
         * make the loader reject every well-formed module under [JAR1-ISO-08] and, had it
         * not, would make the module's `ContractModule` implementation unresolvable
         * (parent-first would search a parent that has never seen it), which is the class
         * discovery — feature computenet-051.3 — has to load.
         *
         * So the split is: `civictech.gen.wire.` is shared **API**, delegated parent-first;
         * `civictech.gen.wire.generated.` is per-module **output**, resolved child-first
         * and permitted in a module jar. The exemption is stated as its own enumerable
         * constant for the same reason [SHARED_PREFIXES] is — 051-R2's objection is to
         * boundaries that cannot be read off a page, not to boundaries with two parts.
         *
         * This is a deliberate divergence from the literal reading of [JAR1-ISO-03] and
         * [JAR1-ISO-08], recorded on computenet-051.1.2.
         */
        val MODULE_PRIVATE_SHARED_SUBPREFIXES: Set<String> = java.util.Collections.unmodifiableSet(
            linkedSetOf("civictech.gen.wire.generated.")
        )

        /**
         * The parent every [ModuleClassLoader] gets by default [JAR1-ISO-01]: the loader
         * that loaded [ContractModule]. Named that way rather than "the app classloader"
         * because the requirement is about the loader that owns the *registry types* — in
         * a host that is itself loaded in a container, those are not the same loader.
         */
        val hostParent: ClassLoader get() = ContractModule::class.java.classLoader

        private val liveLoaders = ConcurrentHashMap.newKeySet<ModuleClassLoader>()

        /**
         * Every loader created by [open] and not yet closed. The observable state
         * [JAR1-ISO-08]'s "leaving no classloader open" is asserted against.
         */
        val openLoaders: Set<ModuleClassLoader> get() = java.util.Collections.unmodifiableSet(liveLoaders)

        /** True when [name] is owned by the host and must never be defined by a module. */
        fun isShared(name: String): Boolean =
            SHARED_PREFIXES.any { name.startsWith(it) } &&
                MODULE_PRIVATE_SHARED_SUBPREFIXES.none { name.startsWith(it) }

        /**
         * Open [jar] as an isolated module [JAR1-ISO-01].
         *
         * The jar's entries are scanned **eagerly**, here, rather than at first resolution:
         * [JAR1-ISO-08] refuses the *load*, and a lazy scan would leave a module half-live
         * — registered, linked, possibly already dispatched to — before anyone noticed the
         * smuggled type.
         *
         * The loader is constructed before the scan and closed if the scan refuses, so the
         * refusal path exercises the same close-on-failure discipline as every other
         * failure in this epic (ERR-05) rather than avoiding it by construction ordering.
         *
         * @throws SmuggledSharedPrefixException if the jar bundles a shared-prefix class.
         */
        fun open(jar: File, parent: ClassLoader = hostParent): ModuleClassLoader {
            val loader = ModuleClassLoader(jar, parent)
            liveLoaders.add(loader)
            try {
                val smuggled = smuggledClassesIn(jar)
                if (smuggled.isNotEmpty()) throw SmuggledSharedPrefixException(jar, smuggled)
            } catch (t: Throwable) {
                loader.close()
                throw t
            }
            return loader
        }

        /**
         * Every class entry in [jar] whose name [isShared]. Returned sorted so a diagnostic
         * naming several classes reads the same way on every run.
         */
        private fun smuggledClassesIn(jar: File): List<String> =
            JarFile(jar).use { open ->
                open.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }
                    .filter { isShared(it) }
                    .toSortedSet()
                    .toList()
            }
    }
}
