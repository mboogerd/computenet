// Lives in :nature (T09 §A) — see Contract.kt's header for why the package stays
// `civictech.gen.wire` despite the module move.
package civictech.gen.wire

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

    init {
        ServiceLoader.load(ProxyModule::class.java, ProxyModule::class.java.classLoader)
            .forEach(::register)
    }

    fun register(module: ProxyModule) {
        byInterface.putAll(module.factories)
    }

    /** The generated proxy constructor for [clazz], or null when it carries no `@Contract`. */
    fun factory(clazz: Class<*>): ProxyConstructor? = byInterface[clazz]
}
