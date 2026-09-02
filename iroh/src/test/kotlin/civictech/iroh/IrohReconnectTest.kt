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
import civictech.identity.Ed25519
import civictech.iroh.SidecarProtocol.DIRECTION_OUTBOUND
import civictech.iroh.SidecarProtocol.NODE_ID_LEN
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Severing and re-establishing an iroh peering (feature `computenet-egl.2`
 * example 2, task `computenet-egl.2.3`): what a re-established link shares with
 * the one it replaces, and what an *unplanned* drop does on its own.
 *
 * Three properties, one per test. The first two run over two real sidecar child
 * processes; the third ([FakeSidecar]) does not — see below.
 *
 * - a heal is a **new link instance** — a fresh `RegistryMirrorCell` serves
 *   post-heal announcements while the pre-heal mirror's gate stays shut for good
 *   (computenet-dqy.14, `RegistryMirrorCell.detach`) — and a close this side
 *   *asked* for is never re-dialled;
 * - an **unplanned** drop is re-dialled on the injected backoff schedule until
 *   the peering converges again;
 * - a `heal()` issued **while that re-dial loop is already inside `openLink`**
 *   opens no second link (computenet-m475).
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
 * Skip-gated: without `-Piroh.enabled=true` (hence without a built sidecar) the
 * first two tests here report SKIPPED, never failed — see [SidecarBinary]. The
 * third one is **not** gated: it drives a [FakeSidecar] loopback socket that
 * speaks `PROTOCOL.md` by hand, spawns no child process, and therefore executes
 * on the default lanes too. That is not a convenience — it is what makes the
 * race deterministic. A real sidecar answers a `DIAL` whenever it likes, so the
 * window in which the re-dial loop sits *inside* `openLink` cannot be held open;
 * a fake that simply does not answer holds it open indefinitely, and the
 * property becomes a fact about frame counts rather than about timing.
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
     * **computenet-m475.** `heal()` and the re-dial loop must not both be inside
     * `openLink` at once.
     *
     * The two paths were mutually exclusive only by convention. `sever()` sets
     * the planned-close flag, so `retire()` starts no loop and a heal after a
     * sever is alone in `openLink`; but after an **unplanned** drop the loop IS
     * running, and a host that calls `heal()` then put a second thread into
     * `openLink`. Both dial, and both finish with an unconditional
     * `currentLink.set(...)` / `currentSession.set(...)` — so the loser's link is
     * never closed, its `LinkListener` stays registered, its `Session`'s ingress
     * stays installed, and its `RegistryMirrorCell` is never detached. A
     * duplicated peering and a leaked mirror, which is exactly the per-instance
     * discipline computenet-dqy.14 exists to keep.
     *
     * ## How the race is forced open rather than waited for
     *
     * Nothing here sleeps hoping to land in a window. The fake answers the first
     * `DIAL` and then, after the drop, deliberately **withholds** the `LINK_UP`
     * for the re-dial's `DIAL`. `SidecarClient.dial` blocks until that answer, so
     * the loop thread is provably parked inside `openLink` — not likely to be,
     * parked — for as long as this test wants. `heal()` is then called from its
     * own thread (it too would block in `dial` on the unfixed code, so the test
     * thread cannot host it) and the pin is an **absence**: no second `DIAL`
     * reaches the fake. An absence held over an interval can only fail
     * spuriously if the forbidden thing happens, which is a true failure, never
     * load.
     *
     * The settled assertions afterwards read [quiesced] counters rather than a
     * single sample taken right after an await (the `computenet-zl4z` rule): a
     * `DIAL` total that has stopped changing is what says no extra link was ever
     * opened, and therefore that no orphan `Session` exists to hold an
     * undetached mirror.
     */
    @Test
    fun `a heal while the re-dial loop is inside openLink opens no second link`() {
        // A real Ed25519 point: the dialler fingerprints the NodeId it dialled
        // into this link's admission key, and 32 arbitrary bytes would be
        // refused as MALFORMED_HELLO before a mirror was ever bound.
        val peerNodeId = Ed25519.rawPublicKey(Ed25519.generateKeyPair().public)
        val b = Stack(name = "bob")

        FakeSidecar().use { fake ->
            SidecarClient.connect(fake.port).use { client ->
                val connection = IrohTransport.IrohConnection(
                    sidecar = NoSidecar,
                    client = client,
                    side = b.side,
                    peerNodeId = peerNodeId,
                    // Short enough that the loop's first re-dial arrives at once;
                    // the loop is held by the withheld LINK_UP, not by this.
                    backoff = { 10L },
                    redialTimeout = 30.seconds,
                )
                try {
                    // ---- link 1: up, and admitted ---------------------------
                    val link1 = fake.openAndAdmit(connection, peerNodeId)
                    await("the first link is peered") { connection.peered }
                    val mirror1 = connection.mirrorCell ?: fail("the first link minted no mirror")

                    // ---- an UNPLANNED drop: the loop starts ------------------
                    fake.send(SidecarMessage.LinkDown(link1, "peer vanished"))
                    await("the dialler's link went down") { !connection.peered }

                    // The loop's re-dial. It is NOT answered: `SidecarClient.dial`
                    // blocks on the LINK_UP, so from here to the end of the pin
                    // below the loop thread is inside openLink.
                    val redial = fake.nextDial()

                    // ---- THE PIN --------------------------------------------
                    // heal() would itself block in `dial` on the unfixed code, so
                    // it cannot run on this thread.
                    Thread({ connection.heal() }, "m475-heal").apply { isDaemon = true }.start()
                    assertEquals(
                        2L,
                        quiesced { fake.dials.get() },
                        "heal() dialled while the re-dial loop was already inside openLink: two threads " +
                            "are opening a link on one connection, and the loser's link/session/mirror is " +
                            "orphaned (computenet-m475)",
                    )

                    // ---- let the loop's own link finish ----------------------
                    fake.admit(redial.link, peerNodeId)
                    await("the re-dial converged") { connection.peered }

                    // ---- settled state: exactly one link was ever re-opened --
                    assertEquals(
                        2L,
                        quiesced { fake.dials.get() },
                        "the connection opened more links than the one initial dial plus the one re-dial; " +
                            "every extra DIAL is an orphaned link/session/mirror",
                    )
                    val mirror2 = connection.mirrorCell ?: fail("the re-dialled link minted no mirror")
                    assertNotSame(mirror1, mirror2, "a reconnect must mint a FRESH mirror (computenet-dqy.14)")

                    // ...and the only other mirror this connection ever minted is
                    // detached for good. Nothing addresses a retired mirror any
                    // more, so drive it directly: a detached mirror refuses, and
                    // installs nothing.
                    val ghost = CellRef(UUID.randomUUID())
                    val refusedBefore = mirror1.refusedAnnouncements
                    mirror1.inlet.call.published(ghost)
                    await("the dropped link's mirror refuses an announcement addressed to it") {
                        mirror1.refusedAnnouncements == refusedBefore + 1
                    }
                    assertEquals(
                        null,
                        b.registry.location(ghost),
                        "a detached mirror installed a location; its fence is not permanent",
                    )
                } finally {
                    connection.close()
                }
            }
        }
    }

    /**
     * Reads [value] only once it has stopped changing for [settleMillis]
     * (computenet-6lam) — copied from [IrohKeyBoundAdmissionTest] rather than
     * imported, as that file copied it from [IrohPeeringTest], since neither may
     * be modified from here.
     */
    private fun quiesced(settleMillis: Long = 1_500, timeoutMs: Long = 30_000, value: () -> Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = value()
        var lastChangedAt = System.currentTimeMillis()
        while (true) {
            Thread.sleep(50)
            val now = value()
            val time = System.currentTimeMillis()
            if (now != last) {
                last = now
                lastChangedAt = time
            } else if (time - lastChangedAt >= settleMillis) {
                return last
            }
            if (time > deadline) fail("timed out waiting for value to quiesce (stuck at $last)")
        }
    }

    /** There is no child process behind [FakeSidecar]; the node id is never read here. */
    private object NoSidecar : IrohTransport.Sidecar {
        override val nodeId: ByteArray get() = ByteArray(NODE_ID_LEN)
        override fun close() = Unit
    }

    /**
     * A loopback socket that speaks `PROTOCOL.md` by hand: one accepted host
     * connection, a reader thread decoding host → sidecar messages into a queue,
     * and [send] for sidecar → host ones. It implements no behaviour — every
     * reply is written by the test.
     *
     * Copied from `SidecarBackpressureTest`'s private fixture of the same name
     * rather than shared, for the reason [quiesced] is copied: that file is
     * outside this change's scope. Extracting one shared fake is worth doing and
     * is filed as its own item.
     */
    private class FakeSidecar : AutoCloseable {

        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val accepted = ArrayBlockingQueue<Socket>(1)
        private val received = LinkedBlockingQueue<HostMessage>()

        /** Every `DIAL` this fake has decoded — the count the pin above settles on. */
        val dials = AtomicLong()

        val port: Int get() = server.localPort

        private val acceptor = Thread({
            runCatching {
                val socket = server.accept()
                accepted.put(socket)
                val input = DataInputStream(socket.getInputStream().buffered())
                while (true) {
                    val declared = input.readInt()
                    val body = ByteArray(declared)
                    input.readFully(body)
                    val frame = SidecarCodec.decodeBody(body, 0, declared)
                    when (val decoded = SidecarCodec.asHostMessage(frame)) {
                        is Decoded.Ok -> {
                            if (decoded.message is HostMessage.Dial) dials.incrementAndGet()
                            received.put(decoded.message)
                        }

                        is Decoded.Malformed -> fail("the host sent an undecodable message: $decoded")
                    }
                }
            }
        }, "fake-sidecar-m475").apply { isDaemon = true; start() }

        private fun connection(): Socket =
            accepted.peek() ?: accepted.poll(30, TimeUnit.SECONDS)?.also { accepted.put(it) }
                ?: fail("no host connected within 30s")

        /** One sidecar → host message, encoded exactly as `PROTOCOL.md` §2 lays it out. */
        fun send(message: SidecarMessage) {
            val socket = connection()
            socket.getOutputStream().apply {
                write(SidecarCodec.encode(message))
                flush()
            }
        }

        fun nextHostMessage(seconds: Long = 30): HostMessage =
            received.poll(seconds, TimeUnit.SECONDS) ?: fail("no host message within ${seconds}s")

        /**
         * The next `DIAL`, skipping any `DATA` an announcer wrote in between —
         * this fixture asserts on dial *identity* and on [dials], never on the
         * traffic a peering happens to produce.
         */
        fun nextDial(seconds: Long = 30): HostMessage.Dial {
            val deadline = System.currentTimeMillis() + seconds * 1_000
            while (System.currentTimeMillis() < deadline) {
                val next = received.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (next is HostMessage.Dial) return next
            }
            fail("no DIAL within ${seconds}s")
        }

        /**
         * One link opened by [connection]'s own `openLink`, brought all the way to
         * *admitted*: `DIAL` answered with `LINK_UP`, this side's hello drained,
         * and a peer hello written back so the Session binds a mirror and installs
         * an ingress. Returns the link id.
         */
        fun openAndAdmit(connection: IrohTransport.IrohConnection, peerNodeId: ByteArray): Long {
            val opened = ArrayBlockingQueue<Result<Unit>>(1)
            Thread({ opened.put(runCatching { connection.openLink(30.seconds) }) }, "m475-open-link")
                .apply { isDaemon = true }
                .start()
            val dial = nextDial()
            admit(dial.link, peerNodeId)
            (opened.poll(30, TimeUnit.SECONDS) ?: fail("openLink did not settle within 30s")).getOrThrow()
            return dial.link
        }

        /**
         * Answer an outstanding `DIAL` on [link] with `LINK_UP`, drain the hello
         * the dialler writes as its first frame, and answer it with a peer hello —
         * `IROH-HELLO1 <mirror ref>`, the whole grammar (`PROTOCOL.md` §3).
         */
        fun admit(link: Long, peerNodeId: ByteArray) {
            send(SidecarMessage.LinkUp(link, peerNodeId, DIRECTION_OUTBOUND))
            assertIs<HostMessage.Data>(nextHostMessage())
            send(
                SidecarMessage.Data(
                    link,
                    (IrohTransport.HELLO_PREFIX + UUID.randomUUID()).toByteArray(StandardCharsets.UTF_8),
                ),
            )
        }

        override fun close() {
            acceptor.interrupt()
            runCatching { accepted.peek()?.close() }
            runCatching { server.close() }
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
