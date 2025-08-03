package civictech.kernel.link

import civictech.kernel.port.PortRef

interface Link {

    val fromRef: PortRef
    val toRef: PortRef

    fun other(port: PortRef): PortRef? {
        return when (port) {
            fromRef -> toRef
            toRef -> fromRef
            else -> null
        }
    }
}

