package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.membrane.AuthLevel
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.wire.Peering
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.UUID

/**
 * The epic `computenet-ssa` §9.1 mixed-version proof, both directions: an old
 * (pre-epic) side and a new (post-epic) side either interoperate exactly as
 * before, or the new grammar's collision-proofing turns a would-be misparse
 * into a loud connection error instead of a corrupted identity.
 *
 * ## Old client -> new Open listener
 *
 * A peering configured exactly as every pre-epic caller — no `auth`, no
 * `credentials` — admits at `TransportVouched`, byte for byte
 * (`[DSC1-HELLO-10]`; this feature's regression clause). "No `HELLO2`/`PROOF`
 * text appears on the wire" is checked against the *literal* string both
 * production `onOpen` paths send verbatim — `WsTransport.kt`'s
 * `conn.send(session.hello())` (listener) and `send(session.hello())`
 * (dialer) — so this is the actual wire content, not an inference from
 * behaviour.
 *
 * ## New credentialed client -> old listener
 *
 * The pre-epic listener code no longer exists on this branch (computenet-
 * ssa.2.1 replaced it), so its parse shape is reconstructed here —
 * `require(startsWith("HELLO "))` then `split(" ", limit = 2)`,
 * `WsTransport.kt`'s `onLegacyHello` verbatim (`private const val HELLO =
 * "HELLO "`, trailing space) — and fed a credentialed side's `HELLO2` line.
 * `HelloProtocol`'s file KDoc states the argument for why this must throw:
 * the sixth character of `"HELLO2 ..."` is `'2'`, not a space, so
 * `startsWith("HELLO ")` is false and the legacy `require` throws before
 * `split` ever runs — the grammar's no-identity-corruption guarantee. An old
 * listener errors the connection; it never admits a wrong name.
 */
class WsHelloMixedVersionTest {

    private fun identity(seed: String) = PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()))

    class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = Unit
            })
        }
    }

    /** Every default: `PeerAuthPolicy.Open`, no credentials — the exact shape of every pre-epic `Peering.Side` caller. */
    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    // Same shape and deadline as WsPeerIdentityTest.await / WsAuthenticatedHelloTest.await.
    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    @Test
    fun `an old-shaped peering never puts HELLO2 or PROOF on the wire and admits at TransportVouched`() {
        // The exact string both production onOpen paths send, unmodified: a
        // direct proof about wire content, not an inference from behaviour.
        val standalone = WsTransport.Session(Stack().side, send = {}, refuse = {})
        val line = standalone.hello()
        line.startsWith(HELLO2_PREFIX) shouldBe false
        line.startsWith(PROOF_PREFIX) shouldBe false
        line.startsWith(LEGACY_HELLO_PREFIX) shouldBe true

        // And the behavioural half, over a real socket: two sides configured
        // exactly as every pre-epic caller, admitted at TransportVouched.
        val server = Stack()
        val client = Stack()
        val collector = CollectingCell()
        server.host.managementInlet.call.spawn(collector)

        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side) { 0L }
        try {
            await("the dialer learned the listener's collector") {
                client.registry.location(collector.ref) is LocationRegistry.Remote
            }
            await("both sides reported TransportVouched") {
                connection.achievedAuthLevel == AuthLevel.TransportVouched &&
                    listener.achievedAuthLevels == listOf(AuthLevel.TransportVouched)
            }
            listener.admissionDenialCount shouldBe 0L
            connection.admissionDenialCount shouldBe 0L
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }

    /**
     * `WsTransport.kt`'s `onLegacyHello`, reconstructed: the pre-epic parse
     * shape this branch's listener code no longer carries. Kept byte-for-byte
     * identical to the production shape it stands in for (see this file's
     * KDoc), so a divergence here would be a divergence from the real
     * behaviour rather than from this test's own invention.
     */
    private fun legacyParse(line: String): List<String> {
        require(line.startsWith(LEGACY_HELLO_PREFIX)) { "unexpected text message: $line" }
        return line.removePrefix(LEGACY_HELLO_PREFIX).trim().split(" ", limit = 2)
    }

    @Test
    fun `a credentialed side's HELLO2 throws under the legacy parse before any PeerId is produced`() {
        val side = Peering.Side(
            LocationRegistry(),
            ManagedHost(),
            credentials = identity("mixed-credentialed").asPeerCredentials(),
        )
        val session = WsTransport.Session(side, send = {}, refuse = {})
        val line = session.hello()

        // sanity: this really is the new grammar, not a legacy line by accident
        line.startsWith(HELLO2_PREFIX) shouldBe true

        // `require` throws before `split` ever runs — no name, right or
        // wrong, is ever produced from these bytes
        shouldThrow<IllegalArgumentException> { legacyParse(line) }
    }
}
