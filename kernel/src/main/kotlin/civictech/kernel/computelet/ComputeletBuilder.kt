package civictech.kernel.computelet

import civictech.kernel.link.DefaultLink
import civictech.kernel.port.*
import civictech.kernel.protocol.Emit
import civictech.kernel.protocol.Message

class ComputeletBuilder(
    private val name: String
) {
    private val ports = mutableListOf<DefaultPort>()
    private var handler: ((Port<DefaultLink>, Message) -> Iterable<Emit>)? = null

    fun port(
        name: String,
        direction: PortDirection,
        cardinality: PortCardinality = PortCardinality.MULTIPLE
    ): DefaultPort {
        val port = DefaultPort(PortRef.generate(), name, direction, cardinality)
        ports += port
        return port
    }

    fun privatePort(name: String) =
        port(name, PortDirection.INPUT, PortCardinality.NONE)

    fun inputPort(name: String, cardinality: PortCardinality = PortCardinality.SINGLE) =
        port(name, PortDirection.INPUT, cardinality)

    fun outputPort(name: String, cardinality: PortCardinality = PortCardinality.MULTIPLE) =
        port(name, PortDirection.OUTPUT, cardinality)

    fun onMessage(handler: (Port<DefaultLink>, Message) -> Iterable<Emit>): ComputeletBuilder {
        this.handler = handler
        return this
    }

    fun build(): Computelet {
        val computeletHandler = handler ?: error("No handler provided for Computelet $name")
        val computelet = object : Computelet {
            private val portMap = this@ComputeletBuilder.ports.associateBy { it.name }

            override val ports: Map<PortRef, DefaultPort>
                get() = this@ComputeletBuilder.ports.associateBy { it.ref }

            override val ref: ComputeletRef
                get() = ComputeletRef.generate()

            init {
                ports.values.forEach { it.setOwner(this) }
            }

            fun ports(): List<Port<DefaultLink>> = portMap.values.toList()

            override fun port(name: String): DefaultPort? =
                portMap[name] ?: error("Port $name does not exist in Computelet $name")

            override fun process(port: DefaultPort, link: DefaultLink, message: Message): Iterable<Emit> =
                computeletHandler(port, message)
        }
        return computelet
    }
}