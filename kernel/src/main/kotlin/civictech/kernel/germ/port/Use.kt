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

    companion object Companion {
        fun <Api> fixed(api: Api, fixedPortRef: PortRef? = null): Use<Api> = object : Use<Api> {
            override fun use(portRef: PortRef, block: Api.() -> Any?) {
                api.takeIf { fixedPortRef == null || portRef == fixedPortRef }?.block()
            }

            override fun use(block: Api.() -> Any?) {
                api.block()
            }
        }
    }
}
