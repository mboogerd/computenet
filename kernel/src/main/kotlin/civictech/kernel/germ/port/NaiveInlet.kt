package civictech.kernel.germ.port

//class NaiveInlet<Api> : Port<Api> {
//
//    /** Current usable API instance */
//    private var api: Api? = null
//
//    /**
//     * Resolves the current API instance, rebuilding from origin if stale.
//     */
//    override fun use(): Api {
//        return api ?: throw IllegalStateException("Port has not been initialized")
//    }
//
//    /**
//     * Creates a new Port that derives from this one, without modification.
//     */
//    override fun reuse(): Use<Api> = Inlet<Api>()
//        .apply {
//            origin = this@Inlet
//            stale = true
//        }.also {
//            children += it
//        }
//
//    /**
//     * Replace the root and marks all downstream branches as stale.
//     */
//    override fun serve(api: Api) {
//        origin?.detach(this)
//        this@NaiveInlet.api = api
//        stale = false
//        children.forEach { it.invalidate() }
//    }
//
//    /**
//     * Sets the origin to a new Use, clearing any prior origin.
//     */
//    override fun delegate(useApi: Use<Api>) {
//        require(useApi != this)
//        setOrigin(useApi)
//        invalidate()
//    }
//
//    override fun detach(useApi: Use<Api>) {
//        children.remove(useApi)
//    }
//
//    /**
//     * true if this Port has no origin, false otherwise.
//     */
//    fun isRoot(): Boolean = origin == null
//
//    /**
//     * Marks this handle and each downstream branch/fork as stale.
//     */
//    override fun invalidate() {
//        if (stale) return
//        stale = true
//        children.forEach { it.invalidate() }
//    }
//
//    /**
//     * Changes the origin of this handle.
//     */
//    private fun setOrigin(newOrigin: Use<Api>?) {
//        origin?.detach(this)
//        origin = newOrigin
//    }
//
//    companion object Companion {
//        /**
//         * Create a root handle wrapping some API.
//         */
//        fun <Api> root(initialApi: Api): Inlet<Api> = Inlet<Api>().apply { serve(initialApi) }
//    }
//
//}