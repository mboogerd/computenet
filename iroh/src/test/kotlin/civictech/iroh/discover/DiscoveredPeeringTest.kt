package civictech.iroh.discover

import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityResolution
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import civictech.identity.Ed25519
import civictech.identity.fingerprint
import civictech.iroh.FakeSidecar
import civictech.iroh.Frame
import civictech.iroh.HostMessage
import civictech.iroh.IrohNode
import civictech.iroh.IrohTransport
import civictech.iroh.SidecarClient
import civictech.iroh.SidecarMessage
import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.SidecarProtocol.Kind
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import civictech.iroh.await
import civictech.iroh.neverWithin
import civictech.iroh.quiesced
import org.junit.jupiter.api.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * [DiscoveredPeering]: discovery events in, bounded and clock-injected dials
 * out (DSC2 feature `computenet-ktn1l`, task `.3`; F3-D1, F3-D3, F3-D4, F3-D8,
 * F3-D9, and requirements [DSC2-DIAL-01..04], [DSC2-DIAL-06..09],
 * [DSC2-NEU-04], scenario BS-12).
 *
 * ## Everything here runs on the default lanes, and no test waits
 *
 * No test in this file spawns a sidecar: each drives a [FakeSidecar] loopback
 * socket that answers exactly when the test says so. There is **no
 * `Thread.sleep` in this package**, production or test — a delay is a number
 * the policy computes and a [ManualTimer] the test advances, so a backoff is
 * asserted rather than waited out. Presences are `await`, absences are
 * `neverWithin`/`quiesced` and `pollHostMessage`.
 *
 * What this file does NOT cover, by design: anything that needs two nodes —
 * the mutual-dial tie-break, key rotation, and the identity-mismatch refusal.
 * Those verdicts are mapped in [DiscoveredPeering] and proven by task `.4`.
 * Here only the plain `Admit` arm of `PeerTable.judge` is exercised.
 */
class DiscoveredPeeringTest {

    // --------------------------------------------------------------- fixtures

