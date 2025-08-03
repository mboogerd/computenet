package civictech.runtime.blocking

import civictech.kernel.port.PortHandle
import civictech.kernel.port.PortRef
import civictech.kernel.protocol.Message
import java.util.concurrent.BlockingQueue

/**
 * For out-of-host blocking operations on a port
 */
data class BlockingPortHandle(
    override val ref: PortRef,
    val hostQueue: BlockingQueue<Message>
) : PortHandle {

    fun link(link: BlockingLink) {

    }

    fun link(other: PortHandle) {
        TODO("create a link by link(blocking) or create an adapter")
    }

    fun link(other: BlockingPortHandle) {
        TODO("create a link for a local handle (method dispatch) or a remote one (enqueue)")
    }
}