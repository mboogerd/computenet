package civictech.kernel.germ.port

interface LinkTo<Api> : Port {
    fun linkTo(useApi: Use<Api>)
    fun linkTo(linkFrom: LinkFrom<Api>) = linkFrom.linkFrom(this)
}