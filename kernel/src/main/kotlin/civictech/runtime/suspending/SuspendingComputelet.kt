package civictech.runtime.suspending

import civictech.kernel.computelet.Connectable
import civictech.kernel.link.DefaultLink
import civictech.kernel.port.DefaultPort

interface SuspendingComputelet : Connectable<DefaultPort, DefaultLink>, SuspendingMessageProcessor {


}