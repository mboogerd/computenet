package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

interface Use<Api> {
    /**
     * Resolves the current API instance, rebuilding from origin if stale.
     */
    fun use(portRef: PortRef, block: Api.() -> Any?)

    /**
     * An Api instance that broadcasts each method invocation to all subscriptions.
     */
    fun use(block: Api.() -> Any?)

    /**
     * Registers the listener as an observer of implementation changes
     * (which invalidates any cached `use`s)
     */
    fun attach(invalidating: Invalidating)

    /**
     * Detaches the given use from this one if it was a reuse, or a no-op otherwise
     */
    fun detach(invalidating: Invalidating)

    companion object Companion {
        fun <Api> fixed(api: Api, fixedPortRef: PortRef? = null): Use<Api> = object : Use<Api> {
            override fun use(portRef: PortRef, block: Api.() -> Any?) { api.takeIf { fixedPortRef == null || portRef == fixedPortRef }?.block() }
            override fun use(block: Api.() -> Any?) { api.block() }
            override fun attach(invalidating: Invalidating) { invalidating.invalidate() }
            override fun detach(invalidating: Invalidating) {}
        }
    }
}
