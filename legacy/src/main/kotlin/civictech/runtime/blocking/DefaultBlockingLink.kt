package civictech.runtime.blocking

import civictech.kernel.port.PortRef
import civictech.kernel.protocol.Message

data class DefaultBlockingLink(val from: BlockingPort, val to: BlockingPort) : BlockingLink {
    override fun BlockingPort.send(message: Message) {
        when(this) {
            from -> to.process(this@DefaultBlockingLink, message)
            to -> from.process(this@DefaultBlockingLink, message)
            else -> throw IllegalArgumentException("Not part of this links' ports: $this")
        }
    }

    override val fromRef: PortRef
        get() = from.ref
    override val toRef: PortRef
        get() = to.ref
}