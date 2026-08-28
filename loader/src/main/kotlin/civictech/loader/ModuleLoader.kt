package civictech.loader

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.wire.WireSerializers
import civictech.gen.wire.ProxyModule
import civictech.nature.ContractModule
import civictech.nature.ModuleId
import civictech.nature.ModuleRegistration
import java.io.File
import java.lang.System.Logger
import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import java.util.jar.JarFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The manifest main attributes that make a jar a ComputeNet module
 * [JAR1-DISC-01]. Decided at feature computenet-051.3; JAR2 and JAR3 inherit
 * these exact names.
 *
 * They are declared here, in `:loader` main sources, because they are the
 * *loader's* contract with module authors: the fixture build files under
 * `loader/fixtures/` set them, but a fixture is a consumer of this decision,
 * not its home.
 */
object ModuleManifest {
    /** Main attribute carrying the module's [ModuleId] string. */
    const val MODULE_ID = "ComputeNet-Module-Id"

    /**
     * Main attribute carrying the module's version string. Recorded **verbatim**
     * and never parsed, ordered, or compared [JAR1-DISC-04] — see
     * [ModuleHandle.version].
     */
    const val MODULE_VERSION = "ComputeNet-Module-Version"
}

/**
 * How far a [ModuleHandle] got. `LOADED -> VALIDATED -> REGISTERED`, and nothing
 * else: this feature never unloads, so `QUIESCING`, `UNREGISTERED` and `CLOSED`
 * are deliberately absent and belong to feature **computenet-051.4**, together
 * with the `unload` entry point that would drive them. A handle that reaches
 * [REGISTERED] stays there for the life of the process.
 */
enum class ModuleState {
    /** The jar is a module and its classloader is open; nothing is discovered yet. */
    LOADED,

    /**
     * Every discovered descriptor's type resolved inside the module's own loader
     * [JAR1-ERR-04]. Nothing is registered yet.
     */
    VALIDATED,

    /** The discovered tables are committed into the host registries [JAR1-REG-01]. */
    REGISTERED,
}

/**
 * What a load contributed, and where it came from. Handed to
 * [ModuleLoader]'s observation callback on every **successful** load
 * [JAR1-SEC-04].
 */
data class ModuleLoadRecord(
    val id: ModuleId,
    /** Verbatim, uninterpreted — see [ModuleHandle.version]. */
    val version: String,
    val jar: File,
    /**
     * Every descriptor this module contributed, as `kind:id` strings — contract
     * ids, protocol ids, cell fqns, and proxied class names. A flat list of
     * strings rather than the structured lists on [ModuleHandle] because this is
     * the *log record*: it has to survive being written to a line of text.
     */
    val contributedDescriptorIds: List<String>,
)

/**
 * One successfully loaded module. Obtained from [ModuleLoader.load]; never
 * constructed directly.
 *
 * The handle is the only place a module's identity, its verbatim version, its
 * classloader and what it contributed are held together, and it is what feature
 * computenet-051.4's unload path will take as its argument.
 */
