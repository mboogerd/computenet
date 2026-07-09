package civictech.kernel.computelet

import civictech.kernel.link.DefaultLink
import civictech.kernel.port.DefaultPort
import civictech.kernel.protocol.Emit
import civictech.kernel.protocol.Message

interface MessageProcessor {
    fun process(port: DefaultPort, link: DefaultLink, message: Message): Iterable<Emit>
}