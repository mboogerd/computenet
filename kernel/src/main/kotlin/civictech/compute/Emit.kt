package civictech.compute

sealed interface Emit : Protocol {
    val port: Port
    val payload: Any?
}

data class Broadcast(
    override val payload: Any?,
    override val port: Port,
    override val protocolId: Int,
) : Emit

data class Unicast(
    override val payload: Any?,
    override val port: Port,
    override val protocolId: Int,
    val link: Link
) : Emit