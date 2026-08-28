package civictech.wire

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import civictech.cell.wire.Peering
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireSerializers
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.net.URI
import java.util.Collections
import java.util.UUID

/**
 * JAR1 [JAR1-REG-08], arm (1), scenario B13 first arm — over a REAL `:wire`
 * connection rather than a direct `WireCodec.encode`/`decode` call.
 *
 * The sibling task (computenet-051.6.1, merged) proved the codec-level
 * round-trip in-process against [WireCodec.contribute]/[WireCodec.withdraw]
 * directly. This test proves the wire half: two full stacks bridged over a
 * real `WsTransport` socket (same scaffolding as [WsTransportSmokeTest]),
 * where a delta type is contributed to [WireCodec] via [WireCodec.contribute]
 * — a test-source [WireSerializers] implementation NOT listed in
 * `META-INF/services/civictech.cell.wire.WireSerializers` — strictly AFTER
 * the connection has already carried an ordinary frame, so the codec's
 * baseline `Json` has demonstrably already been built and used before the
 * late contribution arrives.
 *
 * Honest limitation (stated per the task, not just in the bead): both
 * endpoints run in one JVM, so a single process-global [WireCodec] serves
 * both encode and decode. This test proves a LATE contribution crossing a
 * REAL socket; it does not prove per-module isolation across independent
 * classloaders/JVMs.
 *
 * ## This test is one half of B13; the other half lives in `:loader`
 *
 * The delta type here is a **test-source stand-in** for a module's type, not a
 * type sourced from a real jar. The provenance half — a delta type whose
 * `Class` exists only inside a loaded jar's own `ModuleClassLoader`, encoded and
 * decoded by the live [WireCodec] across two `ManagedHost`s — is proved by
 * `civictech.loader.B13ModuleWireSerializersTest` (`:loader`,
 * computenet-051.6.4), which cannot live here or reach a socket there:
 * `civictech.loader.ModuleDependencyTest` asserts `:wire` is absent from
 * `:loader`'s build file and test runtime classpath.
 *
 * **Bug computenet-06cn decided that this decomposition satisfies B13** and that
 * no composed jar-plus-socket test is owed — the two halves meet at a
 * type-agnostic byte boundary, since `WsTransport` encodes nothing itself but
 * constructs the same `BridgeEgressCell` / `Peering.hostIngress` pair
 * (`WsTransport.kt:450`, `:1229`) whose `WireCodec` calls live in
 * `BridgeCells.kt:62` / `:296`, and a socket cannot discriminate on the
 * provenance of the opaque `ByteArray` it carries. The full reasoning, including
 * why `:testkit` and `:wire` were both rejected as hosts for a composed test, is
 * in `B13ModuleWireSerializersTest`'s KDoc — read it there before re-opening
 * this.
 */
class WsLateWireSerializersRoundTripTest {

    /** A test-local delta type, unknown to the kernel's baseline module. */
    @Serializable
    @SerialName("LateWireRoundTripDelta")
    private data class LateWireRoundTripDelta(val payload: String, val tag: Timestamp)

    /** What a dynamically loaded module would contribute for the type above. */
    private class LateRoundTripSerializers : WireSerializers {
        override val module: SerializersModule = SerializersModule {
            polymorphic(Any::class) {
                subclass(LateWireRoundTripDelta::class, LateWireRoundTripDelta.serializer())
            }
        }
    }

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private interface SetDeltaInletProxy {
        val inlet: Use<Propagate<SetDelta<String>>>
    }

    private interface LateDeltaInletProxy {
        val inlet: Use<Propagate<LateWireRoundTripDelta>>
    }

    /** Ordinary collector — proves an everyday frame crosses first (M5.5 pattern). */
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

    /** Receives the late-contributed delta type and records the [MessageContext] it arrived under. */
    private class LateDeltaCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals: MutableList<Pair<LateWireRoundTripDelta, MessageContext?>> =
            Collections.synchronizedList(mutableListOf())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<LateWireRoundTripDelta>>())

        init {
            inlet.serve(object : Propagate<LateWireRoundTripDelta> {
                override fun propagate(value: LateWireRoundTripDelta) {
                    arrivals += value to CurrentContext.get()
                }
            })
        }
    }

    /** A bare outlet-only cell: emits the late-contributed delta type on demand. */
    private class LateDeltaWriterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<LateWireRoundTripDelta>>())
    }

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
    fun `a delta type contributed after the connection is live round-trips across a real ws connection`() {
        val server = Stack()
        val client = Stack()
        val listener = WsTransport.listen(0, server.side)
        val connection = WsTransport.connect(URI("ws://localhost:${listener.port}"), client.side)
        try {
            // --- 1. touch the codec first: an ordinary SetCell -> SetDelta frame
            //     crosses the live connection, proving WireCodec's baseline Json
            //     is already built and in use before any late contribution.
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

            // --- 2. late contribution: a test-source WireSerializers, never
            //     listed in META-INF/services — the point of "late".
            val lateSerializers = LateRoundTripSerializers()
            WireCodec.contribute(lateSerializers)
            try {
                // --- 3. a frame carrying the newly contributed delta type crosses
                //     the SAME live connection and decodes to an equal value.
                val lateCollector = LateDeltaCollectorCell()
                server.host.managementInlet.call.spawn(lateCollector)
                await("late collector announced to client") {
                    client.registry.location(lateCollector.ref) is LocationRegistry.Remote
                }
                val lateWriter = LateDeltaWriterCell()
                client.host.managementInlet.call.spawn(lateWriter)
                val lateRemoteInlet = (
                    HostedCellProxy.create(lateCollector.ref, client.registry, LateDeltaInletProxy::class.java)
                        as LateDeltaInletProxy
                    ).inlet.call
                lateWriter.outlet.subscribe(Use.fixed(lateRemoteInlet, PortRef.generate()))

                val first = LateWireRoundTripDelta("late-payload-1", Timestamp(UUID.randomUUID(), 1))
                val second = LateWireRoundTripDelta("late-payload-2", Timestamp(UUID.randomUUID(), 2))
                lateWriter.outlet.call.propagate(first)
                lateWriter.outlet.call.propagate(second)

                await("both late-contributed frames arrived") {
                    lateCollector.arrivals.size >= 2
                }

                val arrived = lateCollector.arrivals.toList()
                arrived shouldHaveSize 2

                // value equality: the payload AND its embedded tag survive the
                // wire byte-identical (the "tag intact" half).
                arrived.map { it.first } shouldBe listOf(first, second)

                // context intact: every arrival carries a non-null MessageContext
                // whose wave (timestamp) is present and strictly increasing —
                // the "wave intact" half, each spontaneous emission from the
                // writer's outlet minting its own fresh wave.
                val waveCounters = arrived.map { (_, ctx) -> checkNotNull(ctx) { "context missing on arrival" }.timestamp.counter }
                waveCounters shouldBe waveCounters.sorted()
                waveCounters.toSet() shouldHaveSize 2
            } finally {
                WireCodec.withdraw(lateSerializers)
            }

            // withdrawing leaves the connection itself untouched: an ordinary
            // frame still crosses fine afterwards.
            ordinaryApi.add("eggs")
            await("a post-withdraw ordinary frame still crosses") {
                ordinaryCollector.arrivals.size >= 2
            }
        } finally {
            connection.shutdown()
            runCatching { listener.stop(1000) }
        }
    }
}