    /** A fresh, valid 32-byte iroh NodeId. */
    private fun nodeId(): ByteArray = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)

    private fun keyOf(nodeId: ByteArray): KeyId = fingerprint(Ed25519.publicKeyFromRaw(nodeId))

    private fun side(allow: Set<PeerId>? = null): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), peer = PeerId("node"), allow = allow)
    }

    /** Who [side]'s binding resolves a link's key to — the identity a mirror is stamped with. */
    private fun resolved(side: Peering.Side, nodeId: ByteArray): PeerId =
        when (val resolution = side.identityBinding.resolve(keyOf(nodeId), emptyList())) {
            is IdentityResolution.Bound -> resolution.peer
            is IdentityResolution.Unbound -> fail("expected ${keyOf(nodeId)} to be bound, got $resolution")
        }

    /** There is no child process behind [FakeSidecar]; this node's own id is a value the test chooses. */
    private class FixedSidecar(override val nodeId: ByteArray) : IrohTransport.Sidecar {
        override fun close() = Unit
    }

    /**
     * A node, a policy over it, and the fake both speak to.
     *
     * The clock is a plain field the test writes: [advanceTo] moves it and
     * then releases every retry due at that point, which is the only way time
     * passes anywhere in this file.
     */
    private class Rig(
        val fake: FakeSidecar,
        val client: SidecarClient,
        val node: IrohNode,
        val own: ByteArray,
        val side: Peering.Side,
    ) : AutoCloseable {

        @Volatile
        var now: Long = 0L

        val timer = ManualTimer { now }

        lateinit var peering: DiscoveredPeering

        /** Move the clock to [instant] and run every retry armed for it or earlier. */
        fun advanceTo(instant: Long) {
            now = instant
            timer.advanceTo(instant)
        }

        /** One `PEER_DISCOVERED`, as the sidecar's mDNS enumeration emits it. */
        fun discover(key: ByteArray, addresses: List<String> = listOf("127.0.0.1:1")) {
            fake.send(SidecarMessage.PeerDiscovered(key, addresses))
        }

        fun expire(key: ByteArray) = fake.send(SidecarMessage.PeerExpired(key))

        fun viewOf(key: ByteArray): PeerView? = peering.snapshot().firstOrNull { it.keyHex == NodeKey(key).hex }

        /** The next `DIAL`, strictly: anything else in front of it — an `ADD_PEER`, say — fails here. */
        fun nextDial(): HostMessage.Dial = assertIs<HostMessage.Dial>(fake.nextHostMessage())

        override fun close() {
            runCatching { if (::peering.isInitialized) peering.close() }
            runCatching { client.close() }
            runCatching { fake.close() }
        }
    }

    /**
     * Start a node and a [DiscoveredPeering] over a fake, answering the
     * `LISTEN` and the `WATCH_PEERS` by hand, and run [body] against them.
     *
     * Both start calls block on a control reply, so each runs on its own
     * thread while this one plays the sidecar.
     */
    private fun withPeering(
        side: Peering.Side = side(),
        own: ByteArray = OWN_ID,
        policy: DialPolicy = DialPolicy(),
        body: (Rig) -> Unit,
    ) {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val rig = Rig(fake, client, IrohNode(FixedSidecar(own), client, side), own, side)
                rig.use {
                    settle("node start") { rig.node.start(30.seconds) }.let { started ->
                        assertEquals(HostMessage.Listen, fake.nextHostMessage(), "a node LISTENs on its one shared client")
                        fake.send(SidecarMessage.Listening(listOf("127.0.0.1:1")))
                        started()
                    }
                    val startPeering = settle("peering start") {
                        DiscoveredPeering.start(rig.node, policy, clock = { rig.now }, timer = rig.timer)
                    }
                    assertEquals(
                        HostMessage.WatchPeers,
                        fake.nextHostMessage(),
                        "start() subscribes to discovery on the node's own client",
                    )
                    fake.send(SidecarMessage.Watching)
                    rig.peering = startPeering()
                    body(rig)
                }
            }
        }
    }

    /** Run [call] on a daemon thread and return a function that waits for its result. */
    private fun <T> settle(what: String, call: () -> T): () -> T {
        val done = ArrayBlockingQueue<Result<T>>(1)
        Thread({ done.put(runCatching { call() }) }, what).apply { isDaemon = true }.start()
        return { (done.poll(30, TimeUnit.SECONDS) ?: fail("$what did not settle within 30s")).getOrThrow() }
    }

    /** Every counter of [counters], by name, plus the refusals — one comparable sample ([DSC2-OBS-02]). */
    private fun sample(counters: DiscoveryCounters): Map<String, Long> =
        counters.every.associate { it.name to it.count } +
            counters.refusedBy().entries.associate { "refused:${it.key}" to it.value }

    /** No count in [after] is below the same count in [before] ([DSC2-OBS-02]). */
    private fun assertMonotonic(before: Map<String, Long>, after: Map<String, Long>, step: String) {
        before.forEach { (name, was) ->
            val now = after[name] ?: 0L
            assertTrue(now >= was, "$name went backwards over '$step': $was -> $now")
        }
    }

    // ------------------------------------------------------- BS-12: self drop

    /**
     * [DSC2-DIAL-04], BS-12. The event is counted as received and as
     * self-dropped, and leaves nothing at all behind: no entry, so no
     * eviction pressure and nothing to dial.
     *
     * Prescribed mutation: drop `observe`'s own-key comparison and this test
     * fails on `selfDropped`.
     */
    @Test
    fun `an event naming this node's own key is dropped, never retained and never dialled`() {
        withPeering { rig ->
            rig.discover(rig.own)

            await("the self event to be counted") { rig.peering.counters.eventsReceived.count == 1L }
            await("the self drop") { rig.peering.counters.selfDropped.count == 1L }
            assertEquals(emptyList(), rig.peering.snapshot(), "a self sighting retains nothing")
            assertTrue(neverWithin(500) { rig.fake.dials.get() > 0L }, "this node never dials itself")
            assertNull(rig.fake.pollHostMessage(200), "and writes nothing at all about itself")
        }
    }

    // ------------------------------------------- DIAL-02: one dial, no ADD_PEER

    @Test
    fun `a discovered key is dialled exactly once, with no ADD_PEER, and a second sighting is suppressed`() {
        withPeering { rig ->
            val key = nodeId()
            rig.discover(key)

            // Strict: an ADD_PEER ahead of the DIAL would fail this assertion
            // rather than be skipped, which is what pins "no ADD_PEER"
            // ([DSC2-DIAL-02]) — the sidecar already knows these addresses.
            val dial = rig.nextDial()
            assertTrue(dial.peerId.contentEquals(key), "the key discovery named is the key dialled")
            assertEquals(1L, rig.peering.counters.dialsAttempted.count)

            // A second sighting while the first dial is still in flight.
            rig.discover(key)
            await("the duplicate to be suppressed") { rig.peering.counters.duplicatesSuppressed.count == 1L }
            assertEquals(1L, quiesced { rig.fake.dials.get() }, "a key with a dial in flight is not dialled again")
            assertNull(rig.fake.pollHostMessage(200), "nothing else was written for this key — no ADD_PEER, no re-DIAL")
            assertEquals("Dialling(attempt=0)", assertNotNull(rig.viewOf(key)).state)
        }
    }

    // --------------------------------------------- DIAL-03: the injected schedule

    /**
     * [DSC2-DIAL-03]. The delays asserted here — 1000 and then 3000 — are the
     * *policy's* numbers, `schedule(0)` and `schedule(1)` applied to the clock
     * the test owns. Nothing waits for them: the dial that does not arrive at
     * 999 is an absence proven by the queue being empty while the clock says
     * 999.
     */
    @Test
    fun `a failed dial is retried at clock plus schedule, and not one tick earlier`() {
        withPeering(policy = DialPolicy(schedule = { attempt -> 1_000L * (attempt + 1) })) { rig ->
            val key = nodeId()
            rig.discover(key)

            val first = rig.nextDial()
            rig.fake.send(SidecarMessage.Failure(first.link, "unreachable"))
            await("the failed dial to be counted") { rig.peering.counters.dialsFailed.count == 1L }
            await("a retry to be armed") { rig.timer.pending() == 1 }

            rig.advanceTo(999)
            assertNull(rig.fake.pollHostMessage(200), "schedule(0) is 1000ms: nothing is due at 999")
            assertEquals(1, rig.timer.pending(), "and the retry is still armed")

            rig.advanceTo(1_000)
            val second = rig.nextDial()
            assertTrue(second.peerId.contentEquals(key))
            assertEquals(2L, rig.peering.counters.dialsAttempted.count)

            // schedule(1) is 2000ms, from the clock's 1000 — so 3000, not 2000.
            rig.fake.send(SidecarMessage.Failure(second.link, "unreachable"))
            await("the second failure to be counted") { rig.peering.counters.dialsFailed.count == 2L }
            rig.advanceTo(2_999)
            assertNull(rig.fake.pollHostMessage(200), "the second backoff runs to 3000")
            rig.advanceTo(3_000)
            assertTrue(rig.nextDial().peerId.contentEquals(key), "the third attempt")
        }
    }

    /**
     * [DSC2-DIAL-03]'s bound. Five keys are announced and none is answered, so
     * every dial that starts stays in flight; exactly `maxInFlightDials` of
     * them may exist, and a slot only frees when one of them ends.
     *
     * Prescribed mutation: make `nextDue` ignore `maxInFlight` and the
     * `quiesced` assertion below fails at 5.
     */
    @Test
    fun `dials in flight never exceed maxInFlightDials, and a failure frees exactly one slot`() {
        withPeering(policy = DialPolicy(maxInFlightDials = 2, schedule = { 60_000L })) { rig ->
            val keys = List(5) { nodeId() }
            keys.forEach { rig.discover(it) }

            await("every sighting to be counted") { rig.peering.counters.eventsReceived.count == 5L }
            assertEquals(2L, quiesced { rig.fake.dials.get() }, "two dials in flight, three keys waiting")
            assertEquals(2, rig.peering.snapshot().count { it.state.startsWith("Dialling") })
            assertEquals(5, rig.peering.snapshot().size, "the other three are retained and due")

            val inFlight = rig.nextDial()
            rig.fake.send(SidecarMessage.Failure(inFlight.link, "unreachable"))
            await("the third dial to take the freed slot") { rig.fake.dials.get() == 3L }
            assertEquals(3L, quiesced { rig.fake.dials.get() }, "one slot freed means exactly one more dial")
            assertEquals(2, rig.peering.snapshot().count { it.state.startsWith("Dialling") })
        }
    }

    // --------------------------------------- DIAL-06: re-dial, and expiry cancels

    @Test
    fun `a peering that drops unplanned returns to the dialable set and is re-dialled`() {
        withPeering { rig ->
            val key = nodeId()
            rig.discover(key)

            val dial = rig.nextDial()
            rig.fake.admit(dial.link, key)
            await("the key to read as peered") { rig.viewOf(key)?.state?.startsWith("Peered") == true }
            assertEquals(
                resolved(rig.side, key).name,
                assertNotNull(rig.viewOf(key)).attributedPeer,
                "the view carries the Session's own attribution, never a second derivation",
            )

            rig.fake.send(SidecarMessage.LinkDown(dial.link, "peer went away"))
            // The skipping form here, not Rig.nextDial: an admitted peering
            // announces, so `DATA` frames precede the re-dial. The "no
            // ADD_PEER" clause is pinned by the strict form in the
            // one-dial-per-key test, where no link is ever admitted.
            val redial = rig.fake.nextDial()
            assertTrue(redial.link != dial.link, "a re-dial is a new link id")
            assertTrue(redial.peerId.contentEquals(key))
            assertEquals(
                0,
                assertNotNull(rig.peering.connectionFor(NodeKey(key))).unadmittedOpens,
                "the dropped link had been admitted, so it charges no unadmitted open",
            )
        }
    }

    @Test
    fun `PEER_EXPIRED for a key with no live link cancels its armed retry for good`() {
        withPeering(policy = DialPolicy(schedule = { 1_000L })) { rig ->
            val key = nodeId()
            rig.discover(key)

            val dial = rig.nextDial()
            rig.fake.send(SidecarMessage.Failure(dial.link, "unreachable"))
            await("a retry to be armed") { rig.timer.pending() == 1 }

            rig.expire(key)
            await("the retry to be cancelled") { rig.timer.pending() == 0 }
            assertEquals("Expired", assertNotNull(rig.viewOf(key)).state)

            rig.advanceTo(Long.MAX_VALUE / 2)
            assertNull(rig.fake.pollHostMessage(300), "an expired key is not dialled however far the clock moves")
            assertEquals(1L, rig.fake.dials.get())
        }
    }

    // ------------------------- DIAL-01 / DIAL-07: one peering across the sources

    @Test
    fun `a key already peered on an accepted link is never dialled`() {
        val local = side()
        withPeering(local) { rig ->
            val key = nodeId()
            rig.fake.presentInbound(9, key)
            rig.fake.hello1From(9)
            assertIs<HostMessage.Data>(rig.fake.nextHostMessage(), "the accepting side answers with a hello")
            await("the accepted link to read as peered") { rig.viewOf(key)?.state?.startsWith("Peered") == true }
            assertEquals("ACCEPTED", assertNotNull(rig.viewOf(key)).source)

            rig.discover(key)
            await("the sighting to be suppressed") { rig.peering.counters.duplicatesSuppressed.count == 1L }
            assertEquals(0L, quiesced { rig.fake.dials.get() }, "[DSC2-DIAL-01]: a live peering means no dial")
        }
    }

    @Test
    fun `a key with a configured peering under this node is never dialled by discovery`() {
        withPeering { rig ->
            val key = nodeId()
            val connected = settle("connect-configured") {
                rig.node.connectConfigured(key, listOf("127.0.0.1:4242"), backoff = { 10L })
            }
            val added = assertIs<HostMessage.AddPeer>(rig.fake.nextHostMessage())
            assertTrue(added.nodeId.contentEquals(key))
            rig.fake.send(SidecarMessage.PeerAdded(key))
            val dial = rig.nextDial()
            rig.fake.send(SidecarMessage.LinkUp(dial.link, key, DIRECTION_OUTBOUND))
            connected()
            assertIs<HostMessage.Data>(rig.fake.nextHostMessage(), "the dialler's hello is its first frame")
            await("the configured link to reach the table") { rig.viewOf(key) != null }
            assertEquals("CONFIGURED", assertNotNull(rig.viewOf(key)).source)

            rig.discover(key)
            await("the sighting to be suppressed") { rig.peering.counters.duplicatesSuppressed.count == 1L }
            assertEquals(1L, quiesced { rig.fake.dials.get() }, "[DSC2-DIAL-07]: the configured dial is the only one")
        }
    }

    // ------------------------------- BS-05a: a stranger is dialled, then refused

    /**
     * BS-05a, [DSC2-ID-01..04]. The policy knows nothing about who is
     * allowed: it dials the stranger, and `Session` refuses the hello on this
     * side's allowlist — a refusal this package only ever *accounts for*.
     *
     * The absence that carries the requirement is asserted by a grep in the
     * task's verification, not here: `civictech.iroh.discover` reads
     * `Side.allow` nowhere and constructs no `PeerId`.
     */
    @Test
    fun `a discovered stranger is dialled, refused inside Session, and abandoned after the refusal limit`() {
        val friendly = side(allow = setOf(PeerId("bob")))
        withPeering(friendly, policy = DialPolicy(refusedDialLimit = 2)) { rig ->
            val stranger = nodeId()
            rig.discover(stranger)

            repeat(2) { attempt ->
                val dial = rig.nextDial()
                // Bring the link all the way to a hello: LINK_UP, our hello
                // drained, the peer's hello written back. The allowlist then
                // refuses the identity it resolves to.
                rig.fake.admit(dial.link, stranger)
                assertEquals(
                    HostMessage.CloseLink(dial.link),
                    rig.fake.nextHostMessage(),
                    "attempt $attempt: a hello outside the allowlist is closed",
                )
                rig.fake.send(SidecarMessage.LinkDown(dial.link, "refused"))
            }

            await("the key to be abandoned") { rig.viewOf(stranger)?.state?.startsWith("Abandoned") == true }
            val view = assertNotNull(rig.viewOf(stranger))
            assertEquals("Abandoned(NOT_ADMITTED)", view.state)
            assertEquals(DenialReason.NOT_ADMITTED, view.lastDenial)
            assertEquals(
                mapOf(DenialReason.NOT_ADMITTED to 2L),
                rig.peering.counters.refusedBy(),
                "each refused open is counted once, by reason",
            )
            assertEquals(2L, rig.peering.counters.dialsAttempted.count)

            // An abandoned key is a terminal state: a fresh sighting suppresses.
            rig.discover(stranger)
            await("the post-abandonment sighting to be suppressed") {
                rig.peering.counters.duplicatesSuppressed.count == 1L
            }
            assertEquals(2L, quiesced { rig.fake.dials.get() }, "an abandoned key is not dialled again")
        }
    }

    // --------------------------------------------------- OBS: counters and views

    /**
     * [DSC2-OBS-01..03]. Two properties in one run: every counter is
     * non-decreasing across a sequence of unrelated steps, and every row of
     * `snapshot()` is public material — a 64-character key hex, the addresses
     * the LAN advertised, a state name, a source name.
     */
    @Test
    fun `counters are monotonic across a mixed run and views carry only public material`() {
        withPeering(policy = DialPolicy(schedule = { 500L })) { rig ->
            var before = sample(rig.peering.counters)
            fun step(what: String, body: () -> Unit) {
                body()
                val after = sample(rig.peering.counters)
                assertMonotonic(before, after, what)
                before = after
            }

            val key = nodeId()
            step("a self sighting") {
                rig.discover(rig.own)
                await("the self drop") { rig.peering.counters.selfDropped.count == 1L }
            }
            step("a discovered key") {
                rig.discover(key, listOf("192.0.2.7:4242"))
                rig.nextDial()
            }
            step("a duplicate sighting") {
                rig.discover(key)
                await("the duplicate") { rig.peering.counters.duplicatesSuppressed.count == 1L }
            }
            step("a failed dial and its retry") {
                rig.fake.send(SidecarMessage.Failure(1, "unreachable"))
                await("the failure") { rig.peering.counters.dialsFailed.count == 1L }
                rig.advanceTo(500)
                rig.nextDial()
            }

            assertEquals(
                0L,
                rig.peering.counters.malformedEvents.count,
                "malformedEvents is read live from the client, which saw no bad frame",
            )
            val view = assertNotNull(rig.viewOf(key))
            assertEquals(64, view.keyHex.length, "a key hex is the full 32 bytes")
            assertTrue(view.keyHex.all { it in "0123456789abcdef" }, "lowercase hex and nothing else")
            assertEquals(listOf("192.0.2.7:4242"), view.addresses, "the addresses the LAN advertised, unchanged")
            assertEquals("DISCOVERED", view.source)
            assertNull(view.lastDenial, "nothing was refused here")
        }
    }

    /**
     * [DSC2-OBS-01]. `malformedEvents` is the one count this class does not
     * keep: the frames it counts are rejected inside `SidecarClient` and never
     * reach the policy at all, so the number is *read* from
     * `SidecarClient.malformedDiscoveryEvents` rather than copied
     * (`Counter.derived`). The assertion that matters is the second one — the
     * counter equals the client's own number — because a copy taken at start
     * would still read 0 here while the client read 1.
     *
     * The malformed frame is the one `SidecarWatchPeersTest` uses for BS-10: a
     * `PEER_DISCOVERED` whose payload is 5 bytes, short of the 32 a NodeId
     * needs.
     */
    @Test
    fun `malformedEvents is the client's own live count, and no such frame is a discovery event`() {
        withPeering { rig ->
            assertEquals(0L, rig.peering.counters.malformedEvents.count, "nothing malformed has arrived yet")

            rig.fake.sendRaw(Frame(Kind.PEER_DISCOVERED, 0L, ByteArray(5)))

            await("the malformed frame to show in the policy's counters") {
                rig.peering.counters.malformedEvents.count == 1L
            }
            assertEquals(
                rig.client.malformedDiscoveryEvents,
                rig.peering.counters.malformedEvents.count,
                "the counter reports the client's number, not a copy of it",
            )
            assertEquals(0L, rig.peering.counters.eventsReceived.count, "a frame the codec refused is no sighting")
            assertEquals(emptyList(), rig.peering.snapshot(), "and it retains nothing")
        }
    }

    // ------------------------------------------------- NEU-04: whose threads

    /**
     * [DSC2-NEU-04], and the structural constraint this whole class exists to
     * hold: **the sidecar reader thread only enqueues.**
     *
     * Two facts, and the second is the one that would catch a regression:
     *
     * 1. While a dial is blocked on an unanswered `DIAL`, no thread outside
     *    `iroh-discover-policy`, `iroh-discover-dial-*` and this test carries
     *    a `civictech.iroh.discover` frame — in particular not
     *    `iroh-sidecar-reader`, whose presence is asserted so the check cannot
     *    pass vacuously.
     * 2. The reader thread is still *serving* while that dial blocks: an
     *    inbound link presented at that moment is accepted and answered. A
     *    policy that dialled from the reader thread would deadlock here — the
     *    `LINK_UP` it waits for can only be delivered by the thread it is
     *    blocking — and this test would hang rather than fail, which is why
     *    the bounded `nextHostMessage` is the assertion.
     */
    @Test
    fun `the reader thread only enqueues, and keeps serving while a dial blocks`() {
        withPeering { rig ->
            val key = nodeId()
            rig.discover(key)
            rig.nextDial() // Never answered: a dial thread is now parked inside openLink.
            await("the key to read as dialling") { rig.viewOf(key)?.state?.startsWith("Dialling") == true }

            val offenders = Thread.getAllStackTraces()
                .filterValues { frames -> frames.any { it.className.startsWith("civictech.iroh.discover") } }
                .keys
                .map { it.name }
                .filterNot { it == "iroh-discover-policy" || it.startsWith("iroh-discover-dial-") }
                .filterNot { it == Thread.currentThread().name }
            assertEquals(emptyList(), offenders, "policy code ran on a thread this package does not own")
            assertTrue(
                Thread.getAllStackTraces().keys.any { it.name == "iroh-sidecar-reader" },
                "the reader thread exists, so the absence above is a real absence",
            )

            // The endpoint is alive while the dial blocks.
            val other = nodeId()
            rig.fake.presentInbound(9, other)
            rig.fake.hello1From(9)
            assertIs<HostMessage.Data>(
                rig.fake.nextHostMessage(),
                "the reader thread answered an inbound hello while a dial was blocked",
            )
            await("the accepted link to be peered") { rig.viewOf(other)?.state?.startsWith("Peered") == true }
        }
    }

    private companion object {
        /** This node's own endpoint id. Distinct from every [nodeId] a test mints. */
        val OWN_ID: ByteArray = ByteArray(NODE_ID_LEN) { 0x11 }
    }
}
