package civictech.kernel.port

import civictech.kernel.link.Link

interface Port<L : Link> : PortHandle {

    /**
     * A Port carries a name that is unique within a local scope, typically, a Task definition/instance
     */
    val name: String

    /**
     * Whether this is an input port, an output port or both
     */
    val direction: PortDirection

    /**
     * Whether this port accepts a single connection or any number
     */
    val cardinality: PortCardinality
}

