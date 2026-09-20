package civictech.iroh

import civictech.cell.DenialReason
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.IdentityResolution
import civictech.cell.link.KeyId
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import civictech.identity.Ed25519
import civictech.identity.fingerprint
import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * [IrohNode]: one endpoint that listens and dials on one [SidecarClient], the
 * [HelloGate] it consults on every hello, and the blame-free quiet close a
 * delegated-reconnect [IrohTransport.IrohConnection] gives a host (DSC2 feature
 * `computenet-ktn1l`, task `.1`; decisions ktn1l-D11..D15).
 *
 * ## Everything here runs on the default lanes
 *
 * No test in this file spawns a sidecar and none calls [SidecarBinary.orSkip]:
 * every one drives a [FakeSidecar] loopback socket that speaks `PROTOCOL.md`
 * by hand. That is not a convenience. The properties under test are *decisions
 * this side takes about a link* — which verdict a gate got, what a drop was
 * charged to, whether a dial followed — and a real sidecar answers when it
 * likes, so each of them would become a race. A fake that answers exactly when
 * the test says makes them facts about frame counts ([FakeSidecar.dials]) and
 * about counters, never about timing. There is no `Thread.sleep` here: waits
 * are [await], absences are [neverWithin] and [quiesced].
 *
 * What this file does NOT cover, by design: any *policy* — which verdict a key
 * deserves, when a discovered key is dialled, how a mutual dial is resolved.
 * That is `civictech.iroh.discover`'s (tasks `.2`/`.3`) and its two-node proof
 * is task `.4`'s. Here a gate is a lambda a test wrote.
 */
class IrohNodeTest {

