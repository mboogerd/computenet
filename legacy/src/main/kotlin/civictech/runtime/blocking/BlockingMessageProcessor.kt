package civictech.runtime.blocking

import civictech.kernel.protocol.Message

interface BlockingMessageProcessor {
    fun process(port: BlockingPort, link: BlockingLink, message: Message)
}