package civictech.cell.port

import civictech.cell.Cell
import civictech.nature.ContractRegistry
import civictech.nature.NatureVector
import java.util.Collections
import java.util.WeakHashMap

/**
 * JVM-global weak port → [NatureVector] table, mirroring [PortIdentities]: the
 * KSP-generated descriptor is the authority, this only records the vector it
 * projected onto a live [Port] so the handshake can read it back off a bare
 * port. Absent ⇒ [NatureVector.DEFAULT] ⇒ today's behavior.
 */
internal object PortNatures {
    private val table = Collections.synchronizedMap(WeakHashMap<Port, NatureVector>())

    /**
     * Projects the generated [civictech.nature.PortDescriptor.natures] of
     * [name] onto [port] when it is registered on a [Cell] with a generated
     * descriptor (CP-F2). Ports on a non-cell owner, or cells the processor
     * never saw, carry no descriptor and stay DEFAULT — [of] returns DEFAULT.
     */
    fun project(owner: Any?, name: String, port: Port) {
        if (owner !is Cell) return
        val natures = ContractRegistry.cellDescriptor(owner.javaClass)
            ?.ports?.firstOrNull { it.name == name }
            ?.natures ?: return
        if (!natures.isDefault) table[port] = natures
    }

    /** Test/infra seam: stamp a vector directly onto a port. */
    fun stamp(port: Port, natures: NatureVector) {
        if (natures.isDefault) table.remove(port) else table[port] = natures
    }

    fun of(port: Port): NatureVector = table[port] ?: NatureVector.DEFAULT
}

/** The declared natures projected onto this port (CP-F2/F3), or DEFAULT. */
val Port.natures: NatureVector get() = PortNatures.of(this)
