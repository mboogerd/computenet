package civictech.iroh

import civictech.cell.CellRef
import civictech.cell.control.Attention
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.AuthLevel
import civictech.cell.link.PeerId
import civictech.cell.membrane.Principal
import civictech.cell.port.PortRef
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.identity.Ed25519
import civictech.identity.PeerIdentity
import civictech.identity.anchor.AnchorIssuer
import civictech.identity.anchor.AnchorVouchedBinding
import civictech.identity.fingerprint
import civictech.iroh.discover.DialPolicy
import civictech.iroh.discover.DiscoveredPeering
import civictech.iroh.discover.ManualTimer
import civictech.iroh.discover.NodeKey
import civictech.iroh.discover.PeerView
import civictech.wire.asPeerCredentials
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.KeyPair
import java.security.interfaces.EdECPrivateKey
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * **BS-06 over REAL sidecars** (`computenet-qzr7n.1`, feature
 * `computenet-qzr7n`, epic `computenet-aas` §3 aas-D6, §4.2 `[DSC2-ID-06]`,
 * §4.8 `[DSC2-NV-01]`): the real-sidecar half of
 * [civictech.iroh.discover.KeyRotationContinuityFakeTest]. The fake twin plays
 * the same two facts against a hand-written sidecar; this file plays them
 * through three real `--offline --mdns` child processes, real mDNS discovery,
 * a real dial and a real hello — and, unlike the twin, it reads the identity
 * off a **delivered frame** rather than off a `LinkView`.
 *
 * This is also the first test anywhere to drive [IrohTransport.node] and
 * [DiscoveredPeering] against a real sidecar binary (open task
 * `computenet-g0n34` records that `IrohTransport.node` had zero such callers).
 *
 * ## The rig
 *
 * Node **A** is the discoverer: one [IrohTransport.node] endpoint seeded with
 * its own key, `--offline --mdns`, `allow = {b}`, an [AnchorVouchedBinding]
 * accepting exactly one anchor, and a [DiscoveredPeering] on a [ManualTimer]
 * over a frozen clock — so every "later" in this file is a value the test
 * writes, never a wall-clock wait on the policy (`[DSC2-DIAL-08]`). **B1** and
 * **B2** are two [IrohTransport.listen] endpoints, each a separate process
 * under a separate key (K1, K2) but each vouched by the same anchor as the
 * same name `PeerId("b")`. Neither B watches for peers and neither ever dials:
 * there is no mutual dial and no tie-break in this file (BS-08 over real
 * sidecars is `computenet-md1dt`'s).
 *
 * ## Arm 1 — the rotation (BS-06, `[DSC2-ID-06]`)
 *
 * K2's hello arrives **while K1's link is still live**. That overlap is what
 * aas-D6 means by "a name that already has a live peering", and it is the only
 * ordering the landed [civictech.iroh.discover.PeerTable] recognises as a
 * rotation. A peers K2 as the same `b`, refuses nobody, marks K1
 * `Superseded(by=K2)` without touching its link, and — once B1 dies — never
 * dials K1 again, not when its link drops and not ten years later.
 *
 * ## Arm 2 — the clean-restart ordering, recorded as it is
 *
 * B1 is killed first and B2 restarted at the same `--bind-addr` under K2. No
 * supersession fires: `PeerTable.linkDown` nulls the entry's `attributedPeer`,
 * so by the time K2's hello is judged the table has forgotten that K1 was `b`,
 * and K2 is a plain `Admit`. K1's own link drop answers `Redial`, so the policy
 * dials the dead endpoint at once and that dial fails. Every identity claim
 * still holds — same `PeerId`, same `Principal`, no denial — so what the arm
 * pins beyond identity is the *retry* behaviour, and it pins it as **landed**,
 * not as desired.
 *
 * ### What follows the failed dial, and why it is not "a retry is armed"
 *
 * An earlier version of this arm asserted `timer.pending() == 1` right after
 * that failure. The first real execution (CI run 35552410099) disproved it:
 * `dialsFailed` reached 1 and `pending()` stayed 0 for a full 60 s. The landed
 * path says why. [DiscoveredPeering]'s `onDialDone` increments `dialsFailed`
 * and then arms a retry **only** if `PeerTable.dialFailed` hands it a `dueAt`,
 * and `dialFailed` returns null for a key that is no longer `Dialling` when the
 * dial's result lands (`entry.state as? PeerState.Dialling ?: return null`).
 * Between `markDialling` and that result exactly two commands can move the
 * entry, and B1's death produces both: the QUIC `LINK_DOWN` and the sidecar's
 * `PEER_EXPIRED`.
 *
 * Only one of them leaves `pending()` at 0 for good. `PeerTable.expire` takes a
 * `Dialling` entry to `Expired` and `onExpired` cancels its retry, and nothing
 * returns an `Expired` entry to the dial schedule but a fresh sighting, which a
 * dead process cannot produce. A `LINK_DOWN` race cannot do it: it answers
 * `Redial` with `dueAt = now`, `pump` dials again at once, and the late result
 * then finds a `Dialling` entry and does arm. So the run that failed is the
 * ordering where A's sidecar reported K1 expired **while the re-dial was still
 * in flight** — within the dial's 5 s bound of the process exiting, rather than
 * at the 30-43 s mDNS TTL that `ne2oh-B5` measured for a peer that merely stops
 * being seen.
 *
 * The opposite ordering is not excluded and this file must not depend on
 * either: a `PEER_EXPIRED` delivered *before* the `LINK_DOWN` is swallowed
 * (`PeerTable.expire` refuses a key whose `upLinks` are not empty), and K1 then
 * stays `Retained` with one armed retry that the frozen clock never releases.
 * The arm therefore asserts what both orderings share — at most one retry armed
 * for the single key on the dial path, K1 never `Superseded` and never
 * re-`Peered`, and ten years of clock releasing at most that one retry rather
 * than a re-dial loop.
 *
 * That is a real tension with the letter of `[DSC2-ID-06]` ("the old key is
 * retired without re-dial once its link drops"): after a clean restart the old
 * key is dialled again the moment its link drops, and is retired — if it is
 * retired at all — by a `PEER_EXPIRED` whose arrival this policy neither
 * controls nor orders, rather than by supersession at the new hello. It is
 * named here and in the DSC2 findings
 * entry (`doc/distribution/findings.md`, task `computenet-qzr7n.2`) so that a
 * reader of a green run knows which half of the requirement this file proves.
 * Nothing is proposed and no mechanism is added; whether to build one is the
 * orchestrator's call.
 *
 * ## Which assertions the prescribed mutation reddens (qzr7n-D7)
 *
 * Deleting `old.state = PeerState.Superseded(byKey = key, since = now)` from
 * `PeerTable.judge` step 4 — keeping its `return Judgement.Supersede(old.key)`
 * — leaves K1's entry `Peered` after the rotation. `counters.superseded` still
 * counts, so arm 1 runs past its await and fails on its own assertions, in
 * this order:
 *
 * 1. `the old key names its successor` — K1's view reads `Peered(OUTBOUND)`
 *    instead of `Superseded(by=<K2>)`;
 * 2. and, had that one been removed, the ones after B1's death: a `Peered`
 *    entry is not in `linkDown`'s no-redial set, so its drop returns `Redial`
 *    and `dialsAttempted` climbs against a dead endpoint — reddening `no dial
 *    for a superseded key, ever`. `nothing is armed for a superseded key`
 *    (`pending() == 0`) reddens too whenever that dial's result lands on a
 *    still-`Dialling` entry, which arm 2's KDoc explains is a race and not a
 *    certainty; the dial itself is the reliable half.
 *
 * Arm 2 is untouched by that mutation, and that is the point: its `judge`
 * never reaches step 4's `old != null` branch at all, because `linkDown`
 * already cleared the attribution the branch searches for. A run where **both**
 * arms redden is a broken rig, not a discriminating mutation.
 *
 * ## Platform
 *
 * Gated twice, in order: [SidecarBinary.orSkip] (no `-Piroh.enabled=true`,
 * hence no built sidecar) then [MulticastGate.deliveryOrSkip] (this host does
 * not deliver multicast — true on MacBoo, `ne2oh-B6`). On macOS this class
 * therefore reports SKIPPED; its executed evidence comes from CI's
 * `iroh-sidecar` lane, which runs the whole `:iroh:check -Piroh.enabled=true`
 * and uploads `iroh/build/test-results/test/` as `iroh-test-results`.
 *
 * A discovery timeout is a SKIP only when the sidecar's own stderr carries its
 * `mdns unavailable` line ([MulticastGate.reasonFromStderr]); any other
 * timeout is a hard FAIL, because an unexplained silence must never read as a
 * quiet pass (`[DSC2-NV-01]`). And everything here runs on loopback with
 * `--offline`: it proves the flag wiring, the discovery event path, the dial
 * and the identity continuity on ONE host, and nothing about a real LAN
 * (`[DSC2-NV-01]`).
 */
class IrohDiscoveredKeyRotationTest {

    private val b = PeerId("b")

    /** Ten years in millis — the "and never again" horizon both arms advance to. */
    private val tenYears = 10L * 365 * 24 * 60 * 60 * 1_000

    // ------------------------------------------------------------- the rig

    /**
     * The frozen clock [DiscoveredPeering] and its [ManualTimer] share. A
     * field rather than a captured `var` so both lambdas read one cell, and
     * `@Volatile` because the policy thread reads what the test thread writes.
     */
    private class TestClock {
        @Volatile
        var now: Long = 0L
    }

    /** `--secret-key <hex>`: the sidecar's NodeId is then [keys]' public half. */
    private fun seedArgs(keys: KeyPair): List<String> =
        listOf("--secret-key", (keys.private as EdECPrivateKey).bytes.orElseThrow().joinToString("") { "%02x".format(it) })

    /** The 32-byte iroh NodeId a sidecar seeded with [keys] reports. */
    private fun nodeIdOf(keys: KeyPair): ByteArray = Ed25519.rawPublicKey(keys.public)

    /**
     * A loopback UDP port nothing holds right now — [IrohReconnectTest]'s
     * idiom, duplicated rather than widened into a shared file (this task's
     * claim is one test file). Racy in principle and accepted for the same
     * reason: a child process binds its own socket and cannot be handed one.
     */
    private fun freeUdpPort(): Int =
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** One peering side over its own registry, plus a host on that registry for cells. */
    private class Stack(identity: PeerIdentity, binding: AnchorVouchedBinding, allow: Set<PeerId>? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val side = Peering.Side(
            registry,
            ManagedHost(registry = registry),
            allow = allow,
            auth = PeerAuthPolicy.Open,
            credentials = identity.asPeerCredentials(),
            identityBinding = binding,
        )
    }

    private fun protocolFrame(target: CellRef) = HostedPortInvocation(
        cellRef = target,
        portName = "outlet",
        type = HostedPortInvocation.Type.PORT_PROTOCOL,
        invocation = Invocation("", emptyList(), emptyList()),
        protocolId = Protocols.Attention,
        protocolLink = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(target),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(target, "outlet"),
        ),
        protocolMessage = Attention(1f),
    )

    /**
     * Every sidecar's stderr, pooled: the skip discipline reads the lines of
     * ALL processes, because the one that could not bind mDNS is not
     * necessarily the one being waited on.
     */
    private val stderr = CopyOnWriteArrayList<String>()

    private fun stderrSink(label: String): (String) -> Unit = { line ->
        stderr += line
        println("[iroh-stderr $label] $line")
    }

    /** The mDNS-only flags every sidecar in this file carries. */
    private val mdnsArgs = listOf("--offline", "--mdns")

    // ------------------------------------------------------------- helpers

    /**
     * Poll until A holds a peered link for [key], for up to [seconds].
     *
     * On timeout: a pooled stderr line carrying the sidecar's own
     * `mdns unavailable` report turns this into a named SKIP; anything else is
     * a hard FAIL naming [what] (`[DSC2-NV-01]`,
     * [SidecarMdnsDiscoveryTest]'s discipline).
     */
    private fun awaitPeeredOrSkip(node: IrohNode, key: ByteArray, what: String, seconds: Long = 30) {
        val deadline = System.currentTimeMillis() + seconds * 1_000
        while (true) {
            if (node.links(key).any { it.peered }) return
            if (System.currentTimeMillis() >= deadline) {
                val reason = MulticastGate.reasonFromStderr(stderr)
                if (reason != null) assumeTrue(false) { reason }
                fail("$what within ${seconds}s and no sidecar reported an mDNS problem")
            }
            Thread.sleep(50)
        }
    }

    private fun viewOf(peering: DiscoveredPeering, key: ByteArray): PeerView? =
        peering.snapshot().firstOrNull { it.keyHex == NodeKey(key).hex }

    private fun stateOf(peering: DiscoveredPeering, key: ByteArray): String =
        assertNotNull(viewOf(peering, key), "no table entry for ${NodeKey(key).short}").state

    /** Move the shared clock to [instant] and release every retry armed for it or earlier. */
    private fun advanceTo(clock: TestClock, timer: ManualTimer, instant: Long) {
        clock.now = instant
        timer.advanceTo(instant)
    }

    /**
     * Deliver one `PORT_PROTOCOL` frame from [from]'s registry to [probe] on
     * A's host and return the [Principal] A stamped it with — read from A,
     * never from the sender.
     */
    private fun stampOf(
        from: Stack,
        probe: IrohKeyBoundAdmissionTest.PrincipalProbeCell,
        expectedIndex: Int,
        what: String,
    ): Principal {
        await("$what learns A's probe") { from.registry.location(probe.ref) is LocationRegistry.Remote }
        from.registry.deliver(protocolFrame(probe.ref))
        await("$what's delivery was dispatched on A") { probe.principals.size >= expectedIndex + 1 }
        return probe.principals[expectedIndex]
    }

    // ------------------------------------------------------------ the arms

    @Test
    fun `a discovered peer rotates its key under a live link  the new key is peered as the same identity, the old key is superseded and never re-dialled`() {
        val binary = SidecarBinary.orSkip()
        MulticastGate.deliveryOrSkip()

        val anchor = AnchorIssuer(PeerIdentity(Ed25519.generateKeyPair()))
        val wallNow = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1_000
        fun binding() = AnchorVouchedBinding(mapOf(anchor.issuerId to anchor.publicKey))
        fun vouched(keys: KeyPair, name: PeerId, issuance: Long) =
            PeerIdentity(keys, name, listOf(anchor.bind(name, fingerprint(keys.public), issuance, wallNow - day, wallNow + day)))

        val aKeys = Ed25519.generateKeyPair()
        val k1 = Ed25519.generateKeyPair()
        val k2 = Ed25519.generateKeyPair()
        val id1 = nodeIdOf(k1)
        val id2 = nodeIdOf(k2)

        val aStack = Stack(vouched(aKeys, PeerId("a"), 1), binding(), allow = setOf(b))
        val expected = Principal.Peer(b, AuthLevel.Authenticated, anchor.issuerId)

        IrohTransport.node(
            aStack.side,
            binary,
            stderrSink = stderrSink("a-node"),
            sidecarArgs = seedArgs(aKeys) + mdnsArgs,
        ).use { node ->
            val clock = TestClock()
            val timer = ManualTimer { clock.now }
            // A shortened dialTimeout bounds the one dial arm 2 makes to a dead
            // endpoint; nothing in THIS arm dials anything that is not there.
            DiscoveredPeering.start(
                node,
                DialPolicy(dialTimeout = 5.seconds),
                clock = { clock.now },
                timer = timer,
            ).use { peering ->
                val probe = IrohKeyBoundAdmissionTest.PrincipalProbeCell()
                aStack.host.managementInlet.call.spawn(probe)

                val b1Stack = Stack(vouched(k1, b, 1), binding(), allow = setOf(PeerId("a")))
                val listenerB1 = IrohTransport.listen(
                    b1Stack.side,
                    binary,
                    stderrSink = stderrSink("b1"),
                    sidecarArgs = seedArgs(k1) + mdnsArgs,
                )
                var listenerB1Open = true
                try {
                    assertTrue(listenerB1.nodeId.contentEquals(id1), "B1's sidecar did not take K1's seed")

                    // ---- b, under her first key: discovered, dialled, peered.
                    awaitPeeredOrSkip(node, id1, "A never peered the discovered K1")
                    assertEquals(b, node.links(id1).single().attributedPeer, "K1's link is attributed to b")
                    assertEquals(
                        expected,
                        stampOf(b1Stack, probe, 0, "B1"),
                        "a frame over K1's peering is stamped with the anchor-vouched name",
                    )

                    // ---- b again, under a second key, while the first is LIVE.
                    val b2Stack = Stack(vouched(k2, b, 2), binding(), allow = setOf(PeerId("a")))
                    IrohTransport.listen(
                        b2Stack.side,
                        binary,
                        stderrSink = stderrSink("b2"),
                        sidecarArgs = seedArgs(k2) + mdnsArgs,
                    ).use { listenerB2 ->
                        assertTrue(listenerB2.nodeId.contentEquals(id2), "B2's sidecar did not take K2's seed")

                        awaitPeeredOrSkip(node, id2, "A never peered the rotated K2")
                        await("the supersession to be counted") { peering.counters.superseded.count == 1L }

                        assertEquals(0L, node.admissionDenialCount, "a rotation is not a refusal ([DSC2-ID-06])")
                        assertTrue(
                            peering.counters.refusedBy().isEmpty(),
                            "nothing was attributed to any denial reason: ${peering.counters.refusedBy()}",
                        )

                        val oldLink = node.links(id1).single()
                        val newLink = node.links(id2).single()
                        assertTrue(oldLink.peered, "the old link stays up and peered until it drops on its own")
                        assertEquals(b, oldLink.attributedPeer, "K1's link is still attributed to b")
                        assertEquals(b, newLink.attributedPeer, "K2's link is attributed to the SAME b")

                        // The table says the same, by name and by state. This is
                        // the FIRST assertion the prescribed mutation reddens.
                        assertEquals(
                            "Superseded(by=${NodeKey(id2).short})",
                            stateOf(peering, id1),
                            "the old key names its successor",
                        )
                        assertEquals("Peered(OUTBOUND)", stateOf(peering, id2))
                        assertEquals("b", assertNotNull(viewOf(peering, id1)).attributedPeer)
                        assertEquals("b", assertNotNull(viewOf(peering, id2)).attributedPeer)

                        // And the identity a DELIVERY carries is unchanged — the
                        // claim the fake twin could not make, having no hosted
                        // cell on either end.
                        assertEquals(
                            probe.principals[0],
                            stampOf(b2Stack, probe, 1, "B2"),
                            "a frame over the ROTATED peering stamps exactly the Principal the old one did",
                        )
                        assertEquals(expected, probe.principals[1])

                        // ---- the old process dies. Nothing dials it, then or ever.
                        listenerB1.close()
                        listenerB1Open = false
                        await("the old link to be gone") { node.links(id1).isEmpty() }

                        val dialsBefore = peering.counters.dialsAttempted.count
                        assertEquals(
                            0,
                            timer.pending(),
                            "nothing is armed for a superseded key: its drop answers NoRedial ([DSC2-ID-06])",
                        )
                        advanceTo(clock, timer, tenYears)
                        assertTrue(
                            neverWithin(3_000) { peering.counters.dialsAttempted.count != dialsBefore },
                            "no dial for a superseded key, ever: dialsAttempted moved off $dialsBefore",
                        )
                        assertEquals(
                            "Superseded(by=${NodeKey(id2).short})",
                            stateOf(peering, id1),
                            "ten years later the old key still names its successor",
                        )
                        assertEquals(1L, peering.counters.superseded.count, "superseded moved exactly once")

                        // The surviving peering is untouched by all of it, and
                        // still delivers under the same name.
                        assertTrue(node.links(id2).single().peered, "b is still peered, on her new key")
                        assertEquals(
                            expected,
                            stampOf(b2Stack, probe, 2, "B2 after the old link died"),
                            "the rotated peering still stamps the same name after the old key's process is gone",
                        )
                        assertEquals(0L, node.admissionDenialCount, "still no refusal anywhere in a rotation")
                    }
                } finally {
                    if (listenerB1Open) runCatching { listenerB1.close() }
                }
            }
        }
    }

    @Test
    fun `a discovered peer restarts under a new key at the same address  the new key is peered as the same identity, the old key's drop puts it back on the dial path, and no supersession is recorded`() {
        val binary = SidecarBinary.orSkip()
        MulticastGate.deliveryOrSkip()

        val anchor = AnchorIssuer(PeerIdentity(Ed25519.generateKeyPair()))
        val wallNow = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1_000
        fun binding() = AnchorVouchedBinding(mapOf(anchor.issuerId to anchor.publicKey))
        fun vouched(keys: KeyPair, name: PeerId, issuance: Long) =
            PeerIdentity(keys, name, listOf(anchor.bind(name, fingerprint(keys.public), issuance, wallNow - day, wallNow + day)))

        val aKeys = Ed25519.generateKeyPair()
        val k1 = Ed25519.generateKeyPair()
        val k2 = Ed25519.generateKeyPair()
        val id1 = nodeIdOf(k1)
        val id2 = nodeIdOf(k2)

        val aStack = Stack(vouched(aKeys, PeerId("a"), 1), binding(), allow = setOf(b))
        val expected = Principal.Peer(b, AuthLevel.Authenticated, anchor.issuerId)

        // The restart is "the same address under a new key": pinning the UDP
        // bind address is what makes B2 the same endpoint address as B1, and
        // the DIFFERENT --secret-key is what makes it a rotation rather than
        // IrohReconnectTest's plain reconnect.
        val bindAddr = "127.0.0.1:${freeUdpPort()}"

        IrohTransport.node(
            aStack.side,
            binary,
            stderrSink = stderrSink("a-node"),
            sidecarArgs = seedArgs(aKeys) + mdnsArgs,
        ).use { node ->
            val clock = TestClock()
            val timer = ManualTimer { clock.now }
            DiscoveredPeering.start(
                node,
                DialPolicy(dialTimeout = 5.seconds),
                clock = { clock.now },
                timer = timer,
            ).use { peering ->
                val probe = IrohKeyBoundAdmissionTest.PrincipalProbeCell()
                aStack.host.managementInlet.call.spawn(probe)

                val b1Stack = Stack(vouched(k1, b, 1), binding(), allow = setOf(PeerId("a")))
                val listenerB1 = IrohTransport.listen(
                    b1Stack.side,
                    binary,
                    stderrSink = stderrSink("b1"),
                    sidecarArgs = seedArgs(k1) + mdnsArgs + listOf("--bind-addr", bindAddr),
                )
                var listenerB1Open = true
                try {
                    assertTrue(listenerB1.nodeId.contentEquals(id1), "B1's sidecar did not take K1's seed")

                    awaitPeeredOrSkip(node, id1, "A never peered the discovered K1")
                    assertEquals(b, node.links(id1).single().attributedPeer, "K1's link is attributed to b")
                    assertEquals(expected, stampOf(b1Stack, probe, 0, "B1"))

                    // ---- B1's process dies FIRST. Nothing rotates yet.
                    listenerB1.close()
                    listenerB1Open = false
                    await("the old link to be gone") { node.links(id1).isEmpty() }

                    // `linkDown` answered Redial — attribution cleared, dueAt =
                    // now — so the policy dials a dead endpoint at once. Each
                    // attempt is bounded by the 5 s dialTimeout. This is the
                    // half of [DSC2-DIAL-06] the clean restart does honour: the
                    // dropped key goes straight back on the dial schedule.
                    await("the re-dial to a dead endpoint to fail", timeoutMs = 90_000) {
                        peering.counters.dialsFailed.count >= 1L
                    }

                    // What follows the failure is NOT "a retry is armed" — see
                    // this class's KDoc: `onDialDone` arms one only when
                    // `PeerTable.dialFailed` returns a dueAt, and that method
                    // returns null for a key the sidecar's `PEER_EXPIRED` has
                    // already moved off `Dialling`. The first real execution
                    // took that branch. Both orderings are landed behaviour, so
                    // what is asserted here is what they share, read at a
                    // settled point rather than waited for: the dialling stops
                    // (a Retained entry's dueAt is now + schedule(0), which a
                    // clock frozen at 0 never reaches, and `observe` does not
                    // reset it on a re-sighting; an Expired entry is off the
                    // schedule entirely), at most one retry is armed for the
                    // only key on the dial path, and K1 is on that path or
                    // expired — never peered, superseded or abandoned.
                    val dialsAfterFailure = quiesced { peering.counters.dialsAttempted.count }
                    val armedAfterFailure = timer.pending()
                    assertTrue(
                        armedAfterFailure <= 1,
                        "at most one retry is armed for K1, the only key on the dial path; found $armedAfterFailure",
                    )
                    val afterFailure = stateOf(peering, id1)
                    assertTrue(
                        afterFailure == "Retained" || afterFailure == "Expired" || afterFailure.startsWith("Dialling"),
                        "a failed dial leaves K1 on the dial path or expired, not '$afterFailure'",
                    )
                    assertEquals(
                        0L,
                        peering.counters.superseded.count,
                        "nothing has been superseded: B2 has not started yet",
                    )
                    assertTrue(
                        neverWithin(3_000) { peering.counters.dialsAttempted.count != dialsAfterFailure },
                        "the frozen clock releases no retry: dialsAttempted moved off $dialsAfterFailure",
                    )

                    // ---- the same address comes back, under a NEW key.
                    val b2Stack = Stack(vouched(k2, b, 2), binding(), allow = setOf(PeerId("a")))
                    IrohTransport.listen(
                        b2Stack.side,
                        binary,
                        stderrSink = stderrSink("b2"),
                        sidecarArgs = seedArgs(k2) + mdnsArgs + listOf("--bind-addr", bindAddr),
                    ).use { listenerB2 ->
                        assertTrue(listenerB2.nodeId.contentEquals(id2), "B2's sidecar did not take K2's seed")

                        awaitPeeredOrSkip(node, id2, "A never peered the restarted K2")

                        // Every IDENTITY claim of [DSC2-ID-06] still holds.
                        assertEquals(b, node.links(id2).single().attributedPeer, "K2's link is attributed to b")
                        assertEquals(0L, node.admissionDenialCount, "a restart under a new key is not a refusal")
                        assertTrue(
                            peering.counters.refusedBy().isEmpty(),
                            "nothing was attributed to any denial reason: ${peering.counters.refusedBy()}",
                        )
                        assertEquals(
                            expected,
                            stampOf(b2Stack, probe, 1, "B2"),
                            "a frame over the restarted peering stamps the same anchor-vouched name",
                        )
                        assertEquals(probe.principals[0], probe.principals[1])

                        // And the SUPERSESSION claim does not: this is landed
                        // behaviour, recorded, not a wish. `judge` never
                        // reached its supersession branch, because `linkDown`
                        // had already nulled the attribution it searches for.
                        assertEquals(
                            0L,
                            peering.counters.superseded.count,
                            "no supersession fires on the clean-restart ordering (qzr7n-D6)",
                        )
                        val afterRotation = stateOf(peering, id1)
                        assertFalse(
                            afterRotation.startsWith("Superseded"),
                            "the old key was NOT superseded by the restart, yet reads '$afterRotation'",
                        )
                        // `PeerTable.describe` renders these three without
                        // arguments except Dialling's attempt — the bead's
                        // "Retained(" / "Expired(" spelling is not what the
                        // code prints. Expired is admitted here because A's
                        // sidecar may report K1 expired at any point after B1's
                        // process exits — promptly, as CI run 35552410099
                        // showed, or at the 30-43 s mDNS TTL (ne2oh-B5), or on
                        // a slow runner not before this line; the load-bearing
                        // half is the assertion above it.
                        assertTrue(
                            afterRotation == "Retained" || afterRotation == "Expired" || afterRotation.startsWith("Dialling"),
                            "the old key should still be on the dial path or expired, not '$afterRotation'",
                        )

                        // ---- ten years later. There is deliberately no wait on
                        // the sidecar's mDNS expiry here: whether K1 has already
                        // been retired by a `PEER_EXPIRED` or is still Retained
                        // holding one armed retry is the ordering race the KDoc
                        // names, and waiting for one of the two would be waiting
                        // for a coin. What both owe is the same bound —
                        // advancing the clock releases AT MOST the retry that is
                        // armed right now. `ManualTimer.advanceTo` runs it, it
                        // posts one `Due`, `pump` dials the one key that is due,
                        // and that dial's failure re-arms at
                        // `tenYears + schedule(attempt)`, in the future again
                        // ([DSC2-DIAL-03]). An expired key is released by
                        // nothing at all, which is the `armedBefore == 0` case
                        // of the same inequality.
                        val armedBefore = timer.pending()
                        assertTrue(armedBefore <= 1, "at most one retry is armed for K1, found $armedBefore")
                        val dialsBefore = quiesced { peering.counters.dialsAttempted.count }
                        advanceTo(clock, timer, tenYears)
                        assertTrue(
                            neverWithin(3_000) {
                                peering.counters.dialsAttempted.count > dialsBefore + armedBefore
                            },
                            "ten years release at most the one armed retry, never a re-dial loop: " +
                                "dialsAttempted passed ${dialsBefore + armedBefore}",
                        )
                        val tenYearsOn = stateOf(peering, id1)
                        assertFalse(
                            tenYearsOn.startsWith("Superseded") || tenYearsOn.startsWith("Peered"),
                            "ten years later the old key is neither superseded nor re-peered, yet reads '$tenYearsOn'",
                        )
                        assertTrue(node.links(id2).single().peered, "b is still peered, on her new key")
                        assertEquals(
                            0L,
                            peering.counters.superseded.count,
                            "and the supersession never fired at any point in this arm",
                        )
                    }
                } finally {
                    if (listenerB1Open) runCatching { listenerB1.close() }
                }
            }
        }
    }
}
