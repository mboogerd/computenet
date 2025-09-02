package civictech.kernel.germ.port

/*
- Question: Can we `use` a Port if we're not registered?
- Answer:
  - Not yet sure, but perhaps we can parameterize the configuration of a Cell to cover for this being mandatory or not
  - The .use(...) lends itself also well for passing in context: what client is using the API, in the context of what timestamp?

This touches on a bigger subject though:
- There are change roots, e.g. user actions
- There are also derived events, e.g. they match the timestamp but were indirectly triggered as a consequence of a root change on some other entity

Some cells may change by users, e.g. ad hoc; other cells might need to know about upstream cells?
- Question: When is that?
- Answer: Whenever a cell cannot change by a user, e.g. it has an invariant to uphold. In that case, user interference is undesirable.
          So, we mean to _prevent_ a Use from being obtained in an ad hoc way. Registration is a
          prerequisite for acquiring the Use.
 */
class SimplePort<Api>(val default: Api? = null) : Port<Api> {

    /** Current usable API implementation */
    private var activeImplementation: Api? = null

    /** Whether this Port's [activeImplementation] is outdated and must be rebuilt from origin */
    private var stale: Boolean = false

    /** Downstream Use from which this one derives, if any */
    private var origin: Use<Api>? = null

    /** Upstream branches that depend on this Use */
    private val implementationTrackers: MutableList<Invalidating> = mutableListOf()

    /**
     * Resolves the current API instance, rebuilding from origin if stale.
     */
    override fun use(): Api {
        if (stale) {
            val base = origin?.use() ?: throw IllegalStateException("No downstream to rebuild from")
            return base.also {
                activeImplementation = it
                stale = false
            }
        }
        return activeImplementation ?: default ?: throw IllegalStateException("Port has not been initialized")
    }

    /**
     * Replace the root and invalidates upstream branches
     */
    override fun serve(api: Api) {
        origin?.detach(this)
        activeImplementation = api
        stale = false
        implementationTrackers.forEach { it.invalidate() }
    }

    /**
     * Sets the origin to a new Use, clearing any prior origin.
     */
    override fun delegate(useApi: Use<Api>) {
        require(useApi != this)
        setOrigin(useApi)
        useApi.attach(this)
        invalidate()
    }

    override fun detach(invalidating: Invalidating) {
        implementationTrackers.remove(invalidating)
    }

    /**
     * true if this Port has no origin, false otherwise.
     */
    fun isRoot(): Boolean = origin == null

    /**
     * Marks this handle and each downstream branch/fork as stale.
     */
    override fun invalidate() {
        if (stale) return
        stale = true
        implementationTrackers.forEach { it.invalidate() }
    }

    override fun attach(invalidating: Invalidating) {
        implementationTrackers += invalidating
    }

    /**
     * Changes the origin of this handle.
     */
    private fun setOrigin(newOrigin: Use<Api>?) {
        origin?.detach(this)
        origin = newOrigin
    }

    internal fun isStale(): Boolean = stale

    companion object Companion {
        /**
         * Create a root handle wrapping some API.
         */
        fun <Api> root(initialApi: Api): SimplePort<Api> = SimplePort<Api>().apply { serve(initialApi) }
    }

}