package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.CountCell
import civictech.cell.data.CounterDelta
import civictech.cell.data.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.UnionSetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.host.link
import civictech.cell.port.LinkResult
import civictech.cell.port.PortIdentity
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.identity
import civictech.cell.port.inlet
import civictech.cell.port.outlet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Typed port wiring (typed-port-links, 05): `link(a.outlet, b.inlet)` connects
 * typed port *objects* and lowers to the exact same `connect(ref, name, ...)`
 * call as the stringly-typed host API — same runtime link, byte-identical
 * recorded [ConnectStep].
 *
 * ## The compile-error acceptance criterion
 * "A payload-type mismatch fails to compile" and "connecting two inlets / two
 * outlets is a compile error" cannot be expressed as runtime assertions — a
 * non-compiling call cannot sit in a compiling test. It is instead guaranteed
 * *structurally* by the [civictech.cell.host.link] signature
 * `link(out: Subscribe<Api>, inn: Serve<Api>)`: the shared, invariant [Api]
 * type parameter can only unify when both payload protocols are identical, and
 * only an outlet is a `Subscribe` while only an inlet is a `Serve`. The lines
 * below would each be rejected by the Kotlin compiler; they are kept commented
 * as executable documentation of the negative:
 *
 * ```
 * // link(count.outlet, union.inlet)   // Subscribe<Propagate<CounterDelta>> vs Serve<Propagate<SetDelta<String>>> — payload mismatch
 * // link(writer.outlet, union.outlet) // Serve expected, got Subscribe — two outlets, wrong direction
 * // link(union.inlet,  count.inlet)   // Subscribe expected, got Serve — two inlets, wrong direction
 * ```
 */
class TypedLinkTest {

    // --- identity back-reference: both registration paths stamp (ownerRef, name) ---

