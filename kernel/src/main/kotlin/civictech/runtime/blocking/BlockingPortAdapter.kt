package civictech.runtime.blocking

import civictech.kernel.port.DefaultPort
import civictech.kernel.port.Port
import civictech.kernel.port.PortCardinality
import civictech.kernel.port.PortDirection
import civictech.kernel.port.PortRef
import civictech.kernel.protocol.Message

class BlockingPortAdapter(val port: DefaultPort, val owner: BlockingComputelet) : BlockingPort {

    val links: MutableSet<BlockingLink> = mutableSetOf()

    override fun link(peer: Port<BlockingLink>): BlockingLink {
        if (peer is BlockingLink) {
            return link(peer as BlockingLink)
        } else {
            TODO("DefaultLink => error, or SuspendingLink => create blocking adapter")
        }
    }

    override fun link(link: BlockingLink): BlockingLink {
        links += link
        return link
    }

    override fun unlink(link: BlockingLink) {
        links -= link
    }

    override fun process(link: BlockingLink, message: Message) {
        owner.process(this, link, message)
    }

    override val name: String = port.name
    override val direction: PortDirection = port.direction
    override val cardinality: PortCardinality = port.cardinality
    override val ref: PortRef = port.ref
}