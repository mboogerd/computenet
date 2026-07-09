package civictech.runtime.blocking

import civictech.kernel.port.Port
import civictech.kernel.port.PortCardinality
import civictech.kernel.port.PortDirection
import civictech.kernel.port.PortRef
import civictech.kernel.protocol.Message

data class DefaultBlockingPort(
    override val ref: PortRef,
    override val name: String,
    override val direction: PortDirection,
    override val cardinality: PortCardinality
) : BlockingPort {

    val links: MutableSet<BlockingLink> = mutableSetOf()
    private var owner: BlockingComputelet? = null

    fun getOwner(): BlockingComputelet? = owner
    fun setOwner(computelet: BlockingComputelet) {
        if (this.owner != null && this.owner != computelet) throw IllegalStateException("Computelet already set")

        this.owner = computelet
    }

    override fun link(peer: Port<BlockingLink>): BlockingLink {
        return if (peer is BlockingPort) {
            link(peer)
        } else {
            TODO("Create a BlockingSuspendingPortAdapter")
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
        val owner = owner
            ?: throw java.lang.IllegalStateException("Cannot trigger processing on this port as the owner isn't set")
        owner.process(this, link, message)
    }

    companion object {

        fun blockingInput(
            ref: PortRef = PortRef.generate(),
            name: String = "in",
            direction: PortDirection = PortDirection.INPUT,
            cardinality: PortCardinality = PortCardinality.SINGLE
        ): BlockingPort =
            DefaultBlockingPort(ref, name, direction, cardinality)

        fun BlockingComputelet.blockingInput(
            ref: PortRef = PortRef.generate(this.ref),
            name: String = "in",
            direction: PortDirection = PortDirection.INPUT,
            cardinality: PortCardinality = PortCardinality.SINGLE
        ): BlockingPort =
            DefaultBlockingPort(ref, name, direction, cardinality)

        fun blockingOutput(
            ref: PortRef = PortRef.generate(),
            name: String = "out",
            direction: PortDirection = PortDirection.OUTPUT,
            cardinality: PortCardinality = PortCardinality.MULTIPLE
        ): BlockingPort =
            DefaultBlockingPort(ref, name, direction, cardinality)

        fun BlockingComputelet.blockingOutput(
            ref: PortRef = PortRef.generate(this.ref),
            name: String = "out",
            direction: PortDirection = PortDirection.OUTPUT,
            cardinality: PortCardinality = PortCardinality.MULTIPLE
        ): BlockingPort =
            DefaultBlockingPort(ref, name, direction, cardinality)
    }
}