    /** A cell whose ports are declared via the `by input()`/`by output()` delegates. */
    private class DelegatePortCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by civictech.cell.port.input<Consumer<String>>()
        val outlet by civictech.cell.port.output<Consumer<String>>()
    }

    /** A cell whose ports are declared via the reified `inlet()`/`outlet()` helpers. */
    private class ReifiedPortCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inn = inlet<Consumer<String>>("inn")
        val out = outlet<Consumer<String>>("out")
    }

    @Test
    fun `registerPort stamps a port's owner ref and name`() {
        val writer = SetCell<String>()
        writer.outlet.identity() shouldBe PortIdentity(writer.ref, "outlet")
        writer.inlet.identity() shouldBe PortIdentity(writer.ref, "inlet")
    }

    @Test
    fun `by-input and by-output delegates stamp identity too`() {
        val cell = DelegatePortCell()
        cell.outlet.identity() shouldBe PortIdentity(cell.ref, "outlet")
        cell.inlet.identity() shouldBe PortIdentity(cell.ref, "inlet")
    }

    @Test
    fun `reified inlet and outlet helpers register and stamp identity`() {
        val cell = ReifiedPortCell()
        cell.inn.identity() shouldBe PortIdentity(cell.ref, "inn")
        cell.out.identity() shouldBe PortIdentity(cell.ref, "out")
    }

    // --- host-level link: runtime-identical to the string form ---

    @Test
    fun `host link produces a working runtime link equal to the string form`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call

        val writer = SetCell<String>()
        val union = UnionSetCell<String>()
        val count = CountCell<String>()
        hostApi.spawn(writer)
        hostApi.spawn(union)
        hostApi.spawn(count)

        // typed wiring — both edges carry Propagate<SetDelta<String>>
        val r1 = hostApi.link(writer.outlet, union.inlet)
        val r2 = hostApi.link(union.outlet, count.inlet)
        r1.shouldBeInstanceOf<LinkResult.Connected>()
        r2.shouldBeInstanceOf<LinkResult.Connected>()

        val counts = countsOf(count.outlet)
        controller.runToIdle()

        writer.inlet.call.add("x")
        writer.inlet.call.add("y")
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 2
    }

    @Test
    fun `host link lowers to the same connect the string form calls`() {
        val controller = SimulationController(seed = 7)
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call

        val writer = SetCell<String>()
        val union = UnionSetCell<String>()
        hostApi.spawn(writer)
        hostApi.spawn(union)

        // link resolves (ref, name) off the ports; assert those are exactly what
        // the caller would have typed into the string connect.
        writer.outlet.identity() shouldBe PortIdentity(writer.ref, "outlet")
        union.inlet.identity() shouldBe PortIdentity(union.ref, "inlet")
        hostApi.link(writer.outlet, union.inlet).shouldBeInstanceOf<LinkResult.Connected>()
    }

    // --- GraphBuilder.link: byte-identical recorded ConnectStep ---

    @Test
    fun `graph built with link records the same ConnectSteps as the string form`() {
        val controller = SimulationController(seed = 2)

        val hostLink = ManagedHost(scheduler = controller.scheduler())
        val w = SetCell<String>()
        val u = UnionSetCell<String>()
        val c = CountCell<String>()
        val specLink = graph(hostLink.managementInlet) {
            spawn("w") { w }
            spawn("u") { u }
            spawn("c") { c }
            link(w.outlet, u.inlet)
            link(u.outlet, c.inlet)
        }

        val hostConnect = ManagedHost(scheduler = controller.scheduler())
        val specConnect = graph(hostConnect.managementInlet) {
            val wh = spawn("w") { SetCell<String>() }
            val uh = spawn("u") { UnionSetCell<String>() }
            val ch = spawn("c") { CountCell<String>() }
            connect(wh, "outlet", uh, "inlet")
            connect(uh, "outlet", ch, "inlet")
        }

        // graphs-as-data (G-30): the recorded topology is identical, so a spec
        // built with link replays byte-for-byte like one built with connect.
        specLink.steps.filterIsInstance<ConnectStep>() shouldBe
            specConnect.steps.filterIsInstance<ConnectStep>()
        specLink.steps.filterIsInstance<ConnectStep>() shouldBe listOf(
            ConnectStep("w", "outlet", "u", "inlet"),
            ConnectStep("u", "outlet", "c", "inlet"),
        )
    }

    @Test
    fun `a link-built graph is live and behaves like a connect-built one`() {
        val controller = SimulationController(seed = 3)
        val host = ManagedHost(scheduler = controller.scheduler())

        val w = SetCell<String>()
        val u = UnionSetCell<String>()
        val c = CountCell<String>()
        graph(host.managementInlet) {
            spawn("w") { w }
            spawn("u") { u }
            spawn("c") { c }
            link(w.outlet, u.inlet)
            link(u.outlet, c.inlet)
        }

        val counts = countsOf(c.outlet)
        controller.runToIdle()
        w.inlet.call.add("x")
        w.inlet.call.add("x") // second tag, same element: no membership change
        w.inlet.call.add("z")
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 2
    }

    // --- escape hatch: the stringly-typed connect stays available and required ---

    @Test
    fun `string connect remains the escape hatch and still works`() {
        val controller = SimulationController(seed = 4)
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call

        val writer = SetCell<String>()
        val union = UnionSetCell<String>()
        val count = CountCell<String>()
        hostApi.spawn(writer)
        hostApi.spawn(union)
        hostApi.spawn(count)

        // by-ref wiring, unchanged — the dynamic path GraphSpec/routed proxies use
        hostApi.connect(writer.ref, "outlet", union.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()
        hostApi.connect(union.ref, "outlet", count.ref, "inlet").shouldBeInstanceOf<LinkResult.Connected>()

        val counts = countsOf(count.outlet)
        controller.runToIdle()
        writer.inlet.call.add("a")
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 1
    }

    @Test
    fun `link rejects a port with no logical identity, pointing at the escape hatch`() {
        val host = ManagedHost()
        val hostApi = host.managementInlet.call

        val union = UnionSetCell<String>()
        hostApi.spawn(union)

        // an ad-hoc Use.fixed endpoint carries no (ownerRef, name): only the
        // string connect can wire it. Prove it has no identity, and that a
        // hand-built outlet with no identity is refused by link.
        val adHoc = Use.fixed(object : Propagate<CounterDelta> {
            override fun propagate(value: CounterDelta) {}
        }, PortRef.generate())
        (adHoc as civictech.cell.port.Port).identity() shouldBe null

        val orphanOutlet: Subscribe<Propagate<SetDelta<String>>> =
            civictech.cell.port.FanOutlet.create()
        orphanOutlet.identity() shouldBe null
        shouldThrow<IllegalArgumentException> { hostApi.link(orphanOutlet, union.inlet) }
    }

    // --- TypedCellHandle: typed ports from a PURE factory (replay-safe) ---

    @Test
    fun `typed handles keep factories pure and the link-built spec replays onto a fresh host`() {
        val controller = SimulationController(seed = 5)
        val hostA = ManagedHost(scheduler = controller.scheduler())

        // no instance captured outside the factory: replaying this spec mints
        // fresh cells (graphs-as-data), yet wiring is still typed via handle.cell
        val spec = graph(hostA.managementInlet) {
            val a = spawn("a") { ref -> SetCell<String>(ref = ref) }
            val u = spawn("u") { ref -> UnionSetCell<String>(ref = ref) }
            link(a.cell.outlet, u.cell.inlet)
        }

        val bytes = java.io.ByteArrayOutputStream()
            .also { java.io.ObjectOutputStream(it).use { out -> out.writeObject(spec) } }
            .toByteArray()
        val revived = java.io.ObjectInputStream(java.io.ByteArrayInputStream(bytes)).readObject() as GraphSpec
        val hostB = ManagedHost(scheduler = controller.scheduler())
        val refs = revived.applyTo(hostB.managementInlet)
        refs.keys shouldBe setOf("a", "u")
    }

    @Test
    fun `builder link rejects a cell not spawned on this builder`() {
        val controller = SimulationController(seed = 6)
        val host = ManagedHost(scheduler = controller.scheduler())
        val foreign = SetCell<String>() // registered ports, but never spawned here
        shouldThrow<IllegalArgumentException> {
            graph(host.managementInlet) {
                val u = spawn("u") { ref -> UnionSetCell<String>(ref = ref) }
                link(foreign.outlet, u.cell.inlet)
            }
        }
    }

    // --- ref-only path: generated <Cell>Ports ids through the typed connect overloads ---

    @Test
    fun `generated Ports ids connect via the ref-only typed overloads`() {
        val controller = SimulationController(seed = 8)
        val host = ManagedHost(scheduler = controller.scheduler())
        val hostApi = host.managementInlet.call

        val writer = SetCell<String>()
        val union = UnionSetCell<String>()
        hostApi.spawn(writer)
        hostApi.spawn(union)

        // payload type unifies through the phantom ids; lowers to the string connect
        val result = hostApi.connect(
            writer.ref, civictech.cell.data.SetCellPorts.outlet<String>(),
            union.ref, civictech.cell.data.UnionSetCellPorts.inlet<String>(),
        )
        result.shouldBeInstanceOf<LinkResult.Connected>()

        val counts = mutableListOf<SetDelta<String>>()
        union.outlet.subscribe(Use.fixed(Propagate { counts += it }, PortRef.generate()))
        writer.inlet.call.add("x")
        controller.runToIdle()
        counts.size shouldBe 1
    }

    private fun countsOf(outlet: Subscribe<Propagate<CounterDelta>>): MutableList<CounterDelta> {
        val counts = mutableListOf<CounterDelta>()
        outlet.subscribe(Use.fixed(object : Propagate<CounterDelta> {
            override fun propagate(value: CounterDelta) {
                counts += value
            }
        }, PortRef.generate()))
        return counts
    }
}
