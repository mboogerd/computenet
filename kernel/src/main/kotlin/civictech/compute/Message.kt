package civictech.compute

/**
 * Generic Message interface
 */
interface Message : Protocol {
    val fromLink: Link
}

/**
 * Simple implementation of Message where we can attach an arbitrary payload
 */
data class ProtocolMessage(
    override val protocolId: Int,
    override val fromLink: Link,
    val payload: Any?
) : Message