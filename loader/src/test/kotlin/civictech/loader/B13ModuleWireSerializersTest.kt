package civictech.loader

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import civictech.cell.wire.WireCodec
import civictech.cell.wire.WireSerializers
import civictech.nature.ModuleRegistration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID

/**
 * JAR1 [JAR1-REG-08], arm (1), scenario B13 — the jar-loaded end-to-end, task
 * computenet-051.6.4. Fixture (h) (`:loader:fixtures:wire-delta`,
 * `FixtureJars.wireDelta`) is a real module jar, built through the ordinary
 * Gradle/KSP pipeline, that contributes a [WireSerializers] table for its OWN
 * `@Serializable` delta type — nothing here is a test-source stand-in for a
 * module the way `civictech.wire.WireCodecLateContributionTest` and
 * `civictech.wire.WsLateWireSerializersRoundTripTest` (computenet-051.6.1/.6.2,
 * both merged) used one.
 *
 * ## Only the first EITHER branch
 *
 * The epic decided arm (1) of [JAR1-REG-08] on feature computenet-051.6
 * (`WireCodec.contribute`/`withdraw`, already implemented and tested at the
 * codec level): a late contribution is folded into the live codec rather than
 * refused. Arm (2) — refusing a wire-incapable module at load time — was NOT
 * chosen and is NOT implemented or asserted here.
 *
 * ## Honest limitation: `Peering.loopback`, not a live `WsTransport` socket
 *
 * The bead's governing text asks for the round trip "over WsTransport", the
 * literal mechanism `WsLateWireSerializersRoundTripTest` uses. That test lives
 * in `:wire` precisely because `civictech.loader.ModuleDependencyTest` (feature
 * computenet-051.1, already merged) asserts `:loader`'s test classpath carries
 * NO `:wire` fingerprint at all (`civictech.wire.WsTransport` must be
 * `ClassNotFoundException` from this module) and that `loader/build.gradle.kts`
 * declares no dependency on `:wire` — the whole point being that the dynamic
 * loader sits below the transport layer, not beside it. Adding a `:wire` test
 * dependency here to get a literal socket would break that already-merged
 * guardrail test, which sits outside this task's file claim.
 *
 * The available, equally-real substitute is [Peering.loopback]: the exact same
 * `BridgeEgressCell`/`BridgeIngressCell` pair and the exact same
 * [WireCodec.encode]/[WireCodec.decode] calls `WsTransport` itself sits on top
 * of (see `civictech.cell.wire.Peering.loopback`'s own KDoc and its many
 * `:kernel` callers, e.g. `civictech.cell.wire.RemoteAddressingTest`) — minus
 * the literal TCP/WebSocket bytes in between. What B13 is actually probing —
 * that a module's late-contributed delta type survives a real
 * `WireCodec.encode` → bytes → `WireCodec.decode` crossing between two
 * different [ManagedHost]s — holds here exactly as it would over a socket;
 * only the "over an actual live connection" half of "real :wire round-trip" is
 * reduced. Reported on the bead rather than silently substituted.
 */
class B13ModuleWireSerializersTest {

    private companion object {
        const val DELTA_FQN = "civictech.loader.fixture.wiredelta.WireDeltaFixtureDelta"
    }

    /** Erased on purpose: the delta type is only known inside the module's own classloader. */
    private interface DeltaInletProxy {
        val inlet: Use<Propagate<Any>>
    }

