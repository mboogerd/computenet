package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkResult
import civictech.cell.port.registerPort
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Minimal organelle (spec 10/11): relays every inbound value to its own outlet. */
private class RelayOrganelle(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())
    val outlet = registerPort("outlet", FanOutlet.create<Consumer<String>>())

    init {
        inlet.serve(object : Consumer<String> {
            override fun provide(input: String) {
                outlet.call.provide(input)
            }
        })
    }
}

private class Collector(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val received = mutableListOf<String>()
    val inlet = registerPort("inlet", FanInlet.create<Consumer<String>>())

    init {
        inlet.serve(object : Consumer<String> {
            override fun provide(input: String) {
                received += input
            }
        })
    }
}

private class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    val outlet = registerPort("outlet", FanOutlet.create<Consumer<String>>())
}

/**
 * A composite cell (spec 10/11) exposing one organelle's ports via Flatten,
 * another organelle's inlet via Mediate, and hiding a third organelle
 * entirely — the hidden-by-default default (G-9).
 */
private class ExampleMembrane(
    val flattened: RelayOrganelle = RelayOrganelle(),
    val mediated: RelayOrganelle = RelayOrganelle(),
    val hidden: RelayOrganelle = RelayOrganelle(),
) : CompositeCell() {
    val flatInlet: FanInlet<Consumer<String>> = flatten("flatInlet", "inlet", flattened.inlet)
    val flatOutlet: FanOutlet<Consumer<String>> = flatten("flatOutlet", "outlet", flattened.outlet)
    val mediateInlet: FanInlet<Consumer<String>> = mediate("mediateInlet", "inlet", mediated.inlet)
    val mediateOutlet: FanOutlet<Consumer<String>> = flatten("mediateOutlet", "outlet", mediated.outlet)

    // `hidden` is intentionally never exposed via flatten()/mediate(): its
    // ports carry no entry in this cell's PortRegistry namespace and its ref
    // is never spawned onto the host, so external resolution cannot reach it.
}

class CompositeCellTest {

    @Test
    fun `exposure map records only the declared surfaces, hidden organelle absent`() {
        val composite = ExampleMembrane()

        composite.exposureMap.keys shouldBe setOf("flatInlet", "flatOutlet", "mediateInlet", "mediateOutlet")
        composite.exposureMap.getValue("flatInlet").mode shouldBe SurfaceMode.FLATTEN
        composite.exposureMap.getValue("mediateInlet").mode shouldBe SurfaceMode.MEDIATE
    }

    @Test
    fun `flatten re-registers the same organelle port, off the per-message path`() {
        val composite = ExampleMembrane()
        // Flatten is `delegate`'s O(1) collapse (10/14): the exposed port IS
        // the organelle's own port object, not a wrapper around it.
        composite.flatInlet shouldBe composite.flattened.inlet
        composite.flatInlet.ref shouldBe composite.flattened.inlet.ref
    }

    @Test
    fun `mediate installs a distinct served proxy on the per-message path`() {
        val composite = ExampleMembrane()
        // Mediate is `serve(proxy)` (10/14): a real, separately-identified
        // cell on the path, budget counted at its own external face.
        composite.mediateInlet shouldNotBe composite.mediated.inlet
        composite.mediateInlet.ref shouldNotBe composite.mediated.inlet.ref
    }

    @Test
    fun `external callers reach organelles only via exposed names, both surface modes deliver`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())

        val composite = ExampleMembrane()
        val compositeRef = host.managementInlet.call.spawn(composite)

        val flattenSource = Source()
        val mediateSource = Source()
        val flattenCollector = Collector()
        val mediateCollector = Collector()
        val flattenSourceRef = host.managementInlet.call.spawn(flattenSource)
        val mediateSourceRef = host.managementInlet.call.spawn(mediateSource)
        val flattenCollectorRef = host.managementInlet.call.spawn(flattenCollector)
        val mediateCollectorRef = host.managementInlet.call.spawn(mediateCollector)

        host.managementInlet.call.connect(flattenSourceRef, "outlet", compositeRef, "flatInlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        host.managementInlet.call.connect(compositeRef, "flatOutlet", flattenCollectorRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        host.managementInlet.call.connect(mediateSourceRef, "outlet", compositeRef, "mediateInlet")
            .shouldBeInstanceOf<LinkResult.Connected>()
        host.managementInlet.call.connect(compositeRef, "mediateOutlet", mediateCollectorRef, "inlet")
            .shouldBeInstanceOf<LinkResult.Connected>()

        flattenSource.outlet.call.provide("via-flatten")
        mediateSource.outlet.call.provide("via-mediate")
        controller.runToIdle()

        flattenCollector.received shouldBe listOf("via-flatten")
        mediateCollector.received shouldBe listOf("via-mediate")
    }

    @Test
    fun `hidden-by-default refuses the organelle's real port name`() {
        val controller = SimulationController(seed = 2)
        val host = ManagedHost(scheduler = controller.scheduler())
        val composite = ExampleMembrane()
        val compositeRef = host.managementInlet.call.spawn(composite)
        val source = Source()
        val sourceRef = host.managementInlet.call.spawn(source)

        // "inlet" is the organelle's real port name, never registered under
        // that name on the composite itself — only "flatInlet" is exposed.
        assertThrows<IllegalArgumentException> {
            host.managementInlet.call.connect(sourceRef, "outlet", compositeRef, "inlet")
        }
    }

    @Test
    fun `hidden-by-default refuses the organelle's own cell ref, unpublished by construction`() {
        val controller = SimulationController(seed = 3)
        val host = ManagedHost(scheduler = controller.scheduler())
        val composite = ExampleMembrane()
        host.managementInlet.call.spawn(composite)
        val source = Source()
        val sourceRef = host.managementInlet.call.spawn(source)

        // `composite.hidden` was never spawned onto the host: its CellRef is
        // simply unknown to host-level resolution, exactly as an
        // unexposed organelle must be (G-9).
        assertThrows<IllegalArgumentException> {
            host.managementInlet.call.connect(sourceRef, "outlet", composite.hidden.ref, "inlet")
        }
        host.lookup<Any>(composite.hidden.ref) shouldBe null
    }
}
