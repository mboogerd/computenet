package civictech.cell.wire

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.TopologyLink
import civictech.cell.link.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.nature.ContractRegistry
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `computenet-ssa.4.2` — the **emit** half of [DSC1-ANN-01] / [DSC1-ANN-04]:
 * a signing side signs every [RegistryAnnounce] it sends, with a
 * strictly-increasing per-peer-identity counter and an injected-clock expiry,
 * and changes nothing at all for a side with no identity configuration
 * ([DSC1-WIRE-06]).
 *
 * ## No cryptography here, deliberately
 *
 * [DSC1-WIRE-04]: `:kernel` depends on neither `:identity` nor any crypto
 * library, so this file cannot import a real key or a real canonical encoder
 * and does not pretend to. [FakeCredentials.sign] prefixes its input and
 * [recordingEncoder] emits a readable, injective-enough transcript of the
 * [SignableAnnouncement] it was handed. That is *stronger* evidence for what
 * this task owns than a real Ed25519 signature would be: the assertions below
 * read the signed region back out and check it committed to exactly the eight
 * fields the specification names, which an opaque 64-byte signature could not
 * show. Real-crypto end-to-end coverage (BS-02) belongs to the ingress
 * successor, over `:wire`.
 */
class SignedAnnouncementEmitTest {

    // ---------------------------------------------------------------- fixture

    /** See the class KDoc: data, not cryptography ([DSC1-WIRE-04]). */
    private class FakeCredentials(override val peerId: PeerId) : PeerCredentials {
        override val publicKey: ByteArray = "public-key-of-${peerId.name}".toByteArray()
        override fun sign(message: ByteArray): ByteArray = "SIG(".toByteArray() + message + ")".toByteArray()
    }

    /**
     * Stands in for `civictech.identity.announce.canonicalBytes`: writes every
     * field of the [SignableAnnouncement] it is handed, in order, so a test can
     * read the signed region back and see what was committed to.
     */
    private fun transcript(input: SignableAnnouncement): String =
        "peer=${input.mintingPeerId.name}|n=${input.counter}|exp=${input.notAfter}" +
            "|c=${input.contractId}|m=${input.methodId}|ref=${input.cellRef}" +
            "|port=${input.portName}|args=${input.args}"

    private val encoded = CopyOnWriteArrayList<SignableAnnouncement>()

    private fun recordingEncoder(input: SignableAnnouncement): ByteArray {
        encoded += input
        return transcript(input).toByteArray()
    }

