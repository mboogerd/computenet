package civictech.runtime.blocking

import civictech.kernel.computelet.ComputeletRef
import civictech.kernel.port.PortRef
import civictech.kernel.protocol.Message
import java.util.concurrent.BlockingQueue

data class BlockingComputeletHandle(
    val ref: ComputeletRef,
    private val blockingQueue: BlockingQueue<Message>,
    val ports: Map<PortRef, BlockingPortHandle>
) {
}