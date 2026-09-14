package civictech.wire

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityStatement
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.wire.WsTransport.UnsendableHelloCredentialsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.channels.ServerSocketChannel

/**
 * Credentials no `HELLO3` line can carry are refused by [WsTransport.listen]
 * and [WsTransport.connect] themselves, **before any socket is served or
 * dialled** (computenet-5y8t.6) — the `:wire` half of the check
 * `IrohHelloCredentialsAtStartTest` pins for `:iroh`.
 *
 * `Session.hello`'s own refusal is unchanged and is pinned, unedited, by
 * `WsAnchorVouchedHelloTest`.
 */
class WsHelloCredentialsAtStartTest {

    private val anchor = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("ws-start-anchor".toByteArray())))
    private val aliceKeys = DeterministicKeySource.keyPairFromSeed("ws-start-alice".toByteArray())
    private val aliceKeyId = PeerIdentity(aliceKeys).keyId

    private fun statementFor(name: PeerId): IdentityStatement = anchor.bind(name, aliceKeyId)

    private fun named(name: String, count: Int): PeerIdentity {
        val id = PeerId(name)
        return PeerIdentity(aliceKeys, id, List(count) { statementFor(id) })
    }

    private fun side(identity: PeerIdentity): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), credentials = identity.asPeerCredentials())
    }

    @Test
    fun `a listener whose credentials hold nine statements is refused at listen`() {
        shouldThrow<UnsendableHelloCredentialsException> {
            WsTransport.listen(0, side(named("alice", MAX_HELLO_STATEMENTS + 1)))
        }.message!! shouldContain "at most $MAX_HELLO_STATEMENTS"
    }

    @Test
    fun `a listener over a bound channel is refused before serving it, and the channel stays the caller's`() {
        val channel = ServerSocketChannel.open().bind(WsTransport.loopback(0))
        try {
            shouldThrow<UnsendableHelloCredentialsException> {
                WsTransport.listen(channel, side(named("al ice", 1)))
            }.message!! shouldContain "empty or contains a space"
            channel.isOpen shouldBe true
        } finally {
            channel.close()
        }
    }

    @Test
    fun `a dialler whose credentials cannot be sent is refused at connect, before anything is dialled`() {
        ServerSocket(0, 50, WsTransport.loopback(0).address).use { server ->
            val uri = URI("ws://localhost:${server.localPort}")
            shouldThrow<UnsendableHelloCredentialsException> {
                WsTransport.connect(uri, side(named("alice", MAX_HELLO_STATEMENTS + 1))) { 0L }
            }.message!! shouldContain "at most $MAX_HELLO_STATEMENTS"
            shouldThrow<UnsendableHelloCredentialsException> {
                WsTransport.connect(uri, side(named("al ice", 1))) { 0L }
            }.message!! shouldContain "empty or contains a space"

            // Nothing reached the port: not the reachability probe, not the handshake.
            server.soTimeout = 300
            shouldThrow<SocketTimeoutException> { server.accept().close() }
        }
    }

    @Test
    fun `an unsigned statement is refused at listen`() {
        val id = PeerId("alice")
        val unsigned = PeerIdentity(aliceKeys, id, listOf(statementFor(id).copy(signature = ByteArray(0))))
        shouldThrow<UnsendableHelloCredentialsException> { WsTransport.listen(0, side(unsigned)) }
            .message!! shouldContain "empty signature"
    }

    @Test
    fun `eight statements are not refused - the listener starts`() {
        val listener = WsTransport.listen(0, side(named("alice", MAX_HELLO_STATEMENTS)))
        try {
            (listener.port > 0) shouldBe true
        } finally {
            listener.stop()
        }
    }
}
