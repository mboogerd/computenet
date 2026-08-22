// Lives in :nature (T09 §A) — see Contract.kt's header for why the package stays
// `civictech.gen.wire` despite the module move.
package civictech.gen.wire

import civictech.nature.ModuleId
import civictech.nature.ModuleRegistration
import civictech.nature.Provenance
import civictech.nature.RegistryMutation
import civictech.nature.Staging
import java.lang.reflect.InvocationHandler
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/** Constructs a KSP-generated in-process proxy for one `@Contract` interface. */
typealias ProxyConstructor = (InvocationHandler) -> Any

/**
 * Implemented by one generated file per compilation module — the KSP-emitted
 * factory table pairing each `@Contract` interface with the constructor of
 * its generated proxy class. Registered for `ServiceLoader`, mirroring
 * [ContractModule]'s discovery.
 */
interface ProxyModule {
    val factories: Map<Class<*>, ProxyConstructor>
}

/**
 * Runtime index of KSP-generated in-process proxy classes (C-5 completion,
 * spec 10/14 §Reflection budget): resolves an `@Contract` interface to the
 * constructor of its ahead-of-time-generated proxy class, so
 * `civictech.cell.proxy.Proxy.fromClass` never needs
 * `java.lang.reflect.Proxy.newProxyInstance` for a registered contract. Each
 * generated proxy class still dispatches through the same `InvocationHandler`
 * shape the existing proxy behaviors (`Buffering`, `NoOp`, `Callback`,
 * `HostProxy`, `MediateProxy`, ...) already use, so only proxy *construction*
 * changes — call sites are untouched.
 */
object ProxyRegistry {
    private val byInterface = ConcurrentHashMap<Class<*>, ProxyConstructor>()
    private val provenance = Provenance<Class<*>>()

    init {
        ServiceLoader.load(ProxyModule::class.java, ProxyModule::class.java.classLoader)
            .forEach { register(it) }
    }

    /**
     * Record [module]'s factories as contributions of [owner], defaulting to
     * [ModuleId.HOST] so pre-existing callers are unaffected.
     *
     * Proxy entries are keyed on `Class<*>` and their values are constructors,
     * which cannot be compared by equality — so an already-present key is treated
     * as an idempotent *additional contribution* rather than a conflict. Under
     * JAR1's shared-prefix rule a contract interface is one `Class` across
     * modules and its generated proxy constructor is functionally identical, so
     * there is nothing for a comparison to catch; unregistration drops the entry
     * only when no contributor remains.
     */
    fun register(module: ProxyModule, owner: ModuleId = ModuleId.HOST) {
        synchronized(RegistryMutation.lock) { commit(module, owner) }
    }

    /** Drop [owner]'s factory contributions. Prefer [ModuleRegistration.unregister]. */
    fun unregister(owner: ModuleId) {
        require(owner != ModuleId.HOST) {
            "the host module is not unregisterable: descriptors present at process start back the " +
                "running graph, so removing them would strand live cells"
        }
        synchronized(RegistryMutation.lock) { removeOwner(owner) }
    }

    /** Modules that contributed a proxy constructor for [clazz]. */
    fun contributorsOf(clazz: Class<*>): List<ModuleId> = provenance.of(clazz)

    /**
     * No conflict is reachable here (see [register]): constructors are not
     * comparable, so there is no non-equal contribution to detect. Present so the
     * combined seam can stage all three registries uniformly.
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun stage(module: ProxyModule, staging: Staging) = Unit

    internal fun commit(module: ProxyModule, owner: ModuleId) {
        module.factories.forEach { (clazz, constructor) ->
            // First writer wins: a later contributor of the same Class records itself
            // as an additional contributor without repointing the live constructor,
            // which is what makes removing one contributor leave the other resolvable.
            byInterface.putIfAbsent(clazz, constructor)
            provenance.add(clazz, owner)
        }
    }

    internal fun removeOwner(owner: ModuleId) {
        provenance.drop(owner).forEach { byInterface.remove(it) }
    }

    /** The generated proxy constructor for [clazz], or null when it carries no `@Contract`. */
    fun factory(clazz: Class<*>): ProxyConstructor? = byInterface[clazz]
}
