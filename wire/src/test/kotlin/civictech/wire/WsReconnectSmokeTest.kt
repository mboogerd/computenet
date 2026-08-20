package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.DenialReason
import civictech.cell.host.DeadLetter
import civictech.cell.link.PeerId
import civictech.cell.wire.PeerAuthPolicy
import civictech.cell.wire.Peering
import civictech.identity.DeterministicKeySource
import civictech.identity.PeerIdentity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.Collections
import java.util.UUID
import civictech.cell.data.delta.SetDelta

/**
 * M10.3 smoke: a client survives its listener dying and coming back on the
 * same port — the reconnect loop re-runs the hello, announcements re-mirror
 * both ways, parked sends replay, and traffic flows again. Correctness under
 * chaos stays with the seeded loopback harnesses; this proves the socket
 * glue only.
 */
class WsReconnectSmokeTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface DeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    class CollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals: MutableList<SetDelta<String>> = Collections.synchronizedList(mutableListOf())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    arrivals += value
                }
            })
        }
    }

    private fun membership(deltas: List<SetDelta<String>>): Set<String> {
        val live = mutableMapOf<String, MutableSet<Timestamp>>()
        deltas.forEach { delta ->
            delta.adds.forEach { (e, tags) -> live.getOrPut(e) { mutableSetOf() } += tags }
            delta.dels.forEach { (e, tags) ->
                live[e]?.let { it -= tags; if (it.isEmpty()) live.remove(e) }
            }
        }
        return live.keys
    }

    // T12 finding 4: the reconnect loop's own backoff (WsTransport.DEFAULT_RECONNECT_BACKOFF)
    // used to be the only thing standing between this test and a real wall-clock wait, so
    // every await here carried a 20s deadline. Driving the connection's backoff to zero (see
    // `connect` below) means reconnect is bounded only by scheduling, not sleep.
    //
    // That last step — 20s down to 2s — is what made this test flaky in CI, and the deadline
    // is back up to the 30s `testkit`'s canonical `awaitUntil` uses. "Bounded only by
    // scheduling" is not the same as "bounded by a small number": on CI's 2-core runner,
    // under a full `./gradlew build check`, scheduling is precisely the thing that gets slow.
    // This test timed out here as `parked send replayed via reconnect` on PRs #13 and #15,
    // neither of which touched `wire/` (computenet-8ru).
    //
    // Nothing about the T12 improvement is undone: the zero backoff stays, so a healthy run
    // still returns the moment the condition holds. A deadline is only ever reached when the
    // test is failing, so a generous one costs nothing and buys the runner room to breathe.
    //
    // The poll interval moves 20ms -> 100ms for the same reason, also matching `awaitUntil`.
    // Spinning every 20ms on a saturated 2-core box competes for the very CPU the reconnect
    // needs to make progress, so tight polling here makes the condition it waits for slower
    // to come true.
    //
    // computenet-8ru: the generous deadline was not enough, and the diagnosis above was
    // incomplete. The dialer was not merely slow to reconnect — it was starving itself.
    // `WsConnection.onClose` started a retry thread per close, and java-websocket reports
    // every *failed* connect attempt as a close, so retry loops multiplied without bound
    // for as long as the listener stayed down (~950 threads after 250ms, ~2700 after 1s;
    // by 3s `WsTransport.listen` could no longer win its own 10s start latch). The loops
    // also raced each other's `reset()`/`connect()` on the one client. The transport now
    // runs a single retry loop; `WsReconnectLoopBoundTest` is the regression gate, and
    // these deadlines are left generous because a healthy run never approaches them.
    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(100)
        }
    }

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    /**
     * The BS-13 stack: an **authenticated, signing** side, plus the two
     * observables the reconnect clause is about — the side-scoped replay ledger
     * and every dead letter either of its hosts emits.
     *
     * `PeerAuthPolicy.RequireAuthenticated` here is not decoration: in `:wire`
     * it *implies* both halves ([requireAnnouncementIdentity]), so this stack
     * signs every announcement it emits and verifies every one it receives,
     * under the key the peer's hello proved.
     */
    private class KeyedStack(seed: String, incarnation: Long = System.currentTimeMillis()) {
        val identity = PeerIdentity(DeterministicKeySource.keyPairFromSeed(seed.toByteArray()))
        val peerId: PeerId get() = identity.peerId
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val deadLetters: MutableList<DeadLetter> = Collections.synchronizedList(mutableListOf())
        val side = Peering.Side(
            registry,
            bridgeHost,
            peer = identity.peerId,
            auth = PeerAuthPolicy.RequireAuthenticated(),
            credentials = identity.asPeerCredentials(),
            // `incarnation` is named rather than left to the wall clock so the
            // restart case below can build two incarnations of one identity
            // without waiting for a millisecond to pass (`computenet-ssa.6`);
            // every other stack here takes the production default.
            announcementSigning = socketAnnouncementSigning().copy(incarnation = { incarnation }),
            announcementVerification = socketAnnouncementVerification(),
        )

        init {
            listOf(host, bridgeHost).forEach { h ->
                h.deadLetterOutlet.subscribe(
                    Use.fixed(
                        object : Propagate<DeadLetter> {
                            override fun propagate(value: DeadLetter) {
                                deadLetters += value
                            }
                        },
                        PortRef.generate(),
                    ),
                )
            }
        }

        /** The side-scoped admission ledger — one per identity, shared by every ingress this side hosts. */
        val ledger get() = side.announcementAdmission!!

        /** Announcements this side refused, for any reason ([DSC1-OBS-02..04]). */
        val rejected get() = ledger.rejectedAnnouncements

        /** Every dead letter whose denial names a replay — the thing BS-13 forbids. */
        fun replayDeadLetters(): List<DeadLetter> =
            deadLetters.toList().filter { it.denial?.reason == DenialReason.REPLAY }
    }

    @Test
    fun `a client reconnects after the listener restarts and parked sends replay`() {
        val client = Stack()
        var server = Stack()
        // the endpoint is held for the whole test — the listener dies and returns on
        // it, but the port is never handed back to the OS (computenet-dqy.22,
        // superseding T12 finding 4's probe-and-retry `relisten`; see `HeldPort`)
        val endpoint = HeldPort()
        val port = endpoint.port
        var listener = endpoint.serve(server.side)
        // zero reconnect backoff (T12 finding 4): reconnect timing is then bounded by
        // scheduling, not sleep, so the awaits above can drop their 20s deadlines.
        val connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 0L }
        try {
            var collector = CollectorCell()
            server.host.managementInlet.call.spawn(collector)
            await("collector announced") { client.registry.location(collector.ref) is LocationRegistry.Remote }

            val writer = SetCell<String>()
            client.host.managementInlet.call.spawn(writer)
            val remoteInlet = (HostedCellProxy.create(collector.ref, client.registry, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            writer.outlet.subscribe(Use.fixed(remoteInlet, PortRef.generate()))
            val api = (HostedCellProxy.create(writer.ref, client.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
            api.add("milk")
            await("pre-restart convergence") { membership(collector.arrivals.toList()) == setOf("milk") }

            // the server process "dies": listener gone, refs unpublished, sends park
            // (the endpoint keeps the port bound and resets every reconnect attempt
            // while nothing serves it — `HeldPort`)
            endpoint.release(listener)
            await("unpublish on disconnect") { client.registry.location(collector.ref) == null }
            api.add("cheese") // accepted while down: parks at the client

            // the server comes back on the SAME port with a rebuilt graph
            // (same collector ref — the restart-recovery shape)
            server = Stack()
            collector = CollectorCell(collector.ref)
            server.host.managementInlet.call.spawn(collector)
            listener = endpoint.serve(server.side)

            // no manual reconnect: the client's backoff loop finds the new
            // listener, re-hellos, announcements re-mirror, parked sends replay.
            // (Only the parked delta arrives — recovering "milk" is the
            // journal/replication story, kernel CrashRecoveryTest territory.)
            await("parked send replayed via reconnect") {
                membership(collector.arrivals.toList()).contains("cheese")
            }
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
            endpoint.close()
        }
    }

    /**
     * BS-13 — the reconnect catch-up burst is **not** a replay
     * ([DSC1-ANN-12], [DSC1-HELLO-02]).
     *
     * The same restart the smoke test above performs, over an authenticated
     * signing peering: announcements flow, the socket drops, the client
     * reconnects and re-helloes, and both sides re-announce everything they
     * hold. Every one of those re-announcements is a *new signing event* with
     * the next per-identity counter (sign-at-send), so the receiver's high-water
     * mark accepts the burst with no exemption logic.
     *
     * **Both reconnect shapes are exercised by this one reconnect, and the
     * assertions distinguish them** rather than inferring either from the happy
     * path:
     *
     * - *Retained client `Session`.* `WsConnection` keeps one Session — hence
     *   one `BridgeEgressCell` — for its whole life. What must survive is not
     *   the Session but the **signer**, which lives on the `Peering.Side`: the
     *   client's counter is asserted to be strictly greater after the burst
     *   than before the drop, never restarted.
     * - *Fresh listener `Session`.* `WsListener` builds a new Session, a new
     *   `BridgeIngressCell` and a new mirror per socket, so the server's
     *   post-reconnect ingress is a different object from the one that accepted
     *   the pre-drop announcements. What must survive *that* is the **ledger**,
     *   which also lives on the Side: `highWaterFor(client)` is asserted to have
     *   advanced from its pre-drop value rather than reset to null, and
     *   `trackedPeers` to still be 1 — one entry per identity, not per session.
     *
     * The listener is restarted on the **same** `Peering.Side`, which is what
     * makes the second bullet checkable at all: a rebuilt side would hand the
     * burst a virgin ledger that accepts anything, and the test would pass
     * without the property holding.
     *
     * **Measured discrimination** (`computenet-ssa.4.4`; both mutations compile
     * and leave every other `:wire` test green, `WsAnnouncementIdentityTest`
     * included):
     *
     * - `Peering.Side.announcementAdmission` from a `val` to a `get()`, so each
     *   read mints a fresh ledger — the per-connection mistake in its purest
     *   form. This case fails: *timed out awaiting: the server verified the
     *   client's announcements*.
     * - `AnnouncementAdmission.withVerifier` allocating a fresh `highWater` and
     *   counter instead of sharing the side's. Same failure — which is the
     *   point: the socket path's per-connection rebinding must not fork the
     *   ledger.
     *
     * **What this test does NOT cover, deliberately.** The server is restarted
     * as a *listener*, keeping its `Peering.Side` and therefore its signer. A
     * restarted **process** re-minting the same identity would start its
     * counter at 1 again while the surviving peer's high-water mark is already
     * past it, and its whole catch-up burst would classify as `REPLAY`. Counter
     * durability across a process restart is nowhere in DSC1 — the counter is
     * an in-memory `AtomicLong` by design (epic §9.3) — so that is a gap in the
     * feature, not something this test should hide by giving the rebuilt side a
     * fresh identity. Recorded on the task bead.
     */
    @Test
    fun `BS-13 an authenticated signing peering's reconnect catch-up burst is accepted, not replayed`() {
        val client = KeyedStack("bs13-client")
        val server = KeyedStack("bs13-server")
        val endpoint = HeldPort()
        val port = endpoint.port
        var listener = endpoint.serve(server.side)
        val connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 0L }
        try {
            val collector = CollectorCell()
            server.host.managementInlet.call.spawn(collector)
            await("collector announced") { client.registry.location(collector.ref) is LocationRegistry.Remote }

            val writer = SetCell<String>()
            client.host.managementInlet.call.spawn(writer)
            val remoteInlet = (HostedCellProxy.create(collector.ref, client.registry, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            writer.outlet.subscribe(Use.fixed(remoteInlet, PortRef.generate()))
            val api = (HostedCellProxy.create(writer.ref, client.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
            api.add("milk")
            await("pre-restart convergence") { membership(collector.arrivals.toList()) == setOf("milk") }

            // Both sides really verified signed announcements before the drop —
            // without this the whole test could pass on a peering that never
            // signed anything (the gate is only reached by a signed frame).
            await("the server verified the client's announcements") {
                server.ledger.highWaterFor(client.peerId) != null
            }
            val serverSawBeforeDrop = server.ledger.highWaterFor(client.peerId).shouldNotBeNull()
            val clientSawBeforeDrop = client.ledger.highWaterFor(server.peerId).shouldNotBeNull()
            val clientSignedBeforeDrop = client.side.announcementSigner!!.lastCounter
            server.rejected shouldBe 0L
            client.rejected shouldBe 0L

            // the socket drops; the send made while it is down parks at the client
            endpoint.release(listener)
            await("unpublish on disconnect") { client.registry.location(collector.ref) == null }
            api.add("cheese")

            // the SAME side comes back on the same port: a fresh listener, a
            // fresh Session, a fresh ingress and mirror — and the ledger and
            // signer of before.
            listener = endpoint.serve(server.side)

            await("parked send replayed via reconnect") {
                membership(collector.arrivals.toList()).contains("cheese")
            }

            // -- the burst was ACCEPTED, on both sides
            server.ledger.highWaterFor(client.peerId).shouldNotBeNull() shouldBeGreaterThan serverSawBeforeDrop
            client.ledger.highWaterFor(server.peerId).shouldNotBeNull() shouldBeGreaterThan clientSawBeforeDrop
            // -- and it was a re-SIGNING, not a re-send: the retained Session's
            //    signer carried on from where it was, rather than restarting.
            client.side.announcementSigner!!.lastCounter shouldBeGreaterThan clientSignedBeforeDrop

            // -- nothing was refused, by any reason, on either side
            server.rejected shouldBe 0L
            client.rejected shouldBe 0L
            server.replayDeadLetters().shouldBeEmpty()
            client.replayDeadLetters().shouldBeEmpty()

            // -- the ledger is keyed by IDENTITY: one entry after two sessions,
            //    two ingresses and two mirrors on the server side.
            server.ledger.trackedPeers shouldBe 1
            client.ledger.trackedPeers shouldBe 1
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
            endpoint.close()
        }
    }

    /**
     * `computenet-ssa.6` — the gap BS-13 above names and deliberately does not
     * cover: a signing peer's **process** restarts, re-minting the same
     * identity, and the peer that stayed up accepts its catch-up burst instead
     * of dead-lettering all of it as `REPLAY`.
     *
     * **What is rebuilt, and what is not, is the whole test.** The *client*
     * process dies: its connection is shut down and its entire `KeyedStack` —
     * registry, hosts, `Peering.Side`, and therefore its `AnnouncementSigner`
     * and its counter — is thrown away and rebuilt from the same key seed, so
     * `peerId` is unchanged and the new signer's counter is virgin. The
     * *server* is untouched throughout: same listener, same `Peering.Side`,
     * same `AnnouncementAdmission`, and its high-water mark for the client's
     * identity is already well past `1` when the new process starts talking.
     * That asymmetry is what makes the clause checkable — a rebuilt server, or
     * a client rebuilt under a fresh seed, would hand the burst a virgin ledger
     * that accepts anything and the test would pass without the property
     * holding.
     *
     * The mechanism under test is `AnnouncementSigner.counterFloor`: the second
     * incarnation's counters start above the first's, so the server's
     * `counter <= seen` test admits them with **no change to the gate** — no
     * reset, no window, no exemption. The two incarnations are named a minute
     * apart rather than taken from the wall clock so that the property is
     * asserted rather than raced (see [KeyedStack]).
     *
     * **Measured discrimination** (`computenet-ssa.6`): against the pre-fix
     * signer — `AtomicLong(0)`, no incarnation floor — this case fails at
     * *timed out awaiting: the restarted process's announcements were accepted*,
     * with the server's replay dead letters carrying
     * `counter=1 does not exceed the highest already accepted`. The kernel-level
     * twin, `SignedAnnouncementTest`'s
     * `a signing process that restarts re-minting the same identity is accepted,
     * not REPLAY`, fails deterministically under the same mutation.
     *
     * The complementary clause — that recovery was not bought with replay
     * tolerance — is pinned in `:kernel`, by
     * `a frame captured before the restart is still REPLAY after it`, where a
     * captured frame can actually be re-injected.
     */
    @Test
    fun `an authenticated signing peer that restarts its process re-converges rather than replaying`() {
        val firstBoot = 1_700_000_000_000L
        val server = KeyedStack("ssa6-server", incarnation = firstBoot)
        var client = KeyedStack("ssa6-client", incarnation = firstBoot)
        val clientId = client.peerId
        val endpoint = HeldPort()
        val port = endpoint.port
        val listener = endpoint.serve(server.side)
        var connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 0L }
        try {
            val collector = CollectorCell()
            server.host.managementInlet.call.spawn(collector)
            await("collector announced") { client.registry.location(collector.ref) is LocationRegistry.Remote }

            val writer = SetCell<String>()
            client.host.managementInlet.call.spawn(writer)
            val remoteInlet = (HostedCellProxy.create(collector.ref, client.registry, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            writer.outlet.subscribe(Use.fixed(remoteInlet, PortRef.generate()))
            val api = (HostedCellProxy.create(writer.ref, client.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
            api.add("milk")
            await("pre-restart convergence") { membership(collector.arrivals.toList()) == setOf("milk") }

            // the server really did verify signed announcements from the client,
            // so the high-water mark it holds is earned rather than absent
            await("the server verified the client's announcements") {
                server.ledger.highWaterFor(clientId) != null
            }
            val serverSawBeforeRestart = server.ledger.highWaterFor(clientId).shouldNotBeNull()
            val floorBeforeRestart = client.side.announcementSigner!!.counterFloor
            server.rejected shouldBe 0L

            // -- the client PROCESS dies and comes back, same identity, later
            //    incarnation. Everything client-side is rebuilt; the server's
            //    side, listener and ledger are the ones from before.
            connection.shutdown()
            client = KeyedStack("ssa6-client", incarnation = firstBoot + 60_000L)
            client.peerId shouldBe clientId // the same identity, re-minted
            client.side.announcementSigner!!.counterFloor shouldBeGreaterThan floorBeforeRestart
            client.ledger.highWaterFor(server.peerId) shouldBe null // a virgin ledger, as a restart has
            connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 0L }

            // the burst from the restarted process is ACCEPTED, not replayed:
            // the server's mark for this identity advances past where the dead
            // process left it.
            await("the restarted process's announcements were accepted") {
                (server.ledger.highWaterFor(clientId) ?: 0L) > serverSawBeforeRestart
            }
            server.replayDeadLetters().shouldBeEmpty()
            server.rejected shouldBe 0L

            // and the peering RE-CONVERGES: the new process learns the server's
            // collector and its writes land there again.
            await("the restarted process re-learned the collector") {
                client.registry.location(collector.ref) is LocationRegistry.Remote
            }
            val writer2 = SetCell<String>()
            client.host.managementInlet.call.spawn(writer2)
            val remoteInlet2 = (HostedCellProxy.create(collector.ref, client.registry, DeltaInletProxy::class.java)
                    as DeltaInletProxy).inlet.call
            writer2.outlet.subscribe(Use.fixed(remoteInlet2, PortRef.generate()))
            val api2 = (HostedCellProxy.create(writer2.ref, client.registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call
            api2.add("cheese")
            await("post-restart convergence") { membership(collector.arrivals.toList()).contains("cheese") }

            server.replayDeadLetters().shouldBeEmpty()
            server.rejected shouldBe 0L
            // still one entry per identity: the restart did not mint a second
            server.ledger.trackedPeers shouldBe 1
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
            endpoint.close()
        }
    }
}
