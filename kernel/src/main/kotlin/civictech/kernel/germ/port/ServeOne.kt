package civictech.kernel.germ.port

interface ServeOne<Api> : LinkTo<Api> {
    fun serve(api: Api)
    fun delegate(port: Use<Api>)
}