package civictech.runtime.suspending

import civictech.kernel.link.DefaultLink
import civictech.kernel.port.Port
import civictech.kernel.protocol.Message

interface SuspendingMessageProcessor {
    suspend fun process(port: Port<DefaultLink>, message: Message)
}