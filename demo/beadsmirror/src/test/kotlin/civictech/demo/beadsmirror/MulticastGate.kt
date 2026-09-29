package civictech.demo.beadsmirror

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

/**
 * `:demo:beadsmirror`'s multicast-**delivery** self-test (`ne2oh-B6`) — a copy
 * of `:iroh`'s `civictech.iroh.MulticastGate`, itself the JVM twin of
 * `iroh/sidecar/tests/mdns.rs`'s `multicast_delivery` (feature
 * `computenet-63um5`, 63um5-D3). It gates
 * `e2e.DiscoveredIrohConvergenceSuiteTest`, whose peering forms only by mDNS.
 *
 * ## Why this duplicates `:iroh`'s gate instead of importing it
 *
 * The same argument as [IrohSidecarGate]'s: `:iroh`'s `MulticastGate` is
 * TEST-scoped and therefore not on this module's classpath, and the two ways
 * to share it — a `testFixtures` source set on `:iroh` or a `testArtifacts`
 * configuration — add a permanent build surface to a module whose design claim
 * is that it is small. Two users do not pay for that; a third would be the
 * moment to reconsider. Keep the probe itself in step with `:iroh`'s copy, so
 * a skip in one module is never surprising next to a pass in the other.
 *
 * Neither a successful `MdnsAddressLookupBuilder::build()` (Rust) nor a clean
 * socket bind/join (JVM) predicts whether this host actually **delivers** a
 * multicast datagram: on MacBoo, `build()`/bind/join all succeed, yet a plain
 * `sendto` to a multicast group fails asynchronously with `No route to host`
 * (errno 65) — macOS' Local Network permission denial (`ne2oh-B6`, observed
 * 2026-09-19). So this gate joins a probe group, sends one datagram to itself
 * with multicast loopback enabled, and waits up to 2 s for it to arrive — the
 * same shape as the Rust gate, so a skip on one side is not surprising next to
 * a pass on the other.
 */
object MulticastGate {

    /**
     * A multicast group in the locally-scoped administrative range, picked so a
     * probe cannot be confused with real mDNS traffic on 224.0.0.251.
     */
    private val PROBE_GROUP: InetAddress = InetAddress.getByName("239.255.42.42")

    private const val PROBE_TEXT = "cn"
    private val PROBE_BYTES = PROBE_TEXT.toByteArray(Charsets.US_ASCII)

    /**
     * `null` on success; otherwise the reason this host could not prove it
     * delivers multicast. Binds a receiver on the wildcard address
     * (`0.0.0.0:0`), joins [PROBE_GROUP] on the wildcard and lets the OS choose
     * the interface — matching `ne2oh-B6`'s instruction to mirror the Rust gate
     * rather than try to name the interface that carries the default route,
     * which is not knowable portably.
     */
    @Suppress("DEPRECATION") // MulticastSocket.joinGroup(InetAddress)/leaveGroup(InetAddress): the wildcard-interface form the design calls for.
    internal fun deliveryFailureReason(): String? {
        val receiver = try {
            MulticastSocket(InetSocketAddress(0)).apply { soTimeout = 2_000 }
        } catch (e: Exception) {
            return "binding the receiver failed: ${e.message}"
        }
        try {
            try {
                receiver.joinGroup(PROBE_GROUP)
            } catch (e: Exception) {
                return "joining $PROBE_GROUP failed: ${e.message}"
            }
            try {
                val sender = try {
                    MulticastSocket(InetSocketAddress(0))
                } catch (e: Exception) {
                    return "binding the sender failed: ${e.message}"
                }
                try {
                    try {
                        sender.loopbackMode = false // false = loopback ENABLED (the flag's sense is inverted)
                    } catch (e: Exception) {
                        return "enabling multicast loopback failed: ${e.message}"
                    }
                    try {
                        sender.send(DatagramPacket(PROBE_BYTES, PROBE_BYTES.size, PROBE_GROUP, receiver.localPort))
                    } catch (e: Exception) {
                        return "sending to $PROBE_GROUP:${receiver.localPort} failed: ${e.message}"
                    }
                } finally {
                    sender.close()
                }

                val buf = ByteArray(8)
                val incoming = DatagramPacket(buf, buf.size)
                try {
                    receiver.receive(incoming)
                } catch (e: Exception) {
                    return "nothing was delivered within 2 s: ${e.message}"
                }
                return if (incoming.length == PROBE_BYTES.size && String(buf, 0, incoming.length, Charsets.US_ASCII) == PROBE_TEXT) {
                    null
                } else {
                    "the receiver got ${incoming.length} unexpected bytes from ${incoming.socketAddress}"
                }
            } finally {
                runCatching { receiver.leaveGroup(PROBE_GROUP) }
            }
        } finally {
            receiver.close()
        }
    }

    /**
     * The delivery self-test, or a JUnit SKIP naming the reason — never a
     * failure. Call this after [IrohSidecarGate.orSkip]; a test that spawns real
     * mDNS sidecars is only meaningful once both gates pass.
     */
    fun deliveryOrSkip() {
        val reason = deliveryFailureReason()
        assumeTrue(reason == null) { "multicast delivery unavailable on this host: $reason" }
    }

    /** The fixed prefix `iroh/sidecar/src/endpoint.rs` writes when its mDNS lookup could not bind. */
    private const val MDNS_UNAVAILABLE_MARKER = "mdns unavailable"

    /**
     * The first line among [lines] carrying the sidecar's own
     * `mdns unavailable` report, or `null` when none did. Used to turn a
     * discovery timeout into a named SKIP rather than a bare failure when the
     * sidecar itself already said why nothing will ever arrive.
     */
    fun reasonFromStderr(lines: List<String>): String? = lines.firstOrNull { it.contains(MDNS_UNAVAILABLE_MARKER) }
}
