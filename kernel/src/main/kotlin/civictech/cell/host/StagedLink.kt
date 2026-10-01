package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.link.LinkResult
import civictech.cell.port.LinkTo
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.Proxy

/**
 * Delivery-only target retained by a [civictech.cell.port.FanOutlet] for a
 * staged link. The real inlet remains the handshake endpoint; this stand-in
 * only turns data calls into ordinary target-host intake frames.
 */
internal class StagedStandIn<Api : Any>(
    override val ref: PortRef,
    apiClass: Class<Api>,
    to: CellRef,
    inletName: String,
    host: ManagedHost,
) : Use<Api> {
    override val call: Api = Proxy.fromClass(apiClass) { _, method, args ->
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = to,
                portName = inletName,
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(method, args, CurrentContext.get()),
            )
        )
        null
    }

    override fun at(portRef: PortRef): Api = call

    override fun linkFrom(portOut: LinkTo<Api>): LinkResult =
        error("StagedStandIn is a delivery target, never a handshake endpoint")
}
