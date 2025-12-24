package civictech.kernel.germ

//import civictech.kernel.germ.port.Inlet

///**
// * Metadata about a connection between ports, can be extended with link-specific fields.
// */
//data class ConnectionContext(val timestamp: Long = System.currentTimeMillis())
//
///** Describes the canonical direction of a Port */
//enum class PortDirection { INPUT, OUTPUT }
//
///** Describes whether a protocol aligns with or opposes the port direction */
//enum class ProtocolOrientation { MATCHES_DIRECTION, CONTRA_DIRECTION }
//
///** Metadata describing a protocol and how it relates to port direction */
//data class ProtocolSpec<T>(
//    val type: Class<T>,
//    val orientation: ProtocolOrientation
//)
//
///**
// * A Port represents a directional attachment point on a Cell.
// * It can provide or consume multiple protocols depending on its direction and protocol orientation.
// */
//interface Port {
//    val direction: PortDirection
//    val external: External
//    val internal: Internal
//
//    interface External {
//        /** Retrieves the Handle for a given protocol to call into this port */
//        fun <T> getProtocol(protocol: Class<T>): Handle<T>
//    }
//
//    interface Internal {
//        /** Sets or replaces the implementation for a protocol (only valid when the port must provide it) */
//        fun <T> provide(protocol: Class<T>, factory: () -> T)
//
//        /** Registers a callback to be invoked when a new connection is established */
//        fun onConnect(handler: (ConnectionContext) -> Unit)
//    }
//}
//
///**
// * Basic implementation of a Port using Handle for each protocol.
// */
//class BasicPort(
//    override val direction: PortDirection,
//    val supportedProtocols: List<ProtocolSpec<*>>
//) : Inlet {
//
//    private val protocolHandles = mutableMapOf<Class<*>, Handle<*>>()
//    private val connectHandlers = mutableListOf<(ConnectionContext) -> Unit>()
//
//    override val internal = object : Inlet.Internal {
//        override fun <T> provide(protocol: Class<T>, factory: () -> T) {
//            val spec = supportedProtocols.find { it.type == protocol }
//                ?: throw IllegalArgumentException("Protocol $protocol not supported by this port")
//            if (!requiresProviding(spec))
//                throw IllegalStateException("Port $direction is not responsible for providing ${protocol.simpleName}")
//
//            @Suppress("UNCHECKED_CAST")
//            val handle = (protocolHandles[protocol] as? Handle<T>)
//                ?: Handle.root(factory()).also { protocolHandles[protocol] = it }
//            handle.activate(factory())
//        }
//
//        override fun onConnect(handler: (ConnectionContext) -> Unit) {
//            connectHandlers += handler
//        }
//    }
//
//    override val external = object : Inlet.External {
//        override fun <T> getProtocol(protocol: Class<T>): Handle<T> {
//            @Suppress("UNCHECKED_CAST")
//            return protocolHandles[protocol] as? Handle<T>
//                ?: throw IllegalStateException("Protocol ${protocol.simpleName} not configured on port")
//        }
//    }
//
//    fun notifyConnected(ctx: ConnectionContext) {
//        connectHandlers.forEach { it(ctx) }
//    }
//
//    private fun requiresProviding(spec: ProtocolSpec<*>): Boolean =
//        (direction == PortDirection.INPUT && spec.orientation == ProtocolOrientation.MATCHES_DIRECTION) ||
//        (direction == PortDirection.OUTPUT && spec.orientation == ProtocolOrientation.CONTRA_DIRECTION)
//}
//
///**
// * A Link connects any two Ports and wires their supported protocols according to direction and orientation.
// */
//interface Link {
//    fun connect(from: Inlet, to: Inlet, ctx: ConnectionContext = ConnectionContext())
//}
//
///**
// * Basic implementation of Link that checks protocol compatibility and forwards Handles accordingly.
// */
//class BasicLink : Link {
//    override fun connect(from: Inlet, to: Inlet, ctx: ConnectionContext) {
//        // Notify both ports
//        if (from is BasicPort) from.notifyConnected(ctx)
//        if (to is BasicPort) to.notifyConnected(ctx)
//
//        // Connection is currently only event-driven; protocol Handle forwarding is managed by the Cells
//        // Future logic may inspect supported protocols and establish direct Handle references.
//    }
//}
