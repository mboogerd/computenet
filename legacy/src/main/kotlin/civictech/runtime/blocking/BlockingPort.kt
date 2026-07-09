package civictech.runtime.blocking

import civictech.kernel.port.Port
import civictech.kernel.protocol.Message

interface BlockingPort : Port<BlockingLink> {

    fun link(peer: Port<BlockingLink>): BlockingLink

    fun link(link: BlockingLink): BlockingLink

    fun unlink(link: BlockingLink)

    fun process(link: BlockingLink, message: Message)
}