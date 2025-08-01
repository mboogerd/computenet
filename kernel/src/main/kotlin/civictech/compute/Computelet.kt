package civictech.compute

interface Computelet {
    fun ports(): List<Port>
    fun port(name: String): Port?

    fun process(port: Port, message: Message): Iterable<Emit>
}

