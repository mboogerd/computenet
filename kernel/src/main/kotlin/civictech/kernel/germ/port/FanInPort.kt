package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

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
class FanInPort<Api>(
    override val ref: PortRef = PortRef.generate(),
    default: Api? = null
) : Use<Api>, ServeOne<Api> {

    /** Current usable API implementation */
    private var activeImplementation: Use<Api>? = default?.let { Use.fixed(it) }

    override fun use(portRef: PortRef, block: Api.() -> Any?) {
        if (activeImplementation == null) throw IllegalStateException("Port has not been initialized")

        activeImplementation?.use(portRef) { block() }
    }

    /**
     * Resolves the current API instance, rebuilding from origin if stale.
     */
    override fun use(block: Api.() -> Any?) {
        if (activeImplementation == null) throw IllegalStateException("Port has not been initialized")

        activeImplementation?.use { block() }
    }

    /**
     * Replace the root and invalidates upstream branches
     */
    override fun serve(api: Api) {
        activeImplementation = Use.fixed(api)
    }

    /**
     * Sets the origin to a new Use, clearing any prior origin.
     */
    override fun delegate(useApi: Use<Api>) {
        require(useApi != this)
        activeImplementation = useApi

    }

    override fun linkFrom(portOut: LinkTo<Api>) {
        portOut.linkTo(this)
    }

    override fun linkTo(useApi: Use<Api>) {
        delegate(useApi)
    }

    companion object Companion {
        /**
         * Create a root handle wrapping some API.
         */
        fun <Api> root(initialApi: Api): FanInPort<Api> = FanInPort<Api>().apply { serve(initialApi) }
    }

}