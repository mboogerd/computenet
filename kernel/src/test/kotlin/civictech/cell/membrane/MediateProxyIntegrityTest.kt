package civictech.cell.membrane

import civictech.cell.data.Propagate
import civictech.cell.port.PeerId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Seam 3 `PORT_API` inbound (spec 40/43, decided 93 I-28): `RequireSigned`
 * verifies a signature at ingress before delivery; failure dead-letters
 * (never delivered) rather than throwing or forwarding unverified data. Only
 * `AuthLevel.TransportVouched` strength is available (phase-2 keys/DIDs are
 * research, 95 §R7): [SignatureVerifier.TransportVouched] checks the
 * signature names the peer the transport already vouches for; the
 * increasing per-source counter is what actually defeats replay at this
 * phase.
 */
class MediateProxyIntegrityTest {

    private val propagateMethod = Propagate::class.java.methods.single { it.name == "propagate" }
    private val peer = PeerId("replica-a")

    private fun signed(payload: String, counter: Long, wrongSignature: Boolean = false) = SignedDelta(
        payload = payload,
        mintingPeer = peer,
        counter = counter,
        signature = if (wrongSignature) "not-${peer.name}".toByteArray() else peer.name.toByteArray(),
    )

    @Test
    fun `RequireSigned delivers a validly signed delta with an increasing counter`() {
        val received = mutableListOf<String>()
        val target = object : Propagate<String> {
            override fun propagate(value: String) {
                received += value
            }
        }
        val proxy = MediateProxy(target, IntegrityPolicy.RequireSigned)

        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-1", counter = 1)))
        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-2", counter = 2)))

        received shouldBe listOf("delta-1", "delta-2")
    }

    @Test
    fun `RequireSigned dead-letters an unsigned argument, never forwarding it`() {
        val received = mutableListOf<String>()
        val target = object : Propagate<String> {
            override fun propagate(value: String) {
                received += value
            }
        }
        val proxy = MediateProxy(target, IntegrityPolicy.RequireSigned)

        // A bare, un-enveloped payload — not a SignedDelta at all.
        proxy.invoke(null, propagateMethod, arrayOf("raw-delta"))

        received shouldBe emptyList()
    }

    @Test
    fun `RequireSigned dead-letters an invalid signature`() {
        val received = mutableListOf<String>()
        val target = object : Propagate<String> {
            override fun propagate(value: String) {
                received += value
            }
        }
        val proxy = MediateProxy(target, IntegrityPolicy.RequireSigned)

        proxy.invoke(null, propagateMethod, arrayOf(signed("forged", counter = 1, wrongSignature = true)))

        received shouldBe emptyList()
    }

    @Test
    fun `RequireSigned drops a replayed (non-increasing) counter`() {
        val received = mutableListOf<String>()
        val target = object : Propagate<String> {
            override fun propagate(value: String) {
                received += value
            }
        }
        val proxy = MediateProxy(target, IntegrityPolicy.RequireSigned)

        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-1", counter = 5)))
        // Replay of the same counter (or an earlier one) — defeated.
        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-1-replayed", counter = 5)))
        proxy.invoke(null, propagateMethod, arrayOf(signed("delta-0-replayed", counter = 3)))

        received shouldBe listOf("delta-1")
    }
}
