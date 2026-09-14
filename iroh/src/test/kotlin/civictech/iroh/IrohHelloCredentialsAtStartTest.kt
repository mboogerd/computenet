package civictech.iroh

import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityStatement
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.wire.PeerCredentials
import civictech.cell.wire.Peering
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.iroh.IrohTransport.UnsendableHelloCredentialsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

/**
 * Credentials no `IROH-HELLO2` line can carry are refused by
 * [IrohTransport.listen] and [IrohTransport.connect] themselves, **before a
 * sidecar is spawned** (computenet-5y8t.6).
 *
 * Before this, the refusal lived only in `Session.hello`, and a listener
 * reached it from `onHello` on the `SidecarClient` reader thread — whose
 * catch-all fails every link on the sidecar. Refusing before the sidecar
 * process exists is what makes "a listener given such credentials never loses
 * its reader thread to a hello" a fact rather than a race: there is no
 * sidecar, so no `SidecarClient`, so no reader thread to lose.
 *
 * The "binary" is a stub script that records that it was executed and writes
 * an unreadable handshake line, so this runs on every lane, with or without
 * `-Piroh.enabled` (the `SidecarProcessSpawnRelayUrlTest` precedent). The
 * boundary case — eight statements, which must NOT be refused — is what
 * shows the marker detects a spawn at all, so its absence in the refusal
 * cases is evidence.
 */
@DisabledOnOs(OS.WINDOWS)
class IrohHelloCredentialsAtStartTest {

    private lateinit var dir: Path
    private lateinit var spawnedMarker: Path
    private lateinit var stub: Path

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("iroh-hello-credentials")
        spawnedMarker = dir.resolve("spawned")
        stub = dir.resolve("stub-sidecar")
        stub.writeText(
            """
            #!/bin/sh
            : > '$spawnedMarker'
            printf '%s\n' 'not-a-handshake'
            """.trimIndent() + "\n",
        )
        check(stub.toFile().setExecutable(true)) { "stub sidecar must be executable" }
    }

    @AfterEach
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val anchor = AnchorIssuer(PeerIdentity(DeterministicKeySource.keyPairFromSeed("start-anchor".toByteArray())))
    private val aliceKeys = DeterministicKeySource.keyPairFromSeed("start-alice".toByteArray())
    private val aliceKeyId = PeerIdentity(aliceKeys).keyId

    private fun statementFor(name: PeerId): IdentityStatement = anchor.bind(name, aliceKeyId)

    private fun named(name: String, count: Int): PeerIdentity {
        val id = PeerId(name)
        return PeerIdentity(aliceKeys, id, List(count) { statementFor(id) })
    }

    /** `PeerCredentials` over a [PeerIdentity] — `:wire`'s adapter is not on this classpath. */
    private class TestCredentials(private val identity: PeerIdentity) : PeerCredentials {
        override val keyId: KeyId get() = identity.keyId
        override val peerId: PeerId get() = identity.peerId
        override val publicKey: ByteArray get() = identity.publicKey.encoded
        override fun sign(message: ByteArray): ByteArray = identity.sign(message)
        override val statements: List<IdentityStatement> get() = identity.statements
    }

    private fun side(identity: PeerIdentity): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), credentials = TestCredentials(identity))
    }

    private fun listen(identity: PeerIdentity) = IrohTransport.listen(side(identity), stub, timeout = 5.seconds)

    private fun connect(identity: PeerIdentity) =
        IrohTransport.connect(side(identity), ByteArray(32), listOf("127.0.0.1:1"), stub, timeout = 5.seconds)

    @Test
    fun `a listener whose credentials hold nine statements is refused at listen, before any sidecar exists`() {
        val refused = shouldThrow<UnsendableHelloCredentialsException> {
            listen(named("alice", IrohTransport.MAX_HELLO_STATEMENTS + 1))
        }
        refused.message!! shouldContain "at most ${IrohTransport.MAX_HELLO_STATEMENTS}"
        spawnedMarker.exists() shouldBe false
    }

    @Test
    fun `a listener whose credentials name holds a space is refused at listen, before any sidecar exists`() {
        val refused = shouldThrow<UnsendableHelloCredentialsException> { listen(named("al ice", 1)) }
        refused.message!! shouldContain "empty or contains a space"
        spawnedMarker.exists() shouldBe false
    }

    @Test
    fun `a dialler whose credentials cannot be sent is refused at connect, before any sidecar exists`() {
        shouldThrow<UnsendableHelloCredentialsException> {
            connect(named("alice", IrohTransport.MAX_HELLO_STATEMENTS + 1))
        }.message!! shouldContain "at most ${IrohTransport.MAX_HELLO_STATEMENTS}"
        shouldThrow<UnsendableHelloCredentialsException> { connect(named("al ice", 1)) }
            .message!! shouldContain "empty or contains a space"
        spawnedMarker.exists() shouldBe false
    }

    @Test
    fun `an unsigned statement is refused at listen, before any sidecar exists`() {
        val id = PeerId("alice")
        val unsigned = PeerIdentity(aliceKeys, id, listOf(statementFor(id).copy(signature = ByteArray(0))))
        val refused = shouldThrow<UnsendableHelloCredentialsException> { listen(unsigned) }
        refused.message!! shouldContain "empty signature"
        spawnedMarker.exists() shouldBe false
    }

    @Test
    fun `eight statements are not refused - the sidecar is spawned, which is what makes the marker evidence`() {
        // The stub's handshake line is unreadable, so spawn itself fails —
        // after the stub ran, which is the point.
        shouldThrow<SidecarException> { listen(named("alice", IrohTransport.MAX_HELLO_STATEMENTS)) }
        spawnedMarker.exists() shouldBe true
    }
}
