package civictech.kernel.computelet

import civictech.kernel.link.Link
import civictech.kernel.port.Port
import civictech.kernel.port.PortRef

interface Connectable<P : Port<L>, L : Link> {
    val ref: ComputeletRef
    val ports: Map<PortRef, P>

    fun port(name: String): P?

    fun isValid(): Boolean = ports.all { it.value.ref.getComputeletRef() == ref }
}