package civictech.cell.wire

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.data.Propagate
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PeerId
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.allowPeers
import civictech.cell.port.input
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M8.2–M8.4 (G-29 phase 1, spec 43): identity rides deliveries into link
 * requests, and the bridge boundary refuses unlisted peers — rejections
 * observable as ordinary dead letters, over 100 seeds, with an open-mode
 * control proving the harness would have linked.
 */
class TrustBoundaryTest {

    class CollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<String>()

        @Suppress("unused")
        val inlet by input<Consumer<String>>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<String> {
                override fun provide(input: String) {
                    received += input
                }
            })
        }
    }

    interface CollectorProxy {
        val inlet: Use<Consumer<String>>
    }

    private class Run(seed: Long, allowlisted: Boolean) {
        val controller = SimulationController(seed)
        val rnd = Random(seed)

        val registryP = LocationRegistry()
        val hostP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val bridgeP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        val registryQ = LocationRegistry()
        val hostQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)
        val bridgeQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)

        val deadLettersP = mutableListOf<DeadLetter>()
        val collector = CollectingCell()

        init {
            val p = Peering.Side(
                registryP, bridgeP, peer = PeerId("p"),
                allow = if (allowlisted) setOf(PeerId("good")) else null,
            )
            val q = Peering.Side(registryQ, bridgeQ, peer = PeerId("evil"))
            Peering.loopback(p, q)

            listOf(hostP, bridgeP).forEach { h ->
                h.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        deadLettersP += value
                    }
                }, PortRef.generate()))
            }
            hostP.managementInlet.call.spawn(collector)
            controller.runToIdle()
        }

        fun sendFromQ(count: Int) {
            val proxy = (HostedCellProxy.create(collector.ref, registryQ, CollectorProxy::class.java)
                    as CollectorProxy).inlet.call
            repeat(count) { i ->
                proxy.provide("q-$i")
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()
        }
    }

    @Test
    fun `an unlisted peer's traffic is refused at the boundary on every seed`() {
        for (seed in 0L until 100L) {
            val run = Run(seed, allowlisted = true)
            run.sendFromQ(5)
            run.collector.received.shouldBeEmpty() // nothing crossed
            run.deadLettersP.size shouldBeGreaterThan 0 // and the refusal is visible
        }
    }

    @Test
    fun `control - open mode delivers the same traffic`() {
        val run = Run(seed = 0, allowlisted = false)
        run.sendFromQ(5)
        run.collector.received shouldBe (0 until 5).map { "q-$it" }
        run.deadLettersP.shouldBeEmpty()
    }

    @Test
    fun `link requests carry the delivering peer's identity into policies`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val deadLetters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                deadLetters += value
            }
        }, PortRef.generate()))

        val target = CollectingCell()
        host.managementInlet.call.spawn(target)
        controller.runToIdle()
        target.inlet.linking.policies += allowPeers(PeerId("good"))

        val linkFrom = LinkFrom::class.java.methods.first { it.name == "linkFrom" }
        fun requestFrom(peer: PeerId): FanOutlet<Consumer<String>> {
            val outlet = FanOutlet.create<Consumer<String>>()
            host.enqueueHostedInvocation(
                HostedPortInvocation(
                    target.ref, "inlet", HostedPortInvocation.Type.PORT_MANAGEMENT,
                    Invocation.of(linkFrom, arrayOf(outlet)), peer = peer,
                )
            )
            controller.runToIdle()
            return outlet
        }

        val refused = requestFrom(PeerId("evil"))
        refused.linking.links.shouldBeEmpty()
        deadLetters.any { it.description.contains("allowlist") }.shouldBeTrue()

        val admitted = requestFrom(PeerId("good"))
        admitted.linking.links.size shouldBe 1
    }
}
