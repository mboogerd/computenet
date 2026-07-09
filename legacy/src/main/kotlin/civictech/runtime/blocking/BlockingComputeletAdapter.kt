package civictech.runtime.blocking

import civictech.kernel.computelet.Computelet
import civictech.kernel.computelet.ComputeletRef
import civictech.kernel.link.DefaultLink
import civictech.kernel.port.Port
import civictech.kernel.port.PortRef
import civictech.kernel.protocol.Broadcast
import civictech.kernel.protocol.Message
import civictech.kernel.protocol.Unicast

class BlockingComputeletAdapter(private val computelet: Computelet) : BlockingComputelet {
    override val ref: ComputeletRef = computelet.ref

    override val ports: Map<PortRef, BlockingPort> by lazy {
        computelet.ports.mapValues { it.value.toBlocking() }
    }

    override fun port(name: String): BlockingPort? {
        return computelet.port(name)?.toBlocking()
    }

    override fun process(port: BlockingPort, link: BlockingLink, message: Message) {
        val realPort = computelet.ports[port.ref] ?: return
        val realLink: DefaultLink = TODO()
        computelet.process(realPort, realLink, message).forEach {
            when (it) {
                is Broadcast ->
                    it.port.broadcast(message)

                is Unicast -> TODO()
            }
        }
    }

    fun Port<DefaultLink>.toBlocking(): BlockingPort {
        TODO()
    }
}