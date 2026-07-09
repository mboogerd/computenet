package civictech.kernel.computelet

import civictech.kernel.host.HostRef
import java.util.*

data class ComputeletRef(val id: UUID) {
    @Volatile
    private var hostRef: HostRef? = null

    fun getHostRef(): HostRef? = hostRef

    internal fun setHostRef(hostRef: HostRef) {
        this.hostRef = hostRef
    }

    internal fun clearHostRef() {
        this.hostRef = null
    }

    fun isHosted(): Boolean = hostRef != null
    fun hasHost(hostRef: HostRef): Boolean = this.hostRef == hostRef
    fun hasNoHostOr(hostRef: HostRef): Boolean = !isHosted() || hasHost(hostRef)
    fun sharesHost(other: ComputeletRef): Boolean = hostRef == other.hostRef

    companion object {
        fun generate() = ComputeletRef(UUID.randomUUID())
        fun generate(hostRef: HostRef) = generate().apply { this.hostRef = hostRef }
    }
}