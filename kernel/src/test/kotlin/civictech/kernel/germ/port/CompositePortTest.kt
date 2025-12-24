package civictech.kernel.germ.port


//class UniServe<Api>(private var useApi: Use<Api>? = null) : Serve<Api> {
//
//    override fun serve(api: Api) {
//        useApi = Use.fixed(api)
//    }
//
//    override fun delegate(useApi: Use<Api>) {
//        this.useApi = useApi
//    }
//}
//
//class MultiServe<Api>(private val apis: MutableList<Use<Api>> = mutableListOf()) : Serve<Api> {
//
//    override fun serve(api: Api) {
//        // Doesn't make too much sense. We wouldn't typically provide multiple concrete implementations directly
//        apis += Use.fixed(api)
//    }
//
//    override fun delegate(useApi: Use<Api>) {
//        apis += useApi
//    }
//}
//
//class SingleUse<Api>(private val api: Api) : Use<Api> {
//    override fun use(): Api {
//        TODO("Not yet implemented")
//    }
//
//    override fun observeImplementationChanges(invalidating: Invalidating) {
//        TODO("Not yet implemented")
//    }
//
//    override fun detach(invalidating: Invalidating) {
//        TODO("Not yet implemented")
//    }
//}