    private class DeltaCollectorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val arrivals: MutableList<Pair<Any, MessageContext?>> = Collections.synchronizedList(mutableListOf())
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Any>>())

        init {
            inlet.serve(object : Propagate<Any> {
                override fun propagate(value: Any) {
                    arrivals += value to CurrentContext.get()
                }
            })
        }
    }

    private class DeltaWriterCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Any>>())
    }

    /** [Propagate] is itself `@Contract` (kernel-wide, any `T`) — see `civictech.cell.Propagate`. */
    private val propagateMethod = Propagate::class.java.getMethod("propagate", Any::class.java)

    private fun invocationOf(value: Any?): HostedPortInvocation = HostedPortInvocation(
        cellRef = CellRef(UUID.randomUUID()),
        portName = "inlet",
        type = HostedPortInvocation.Type.PORT_API,
        invocation = Invocation.of(propagateMethod, arrayOf(value), null),
    )

    private fun deltaInstance(loader: ClassLoader, payload: String, counter: Long): Any =
        loader.loadClass(DELTA_FQN)
            .getDeclaredConstructor(String::class.java, Timestamp::class.java)
            .newInstance(payload, Timestamp(UUID.randomUUID(), counter))

    @Test
    fun `fixture h's delta type fails loudly before the module is loaded, and round-trips a real wire crossing once it is`() {
        val jar = FixtureJars.wireDelta

        // --- Before the module is loaded at all: a classloader over the same jar,
        // opened directly (not through ModuleLoader.load), gives a real instance of
        // the module's delta type without registering anything with the kernel —
        // WireCodec has never heard of this type. Encoding it must fail LOUDLY,
        // never silently and never only "at first use" in some other form.
        val unloadedClassLoader = ModuleClassLoader.open(jar)
        try {
            val beforeLoad = deltaInstance(unloadedClassLoader, "pre-load-payload", counter = 0)
            shouldThrow<SerializationException> { WireCodec.encode(invocationOf(beforeLoad)) }
        } finally {
            unloadedClassLoader.close()
        }

        // --- Now load fixture (h) for real, through :loader's public load path,
        // wiring the [JAR1-REG-08] arm-(1) seam feature .1/.3 left unmade:
        // onWireSerializers folds every discovered WireSerializers into the live
        // WireCodec, exactly the shape WireCodecLateContributionTest already proved
        // at the codec level — here sourced from an actual jar's ServiceLoader
        // discovery instead of a test-local instance.
        var discovered: List<WireSerializers> = emptyList()
        val loader = ModuleLoader(
            acceptedLocations = setOf(jar.toPath().toAbsolutePath().normalize().parent),
            onWireSerializers = { _, serializers ->
                discovered = serializers
                serializers.forEach(WireCodec::contribute)
            },
        )
        val handle = loader.load(jar)
        try {
            withClueDiscoveredCount(discovered)

            val first = deltaInstance(handle.classLoader, "late-payload-1", counter = 1)
            val second = deltaInstance(handle.classLoader, "late-payload-2", counter = 2)

            // Codec-level round trip first (M5.5 pattern, as the seam test does): the
            // module's own type, sourced from its own jar/classloader, decodes back
            // equal from a real WireCodec.encode/decode pair.
            WireCodec.decode(WireCodec.encode(invocationOf(first))).invocation.args shouldBe listOf(first)

            // --- Then the real crossing: two DIFFERENT ManagedHosts, bridged by
            // Peering.loopback (see this class's KDoc for why this stands in for a
            // live WsTransport socket here), so the value travels through
            // BridgeEgressCell.deliver -> WireCodec.encode -> bytes -> WireCodec.decode
            // -> BridgeIngressCell, exactly the path a real socket carries the same
            // bytes over.
            val controller = SimulationController(seed = 13)
            val registryA = LocationRegistry()
            val registryB = LocationRegistry()
            val hostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
            val hostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
            val bridgeHostA = ManagedHost(scheduler = controller.scheduler(), registry = registryA)
            val bridgeHostB = ManagedHost(scheduler = controller.scheduler(), registry = registryB)
            Peering.loopback(Peering.Side(registryA, bridgeHostA), Peering.Side(registryB, bridgeHostB))

            val collector = DeltaCollectorCell()
            hostB.managementInlet.call.spawn(collector)
            controller.runToIdle()

            val writer = DeltaWriterCell()
            hostA.managementInlet.call.spawn(writer)
            val remoteInlet = (
                HostedCellProxy.create(collector.ref, registryA, DeltaInletProxy::class.java) as DeltaInletProxy
                ).inlet.call
            writer.outlet.subscribe(Use.fixed(remoteInlet, PortRef.generate()))

            writer.outlet.call.propagate(first)
            writer.outlet.call.propagate(second)
            controller.runToIdle()

            val arrived = collector.arrivals.toList()
            arrived shouldHaveSize 2
            // value equality: the payload AND its embedded Timestamp tag survive the
            // module's own class, byte-identical across the crossing.
            arrived.map { it.first } shouldBe listOf(first, second)
            // context intact: every arrival carries a non-null MessageContext.
            arrived.forEach { (_, ctx) -> checkNotNull(ctx) { "context missing on arrival" } }
        } finally {
            discovered.forEach(WireCodec::withdraw)
            ModuleRegistration.unregister(handle.id)
            handle.classLoader.close()
        }
    }

    /** Non-vacuity: if discovery found nothing, everything below would pass vacuously. */
    private fun withClueDiscoveredCount(discovered: List<WireSerializers>) {
        discovered shouldHaveSize 1
    }
}
