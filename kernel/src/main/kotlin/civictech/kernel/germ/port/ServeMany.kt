package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

interface ServeMany<Api> : LinkTo<Api> {
    fun subscribe(port: Use<Api>)
    fun unsubscribe(portRef: PortRef)
}