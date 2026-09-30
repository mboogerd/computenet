package civictech.cell.wire

import civictech.testkit.PeerTransportContract
import java.util.UUID

/**
 * [PeerTransportContract] against the in-kernel binding. Instant backoff, so
 * the hold cases (c) and (e) test the policy's intent flags: were a re-dial
 * armed after a partition or a close, it would carry well inside the hold.
 */
class LoopbackPeerTransportContractTest : PeerTransportContract() {

    override fun transport(): PeerTransport = LoopbackPeerTransport(backoff = { 0L })

    override fun listenAddress(): PeerAddress = transport.parseAddress("loopback://contract-${UUID.randomUUID()}")
}
