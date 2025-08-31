package civictech.kernel.germ.port

interface Use<Api> {
    /**
     * Resolves the current API instance, rebuilding from origin if stale.
     * @deprecated Use `broadcast` / `announce` or `unicast(portRef)` / `send(portRef)` instead
     */
    fun use(): Api

    /**
     * Registers the listener as an observer of implementation changes
     * (which invalidates any cached `use`s)
     */
    fun observeImplementationChanges(invalidating: Invalidating)

    /**
     * Detaches the given use from this one if it was a reuse, or a no-op otherwise
     */
    fun detach(invalidating: Invalidating)

    companion object {
        fun <Api> fixed(api: Api): Use<Api> = object : Use<Api> {
            override fun use(): Api = api
            override fun observeImplementationChanges(invalidating: Invalidating) {}
            override fun detach(invalidating: Invalidating) {}
        }
    }
}
