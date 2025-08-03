package civictech.kernel.protocol

import civictech.kernel.link.Link
import civictech.kernel.port.DefaultPort

sealed interface Emit : Protocol {
    val port: DefaultPort
    val payload: Message
}

data class Broadcast(
    override val payload: Message,
    override val port: DefaultPort,
) : Emit {
    override val protocolId: Int = payload.protocolId
}

data class Unicast(
    override val payload: Message,
    override val port: DefaultPort,
    val link: Link
) : Emit {
    override val protocolId: Int = payload.protocolId
}