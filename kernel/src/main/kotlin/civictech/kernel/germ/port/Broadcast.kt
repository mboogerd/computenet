package civictech.kernel.germ.port

import civictech.kernel.port.PortRef

interface Broadcast<Api> {
    fun subscribe(portRef: PortRef, portApi: Use<Api>)
    fun unsubscribe(portRef: PortRef)
}

interface Unicast<Api> {
    fun send(portApi: Use<Api>)
}