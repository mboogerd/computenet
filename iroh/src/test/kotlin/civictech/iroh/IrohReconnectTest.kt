package civictech.iroh

import civictech.cell.CellRef
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import org.junit.jupiter.api.Test
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Severing and re-establishing an iroh peering (feature `computenet-egl.2`
 * example 2, task `computenet-egl.2.3`): what a re-established link shares with
 * the one it replaces, and what an *unplanned* drop does on its own.
 *
 * Two properties, one per test, both over two real sidecar child processes:
 *
 * - a heal is a **new link instance** — a fresh `RegistryMirrorCell` serves
 *   post-heal announcements while the pre-heal mirror's gate stays shut for good
 *   (computenet-dqy.14, `RegistryMirrorCell.detach`) — and a close this side
 *   *asked* for is never re-dialled;
 * - an **unplanned** drop is re-dialled on the injected backoff schedule until
 *   the peering converges again.
 *
 * ## These tests do not measure time, and deliberately so
 *
 * A near-zero backoff makes a reconnect fast, not deterministic. So nothing here
 * sleeps for a duration and then asserts: the schedule seam is observed through
 * [IrohTransport.IrohConnection.backoffConsultations] /
 * [IrohTransport.IrohConnection.highestReconnectAttempt] — counts, which a loaded
 * machine cannot falsify — and every outcome is awaited with a bounded [await]
 * rather than expected within a fixed interval. The one interval that IS an
 * assertion, [neverWithin], pins an absence (a severed peering must NOT come
 * back on its own): it can only fail spuriously if the thing it forbids happens,
 * which is a true failure, never load.
 *
 * Skip-gated: without `-Piroh.enabled=true` (hence without a built sidecar) every
 * test here reports SKIPPED, never failed — see [SidecarBinary].
 */
class IrohReconnectTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Stack(name: String? = null) {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = name?.let { PeerId(it) })
    }

    private fun await(what: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out awaiting: $what")
            Thread.sleep(50)
        }
    }

    /** Nothing ever became true within [millis] — used to pin an absence. */
    private fun neverWithin(millis: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return false
            Thread.sleep(50)
        }
        return !condition()
    }

    /** A schedule that costs nothing to consult — the T12 seam, driven to ~0. */
    private val nearZeroBackoff: (attempt: Int) -> Long = { 10L }

    /**
     * Streams a labelled sidecar's stderr into this test's own captured output
     * (JUnit's `system-out`, printed by Gradle on failure and always present in
     * the XML report), so a dial timeout (`SidecarException`) is investigated
     * against what the sidecar itself said rather than against load speculation
     * (computenet-yn7e). Runs on [SidecarProcess]'s dedicated stderr-pump thread,
     * so lines from both sides can interleave — the label is what keeps them
     * attributable.
     *
     * **Expect this to be EMPTY on exactly the failure it was added for, and
     * read that emptiness as a finding rather than as broken instrumentation.**
     * The sidecar writes to stderr from one place only — `main()`'s top-level
     * fatal branch (`iroh/sidecar/src/main.rs`, `eprintln!("computenet-iroh-
     * sidecar: {message}")`, followed by `ExitCode::FAILURE`) — plus whatever
     * Rust's default panic hook emits. A dial timeout is neither: `server.rs`
     * returns `dial failed: …` to the host as a protocol error frame over the
     * loopback socket, which surfaces in Kotlin as `SidecarException` and never
     * touches the child's stderr. So an empty capture rules out a sidecar crash
     * or panic and says nothing more; it does not name a cause. (The re-dial
     * loop is different — `IrohTransport` writes its own attempt lines to
     * `System.err`, so those appear in `system-err` regardless of this sink.)
     */
    private fun stderrSink(label: String): (String) -> Unit = { line ->
        println("[iroh-stderr $label] $line")
    }

    @Test
    fun `a heal mints a fresh mirror, the severed one stays detached, and a requested close never re-dials`() {
        val binary = SidecarBinary.orSkip()
        val a = Stack(name = "alice")
        val b = Stack(name = "bob")

        IrohTransport.listen(a.side, binary, stderrSink = stderrSink("alice-listener")).use { listener ->
            IrohTransport.connect(
                b.side,
                listener.nodeId,
                listener.addresses,
                binary,
                stderrSink = stderrSink("bob-dialler"),
                backoff = nearZeroBackoff,
            ).use { connection ->
                val shared = SetCell<String>()
                a.host.managementInlet.call.spawn(shared)
                await("A's ref reaches B over the first link") {
                    b.registry.location(shared.ref) is LocationRegistry.Remote
                }
                val severedRef: CellRef = connection.mirrorRef ?: fail("the first link minted no mirror")
                val severedMirror = connection.mirrorCell ?: fail("the first link minted no mirror")

                // ---- sever: CLOSE_LINK, one LINK_DOWN per side ---------------
                //
                // The assertion order matters. `!peered` is NOT awaited first:
                // with the planned-close guard removed the re-dial takes ~10ms
                // on this schedule, so the down state can pass by unobserved and
                // the failure would land on a wait for a transient rather than on
                // the property. The schedule-consultation count cannot pass by:
                // it only ever grows, so an absence held over an interval is a
                // real absence, and it is the FIRST thing checked.
                connection.sever()
                assertTrue(
                    neverWithin { connection.backoffConsultations > 0L },
                    "a close THIS side asked for consulted the re-dial schedule; a requested close must " +
                        "not be re-dialled at all (WsTransport's planned-close discipline)",
                )
                assertFalse(
                    connection.peered,
                    "a severed peering came back on its own",
                )
                assertTrue(
                    b.registry.location(shared.ref) !is LocationRegistry.Remote,
                    "the severed instance's mirror did not retract what it had installed",
                )

                // ---- heal: a NEW link, and therefore a new everything --------
                connection.heal()
                await("the healed peering is peered again") { connection.peered }
                val healedRef: CellRef = connection.mirrorRef ?: fail("the healed link minted no mirror")
                assertNotEquals(
                    severedRef,
                    healedRef,
                    "a re-established connection must mint a FRESH mirror (computenet-dqy.14)",
                )

                // The healed instance serves the full localRefs catch-up...
                await("the pre-heal ref is re-announced through the healed instance") {
                    b.registry.location(shared.ref) is LocationRegistry.Remote
                }
                // ...and anything published after the heal too.
                val afterHeal = SetCell<String>()
                a.host.managementInlet.call.spawn(afterHeal)
                await("a post-heal announcement reaches B") {
                    b.registry.location(afterHeal.ref) is LocationRegistry.Remote
                }

                // ---- a post-heal invocation round-trips ----------------------
                val remoteWriter = (
                    HostedCellProxy.create(afterHeal.ref, b.registry, SetInletProxy::class.java)
                        as SetInletProxy
                    ).inlet.call
                remoteWriter.add("milk")
                await("B's post-heal invocation lands on A's cell") {
                    afterHeal.membership() == setOf("milk")
                }

                // ---- the pre-heal mirror's gate never re-opens ---------------
                // Nothing addresses a retired mirror any more, so its refusal
                // count would stay 0 on its own and prove nothing. Drive it
                // directly instead: a detached mirror refuses, and installs
                // nothing.
                val ghost = CellRef(UUID.randomUUID())
                val refusedBefore = severedMirror.refusedAnnouncements
                severedMirror.inlet.call.published(ghost)
                await("the detached mirror refuses an announcement addressed to it") {
                    severedMirror.refusedAnnouncements == refusedBefore + 1
                }
                assertEquals(
                    null,
                    b.registry.location(ghost),
                    "a detached mirror installed a location; its fence is not permanent",
                )
                assertTrue(connection.linkErrors.isEmpty(), "sidecar reported link errors: ${connection.linkErrors}")
            }
        }
    }

    @Test
    fun `an unplanned drop re-dials on the injected schedule until the peering converges`() {
        val binary = SidecarBinary.orSkip()
        // The far side must come back as the SAME endpoint after its process
        // dies — the dialler re-dials the NodeId it was given, at the addresses
        // it was taught. Pinning the key and the UDP bind address is what makes
        // the restart the same endpoint rather than a new peer.
        val secretKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val bindAddr = "127.0.0.1:${freeUdpPort()}"
        val listenerArgs = listOf("--secret-key", secretKey, "--bind-addr", bindAddr)

        val a = Stack(name = "alice")
        val b = Stack(name = "bob")

        var listener = IrohTransport.listen(
            a.side,
            binary,
            stderrSink = stderrSink("alice-listener-1"),
            sidecarArgs = listenerArgs,
        )
        try {
            val peerNodeId = listener.nodeId
            val peerAddresses = listener.addresses
            IrohTransport.connect(
                b.side,
                peerNodeId,
                peerAddresses,
                binary,
                stderrSink = stderrSink("bob-dialler"),
                backoff = nearZeroBackoff,
                // Each failed re-dial costs at most this; the loop retries
                // forever, so the value bounds an attempt, not the test.
                redialTimeout = 3.seconds,
            ).use { connection ->
                val shared = SetCell<String>()
                a.host.managementInlet.call.spawn(shared)
                await("A's ref reaches B over the first link") {
                    b.registry.location(shared.ref) is LocationRegistry.Remote
                }
                val beforeDrop: CellRef = connection.mirrorRef ?: fail("the first link minted no mirror")

                // ---- the far side dies: nothing here asked for this ----------
                listener.close()
                await("the dialler's link went down") { !connection.peered }

                // The far side is gone, so the first re-dials cannot succeed —
                // which is what makes attempt >= 1 an observation rather than a
                // hope. The loop is still retrying when this returns.
                await("the re-dial schedule is consulted at attempt >= 1") {
                    connection.highestReconnectAttempt >= 1
                }

                // ---- the same endpoint comes back ---------------------------
                listener = IrohTransport.listen(
                    a.side,
                    binary,
                    stderrSink = stderrSink("alice-listener-2"),
                    sidecarArgs = listenerArgs,
                )
                assertEquals(
                    peerNodeId.toList(),
                    listener.nodeId.toList(),
                    "the restarted listener is a different endpoint; the reconnect could not be about it",
                )

                await("the unplanned drop reconnected on its own") { connection.peered }
                await("the re-announced ref is reachable again") {
                    b.registry.location(shared.ref) is LocationRegistry.Remote
                }
                assertNotEquals(
                    beforeDrop,
                    connection.mirrorRef,
                    "a reconnect must mint a FRESH mirror, exactly as a heal does",
                )
                assertTrue(
                    connection.backoffConsultations >= 2L,
                    "the schedule seam was bypassed: ${connection.backoffConsultations} consultations",
                )
            }
        } finally {
            listener.close()
        }
    }

    /**
     * A loopback UDP port nothing holds right now. Racy in principle — the port
     * is free at the moment it is read and claimed a moment later — and that
     * race is accepted here for the reason `:wire`'s fixtures accept it: the
     * alternative (asking the OS for an ephemeral port and keeping it) cannot be
     * expressed to a child process that binds its own socket.
     */
    private fun freeUdpPort(): Int =
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
