package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.Buffering
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.ParkQueue
import civictech.cell.proxy.Proxy
import civictech.gen.wire.Contract
import java.util.*

@Contract
interface TrafficLightControl {
    fun setGreen()
    fun setRed()
}

interface TrafficLightApi<T> {
    val controlInlet: Use<TrafficLightControl>
    val dataInlet: Use<T>
    val dataOutlet: Subscribe<T>
}

/**
 * Boundary suspension as a standard membrane behavior (spec 33/11): **red**
 * serves a [Buffering] proxy — invocations park in order, contexts riding
 * along; **green** replays the buffer downstream, then delegates the inlet to
 * the outlet — the cell removes itself from the message path entirely (zero
 * fast-path cost). The same Buffering primitive backs location-level parking
 * in the LocationRegistry; this is its port-granular form.
 *
 * Eager cell (C-7): serves in `init` so it composes host-free; starts red.
 */
class TrafficLightCell<T : Any>(
    clazz: Class<T>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, TrafficLightApi<T> {
    override val controlInlet = registerPort("controlInlet", FanInlet.create<TrafficLightControl>())
    override val dataInlet = registerPort("dataInlet", FanInlet(clazz))
    override val dataOutlet = registerPort("dataOutlet", FanOutlet(clazz))

    private var isStopped = true
    private val buffer = ParkQueue<Invocation>()

    init {
        controlInlet.serve(object : TrafficLightControl {
            override fun setGreen() {
                if (!isStopped) return
                buffer.drain().forEach { invocation ->
                    invocation.invoke(dataOutlet.call)
                }
                dataInlet.delegate(dataOutlet)
                isStopped = false
            }

            override fun setRed() {
                if (isStopped) return
                dataInlet.serve(Proxy.fromClass(clazz, Buffering(buffer)))
                isStopped = true
            }
        })
        dataInlet.serve(Proxy.fromClass(clazz, Buffering(buffer)))
    }

    companion object {
        inline fun <reified T : Any> create(): TrafficLightCell<T> =
            TrafficLightCell(T::class.java)
    }
}
