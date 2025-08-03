package civictech.kernel.link

import civictech.kernel.port.DefaultPort
import civictech.kernel.port.Port
import civictech.kernel.port.PortRef
import civictech.kernel.protocol.Message

data class DefaultLink(
    val fromPort: DefaultPort,
    val toPort: DefaultPort,
) : Link {

    override val fromRef: PortRef
        get() = fromPort.ref
    override val toRef: PortRef
        get() = toPort.ref

    fun DefaultPort.other(): DefaultPort? {
        return when (this) {
            fromPort -> toPort
            toPort -> fromPort
            else -> null
        }
    }

    fun DefaultPort.send(message: Message) {
        other()?.process(this@DefaultLink, message)
    }
}