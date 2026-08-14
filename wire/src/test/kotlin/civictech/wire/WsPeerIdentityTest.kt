package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.Consumer
import civictech.cell.wire.Peering
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.UUID

/**
 * V4-PEERID over a real socket: the case `Peering.loopback` cannot pose.
 *
 * A loopback re-announces through the *same* bridge egress, so nothing about a
 * peer's identity is under stress there. A socket reconnect is different: the
 * listener builds a brand-new `WsTransport.Session` on every `onOpen`
 * (`WsTransport.kt`), hence a new `BridgeEgressCell` and a new registry mirror,
 * so anything that identified a peer by its egress renamed the peer's whole
 * hull on every reconnect — the defect this ticket closes.
 *
 * What is asserted here is therefore both halves:
 *
 * - the sink genuinely changes across the reconnect (otherwise the test would
 *   pass for the wrong reason — a connection that never actually re-formed);
 * - the recorded [PeerId] does not.
 *
 * The **ordering** of the late bind is asserted, not argued: the collector is
 * published on the listener *before* the client ever connects, so the very
 * first announcement the connection carries is `announceTo`'s catch-up burst —
 * the earliest announcement that exists. If the mirror's peer were bound after
 * `Peering.announceTo` rather than before it, that burst would land anonymous
 * and these assertions would fail.
 */
class WsPeerIdentityTest {

    class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

