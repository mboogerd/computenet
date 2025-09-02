package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

interface UseMany<Api> {
    /**
     * Resolves the current API instance, rebuilding from origin if stale.
     */
    fun one(portRef: PortRef): Api?

    /**
     * An Api instance that broadcasts each method invocation to all subscriptions.
     */
    fun all(): Api

    /**
     * Registers the listener as an observer of implementation changes
     * (which invalidates any cached `use`s)
     */
    fun attach(invalidating: Invalidating)

    /**
     * Detaches the given use from this one if it was a reuse, or a no-op otherwise
     */
    fun detach(invalidating: Invalidating)

    companion object {
        fun <Api> fixed(api: Api): UseMany<Api> = object : UseMany<Api> {
            override fun one(portRef: PortRef): Api? = api
            override fun all(): Api = api
            override fun attach(invalidating: Invalidating) {}
            override fun detach(invalidating: Invalidating) {}
        }
    }
}
