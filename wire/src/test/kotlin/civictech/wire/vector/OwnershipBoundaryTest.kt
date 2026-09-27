package civictech.wire.vector

import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.wire.BridgeEgressCell
import civictech.cell.wire.WireCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * Spec 23's machine-boundary rules observed at the BYTES, on a bare
 * [BridgeEgressCell] whose outlet feeds a recording subscriber (epic
 * computenet-ncz, `[WIR1-I16]`/`[WIR1-I17]`, scenarios B3.9/B3.10). Decision
 * ncz.6-D3: the refusal and the consume live in the bridge cell
 * (`BridgeEgressCell.deliver`), so this drives `deliver`, never
 * [WireCodec.encode] directly — a codec-level test would pass for the wrong
 * reason.
 *
 * The hosted-graph twin is `kernel/src/test/kotlin/civictech/cell/wire/OwnershipTest.kt`
 * (`Owned crosses the bridge as move-by-serialize…` and `Leased is refused at
 * the machine boundary`); neither records bytes, which is what this adds:
 * "zero bytes produced" for a lease, "exactly one encode" for an owned move.
 *
 * The invocation is the seed vector's (the first `frame` manifest vector, as
 * [RejectionClassifierTest] picks it — `Propagate::propagate`, whose erased
 * parameter takes any payload) with its args replaced; no new `@Contract`.
 * Delivery is synchronous: `FanOutlet.call` dispatches to the subscriber on the
 * calling thread, with no host or scheduler (observed by these tests' own
 * recorder being populated when `deliver` returns).
 */
class OwnershipBoundaryTest {

    private val seed: VectorDocument =
        VectorLoader.locate().documents().first { it.kind == VectorKind.FRAME && it.encoded?.utf8 != null }

    private fun seedWithArgs(vararg args: Any?): HostedPortInvocation {
        val base = NeutralValues.frameOf(seed).invocation
        return base.copy(invocation = base.invocation.copy(args = args.toList()))
    }

    /** A bare egress and the byte arrays its outlet emits. */
    private fun recordingEgress(): Pair<BridgeEgressCell, MutableList<ByteArray>> {
        val recorded = mutableListOf<ByteArray>()
        val egress = BridgeEgressCell()
        egress.outlet.subscribe(Use.fixed(Propagate<ByteArray> { recorded += it }, PortRef.generate()))
        return egress to recorded
    }

    @Test
    fun `WIR1-I16 B3_9 - a Leased arg is refused by BridgeEgressCell_deliver before any byte is produced`() {
        val (egress, recorded) = recordingEgress()
        val lease = Leased("pooled")

        val refused = assertThrows<IllegalArgumentException> { egress.deliver(seedWithArgs(lease)) }

        // The classification, not the assertThrows type, is what tells the bridge's refusal from a codec
        // accident: without the bridge's require, WireCodec.encode throws kotlinx's SerializationException
        // ("Serializer for subclass 'Leased' is not found…"), which IS an IllegalArgumentException subclass
        // (observed 2026-09-25) and classifies as unclassified(…).
        assertEquals("leased-at-encode", RejectionClassifier.classify(refused), "the refusal is spec 23's, not a codec accident")
        assertTrue(recorded.isEmpty(), "[WIR1-I16] B3.9: ${recorded.size} byte array(s) left the egress for a Leased send")
        // Nothing consumed or released the lease on the way to the refusal: the sender still owns the obligation.
        assertDoesNotThrow { lease.release() }
    }

    @Test
    fun `WIR1-I17 B3_10 - an Owned arg is consumed exactly once by the encode and the sender's reference is dead`() {
        val (egress, recorded) = recordingEgress()
        val owned = Owned("payload")

        egress.deliver(seedWithArgs(owned))

        assertEquals(1, recorded.size, "[WIR1-I17] B3.10: exactly one encode must leave the egress")
        val crossed = WireCodec.decodeFrame(recorded.single()).invocation.invocation.args.single()
        val received = assertInstanceOf<Owned<*>>(crossed, "the value crosses as an Owned (move-by-serialize)")
        assertEquals("payload", received.take(), "the receiver owns the moved value")
        // ncz.6-D9: Owned.consume() is internal and idempotent, so the public observable of "a second
        // consume fails" is that the sender's take() is a use-after-move.
        val dead = assertThrows<IllegalStateException> { owned.take() }
        assertTrue(dead.message!!.contains("already consumed"), dead.message)
    }

    private inline fun <reified T> assertInstanceOf(actual: Any?, message: String): T {
        assertTrue(actual is T, "$message — got ${actual?.javaClass?.name}")
        return actual as T
    }
}
