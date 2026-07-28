package civictech.cell.host

import civictech.cell.link.Link
import civictech.cell.port.Port

/**
 * Overlays a delivery's real local [Port] onto a wire-reconstructed [base]
 * link (spec 41 point 4, G-35 phase B) so identity-gated protocol handlers
 * (`Attention.wire()`, `GlitchFreeCell`) treat it exactly like an in-process
 * link. Everything else — [protocolBridge]/[protocolCapabilities] for the
 * next hop — still comes from [base].
 *
 * File-local extraction from `ManagedHost` (T11-A): purely a wire-link
 * adapter, no dependency on host state — moved here verbatim.
 */
internal class DirectedProtocolLink(
    private val base: Link,
    private val localPort: Port,
    localIsFrom: Boolean,
) : Link by base {
    override val fromPort: Port? = if (localIsFrom) localPort else null
    override val toPort: Port? = if (!localIsFrom) localPort else null
}
