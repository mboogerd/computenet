package civictech.iroh

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PROTOCOL.md` §1's handshake and the `GET_ID` → `ID` exchange, against a real
 * sidecar process.
 *
 * Skip-gated: without `-Piroh.enabled=true` (and therefore without a built
 * crate) this reports SKIPPED, never failed — see [SidecarBinary].
 */
class SidecarHandshakeTest {

    @Test
    fun `the handshake line names the same endpoint id GET_ID reports`() {
        val binary = SidecarBinary.orSkip()

        SidecarProcess.spawn(binary).use { sidecar ->
            // §1: exactly one stdout line, {"port":NNN,"nodeId":"<64 lowercase hex>"}.
            assertTrue(sidecar.port in 1..65535, "handshake port ${sidecar.port} out of range")
            assertEquals(SidecarProtocol.NODE_ID_LEN, sidecar.nodeId.size)
            assertEquals(64, sidecar.nodeIdHex.length)
            assertTrue(sidecar.nodeIdHex.all { it.isDigit() || it in 'a'..'f' }, sidecar.nodeIdHex)
            assertTrue(sidecar.isAlive)

            sidecar.connect().use { client ->
                val reported = client.getId()
                assertContentEquals(
                    sidecar.nodeId,
                    reported,
                    "GET_ID reported ${reported.toHex()} but the handshake announced ${sidecar.nodeIdHex}",
                )
            }
        }
    }
}