class ModuleHandle internal constructor(
    /** From [ModuleManifest.MODULE_ID], and the owner every descriptor is registered under. */
    val id: ModuleId,

    /**
     * From [ModuleManifest.MODULE_VERSION], recorded **exactly** as the manifest
     * spelled it [JAR1-DISC-04].
     *
     * It is a `String` and there is deliberately no API here that parses,
     * orders, or compares it — not `compareTo`, not a `SemVer` view, not an
     * `isNewerThan`. Version *interpretation* is JAR2's decision, and an
     * accessor added here would pre-empt it in the one place every later
     * feature reads.
     */
    val version: String,

    /** The jar this module was loaded from. */
    val jar: File,

    /** The module's isolated loader [JAR1-ISO-01]. Open for the life of the handle. */
    val classLoader: ModuleClassLoader,

    /** `contractId`s of every [civictech.nature.ContractDescriptor] this module contributed. */
    val contractIds: List<Long>,

    /** `protocolId`s of every [civictech.nature.ProtocolDescriptor] this module contributed. */
    val protocolIds: List<String>,

    /** `fqn`s of every [civictech.nature.CellDescriptor] this module contributed. */
    val cellFqns: List<String>,

    /** Contract interfaces this module contributed a generated proxy constructor for. */
    val proxiedClasses: List<Class<*>>,

    /**
     * The [WireSerializers] services this module carries, recorded verbatim.
     *
     * Recorded and handed to [ModuleLoader]'s wire seam; **not** interpreted
     * here. Whether the load path refuses a module that carries none, and
     * whether the live [civictech.cell.wire.WireCodec] is rebuilt from these,
     * is [JAR1-REG-08]'s open arm and belongs to feature
     * **computenet-051.6** — this feature deliberately does not pre-decide it.
     */
    val wireSerializers: List<WireSerializers>,
) {
    @Volatile
    var state: ModuleState = ModuleState.LOADED
        internal set

    /**
     * The refs a [ModuleLoader.track] tracker has attributed to this module —
     * populated and drained only by [track]'s hooks, never by [load]. `internal`
     * (not `private`): [track] is a top-level extension function in this same
     * file/module, not a member of this class, so it needs at least module
     * visibility to reach this set. A concurrent set because the registry's
     * publish/unpublish hooks can fire from a scheduler thread that is not the
     * caller of [track] or of [liveInstances].
     */
    internal val liveRefs: MutableSet<CellRef> = ConcurrentHashMap.newKeySet()

    /**
     * Count of currently live cells attributed to this module by an attached
     * [ModuleLoader.track] tracker [JAR1-UNL-01].
     *
     * Zero until a tracker is attached via [ModuleLoader.track] — see that
     * method's KDoc for the exact scope (attach-time-forward only, local
     * publishes only, registry-less hosts excluded).
     */
    val liveInstances: Int get() = liveRefs.size

    /** The flat `kind:id` view [ModuleLoadRecord] carries. */
    internal fun contributedDescriptorIds(): List<String> =
        contractIds.map { "contract:$it" } +
            protocolIds.map { "protocol:$it" } +
            cellFqns.map { "cell:$it" } +
            proxiedClasses.map { "proxy:${it.name}" }

    override fun toString(): String = "ModuleHandle($id, version=$version, state=$state, jar=${jar.name})"
}

/**
 * Base of every refusal [ModuleLoader.load] raises. An [IllegalStateException]
 * so a caller that only wants "the load did not happen" can catch one type,
 * while the subclasses below distinguish the reasons a diagnostic has to keep
 * apart.
 */
