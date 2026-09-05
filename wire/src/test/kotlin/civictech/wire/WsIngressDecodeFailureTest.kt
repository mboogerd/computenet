package civictech.wire

import civictech.cell.CellRef
import civictech.cell.Cell
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireSerializers
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * computenet-mvu9 — the socket-side residual bug computenet-bb5b named and
 * deliberately left open: bug computenet-bb5b pinned that a [WireCodec] with
 * no live contribution refuses a module type's bytes LOUDLY — a
 * [SerializationException] out of [WireCodec.decode]/[WireCodec.decodeFrame].
 * What that item did NOT settle is what happens to that throw once it fires
 * on a **real socket's ingress path** — [WsTransport.Session.onFrame] handing
 * bytes to the [Peering.hostIngress] proxy that lands on
 * `civictech.cell.wire.BridgeCells.BridgeIngressCell`, whose `init` block
 * calls `WireCodec.decodeFrame(value)` directly (`BridgeCells.kt:317`),
 * reached from this module's `WsTransport.kt` around `Peering.hostIngress`
 * construction (`:1428`) and `Session.onFrame` (`:1443`).
 *
 * ## The three candidate behaviours (from the bead)
 *
 * 1. logged and the frame dropped, connection healthy;
 * 2. silently swallowed;
 * 3. the connection is torn down.
 *
 * ## What this test establishes, and how
 *
 * `BridgeIngressCell.inlet`'s `Propagate<ByteArray>` — the object returned by
 * `Peering.hostIngress` — is not a bare callback invoked on the calling
 * thread: it is a [HostedCellProxy] call that dispatches onto the ingress
 * cell's own [ManagedHost] (here, [Stack.bridgeHost]). A thrown
 * [SerializationException] therefore lands in `ManagedHost.deliver`'s own
 * invocation catch, which — under the default `SupervisionPolicy.PROPAGATE`
 * — unconditionally logs one stderr line, emits a [DeadLetter] on
 * [ManagedHost.deadLetterOutlet], increments `supervisionAccounting().deadLetters`,
 * and leaves the cell (and, orthogonally, the socket) processing subsequent
 * invocations. Nothing in that path ever reaches [WsTransport] itself, or the
 * underlying `WebSocketClient`/`WebSocketServer` — the socket has no idea a
 * decode failed.
 *
 * That is arm (1). This test proves it by asserting the two things that
 * discriminate it from arm (2) ("silently swallowed"): the dead-letter
 * *count* moves, and the dead-letter *outlet* actually emits a record naming
 * the [SerializationException] as its cause. An assertion that only checks
 * the connection survives cannot tell (1) from (2) — a swallowed frame would
 * leave the connection just as healthy, with the counter and the outlet both
 * silent — which is exactly the failure mode
 * `.claude/skills/work/references/task.md`'s acceptance criteria warn against
 * accepting. This test therefore asserts both: the accounting moved, AND the
 * connection still carries an ordinary frame afterwards (arm (3), torn down,
 * is excluded by the same assertion — a torn-down connection could not carry
 * it).
 *
 * ## How the malformed bytes are produced, deterministically (no race)
 *
 * The suggested shape in the bead (contribute, put a frame on the wire, THEN
 * withdraw before the receiver decodes) is a timing race in a single-JVM
 * test: encode and decode share one process-global [WireCodec], so "before
 * the receiver decodes" has no deterministic meaning once the bytes are
 * already travelling. This test instead does the encode and the withdraw in
 * the opposite, race-free order that bug computenet-bb5b's own
 * `B13CrossLoaderWireIdentityTest` established: contribute, `WireCodec.encode`
 * the bytes directly (bypassing the cell layer — no send has happened yet),
 * withdraw, and only THEN hand the already-fixed bytes to the real socket via
 * `WsConnection`'s own (public, inherited from `WebSocketClient`) `send(ByteArray)` —
 * a genuinely real over-the-wire delivery into [WsTransport]'s listener,
 * `Session.onFrame`, and the real [Peering.hostIngress] proxy, with zero
 * dependency on scheduling order.
 */
class WsIngressDecodeFailureTest {

    /** A test-local delta type, unknown to the kernel's baseline module. */
    @Serializable
    @SerialName("WsIngressDecodeFailureDelta")
    private data class LateDelta(val payload: String)

    /** What a dynamically loaded module would contribute for the type above. */
    private class LateSerializers : WireSerializers {
        override val module: SerializersModule = SerializersModule {
            polymorphic(Any::class) {
                subclass(LateDelta::class, LateDelta.serializer())
            }
        }
    }

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private interface SetDeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    /** Ordinary collector — proves the connection is admitted, and stays healthy afterwards. */
    private class SetCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
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

    private val propagateMethod = Propagate::class.java.getMethod("propagate", Any::class.java)