    /** A fresh, valid 32-byte iroh NodeId. */
    private fun nodeId(): ByteArray = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)

    private fun keyOf(nodeId: ByteArray): KeyId = fingerprint(Ed25519.publicKeyFromRaw(nodeId))

    private fun side(allow: Set<PeerId>? = null): Peering.Side {
        val registry = LocationRegistry()
        return Peering.Side(registry, ManagedHost(registry = registry), peer = PeerId("node"), allow = allow)
    }

    /** Who [side]'s binding resolves the link's key to — the identity a mirror is stamped with. */
    private fun resolved(side: Peering.Side, nodeId: ByteArray): PeerId =
        when (val resolution = side.identityBinding.resolve(keyOf(nodeId), emptyList())) {
            is IdentityResolution.Bound -> resolution.peer
            is IdentityResolution.Unbound -> fail("expected ${keyOf(nodeId)} to be bound, got $resolution")
        }

    /** There is no child process behind [FakeSidecar]; this node's own id is never read here. */
    private object NoSidecar : IrohTransport.Sidecar {
        override val nodeId: ByteArray get() = ByteArray(NODE_ID_LEN)
        override fun close() = Unit
    }

    /**
     * A node over [fake], started through the production path — `LISTEN`
     * answered by hand, so the `LISTENING` addresses this asserts are the ones
     * [IrohNode.start] recorded.
     */
    private fun startedNode(fake: FakeSidecar, client: SidecarClient, side: Peering.Side): IrohNode {
        val node = IrohNode(NoSidecar, client, side)
        val started = ArrayBlockingQueue<Result<Unit>>(1)
        Thread({ started.put(runCatching { node.start(30.seconds) }) }, "node-start")
            .apply { isDaemon = true }
            .start()
        assertEquals(HostMessage.Listen, fake.nextHostMessage(), "a node LISTENs on its one shared client")
        fake.send(SidecarMessage.Listening(listOf("127.0.0.1:1")))
        (started.poll(30, TimeUnit.SECONDS) ?: fail("start did not settle within 30s")).getOrThrow()
        assertEquals(listOf("127.0.0.1:1"), node.addresses)
        return node
    }

    /** Every link event this node emitted, in order. */
    private class Events : IrohNode.NodeLinkListener {
        val ups = CopyOnWriteArrayList<IrohNode.LinkView>()
        val admitted = CopyOnWriteArrayList<IrohNode.LinkView>()
        val downs = CopyOnWriteArrayList<Pair<IrohNode.LinkView, IrohTransport.IrohConnection.LinkOutcome?>>()

        override fun onUp(link: IrohNode.LinkView) { ups += link }
        override fun onAdmitted(link: IrohNode.LinkView) { admitted += link }
        override fun onDown(link: IrohNode.LinkView, outcome: IrohTransport.IrohConnection.LinkOutcome?) {
            downs += link to outcome
        }
    }

    /** Runs [body] with a fake, a client over it and a started node on [side]. */
    private fun withNode(side: Peering.Side = side(), body: (FakeSidecar, SidecarClient, IrohNode) -> Unit) {
        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                body(fake, client, startedNode(fake, client, side))
            }
        }
    }

    // ---------------------------------------------------------------- the gate

    @Test
    fun `an accepted hello the gate admits is answered, bound and recorded on the node`() {
        val local = side()
        withNode(local) { fake, _, node ->
            val events = Events()
            node.onLinkEvent(events)
            val remote = nodeId()

            fake.presentInbound(7, remote)
            fake.hello1From(7)

            // Our own hello goes back on the accepted link — the first and only
            // thing an admitted accepting side writes before it announces.
            val answer = assertIs<HostMessage.Data>(fake.nextHostMessage())
            assertEquals(7L, answer.link)
            assertTrue(
                String(answer.payload, StandardCharsets.UTF_8).startsWith(IrohTransport.HELLO_PREFIX),
                "the accepting side answers with a hello",
            )

            await("the accepted link to read as peered") { node.links(remote).firstOrNull()?.peered == true }
            val view = node.links(remote).single()
            assertEquals(7L, view.linkId)
            assertEquals(LinkDirection.INBOUND, view.direction)
            assertEquals(IrohNode.LinkSource.ACCEPTED, view.source)
            assertEquals(resolved(local, remote), view.attributedPeer, "the view carries the Session's own attribution")
            assertEquals(0L, node.admissionDenialCount)

            // The lifecycle, in order, and the down of an accepted link carries
            // no connection accounting.
            assertEquals(listOf(7L), events.ups.map { it.linkId })
            await("the admitted event") { events.admitted.isNotEmpty() }
            assertEquals(resolved(local, remote), events.admitted.single().attributedPeer)
            fake.send(SidecarMessage.LinkDown(7, "peer went away"))
            await("the down event") { events.downs.isNotEmpty() }
            val (downView, outcome) = events.downs.single()
            assertEquals(7L, downView.linkId)
            assertNull(outcome, "an accepted link has no dialling connection and so no LinkOutcome")
            assertTrue(node.links(remote).isEmpty(), "a link that is down is not a link this node holds")
        }
    }

    @Test
    fun `a CloseQuietly verdict closes the link and blames nobody`() {
        withNode { fake, _, node ->
            val events = Events()
            node.onLinkEvent(events)
            node.gate = HelloGate { _, _, _, _ -> Verdict.CloseQuietly("a test's verdict") }
            val remote = nodeId()

            fake.presentInbound(9, remote)
            fake.hello1From(9)

            // The very next thing the host writes is the close: no hello, so no
            // mirror ref reached the peer and no announcement can follow.
            assertEquals(
                HostMessage.CloseLink(9),
                fake.nextHostMessage(),
                "a quietly closed link is closed without being written to",
            )
            assertEquals(0L, node.admissionDenialCount, "a quiet close is not a denial")
            assertEquals(0L, node.preHelloDrops)
            assertNull(assertNotNull(node.sessionFor(9)).lastAdmissionDenial, "nothing was recorded against this link")
            assertFalse(node.links(remote).single().peered)

            fake.send(SidecarMessage.LinkDown(9, "closed"))
            await("the down event") { events.downs.isNotEmpty() }
            assertFalse(events.downs.single().first.peered, "the down reports a link that was never peered")
        }
    }

    @Test
    fun `a Refuse verdict is a real denial, recorded with the gate's own reason`() {
        withNode { fake, _, node ->
            val remote = nodeId()
            node.gate = HelloGate { _, _, _, resolved ->
                Verdict.Refuse(DenialReason.NOT_ADMITTED, resolved, "a test's refusal")
            }

            fake.presentInbound(11, remote)
            fake.hello1From(11)

            assertEquals(HostMessage.CloseLink(11), fake.nextHostMessage())
            await("the denial to be recorded") { node.admissionDenialCount == 1L }
            val denial = assertNotNull(assertNotNull(node.sessionFor(11)).lastAdmissionDenial)
            assertEquals(DenialReason.NOT_ADMITTED, denial.reason)
            assertEquals(resolved(side(), remote), denial.principal, "the gate's principal is what is blamed")
            assertEquals("a test's refusal", denial.detail)
            assertFalse(node.links(remote).single().peered)
        }
    }

    /**
     * ktn1l-D12's ordering, which is a security property and not a detail: the
     * gate is consulted **after** `Peering.Side.allow`, so a stranger's hello
     * never reaches the bookkeeping that decides supersession and tie-breaks.
     *
     * Moving the consult above `Session.admitted` fails this on the first
     * assertion — the counting gate would have been called for a peer the
     * allowlist refuses.
     */
    @Test
    fun `a hello the allowlist refuses never reaches the gate`() {
        val stranger = nodeId()
        val friend = nodeId()
        val friendly = side(allow = setOf(resolved(side(), friend)))
        withNode(friendly) { fake, _, node ->
            val consults = AtomicInteger()
            node.gate = HelloGate { _, _, _, _ ->
                consults.incrementAndGet()
                Verdict.Admit
            }

            fake.presentInbound(7, stranger)
            fake.hello1From(7)

            assertEquals(HostMessage.CloseLink(7), fake.nextHostMessage(), "the allowlist closes it")
            await("the allowlist denial") { node.admissionDenialCount == 1L }
            assertEquals(
                DenialReason.NOT_ADMITTED,
                assertNotNull(assertNotNull(node.sessionFor(7)).lastAdmissionDenial).reason,
            )
            assertEquals(0, consults.get(), "the gate must not see a hello the allowlist refuses")

            // Control: an admitted peer DOES reach the gate, so the zero above
            // is the ordering and not a gate that is never consulted at all.
            fake.presentInbound(9, friend)
            fake.hello1From(9)
            // firstOrNull, not single: this polls from before the LINK_UP has
            // even been dispatched, where `single()` would throw out of the
            // predicate rather than wait.
            await("the friend's link to be admitted") { node.links(friend).firstOrNull()?.peered == true }
            assertEquals(1, consults.get())
        }
    }

    // ------------------------------------------------- the dialling side

    /** A connection [IrohNode.dialDiscovered] built, and every outcome it reported. */
    private class Discovered(node: IrohNode, peerNodeId: ByteArray, refusedDialLimit: Int = IrohTransport.REFUSED_DIAL_LIMIT) {
        val outcomes = LinkedBlockingQueue<IrohTransport.IrohConnection.LinkOutcome>()
        val connection = node.dialDiscovered(peerNodeId, redialTimeout = 30.seconds, refusedDialLimit = refusedDialLimit) {
            outcomes.put(it)
        }

        fun nextOutcome(): IrohTransport.IrohConnection.LinkOutcome =
            outcomes.poll(30, TimeUnit.SECONDS) ?: fail("no LinkOutcome within 30s")
    }

    @Test
    fun `an outbound link the gate closes quietly costs the peer nothing and is not re-dialled`() {
        withNode { fake, _, node ->
            node.gate = HelloGate { _, _, _, _ -> Verdict.CloseQuietly("a test's tie-break") }
            val peer = nodeId()
            val discovered = Discovered(node, peer)

            // dialDiscovered does NOT dial: the caller opens the link itself.
            assertTrue(neverWithin(500) { fake.dials.get() > 0L }, "dialDiscovered opens no link of its own")

            val opened = ArrayBlockingQueue<Result<Unit>>(1)
            Thread({ opened.put(runCatching { discovered.connection.openLink(30.seconds) }) }, "open-link")
                .apply { isDaemon = true }
                .start()
            val dial = fake.nextDial()
            assertContentEquals(peer, dial.peerId, "dialDiscovered dials the key it was given")
            fake.admit(dial.link, peer)
            (opened.poll(30, TimeUnit.SECONDS) ?: fail("openLink did not settle within 30s")).getOrThrow()

            assertEquals(HostMessage.CloseLink(dial.link), fake.nextHostMessage())
            fake.send(SidecarMessage.LinkDown(dial.link, "closed"))

            val outcome = discovered.nextOutcome()
            // The accounting first, deliberately: it is the assertion the
            // prescribed mutation (retire() ignoring `quietClose`) is meant to
            // break, and asserting `outcome.quiet` before it would hide which
            // half of the property failed.
            assertEquals(0, discovered.connection.unadmittedOpens, "a quiet close is not an unadmitted open")
            assertFalse(discovered.connection.abandonedAfterRefusals)
            assertFalse(outcome.peered)
            assertTrue(outcome.quiet, "the gate's quiet close is what ended this link")
            assertFalse(outcome.abandoned)
            assertFalse(outcome.afterRefusal)
            assertNull(outcome.lastDenial)
            assertEquals(1L, quiesced { fake.dials.get() }, "reconnect is the caller's: this connection dials nothing")
        }
    }

    /**
     * The contrast that gives the test above its meaning: the same connection,
     * the same absence of a re-dial — but an ordinary unadmitted drop IS
     * charged, and the outcome says so. Delegation, not a suppressed loop.
     */
    @Test
    fun `an ordinary unadmitted drop is charged, reported and still not re-dialled here`() {
        withNode { fake, _, node ->
            val discovered = Discovered(node, nodeId())

            val link = fake.answerOpenLink(discovered.connection)
            fake.send(SidecarMessage.LinkDown(link, "transport drop"))

            val outcome = discovered.nextOutcome()
            assertFalse(outcome.peered)
            assertFalse(outcome.quiet)
            assertFalse(outcome.abandoned)
            assertEquals(1, discovered.connection.unadmittedOpens)
            assertEquals(1L, quiesced { fake.dials.get() }, "the re-dial decision was delegated, so none was taken")
            assertEquals(0L, discovered.connection.backoffConsultations, "no schedule is consulted under delegation")
        }
    }

    @Test
    fun `delegated reconnect keeps the abandonment accounting inside the connection`() {
        withNode { fake, _, node ->
            val discovered = Discovered(node, nodeId(), refusedDialLimit = 2)

            val first = fake.answerOpenLink(discovered.connection)
            fake.send(SidecarMessage.LinkDown(first, "refused"))
            assertFalse(discovered.nextOutcome().abandoned, "one unadmitted open is not a run")
            assertFalse(discovered.connection.abandonedAfterRefusals)

            val second = fake.answerOpenLink(discovered.connection)
            fake.send(SidecarMessage.LinkDown(second, "refused"))
            val outcome = discovered.nextOutcome()
            assertTrue(outcome.abandoned, "the second unadmitted open reaches the limit")
            assertTrue(discovered.connection.abandonedAfterRefusals)
            assertEquals(2, discovered.connection.unadmittedOpens)
        }
    }

    @Test
    fun `closing a connection that does not own the client leaves the endpoint usable`() {
        withNode { fake, client, node ->
            val discovered = Discovered(node, nodeId())
            val link = fake.answerOpenLink(discovered.connection)

            discovered.connection.close()
            assertEquals(HostMessage.CloseLink(link), fake.nextHostMessage(), "closing takes only its own link down")

            // The shared client is untouched: neither SHUTDOWN nor a closed
            // socket, so a control verb still answers.
            val id = ArrayBlockingQueue<Result<ByteArray>>(1)
            Thread({ id.put(runCatching { client.getId(30.seconds) }) }, "get-id").apply { isDaemon = true }.start()
            assertEquals(HostMessage.GetId, fake.nextHostMessage())
            fake.send(SidecarMessage.Id(ByteArray(NODE_ID_LEN) { 3 }))
            assertContentEquals(
                ByteArray(NODE_ID_LEN) { 3 },
                (id.poll(30, TimeUnit.SECONDS) ?: fail("GET_ID did not settle within 30s")).getOrThrow(),
            )
        }
    }

    @Test
    fun `connectConfigured adds the peer, dials on the shared client and re-dials on its own loop`() {
        withNode { fake, _, node ->
            val peer = nodeId()
            val connected = ArrayBlockingQueue<Result<IrohTransport.IrohConnection>>(1)
            Thread({
                connected.put(
                    runCatching {
                        node.connectConfigured(peer, listOf("127.0.0.1:4242"), backoff = { 10L })
                    },
                )
            }, "connect-configured").apply { isDaemon = true }.start()

            val added = assertIs<HostMessage.AddPeer>(fake.nextHostMessage(), "a configured peering teaches the endpoint its addresses")
            assertContentEquals(peer, added.nodeId)
            assertEquals(listOf("127.0.0.1:4242"), added.addresses)
            fake.send(SidecarMessage.PeerAdded(peer))

            val dial = fake.nextDial()
            fake.send(SidecarMessage.LinkUp(dial.link, peer, DIRECTION_OUTBOUND))
            (connected.poll(30, TimeUnit.SECONDS) ?: fail("connectConfigured did not settle within 30s")).getOrThrow()
            assertIs<HostMessage.Data>(fake.nextHostMessage(), "the dialler's hello is its first frame")

            val view = node.links(peer).single()
            assertEquals(IrohNode.LinkSource.CONFIGURED, view.source)
            assertEquals(LinkDirection.OUTBOUND, view.direction)
            assertEquals(dial.link, view.linkId)

            // Unplanned: this one re-dials itself, unlike a discovered peering.
            fake.send(SidecarMessage.LinkDown(dial.link, "transport drop"))
            val redial = fake.nextDial()
            assertTrue(redial.link != dial.link, "a re-dial is a new link id")
            assertContentEquals(peer, redial.peerId)
        }
    }
}
