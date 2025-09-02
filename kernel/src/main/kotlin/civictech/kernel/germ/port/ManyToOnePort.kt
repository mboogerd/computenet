package civictech.kernel.germ.port

class ManyToOnePort<Api: Any> : Serve<Api>, Use<Api> {
    override fun serve(api: Api) {
        TODO("Not yet implemented")
    }

    override fun delegate(useApi: Use<Api>) {
        TODO("Not yet implemented")
    }

    override fun use(): Api {
        TODO("Not yet implemented")
    }

    override fun attach(invalidating: Invalidating) {
        TODO("Not yet implemented")
    }

    override fun detach(invalidating: Invalidating) {
        TODO("Not yet implemented")
    }
}