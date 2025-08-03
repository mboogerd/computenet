package civictech.kernel.port

import civictech.kernel.computelet.Computelet
import civictech.kernel.link.DefaultLink
import civictech.kernel.link.Link
import civictech.kernel.protocol.Message

data class DefaultPort(
    override val ref: PortRef,
    override val name: String,
    override val direction: PortDirection,
    override val cardinality: PortCardinality,
) : Port<DefaultLink> {
    private var owner: Computelet? = null
    private val links: MutableList<DefaultLink> = mutableListOf()

    fun getOwner(): Computelet? = owner
    fun setOwner(computelet: Computelet) {
        if (this.owner != null && this.owner != computelet) throw IllegalStateException("Computelet already set")

        this.owner = computelet
    }

    fun link(peer: DefaultPort): Link {
        // TODO: Not sure if I want this. Linking same-directions could be a good way to express encapsulation
        require(direction != PortDirection.INPUT || peer.direction != PortDirection.INPUT) { "Cannot link input to input" }
        require(direction != PortDirection.OUTPUT || peer.direction != PortDirection.OUTPUT) { "Cannot link output to output" }

        // TODO: Consider specialization for Unary/N-ary ports and force-overwrite instead of error
        if (cardinality == PortCardinality.SINGLE && links.isNotEmpty()) {
            error("Cannot link more than one peer to a SINGLE port")
        }

        return DefaultLink(this, peer).also { links.add(it) }
    }

    fun link(link: DefaultLink): Link {
        // TODO: Validation
        links.add(link)
        return link
    }

    fun unlink(link: Link) {
        links.remove(link)
    }

    fun broadcast(message: Message) {
        links.forEach {
            it.sendTo(message)
        }
    }

    fun DefaultLink.sendTo(message: Message) {
        require(links.contains(this)) { "Link does not belong to this port" }
        send(message)
    }

    fun process(link: DefaultLink, message: Message) {
        owner?.process(this, link, message)
    }
}