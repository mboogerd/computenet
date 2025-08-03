package civictech.runtime.blocking

import civictech.kernel.link.Link
import civictech.kernel.protocol.Message

interface BlockingLink : Link {
    fun BlockingPort.send(message: Message)
}