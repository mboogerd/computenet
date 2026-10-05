package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.ReplayProvenance
import civictech.cell.ReplayScope
import civictech.cell.Stateful
import civictech.cell.TagFrontier
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.Buffering
import civictech.cell.proxy.Invocation
import civictech.cell.control.ParkQueue
import civictech.cell.proxy.Proxy
import civictech.gen.wire.Contract
import kotlinx.coroutines.runBlocking
import java.io.Serializable
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
 * The green/red bit is [Stateful] so a checkpoint taken after a completed
 * promotion does not erase its green COMMIT. Older checkpoints contain no
 * entry for this formerly-stateless cell and therefore retain their original
 * starts-red recovery behavior. A red gate with parked work refuses a
 * checkpoint rather than silently compacting away exclusive payloads.
 */
class TrafficLightCell<T : Any>(
    private val clazz: Class<T>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, TrafficLightApi<T>, Stateful {
    override val controlInlet = registerPort("controlInlet", FanInlet.create<TrafficLightControl>())
    override val dataInlet = registerPort("dataInlet", FanInlet(clazz))
    override val dataOutlet = registerPort("dataOutlet", FanOutlet(clazz))

    private var isStopped = true
    private data class ParkedInvocation(
        val invocation: Invocation,
        val replayFrontier: TagFrontier?,
        val replayOf: Any?,
    )

    private val buffer = ParkQueue<ParkedInvocation>()

    private fun park(invocation: Invocation) {
        buffer.park(
            ParkedInvocation(
                invocation = invocation,
                replayFrontier = ReplayScope.get(),
                replayOf = ReplayProvenance.get(),
            ),
        )
    }

    private fun setGreen() {
        if (!isStopped) return
        runBlocking {
            buffer.drain().forEach { parked ->
                ReplayScope.withSuspending(parked.replayFrontier) {
                    ReplayProvenance.withSuspending(parked.replayOf) {
                        parked.invocation.invoke(dataOutlet.call)
                    }
                }
            }
        }
        dataInlet.delegate(dataOutlet)
        isStopped = false
    }

    private fun setRed() {
        if (isStopped) return
        dataInlet.serve(Proxy.fromClass(clazz, Buffering(::park)))
        isStopped = true
    }

    init {
        controlInlet.serve(object : TrafficLightControl {
            override fun setGreen() = this@TrafficLightCell.setGreen()

            override fun setRed() = this@TrafficLightCell.setRed()
        })
        dataInlet.serve(Proxy.fromClass(clazz, Buffering(::park)))
    }

    override fun snapshot(): Serializable {
        check(buffer.isEmpty()) {
            "cannot checkpoint red traffic light $ref with ${buffer.size} parked invocation(s)"
        }
        return !isStopped
    }

    override fun restore(state: Serializable) {
        require(state is Boolean) { "traffic light $ref checkpoint state is not a Boolean: ${state.javaClass.name}" }
        if (state) setGreen() else setRed()
    }

    companion object {
        inline fun <reified T : Any> create(): TrafficLightCell<T> =
            TrafficLightCell(T::class.java)
    }
}
