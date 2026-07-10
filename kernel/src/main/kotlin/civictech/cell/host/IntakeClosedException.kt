package civictech.cell.host

import civictech.cell.CellRef

/**
 * Thrown by a closed host intake (spec 33): sends fail fast — never block,
 * never silently drop. This is the sender's re-resolution signal, not an
 * error; link/proxy internals catch it and re-resolve via a
 * [LocationRegistry]. It is deliberately NOT a dead letter.
 */
class IntakeClosedException(hostRef: CellRef) :
    IllegalStateException("host $hostRef intake is closed")