    /** A clock a test advances by hand — no test here sleeps ([DSC1-ANN-04]). */
    private class TestClock(var now: Long = 1_700_000_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private val clock = TestClock()

    private fun signingConfig(ttlMillis: Long = 60_000L) = AnnouncementSigningConfig(
        encode = ::recordingEncoder,
        clock = clock,
        ttlMillis = ttlMillis,
        signerKeyId = "key-1",
    )

    /** Captures the frames one [BridgeEgressCell] emits, decoded back to [WireFrame]s. */
    private class Capture(signer: AnnouncementSigner?) {
        val bytes = CopyOnWriteArrayList<ByteArray>()
        val egress = BridgeEgressCell(signer = signer).also { cell ->
            cell.outlet.subscribe(
                Use.fixed(
                    object : Propagate<ByteArray> {
                        override fun propagate(value: ByteArray) {
                            bytes += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        val frames: List<WireFrame> get() = bytes.map { WireCodec.decodeFrame(it).frame }

        /** The `RegistryAnnounce` api that sends through this egress. */
        fun announcer(peerMirror: CellRef = CellRef(UUID.randomUUID())): RegistryAnnounce =
            (HostedCellProxy.create(peerMirror, egress, Peering.AnnounceInletProxy::class.java)
                as Peering.AnnounceInletProxy).inlet.call
    }

    private fun side(
        registry: LocationRegistry,
        credentials: PeerCredentials? = FakeCredentials(PeerId("a")),
        signing: AnnouncementSigningConfig? = signingConfig(),
    ): Peering.Side = Peering.Side(
        registry,
        ManagedHost(scheduler = SimulationController(0).scheduler(), registry = registry),
        peer = credentials?.peerId,
        credentials = credentials,
        announcementSigning = signing,
    )

    private fun signedRegion(frame: WireFrame): String =
        Base64.getUrlDecoder().decode(frame.signature!!).decodeToString()

    private val announceContractId: Long =
        ContractRegistry.descriptor(RegistryAnnounce::class.java)!!.contractId

    private fun methodId(name: String, vararg params: Class<*>): Long =
        ContractRegistry.idsOf(RegistryAnnounce::class.java.getMethod(name, *params))!!.second

    // ------------------------------------------------------- the four methods

    @Test
    fun `every RegistryAnnounce method is signed over exactly the specified eight fields`() {
        val registry = LocationRegistry()
        val a = side(registry)
        val capture = Capture(a.announcementSigner)
        val mirror = CellRef(UUID.randomUUID())
        val announce = capture.announcer(mirror)

        val published = CellRef(UUID.randomUUID())
        val link = TopologyLink(UUID.randomUUID(), PortRef.generate(), PortRef.generate())
        announce.published(published)
        announce.linked(link)
        announce.unlinked(link.id)
        announce.unpublished(published)

        val frames = capture.frames
        frames shouldHaveSize 4

        // all four carry the full set — none of the four methods is exempt
        frames.forEach { frame ->
            frame.signature.shouldNotBeNull()
            frame.signerKeyId shouldBe "key-1"
            frame.notAfter shouldBe clock.now + 60_000L
        }
        frames.map { it.sigCounter } shouldContainExactly listOf(1L, 2L, 3L, 4L)

        // the signed region committed to (mintingPeerId, counter, notAfter,
        // contractId, methodId, cellRef, portName, args) — read back, field by
        // field, rather than merely "a signature is present"
        signedRegion(frames[0]) shouldBe "SIG(" + transcript(
            SignableAnnouncement(
                mintingPeerId = PeerId("a"),
                counter = 1L,
                notAfter = clock.now + 60_000L,
                contractId = announceContractId,
                methodId = methodId("published", CellRef::class.java),
                cellRef = mirror,
                portName = "inlet",
                args = listOf(published),
            ),
        ) + ")"
        signedRegion(frames[1]) shouldBe "SIG(" + transcript(
            SignableAnnouncement(
                mintingPeerId = PeerId("a"),
                counter = 2L,
                notAfter = clock.now + 60_000L,
                contractId = announceContractId,
                methodId = methodId("linked", TopologyLink::class.java),
                cellRef = mirror,
                portName = "inlet",
                args = listOf(link),
            ),
        ) + ")"

        // the args of every method reached the encoder, so nothing signs a
        // truncated announcement
        encoded.map { it.args } shouldContainExactly listOf(
            listOf(published), listOf(link), listOf(link.id), listOf(published),
        )
    }

    @Test
    fun `notAfter is the injected clock plus the configured TTL, and moves with the clock`() {
        val a = side(LocationRegistry(), signing = signingConfig(ttlMillis = 5_000L))
        val capture = Capture(a.announcementSigner)
        val announce = capture.announcer()

        announce.published(CellRef(UUID.randomUUID()))
        clock.now += 120_000L
        announce.published(CellRef(UUID.randomUUID()))

        capture.frames.map { it.notAfter } shouldContainExactly
            listOf(1_700_000_000_000L + 5_000L, 1_700_000_120_000L + 5_000L)
    }

    // ------------------------------------------- counter across a reconnect

    /**
     * [DSC1-ANN-04] and [DSC1-ANN-12], the two clauses that are easiest to
     * satisfy vacuously, in one reconnect: the egress cell is **replaced** (a
     * reconnect mints a new one), the announcer is closed and re-opened so the
     * catch-up sweep re-announces the same refs, and the counters neither
     * restart nor repeat — and the re-announcement is a *fresh signing event*,
     * not the first frame re-sent, so its signature differs even though every
     * identifying field is identical.
     *
     * Mutation-checked: making `Peering.Side.announcementSigner` a computed
     * `get()` (one signer per read — i.e. per egress) turns this red at the
     * counter assertion, while every other test in this file stays green.
     */
    @Test
    fun `counters continue across egress replacement and a catch-up re-announcement re-signs`() {
        val registry = LocationRegistry()
        val a = side(registry)
        val host = a.bridgeHost
        val ref1 = CellRef(UUID.randomUUID())
        registry.publish(ref1, host)

        val first = Capture(a.announcementSigner)
        val mirror = CellRef(UUID.randomUUID())
        Peering.announceTo(a, peerMirror = mirror, via = first.egress).close()

        // the reconnect: a NEW egress cell, borrowing the same side's signer
        val second = Capture(a.announcementSigner)
        Peering.announceTo(a, peerMirror = mirror, via = second.egress).close()

        first.frames shouldHaveSize 1
        second.frames shouldHaveSize 1
        first.frames[0].sigCounter shouldBe 1L
        // NOT 1 again: the sequence belongs to the peer identity, not the cell
        second.frames[0].sigCounter shouldBe 2L

        // identical announcement, distinct signing event ([DSC1-ANN-12]):
        // everything identifying matches and the signature still differs
        second.frames[0].args shouldBe first.frames[0].args
        second.frames[0].cellRef shouldBe first.frames[0].cellRef
        second.frames[0].signature shouldNotBe first.frames[0].signature
    }

    // --------------------------------------------------- what is NOT signed

    @Test
    fun `a non-announcement frame from a signing side carries no signing fields and burns no counter`() {
        val a = side(LocationRegistry())
        val capture = Capture(a.announcementSigner)

        val add = SetOps::class.java.getMethod("add", Any::class.java)
        capture.egress.deliver(
            HostedPortInvocation(
                cellRef = CellRef(UUID.randomUUID()),
                portName = "inlet",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(add, arrayOf<Any?>("milk"), null),
            ),
        )

        val frame = capture.frames.single()
        frame.signature.shouldBeNull()
        frame.signerKeyId.shouldBeNull()
        frame.sigCounter.shouldBeNull()
        frame.notAfter.shouldBeNull()
        a.announcementSigner!!.lastCounter shouldBe 0L

        // and the next announcement still starts at 1 — a data frame did not
        // silently consume a counter
        capture.announcer().published(CellRef(UUID.randomUUID()))
        capture.frames.last().sigCounter shouldBe 1L
    }

    // ------------------------------------------------ the unconfigured side

    /**
     * [DSC1-WIRE-06], at frame level rather than "the existing suite is green".
     *
     * A `Side` with no identity configuration has no signer, so its egress
     * takes the pre-feature path — and the *bytes* say so: the encoded frame's
     * JSON carries exactly the keys it carried before this task, with none of
     * the four signing keys present even as an explicit `null`. That is what
     * makes the field additive on the wire ([DSC1-WIRE-02]); a `WireFrame`
     * whose new fields were non-nullable, or a codec with `encodeDefaults`
     * turned on, would fail here while every signing test above still passed.
     *
     * Mutation-checked: adding `encodeDefaults = true` to `WireCodec`'s `Json`
     * turns this red and nothing else in this file.
     */
    @Test
    fun `a side with no identity configuration emits byte-identical frames`() {
        val registry = LocationRegistry()
        val plain = Peering.Side(
            registry,
            ManagedHost(scheduler = SimulationController(0).scheduler(), registry = registry),
        )
        plain.announcementSigner.shouldBeNull()

        val capture = Capture(plain.announcementSigner)
        val mirror = CellRef(UUID.randomUUID())
        val published = CellRef(UUID.randomUUID())
        capture.announcer(mirror).published(published)

        val bytes = capture.bytes.single()

        // (a) no signing key is present in the encoding at all
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject.keys shouldContainExactly
            setOf("contractId", "methodId", "cellRef", "portName", "type", "args")

        // (b) byte-for-byte what the pre-feature single-argument entry point
        // produces for the same invocation
        val equivalent = WireCodec.encode(
            HostedPortInvocation(
                cellRef = mirror,
                portName = "inlet",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(
                    RegistryAnnounce::class.java.getMethod("published", CellRef::class.java),
                    arrayOf<Any?>(published),
                    null,
                ),
            ),
        )
        bytes.decodeToString() shouldBe equivalent.decodeToString()
    }

    /**
     * The other half of "no identity configuration": credentials alone, or an
     * encoder alone, is not a signing side — signing needs both, which is what
     * makes `Peering.Side`'s single `announcementSigner` field the whole
     * decision (see its KDoc).
     */
    @Test
    fun `credentials without an encoder, or an encoder without credentials, do not sign`() {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = SimulationController(0).scheduler(), registry = registry)
        Peering.Side(registry, host, credentials = FakeCredentials(PeerId("a")))
            .announcementSigner.shouldBeNull()
        Peering.Side(registry, host, announcementSigning = signingConfig())
            .announcementSigner.shouldBeNull()
    }

    /**
     * The configuration the successor's BS-02 needs: a `Peering.loopback` whose
     * two sides both hold keypairs and both sign, with no socket and no hello
     * ([DSC1-WIRE-05]). Asserted through the signers' own counters, because a
     * loopback's egress feeds the peer's ingress directly and there is nothing
     * in between to tap.
     */
    @Test
    fun `both sides of a keyed loopback sign their own announcements`() {
        val controller = SimulationController(0)
        val registryA = LocationRegistry()
        val registryB = LocationRegistry()
        val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
        val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
        val credsA = FakeCredentials(PeerId("a"))
        val credsB = FakeCredentials(PeerId("b"))
        val a = Peering.Side(
            registryA, hostA, peer = credsA.peerId,
            credentials = credsA, announcementSigning = signingConfig(),
        )
        val b = Peering.Side(
            registryB, hostB, peer = credsB.peerId,
            credentials = credsB, announcementSigning = signingConfig(),
        )
        registryA.publish(CellRef(UUID.randomUUID()), hostA)
        registryB.publish(CellRef(UUID.randomUUID()), hostB)

        Peering.loopback(a, b)
        controller.runToIdle()

        // each side signed its own announcements under its own identity — the
        // exact count depends on how many refs the scheduler has published by
        // the time each catch-up sweep runs, so the assertion is on *whose*
        // identity signed, and that both did
        a.announcementSigner!!.lastCounter shouldBeGreaterThan 0L
        b.announcementSigner!!.lastCounter shouldBeGreaterThan 0L
        encoded.map { it.mintingPeerId }.toSet() shouldBe setOf(PeerId("a"), PeerId("b"))
    }
}