open class ModuleLoadException internal constructor(
    val jar: File,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * [JAR1-SEC-02]: the jar is not under any of the loader's accepted locations.
 * Raised before any I/O beyond path normalization — no file is opened, no
 * classloader is created, and the loader never learns whether the path even
 * exists.
 */
class ModuleLocationRefusedException internal constructor(
    jar: File,
    val acceptedLocations: Set<Path>,
) : ModuleLoadException(
    jar,
    "Module jar ${jar.absolutePath} is refused: it lies outside this loader's accepted " +
        "locations. Accepted: ${acceptedLocations.joinToString(", ")}. The loader never scans a " +
        "directory and has no default location — the embedding application supplies the set."
)

/**
 * [JAR1-DISC-01][JAR1-ERR-02]: a readable jar whose manifest does not declare
 * **both** [ModuleManifest.MODULE_ID] and [ModuleManifest.MODULE_VERSION]. Not
 * a malformed jar — a jar that is simply not a ComputeNet module. No
 * classloader is created [JAR1-ERR-01].
 */
class NotAModuleException internal constructor(
    jar: File,
    val presentAttributes: Set<String>,
) : ModuleLoadException(
    jar,
    "Jar ${jar.absolutePath} is not a ComputeNet module: its META-INF/MANIFEST.MF main " +
        "attributes must declare both ${ModuleManifest.MODULE_ID} and " +
        "${ModuleManifest.MODULE_VERSION}; present: " +
        (if (presentAttributes.isEmpty()) "neither" else presentAttributes.sorted().joinToString(", ")) + "."
)

/**
 * Loads ComputeNet module jars into the running host.
 *
 * ## Security posture — read this before constructing one
 *
 * **A loaded module runs with the full privileges of the host process**
 * [JAR1-SEC-01]. There is no sandbox, no permission set, and no restriction of
 * any kind on what a module's code may do once its classes are defined: it can
 * read and write every file the host user can, open sockets, spawn processes,
 * and call any JDK API. **Loading a jar is equivalent to executing arbitrary
 * code as the host user.** Treat `load` exactly as you would treat running an
 * unknown binary, and only ever point it at jars you would have been willing to
 * run directly.
 *
 * Because that is the exposure, jars are accepted **only** from the
 * [acceptedLocations] set the embedding application supplies [JAR1-SEC-02].
 * There is no default location, no ambient directory, and the loader never
 * *enumerates* or scans a directory — it only answers yes or no about a jar the
 * caller already named. An entry may be a directory (jars directly under it, at
 * any depth, are accepted) or an exact jar path.
 *
 * **This class exposes no network-reachable load endpoint, and none may be
 * added here** [JAR1-SEC-03]. A "load this URL" entry point would move the
 * trust boundary from "the operator chose this file" to "whoever can reach this
 * port chose this file", which is spec 53's trust-boundary question and open
 * gap G-50 — WKB2's decision, not this loader's. A remote-load capability, if
 * one is ever wanted, is built *above* this class by code that has already
 * authenticated and authorized the request and has written the jar into an
 * accepted location.
 *
 * Every successful load is reported through [observe] [JAR1-SEC-04], which
 * defaults to an INFO emission on the `civictech.loader` [System.Logger].
 *
 * ## What a module jar must declare
 *
 * A jar is a ComputeNet module **iff** its `META-INF/MANIFEST.MF` main
 * attributes carry both [ModuleManifest.MODULE_ID] (`ComputeNet-Module-Id`) and
 * [ModuleManifest.MODULE_VERSION] (`ComputeNet-Module-Version`)
 * [JAR1-DISC-01]. Either one missing and the jar is refused as
 * *not-a-module* — [NotAModuleException], distinct from a malformed jar
 * [JAR1-ERR-02]. The version string is recorded verbatim and never interpreted
 * [JAR1-DISC-04]; see [ModuleHandle.version].
 *
 * Nothing else is required of a module author: the metadata a module
 * contributes is the ordinary output of the `ksp-cell` convention, discovered
 * via [ServiceLoader] over the module's own loader and handed to
 * [ModuleRegistration.register] **unmodified** [JAR1-DISC-02][JAR1-DISC-03].
 * This loader never derives, recomputes, or reflectively reconstructs a
 * descriptor, a `NatureVector` or a proxy factory from bytecode or annotations
 * — the generated tables *are* the metadata.
 *
 * @param acceptedLocations directories jars may sit under, or exact jar paths.
 *   An empty set accepts nothing, which is the correct default for a host that
 *   has not decided yet.
 * @param observe invoked once per successful load [JAR1-SEC-04].
 * @param onWireSerializers the [JAR1-REG-08] seam. Invoked after registration
 *   with the module's handle and its discovered [WireSerializers] services. The
 *   default records them on the handle and does nothing else; whether a
 *   non-wire-capable module is refused, or the live codec is rebuilt, is feature
 *   **computenet-051.6**'s decision and is deliberately not made here.
 */
class ModuleLoader(
    acceptedLocations: Set<Path>,
    private val observe: (ModuleLoadRecord) -> Unit = ::logLoadRecord,
    private val onWireSerializers: (ModuleHandle, List<WireSerializers>) -> Unit = { _, _ -> },
) {
    /** Normalized once, at construction — the comparison below must do no I/O. */
    val acceptedLocations: Set<Path> =
        java.util.Collections.unmodifiableSet(
            acceptedLocations.map { it.toAbsolutePath().normalize() }.toCollection(LinkedHashSet())
        )

    private val handles = CopyOnWriteArrayList<ModuleHandle>()

    /** Every module this loader has successfully loaded, in load order. */
    fun loaded(): List<ModuleHandle> = handles.toList()

    /**
     * Load [jar] as a ComputeNet module: detect, discover, validate, register.
     *
     * The steps, in the order the requirements demand:
     *
     * 1. **[JAR1-SEC-02]** — refuse a jar outside [acceptedLocations], before any
     *    I/O beyond path normalization.
     * 2. **[JAR1-ERR-01][JAR1-ERR-02][JAR1-DISC-01]** — read the manifest with
     *    [JarFile]. An unreadable/non-jar file and a manifest without the module
     *    attributes both fail here, and neither creates a classloader — which is
     *    exactly why the manifest is read with `JarFile` rather than by opening a
     *    loader and asking it for the resource.
     * 3. **[JAR1-ISO-01]** — [ModuleClassLoader.open]. From here on, *every*
     *    failure path closes that loader before returning [JAR1-ERR-05].
     * 4. **[JAR1-DISC-02][JAR1-DISC-03]** — [ServiceLoader] over the module's own
     *    loader for [ContractModule], [ProxyModule] and [WireSerializers],
     *    keeping only providers the module's loader itself defined (see
     *    [providersDefinedBy]). A provider that will not instantiate fails the
     *    load, naming it [JAR1-ERR-03].
     * 5. **[JAR1-ERR-04]** — resolve every contributed contract and cell fqn
     *    inside the module's loader. Fail at the door, not mid-wave.
     * 6. **[JAR1-REG-01]** — [ModuleRegistration.register] with the discovered
     *    tables unmodified. A refusal propagates as a load failure.
     *
     * @return a handle in state [ModuleState.REGISTERED].
     * @throws ModuleLocationRefusedException if [jar] is outside [acceptedLocations].
     * @throws NotAModuleException if the manifest lacks the module attributes.
     * @throws ModuleLoadException for an unreadable jar, an un-instantiable
     *   provider, or an unresolvable descriptor type.
     * @throws SmuggledSharedPrefixException if the jar bundles a host-owned class.
     * @throws civictech.nature.RegistrationRefusedException if the contributed
     *   tables collide with what is already registered.
     */
    fun load(jar: File): ModuleHandle {
        requireAcceptedLocation(jar)

        val (id, version) = readModuleAttributes(jar)

        // ERR-05 from here down: the loader exists, so every exit that is not a
        // returned handle closes it.
        val loader = ModuleClassLoader.open(jar)
        try {
            val contractModules = providersDefinedBy(ContractModule::class.java, loader, jar)
            val proxyModules = providersDefinedBy(ProxyModule::class.java, loader, jar)
            val wireSerializers = providersDefinedBy(WireSerializers::class.java, loader, jar)

            val handle = ModuleHandle(
                id = ModuleId(id),
                version = version,
                jar = jar,
                classLoader = loader,
                contractIds = contractModules.flatMap { m -> m.contracts.map { it.contractId } },
                protocolIds = contractModules.flatMap { m -> m.protocols.map { it.protocolId } },
                cellFqns = contractModules.flatMap { m -> m.cells.map { it.fqn } },
                proxiedClasses = proxyModules.flatMap { it.factories.keys },
                wireSerializers = wireSerializers,
            )

            resolveContributedTypes(contractModules, loader, jar)
            handle.state = ModuleState.VALIDATED

            // DISC-03: the discovered objects go in untouched. Anything that
            // rebuilt a descriptor here would pass every test in this file and
            // fail B2 (feature computenet-051.3's doctored-table tripwire).
            ModuleRegistration.register(
                owner = handle.id,
                contractModules = contractModules,
                proxyModules = proxyModules,
            )
            handle.state = ModuleState.REGISTERED

            handles.add(handle)
            onWireSerializers(handle, wireSerializers)
            observe(
                ModuleLoadRecord(
                    id = handle.id,
                    version = handle.version,
                    jar = handle.jar,
                    contributedDescriptorIds = handle.contributedDescriptorIds(),
                )
            )
            return handle
        } catch (t: Throwable) {
            loader.close()
            throw t
        }
    }

    /**
     * [JAR1-SEC-02]. Path comparison only — `toAbsolutePath().normalize()` is
     * lexical and touches no filesystem, so a refused jar is refused without the
     * loader having learned anything about it, not even whether it exists.
     *
     * `toRealPath()` is deliberately NOT used: it is I/O, it fails on a
     * non-existent path (turning a location refusal into a different error), and
     * resolving symlinks would make "accepted" depend on link targets the
     * operator did not enumerate.
     */
    private fun requireAcceptedLocation(jar: File) {
        val candidate = jar.toPath().toAbsolutePath().normalize()
        val accepted = acceptedLocations.any { it == candidate || candidate.startsWith(it) }
        if (!accepted) throw ModuleLocationRefusedException(jar, acceptedLocations)
    }

    /**
     * Read [ModuleManifest.MODULE_ID] and [ModuleManifest.MODULE_VERSION] from
     * the jar's main attributes [JAR1-DISC-01].
     *
     * @throws ModuleLoadException if the file is not a readable jar [JAR1-ERR-01].
     * @throws NotAModuleException if either attribute is absent [JAR1-ERR-02].
     */
    private fun readModuleAttributes(jar: File): Pair<String, String> {
        val (id, version) = try {
            JarFile(jar).use { open ->
                val main = open.manifest?.mainAttributes
                main?.getValue(ModuleManifest.MODULE_ID) to main?.getValue(ModuleManifest.MODULE_VERSION)
            }
        } catch (e: Exception) {
            throw ModuleLoadException(
                jar,
                "Cannot read ${jar.absolutePath} as a jar: ${e.javaClass.simpleName}: ${e.message}. " +
                    "No classloader was created and nothing was registered.",
                e,
            )
        }

        if (id == null || version == null) {
            throw NotAModuleException(
                jar,
                buildSet {
                    if (id != null) add(ModuleManifest.MODULE_ID)
                    if (version != null) add(ModuleManifest.MODULE_VERSION)
                },
            )
        }
        return id to version
    }

    /**
     * [JAR1-DISC-02] — every provider of [service] that **[loader] itself
     * defined**, instantiated.
     *
     * The filter is the load-bearing part. [ServiceLoader] walks the parent
     * chain, so a scan over a module's loader also finds the *host's* own
     * `ContractModule`/`ProxyModule`/`WireSerializers` tables on the process
     * classpath. Those are already registered under [ModuleId.HOST] by each
     * registry's init-time scan; re-registering them under this module's id
     * would attribute host descriptors to a module and, on unload, let a module
     * take the host's descriptors with it. `Provider.type()` is available
     * *without* instantiating, so the filter runs before any module code does.
     *
     * A provider that cannot be instantiated fails the whole load and the
     * diagnostic names it [JAR1-ERR-03]. [ServiceLoader]'s own
     * [ServiceConfigurationError] text already carries the provider's name, so
     * it is wrapped as the cause rather than swallowed.
     */
    private fun <S : Any> providersDefinedBy(service: Class<S>, loader: ModuleClassLoader, jar: File): List<S> {
        val providers = try {
            ServiceLoader.load(service, loader).stream()
                .filter { it.type().classLoader === loader }
                .toList()
        } catch (e: ServiceConfigurationError) {
            throw ModuleLoadException(
                jar,
                "Module jar ${jar.absolutePath} declares a ${service.name} service that could not be " +
                    "resolved: ${e.message}",
                e,
            )
        }

        return providers.map { provider ->
            try {
                provider.get()
            } catch (t: Throwable) {
                throw ModuleLoadException(
                    jar,
                    "Module jar ${jar.absolutePath} declares the ${service.name} provider " +
                        "${provider.type().name}, which could not be instantiated: " +
                        "${t.javaClass.name}: ${t.message}. Nothing was registered.",
                    t,
                )
            }
        }
    }

    /**
     * [JAR1-ERR-04] — "fail at the door, not mid-wave".
     *
     * Every contract fqn and every cell fqn a discovered table names is resolved
     * *inside the module's own loader*, without initializing it (`initialize =
     * false`: linking is what has to succeed, not the class's static state —
     * running a module's `<clinit>` here would execute module code during
     * validation, which is not what validation is for).
     *
     * A module compiled against a shared API type the host no longer declares
     * fails here with a [LinkageError] naming the absent type, rather than
     * surviving load and dying at first spawn — after registration has published
     * its descriptors and a graph has linked to them.
     */
    private fun resolveContributedTypes(modules: List<ContractModule>, loader: ModuleClassLoader, jar: File) {
        val fqns = modules.flatMap { m -> m.contracts.map { it.fqn } + m.cells.map { it.fqn } }.distinct()
        fqns.forEach { fqn ->
            try {
                resolve(fqn, loader)
            } catch (e: ClassNotFoundException) {
                throw ModuleLoadException(
                    jar,
                    "Module jar ${jar.absolutePath} contributes a descriptor for $fqn, which its own " +
                        "classloader cannot resolve: ${e.message}. Nothing was registered.",
                    e,
                )
            } catch (e: LinkageError) {
                throw ModuleLoadException(
                    jar,
                    "Module jar ${jar.absolutePath} contributes a descriptor for $fqn, which cannot be " +
                        "linked against this host: ${e.javaClass.name}: ${e.message}. The module was " +
                        "compiled against a type this host does not supply. Nothing was registered.",
                    e,
                )
            }
        }
    }

    /**
     * `Class.forName` for a descriptor fqn.
     *
     * Descriptor fqns come from KSP's `qualifiedName`, which spells a nested
     * class `Outer.Inner` while the JVM binary name is `Outer$Inner`. So a
     * plain [ClassNotFoundException] on the dotted name is retried with each
     * trailing dot rewritten to `$`, innermost first. A [LinkageError] is
     * **not** retried: the class was found, it just would not link, and that is
     * precisely the [JAR1-ERR-04] failure this method exists to surface.
     */
    private fun resolve(fqn: String, loader: ModuleClassLoader): Class<*> {
        try {
            return Class.forName(fqn, false, loader)
        } catch (first: ClassNotFoundException) {
            var candidate = fqn
            while (true) {
                val dot = candidate.lastIndexOf('.')
                if (dot < 0) throw first
                candidate = candidate.substring(0, dot) + '$' + candidate.substring(dot + 1)
                try {
                    return Class.forName(candidate, false, loader)
                } catch (_: ClassNotFoundException) {
                    // keep walking outwards
                }
            }
        }
    }

    companion object {
        /** The [System.Logger] the default [observe] emits on [JAR1-SEC-04]. */
        const val LOGGER_NAME: String = "civictech.loader"

        private val logger: Logger by lazy { System.getLogger(LOGGER_NAME) }

        /**
         * The default observation callback: one INFO line per successful load,
         * carrying the module id, the verbatim version, the jar location and the
         * contributed descriptor ids [JAR1-SEC-04]. A host with a richer
         * observation seam passes its own.
         */
        fun logLoadRecord(record: ModuleLoadRecord) {
            logger.log(
                Logger.Level.INFO,
                "loaded module ${record.id} version '${record.version}' from ${record.jar.absolutePath} " +
                    "contributing ${record.contributedDescriptorIds.size} descriptor(s): " +
                    record.contributedDescriptorIds.joinToString(", ")
            )
        }
    }
}

/**
 * Live-instance accounting per module [JAR1-UNL-01], resolved by observation
 * (feature computenet-051.4's risk R3) rather than by inventing a kernel
 * callback or wrapping [civictech.nature.CellFactory]: `ManagedHost`'s
 * `LifecycleTransition` KDoc names [LocationRegistry.onPublish] /
 * [LocationRegistry.onUnpublish] as the deliberate spawn/despawn observation
 * seam, and [LocationRegistry.publish] already captures the published cell's
 * concrete class (readable back via [LocationRegistry.describe]) before firing
 * `onPublish`. This attaches to exactly that seam.
 *
 * On every publish this registry announces, [LocationRegistry.describe] is
 * read for the published ref's class; if the class's own [ClassLoader] is
 * identical (`===`) to a loaded module's [ModuleHandle.classLoader], the ref is
 * recorded against that handle — both in the tracker's own ref→handle map and
 * on the handle's [ModuleHandle.liveInstances] set. On unpublish the ref→handle
 * map (not a fresh [LocationRegistry.describe] lookup — [LocationRegistry.unpublish]
 * has already dropped the description by the time `onUnpublish` fires) says
 * which handle to decrement.
 *
 * ## Scope, stated honestly
 *
 * - Only publishes this tracker observes **after** [track] is called are
 *   counted. A cell already live on [registry] when [track] attaches is
 *   invisible to it until that cell's own next despawn or re-publish.
 * - Remote publishes ([LocationRegistry.publish] with an `InvocationSink`)
 *   never carry a description — that capture is a local-publish-only side
 *   effect — so a remote cell is never attributed to any module. Unload is a
 *   local concern; this accounting does not reach across the wire.
 * - A host constructed `ManagedHost(registry = null)` never calls
 *   [LocationRegistry.publish] at all, so its cells are outside this
 *   accounting exactly as they are invisible to [LocationRegistry.describe]
 *   and [LocationRegistry.locate].
 * - Re-publishing an already-tracked ref (e.g. `resumeHost`'s republish) is
 *   idempotent: the ref→handle map entry and the handle's live-ref set are
 *   both plain set/map writes, not counters, so a repeat publish does not
 *   inflate [ModuleHandle.liveInstances].
 *
 * @return a handle whose [AutoCloseable.close] detaches both hook
 *   registrations and clears this tracker's own ref→handle map. Handles'
 *   [ModuleHandle.liveInstances] simply stop updating after detach — closing
 *   the tracker does not reset counts already recorded.
 */
fun ModuleLoader.track(registry: LocationRegistry): AutoCloseable {
    val refToHandle = ConcurrentHashMap<CellRef, ModuleHandle>()

    val onPublish = registry.onPublish { ref ->
        val clazz = registry.describe(ref)
        val handle = clazz?.let { published -> loaded().firstOrNull { it.classLoader === published.classLoader } }
        if (handle != null) {
            refToHandle[ref] = handle
            handle.liveRefs += ref
        }
    }
    val onUnpublish = registry.onUnpublish { ref ->
        refToHandle.remove(ref)?.let { handle -> handle.liveRefs -= ref }
    }

    return AutoCloseable {
        onPublish.close()
        onUnpublish.close()
        refToHandle.clear()
    }
}