    private fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionFailedError("timed out awaiting: $what")
            Thread.sleep(20)
        }
    }

    private class Stack {
        val registry = LocationRegistry()
        val host = ManagedHost(registry = registry)
        val bridgeHost = ManagedHost(registry = registry)
        val side = Peering.Side(registry, bridgeHost)
    }

    @Test
    fun `a decode failure on the real ingress socket is dead-lettered, not swallowed, and the connection stays healthy`() {
        val server = Stack()
        val client = Stack()
        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
        try {
            // --- 1. Establish and admit the connection with an ordinary frame,
            //     exactly as WsLateWireSerializersRoundTripTest does — this is
            //     what makes `Session.onFrame`'s `ingress` non-null on the
            //     listener side, i.e. the real path under test.
            val ordinaryCollector = SetCollectorCell()
            server.host.managementInlet.call.spawn(ordinaryCollector)
            await("ordinary collector announced to client") {
                client.registry.location(ordinaryCollector.ref) is LocationRegistry.Remote
            }
            val ordinaryWriter = SetCell<String>()
            client.host.managementInlet.call.spawn(ordinaryWriter)
            val ordinaryRemoteInlet = (
                HostedCellProxy.create(ordinaryCollector.ref, client.registry, SetDeltaInletProxy::class.java)
                    as SetDeltaInletProxy
                ).inlet.call
            ordinaryWriter.outlet.subscribe(Use.fixed(ordinaryRemoteInlet, PortRef.generate()))
            val ordinaryApi = (
                HostedCellProxy.create(ordinaryWriter.ref, client.registry, SetInletProxy::class.java)
                    as SetInletProxy
                ).inlet.call
            ordinaryApi.add("milk")
            await("an ordinary frame crossed the live connection") {
                ordinaryCollector.arrivals.isNotEmpty()
            }

            // --- 2. Produce bytes carrying a delta type that decodes only while
            //     a contribution is live, then withdraw the contribution BEFORE
            //     the bytes ever reach a decode call — race-free, per this
            //     class's KDoc.
            val contribution = LateSerializers()
            WireCodec.contribute(contribution)
            val bytes = try {
                WireCodec.encode(
                    HostedPortInvocation(
                        cellRef = ordinaryCollector.ref,
                        portName = "inlet",
                        type = HostedPortInvocation.Type.PORT_API,
                        invocation = Invocation.of(propagateMethod, arrayOf(LateDelta("payload")), null),
                    ),
                )
            } finally {
                WireCodec.withdraw(contribution)
            }
            // Sanity: the bytes are now genuinely undecodable by THIS process's
            // codec (no cell/socket involved) — otherwise everything below would
            // be vacuous.
            io.kotest.assertions.throwables.shouldThrow<SerializationException> { WireCodec.decode(bytes) }

            // --- 3. Capture the server bridge host's dead-letter accounting
            //     BEFORE sending, and subscribe to its dead-letter outlet so the
            //     emitted record (and its cause) is directly observable — the
            //     discriminator between "dropped and logged" (1) and "silently
            //     swallowed" (2) that an assertion on connection health alone
            //     cannot provide.
            val deadLettersBefore = server.bridgeHost.supervisionAccounting().deadLetters
            val captured = AtomicReference<DeadLetter?>()
            server.bridgeHost.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            captured.compareAndSet(null, value)
                        }
                    },
                    PortRef.generate(),
                ),
            )

            // --- 4. Hand the already-fixed, now-undecodable bytes to the REAL
            //     socket — WsConnection's own `send(ByteArray)`, inherited from
            //     `WebSocketClient`, bypassing the cell layer's outlet so no
            //     encode happens here; these exact bytes travel.
            connection.send(bytes)

            // --- 5. The dead-letter count on the RECEIVING host (the listener's
            //     bridge host, which hosts the BridgeIngressCell that ran
            //     WireCodec.decodeFrame) must move, and the outlet must have
            //     delivered a record whose cause is the SerializationException.
            var deadLettersAfter = deadLettersBefore
            await("the malformed frame was dead-lettered on the server's bridge host") {
                deadLettersAfter = server.bridgeHost.supervisionAccounting().deadLetters
                deadLettersAfter > deadLettersBefore
            }
            // Assert on the value the poll itself observed, not a fresh read: the
            // counter is live and a second read across the window between the
            // await succeeding and this line could in principle have moved again.
            deadLettersAfter shouldBe deadLettersBefore + 1

            // The counter and the outlet move on different schedules:
            // `DeadLetters.deadLetter` increments `count` synchronously, then
            // hands the emission to `ManagedHost`'s own scheduler
            // (`emit = { dl -> scheduler.submit(0) { deadLetterOutlet.call.propagate(dl) } }`),
            // which queues it as a separate task rather than running it inline.
            // The await above only proves the counter moved; the outlet delivery
            // can still be pending when it does. Await the capture on its own
            // schedule too, rather than reading it once right after.
            await("the dead-letter outlet delivered a record for the malformed frame") {
                captured.get() != null
            }
            val deadLetter = captured.get()
            checkNotNull(deadLetter) { "dead-letter outlet never emitted a record for the malformed frame" }
            (deadLetter.cause is SerializationException) shouldBe true

            // --- 6. Arm (3) excluded, and the connection genuinely healthy, not
            //     merely "not yet observed as closed": an ordinary frame still
            //     crosses the SAME connection afterwards.
            ordinaryApi.add("eggs")
            await("a post-decode-failure ordinary frame still crosses") {
                ordinaryCollector.arrivals.size >= 2
            }
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }
}