        init {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) = Unit
            })
        }
    }

    private class Stack(name: String?) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = name?.let { PeerId(it) })
    }

    // 30s and a 100ms poll, matching `testkit`'s canonical `awaitUntil` — see the longer
    // argument on `WsReconnectSmokeTest.await`, which had the same defect.
    //
    // The deadline here is only ever reached when the test is failing, so a generous one is
    // free on a healthy machine: every await below returns the instant its condition holds.
    // At 5s this test timed out on `re-announced after reconnect` in CI (computenet-pvs) —
    // waiting on a dialer that has to notice the listener died, reconnect, re-hello and
    // re-announce, every step of which is scheduling-bound on a loaded 2-core runner.
    //
    // Note this is NOT the port-rebind race: the re-listen had already succeeded in the
    // observed failures. Diagnosing it as a bind flake and hardening the rebind would have
    // left the actual cause untouched. (The rebind race was real too, and separately fatal
    // in CI — computenet-dqy.22 removed the rebind rather than hardening it; see `HeldPort`.)
    //
    // computenet-8ru: nor was the deadline the cause. The dialer starved itself — a retry
    // thread per close, and java-websocket reports every failed connect attempt as a close,
    // so retry loops multiplied for as long as the listener was down. See
    // `WsReconnectLoopBoundTest`, which asserts the bound directly, and `WsConnection`'s
    // `reconnecting` guard, which is the fix.
    //
    // ## computenet-pvs, closed on evidence rather than on a further change
    //
    // The bead reported this test as a `build-test-fast` flake and proposed hardening the
    // rebind. Its CI history turned out to carry TWO distinct signatures, each already
    // answered by work that landed after the report, so nothing here needed changing:
    //
    //   - `AssertionFailedError: timed out awaiting: re-announced after reconnect`
    //     (run 31278665961, 2026-08-08, against the then-5s deadline) — the dialer
    //     starvation described above. Fixed by `WsConnection`'s `reconnecting` guard
    //     (computenet-8ru); `WsReconnectLoopBoundTest` is the standing regression gate.
    //   - `IllegalStateException: could not re-bind port <n> after 20 attempts`
    //     (runs 31452734430 and 31457374208, both 2026-08-11) — thrown from this class's
    //     own `relisten` helper. computenet-dqy.22 DELETED that helper along with the
    //     close-then-rebind it wrapped: the port is now never unbound (`HeldPort`), so
    //     that throw site no longer exists to be reached. This is a structural argument,
    //     not a rate.
    //
    // Measured after both landed: no CI failure of this class in any `CI` run since
    // computenet-dqy.22 merged (2026-08-12) — every `CI` failure in that window is another
    // test — and 0 occurrences of this class in 200 in-process `civictech.wire` suite
    // iterations on darwin/arm64 (`scripts/flake-loop`, `SUMMARY label=pvs-local runs=200
    // failingIterations=8 failingTests=8 expectedTests=26 unexpectedTestCountIterations=0`;
    // all eight failures were `WsAnnouncementSilenceInventoryTest`, filed as
    // computenet-dqy.67, and none were here). 0/200 bounds this class's per-iteration rate
    // at <= 1.5% (rule of three, 95%) on that platform only: Linux was NOT sampled, and
    // `.github/workflows/wire-suite-sample.yml` is the instrument if an ubuntu rate is ever
    // wanted. NOTE the bead also cited run 31278760922 as a second occurrence here — it is
    // not one: that run failed on `InspectorPagedStateTest` and this class passed in it.
    private fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(100)
        }
    }

    private fun LocationRegistry.remote(ref: CellRef): LocationRegistry.Remote =
        location(ref) as LocationRegistry.Remote

    @Test
    fun `a named peer keeps one identity across a reconnect that mints a new egress`() {
        // the two-inspector demo's exact shape: the listener stays up while the
        // dialer's socket drops and returns. That is the side where the label
        // actually flips — a client keeps ONE Session (hence one egress) across
        // reconnects, so the *listener*, which builds a fresh Session per
        // `onOpen`, is where a peer used to be renamed.
        val client = Stack("jvm-b")
        val server = Stack("jvm-a")

        // both published BEFORE anyone connects: each side's first announcement
        // is then `announceTo`'s catch-up burst, the earliest one that exists
        // (see the class KDoc's ordering argument)
        val collector = CollectingCell()
        server.host.managementInlet.call.spawn(collector)
        val writer = CollectingCell()
        client.host.managementInlet.call.spawn(writer)

        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side) { 0L }
        try {
            await("collector announced to the dialer") {
                client.registry.location(collector.ref) is LocationRegistry.Remote
            }
            await("writer announced to the listener") {
                server.registry.location(writer.ref) is LocationRegistry.Remote
            }
            client.registry.remote(collector.ref).peer shouldBe PeerId("jvm-a")
            server.registry.remote(writer.ref).peer shouldBe PeerId("jvm-b")
            val listenerEgressBefore = server.registry.remote(writer.ref).sink

            // drop the socket from the listener side without stopping it: the
            // dialer's backoff loop reconnects on its own, and the listener
            // accepts it into a brand-new Session
            listener.connections.forEach { it.close() }
            // wait on the *new* egress rather than on the intervening
            // unpublished window: with a zero backoff the dialer can be back
            // before any poll observes the gap, and the gap is not the point —
            // a re-mirroring through a different sink is
            await("the listener re-mirrored the dialer through a new session") {
                (server.registry.location(writer.ref) as? LocationRegistry.Remote)
                    ?.let { it.sink !== listenerEgressBefore } == true
            }

            // that new sink is the whole defect: a new listener Session means a
            // new BridgeEgressCell, which is exactly what used to rename the
            // peer's hull...
            server.registry.remote(writer.ref).sink shouldNotBeSameInstanceAs listenerEgressBefore
            // ...and the identity that survived it, bound on the new Session
            // before it served a single announcement
            server.registry.remote(writer.ref).peer shouldBe PeerId("jvm-b")

            // the dialer's own view came back named too (its re-hello re-binds
            // the same name onto the Session it kept)
            await("the dialer re-learned the listener's refs") {
                client.registry.location(collector.ref) is LocationRegistry.Remote
            }
            client.registry.remote(collector.ref).peer shouldBe PeerId("jvm-a")
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }

    @Test
    fun `a named peer survives its listener dying and re-binding the same port`() {
        // the other reconnect shape (WsReconnectSmokeTest's): the listener
        // process restarts. The dialer's Session — and its egress — persist, so
        // what is under test here is the re-hello re-binding the same name onto
        // a mirror that was already bound once.
        val client = Stack("jvm-b")
        var server = Stack("jvm-a")
        var collector = CollectingCell()
        server.host.managementInlet.call.spawn(collector)

        // the same port across the restart, without ever unbinding it — the whole
        // point of this test is the *same* endpoint, and `HeldPort` is how that is
        // expressed without racing the OS for a freed port number
        // (computenet-dqy.22)
        val endpoint = HeldPort()
        val port = endpoint.port
        var listener = endpoint.serve(server.side)
        val connection = WsTransport.connect(URI("ws://localhost:$port"), client.side) { 0L }
        try {
            await("collector announced") { client.registry.location(collector.ref) is LocationRegistry.Remote }
            client.registry.remote(collector.ref).peer shouldBe PeerId("jvm-a")

            endpoint.release(listener)
            await("unpublish on disconnect") { client.registry.location(collector.ref) == null }

            server = Stack("jvm-a")
            collector = CollectingCell(collector.ref)
            server.host.managementInlet.call.spawn(collector)
            listener = endpoint.serve(server.side)

            await("re-announced after reconnect") {
                client.registry.location(collector.ref) is LocationRegistry.Remote
            }
            client.registry.remote(collector.ref).peer shouldBe PeerId("jvm-a")
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
            endpoint.close()
        }
    }

    @Test
    fun `an unnamed side still peers anonymously over a socket`() {
        val client = Stack(null)
        val server = Stack(null)
        val collector = CollectingCell()
        server.host.managementInlet.call.spawn(collector)

        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side) { 0L }
        try {
            await("collector announced") { client.registry.location(collector.ref) is LocationRegistry.Remote }
            // the pre-V4-PEERID shape verbatim — nothing throws, nothing is
            // invented, and the inspector keeps deriving its `peer-<id>` label
            client.registry.remote(collector.ref).peer.shouldBeNull()
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }
}
