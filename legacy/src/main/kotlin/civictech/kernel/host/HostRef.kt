package civictech.kernel.host

import java.util.UUID

@JvmInline
value class HostRef(val id: UUID) {
    companion object Companion { fun generate() = HostRef(UUID.randomUUID()) }
}