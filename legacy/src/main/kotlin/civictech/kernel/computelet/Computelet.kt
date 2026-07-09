package civictech.kernel.computelet

import civictech.kernel.link.DefaultLink
import civictech.kernel.port.DefaultPort

interface Computelet : Connectable<DefaultPort, DefaultLink>, MessageProcessor
