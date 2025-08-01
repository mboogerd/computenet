package civictech.compute

import civictech.compute.PortDirection.INPUT
import civictech.compute.PortDirection.OUTPUT

interface Port {
    /**
     * A Port carries a name that is unique within a local scope, typically, a Task definition/instance
     */
    val name: String

    /**
     * A Port has a globally unique address, e.g. the name resolved within the scope of a Task definition/instance
     */
//    val address: Address

    /**
     * Whether this is an input port, an output port or both
     */
    val direction: PortDirection

    /**
     * Whether this port accepts a single connection or any number
     */
    val cardinality: PortCardinality
}

data class DefaultPort(
    override val name: String,
    override val direction: PortDirection,
    override val cardinality: PortCardinality,
) : Port {
    private var owner: Computelet? = null
    private val links: MutableList<Link> = mutableListOf()

    fun getOwner(): Computelet? = owner
    fun setOwner(computelet: Computelet) {
        if (this.owner != null) throw IllegalStateException("Computelet already set")

        this.owner = computelet
    }

    fun link(peer: Port): Link {
        // TODO: Not sure if I want this. Linking same-directions could be a good way to express encapsulation
        require(direction != INPUT || peer.direction != INPUT) { "Cannot link input to input" }
        require(direction != OUTPUT || peer.direction != OUTPUT) { "Cannot link output to output" }

        // TODO: Consider specialization for Unary/N-ary ports and force-overwrite instead of error
        if (cardinality == PortCardinality.SINGLE && links.isNotEmpty()) {
            error("Cannot link more than one peer to a SINGLE port")
        }

        return Link(this, peer).also { links.add(it) }
    }

    fun unlink(link: Link) {
        links.remove(link)
    }

    fun broadcast(message: Message) {
        links.forEach {
            it.sendTo(message)
        }
    }

    fun Link.sendTo(message: Message) {
        require(links.contains(this)) { "Link does not belong to this port" }
        send(message)
    }
}

