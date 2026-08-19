package civictech.identity.announce

import civictech.cell.CellRef
import civictech.cell.port.PortRef
import civictech.cell.protocol.ProtocolId
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireEdgeLink
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The reachability measurement behind `computenet-9qgg`'s decision.
 *
 * The bead offered "accept and document, IF the wire codec cannot deliver an
 * unpaired surrogate" as a third option, and said the codec question was worth
 * measuring rather than assuming. This is the measurement, and it **falsifies**
 * that option: [WireCodec] is kotlinx JSON, JSON escapes are per-UTF-16-code-unit,
 * and a lone `\ud800` escape decodes into a lone surrogate `Char` in
 * `WireFrame.portName` — no rejection anywhere on the path. So once
 * `computenet-ssa.4` feeds [canonicalBytes] from a wire-supplied port name, the
 * ill-formed string is remote input and the former collision was reachable from
 * the network by any peer that could write one escape.
 *
 * The frame is built by [WireCodec.encode] and then patched at the JSON level,
 * so what is decoded is the real codec's own output shape with one string
 * literal replaced — not a hand-rolled frame that might not resemble a real one.
 * A `PORT_PROTOCOL` frame is used because it is the one frame kind whose decode
 * needs no `ContractRegistry` lookup, and it carries [HostedPortInvocation.portName]
 * straight through.
 *
 * This test lives in `:identity` (which already depends on `:kernel`, where
 * [WireCodec] is) rather than in `:wire`: the transport module is not on the
 * path being measured. If it ever goes red because the codec started refusing
 * ill-formed strings, that is good news and not a reason to relax
 * [canonicalBytes] — the encoder must stay closed under its own domain,
 * independently of who calls it.
 */
class WirePortNameSurrogateReachabilityTest {

    private val cell = CellRef(UUID.fromString("00000000-0000-4000-8000-000000000001"), 0L)

    private fun probeFrame(): String {
        val invocation = HostedPortInvocation(
            cellRef = cell,
            portName = PROBE_PORT,
            type = HostedPortInvocation.Type.PORT_PROTOCOL,
            invocation = Invocation("", emptyList(), emptyList()),
            protocolId = ProtocolId("probe"),
            protocolLink = WireEdgeLink(
                id = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                from = PortRef(UUID.fromString("01234567-89ab-4cde-8f01-234567890abc")),
                to = PortRef(UUID.fromString("fedcba98-7654-4321-8fed-cba987654321")),
                fromAddr = PortAddress(cell, "out"),
                toAddr = PortAddress(cell, "in"),
            ),
            protocolMessage = null,
        )
        val json = WireCodec.encode(invocation).decodeToString()
        // Guard the patch: if the frame ever stops carrying the port name as a
        // bare JSON string, the replacement below would silently do nothing and
        // the measurement would assert about an unmodified frame.
        assertTrue(json.contains("\"$PROBE_PORT\""), "encoded frame does not carry the probe port name: $json")
        return json
    }

    @Test
    fun `the wire codec decodes a JSON-escaped lone high surrogate straight into portName`() {
        val patched = probeFrame().replace("\"$PROBE_PORT\"", "\"\\ud800\"")

        val decoded = WireCodec.decode(patched.toByteArray())

        assertEquals(1, decoded.portName.length, "portName code units: ${decoded.portName.codeUnits()}")
        assertEquals('\uD800', decoded.portName[0], "portName code units: ${decoded.portName.codeUnits()}")
        assertTrue(decoded.portName[0].isHighSurrogate())
    }

    /**
     * The consequence, stated end to end: the string the codec just handed us is
     * exactly what feature .4 would pass to [canonicalBytes], and [canonicalBytes]
     * now refuses it instead of signing `0x3f`.
     */
    @Test
    fun `a wire-supplied unpaired surrogate port name is refused by the signing encoder`() {
        val patched = probeFrame().replace("\"$PROBE_PORT\"", "\"orders/\\udc00\"")
        val fromWire = WireCodec.decode(patched.toByteArray()).portName

        val failure = assertFailsWith<IllegalArgumentException> {
            canonicalBytes(
                AnnouncementSigningInput(
                    mintingPeerId = civictech.cell.link.PeerId("ed25519:test"),
                    counter = 1L,
                    notAfter = 2L,
                    contractId = 3L,
                    methodId = 4L,
                    cellRef = cell,
                    portName = fromWire,
                    args = emptyList(),
                ),
            )
        }
        assertTrue(failure.message!!.contains("portName"), failure.message)
        assertTrue(failure.message!!.contains("at index 7"), failure.message)
    }

    private companion object {
        /** Distinctive so the JSON patch cannot hit another field's value. */
        const val PROBE_PORT = "PROBE_PORT_9qgg"
    }
}
