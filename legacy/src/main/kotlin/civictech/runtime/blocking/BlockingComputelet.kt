package civictech.runtime.blocking

import civictech.kernel.computelet.Connectable

interface BlockingComputelet : Connectable<BlockingPort, BlockingLink>, BlockingMessageProcessor {
}