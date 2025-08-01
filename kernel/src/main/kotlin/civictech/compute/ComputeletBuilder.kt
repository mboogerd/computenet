package civictech.compute

class ComputeletBuilder(
    private val name: String
) {
    private val ports = mutableListOf<DefaultPort>()
    private var handler: ((Port, Message) -> Iterable<Emit>)? = null

    fun port(
        name: String,
        direction: PortDirection,
        cardinality: PortCardinality = PortCardinality.MULTIPLE
    ): DefaultPort {
        val port = DefaultPort(name, direction, cardinality)
        ports += port
        return port
    }

    fun privatePort(name: String, ) =
        port(name, PortDirection.INPUT, PortCardinality.NONE)

    fun inputPort(name: String, cardinality: PortCardinality = PortCardinality.SINGLE) =
        port(name, PortDirection.INPUT, cardinality)

    fun outputPort(name: String, cardinality: PortCardinality = PortCardinality.MULTIPLE) =
        port(name, PortDirection.OUTPUT, cardinality)

    fun onMessage(handler: (Port, Message) -> Iterable<Emit>): ComputeletBuilder {
        this.handler = handler
        return this
    }

    fun build(): Computelet {
        val computeletHandler = handler ?: error("No handler provided for Computelet $name")
        val computelet = object : Computelet {
            private val portMap = ports.associateBy { it.name }

            init {
                ports.forEach { it.setOwner(this) }
            }

            override fun ports(): List<Port> = portMap.values.toList()

            override fun port(name: String): Port? =
                portMap[name] ?: error("Port $name does not exist in Computelet $name")

            override fun process(port: Port, message: Message): Iterable<Emit> =
                computeletHandler(port, message)
        }
        return computelet
    }
}