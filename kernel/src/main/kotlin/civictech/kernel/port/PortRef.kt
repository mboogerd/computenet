package civictech.kernel.port

import civictech.kernel.computelet.ComputeletRef
import java.util.*

data class PortRef(val id: UUID) {
    private var computeletRef: ComputeletRef? = null

    fun getComputeletRef(): ComputeletRef? = computeletRef

    internal fun setComputeletRef(computeletRef: ComputeletRef) {
        this.computeletRef = computeletRef
    }

    fun isMounted(): Boolean = computeletRef != null

    fun isHosted(): Boolean = computeletRef?.isHosted() ?: false

    fun sharesHost(portRef: PortRef): Boolean {
        val other = portRef.computeletRef ?: return false
        return computeletRef?.sharesHost(other) ?: false
    }

    companion object {
        fun generate() = PortRef(UUID.randomUUID())
        fun generate(computeletRef: ComputeletRef) = generate().apply { this.computeletRef = computeletRef }
    }
}