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
 * ## What this proves, what it does not, and why that is the decided position
 *
 * This test proves the **provenance** half of B13: a delta type whose `Class`
 * exists only inside a jar's own [ModuleClassLoader] is encoded and decoded by
 * the live [WireCodec], and survives a real `WireCodec.encode` → `ByteArray` →
 * `WireCodec.decode` crossing between two different [ManagedHost]s, via
 * [Peering.loopback].
 *
 * It does NOT carry those bytes over a TCP/WebSocket hop. The **socket** half
 * is proved separately by `civictech.wire.WsLateWireSerializersRoundTripTest`
 * (`:wire`, computenet-051.6.2), where a late-contributed delta type crosses a
 * live `WsTransport` connection.
 *
 * **Decision (bug computenet-06cn): the two halves together satisfy B13, and no
 * composed jar-plus-socket test is owed.** The reasoning, so a later reader does
 * not re-open it:
 *
 * 1. The two halves meet at a **type-agnostic byte boundary**. `WsTransport`
 *    encodes and decodes nothing itself: it constructs the very same
 *    `BridgeEgressCell` / `Peering.hostIngress` pair this test uses
 *    (`WsTransport.kt:450`, `:1229`), whose `WireCodec.encode` /
 *    `WireCodec.decodeFrame` calls are `BridgeCells.kt:62` / `:296`. The socket
 *    carries an already-encoded, opaque `ByteArray`, and cannot discriminate on
 *    the provenance of the type those bytes came from — so composing
 *    "jar-sourced type" with "real socket" adds no reachable failure mode that
 *    either half misses. `ByteArray` in equals `ByteArray` out is already proved
 *    for arbitrary payloads by every other `:wire` test.
 * 2. Nothing module-typed crosses the announcement path either. Fixture (h)
 *    carries no `@Contract` and no `Cell`, and the ports above are erased
 *    (`Propagate<Any>`), so only `:kernel` types appear in cell announcements.
 * 3. The composed test has no architecturally sound host today. `:testkit` is
 *    excluded by construction — it is a `testImplementation` of `:loader`, so a
 *    `:wire` dependency there would put `civictech.wire.WsTransport` back on
 *    `:loader`'s own test runtime classpath and redden [ModuleDependencyTest]'s
 *    classpath check. `:wire` *could* host it (no guardrail forbids
 *    `:wire -> :loader`), but that inverts the epic's stated dependency shape —
 *    "applications (the `demo` modules, `inspect/`) add `:loader` when they want dynamic
 *    loading" — by making the deliberately narrow transport module the loader's
 *    first consumer in the repo (today it has none), and would couple
 *    `wire/build.gradle.kts` to JAR1's fixture subprojects. A dedicated
 *    integration module buys the same near-zero marginal coverage for a
 *    permanent subproject.
 *
 * What genuinely remains uncovered is **two-JVM cross-loader wire identity**:
 * the same jar loaded into two independent [ModuleClassLoader]s in two
 * processes, where encode and decode no longer share one process-global
 * [WireCodec]. That is a different scenario from B13 (which is about a late
 * contribution reaching the codec at all), and is worth its own item if and when
 * a demo takes on dynamic loading for real — at which point that demo, already a
 * `:wire` consumer, is the natural host and the dependency edge costs nothing.
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
