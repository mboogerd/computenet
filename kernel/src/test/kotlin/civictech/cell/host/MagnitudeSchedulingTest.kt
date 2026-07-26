package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Magnitude
import civictech.cell.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Magnitude-band dispatch (spec 34, M17): a cell whose staged payload
 * declares a bigger size dispatches first; the boost dies with the queue;
 * without the policy the order is exactly the pre-M17 FIFO.
 */
class MagnitudeSchedulingTest {

    data class Sized(val name: String, val magnitude: Double) : Magnitude {
        override fun size(): Double = magnitude
    }

    class SinkCell(
        private val log: MutableList<String>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Sized>>())

        init {
            inlet.serve(object : Propagate<Sized> {
                override fun propagate(value: Sized) {
                    log += value.name
                }
            })
        }
    }

    interface SinkInletProxy {
        val inlet: Use<Propagate<Sized>>
    }

    private fun runScenario(policy: AttentionPolicy?): List<String> {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler(), attention = policy)
        val log = mutableListOf<String>()
        val a = SinkCell(log)
        val b = SinkCell(log)
        val manage = host.managementInlet.call
        manage.spawn(a)
        manage.spawn(b)
        controller.runToIdle()

        fun send(target: CellRef, payload: Sized) =
            (HostedCellProxy.create(target, host, SinkInletProxy::class.java) as SinkInletProxy)
                .inlet.call.propagate(payload)

        // stage everything before any dispatch runs: band selection must
        // reorder cells, not messages
        send(b.ref, Sized("b-small", 0.1))
        send(a.ref, Sized("a-big", 0.9))
        controller.runToIdle()

        // second round flips the sizes: if the first round's boost leaked
        // past its queue, "a" would still win
        send(a.ref, Sized("a-small", 0.1))
        send(b.ref, Sized("b-big", 0.9))
        controller.runToIdle()

        return log
    }

    @Test
    fun `bigger staged magnitude dispatches first and the boost dies with the queue`() {
        assertEquals(
            listOf("a-big", "b-small", "b-big", "a-small"),
            runScenario(AttentionPolicy(magnitudeBands = AttentionPolicy.QUANTIZE)),
        )
    }

    @Test
    fun `without the policy the order is exactly FIFO`() {
        assertEquals(
            listOf("b-small", "a-big", "a-small", "b-big"),
            runScenario(null),
        )
    }

    @Test
    fun `with attention but no magnitude mapping the order is still FIFO`() {
        assertEquals(
            listOf("b-small", "a-big", "a-small", "b-big"),
            runScenario(AttentionPolicy()),
        )
    }
}
