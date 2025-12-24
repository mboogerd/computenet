package civictech.kernel.germ.port

interface LinkFrom<Api> : Port {
    fun linkFrom(portOut: LinkTo<Api>)
}