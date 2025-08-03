package civictech.kernel.host

import civictech.kernel.computelet.ComputeletRef
import civictech.kernel.computelet.Connectable
import civictech.kernel.link.Link
import civictech.kernel.port.Port
import civictech.kernel.port.PortRef

class ComputeletHost<C, P, L>(private val href: HostRef = HostRef.generate())
        where C : Connectable<P, L>, P : Port<L>, L : Link {
    private val computelets: MutableMap<ComputeletRef, C> = mutableMapOf()
    private val ports: MutableMap<PortRef, P> = mutableMapOf()

    /**
     * Hosts a pure computelet and returns its immutable reference.
     * Ports are assigned unique PortRefs.
     */
    fun host(computelet: C) {
        require(computelet.ref.hasNoHostOr(href)) {
            "Provided Computelet already has a host ref other than this one: ${computelet.ref.getHostRef()}. " +
                    "Make sure to clear it before trying to add the computelet to this host"
        }
        require(computelet.isValid()) { "Provided Computelet cannot be hosted because it is invalid" }

        // set the computelet's host to the current one
        computelet.ref.setHostRef(href)
        // store the computelet
        computelets[computelet.ref] = computelet

        computelet.ports.forEach { entry ->
            ports[entry.value.ref] = entry.value
        }
    }

    /** Resolves a computelet ref to its local handle or null if not owned. */
    operator fun get(ref: ComputeletRef): C? = computelets[ref]

    /** Resolves a port ref to its local handle or null if not owned. */
    operator fun get(ref: PortRef): P? = ports[ref]
}

