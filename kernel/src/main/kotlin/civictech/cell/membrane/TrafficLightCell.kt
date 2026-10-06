package civictech.cell.membrane

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CheckpointFrameSource
import civictech.cell.CheckpointReplayPosition
import civictech.cell.CheckpointReplayPositions
import civictech.cell.CheckpointStateSource
import civictech.cell.ReplayProvenance
import civictech.cell.ReplayScope
import civictech.cell.TagFrontier
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.Buffering
import civictech.cell.proxy.HostedPortInvocation
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
 * An internal checkpoint-only capability preserves the green/red bit when a
 * hosting graph explicitly binds the gate to a journal; it deliberately does
 * not publish the `Stateful`/`DURABLE` nature, so ordinary gates remain
 * journal-optional. Topology checkpoints retain and replay `TopoEvent.Promote`,
 * which is what completes a recovered promotion rather than the colour state
 * standing in for that event. Older checkpoints contain no colour entry and
 * therefore retain their original starts-red recovery behavior. A red gate
 * exposes its parked invocations as checkpoint frames: compaction carries them
 * after the snapshot instead of silently dropping exclusive payloads.
 */
class TrafficLightCell<T : Any>(
    private val clazz: Class<T>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell, TrafficLightApi<T>, CheckpointStateSource, CheckpointFrameSource {
    override val controlInlet = registerPort("controlInlet", FanInlet.create<TrafficLightControl>())
    override val dataInlet = registerPort("dataInlet", FanInlet(clazz))
    override val dataOutlet = registerPort("dataOutlet", FanOutlet(clazz))

    private var isStopped = true
    private data class ParkedInvocation(
        val invocation: Invocation,
        val replayFrontier: TagFrontier?,
        val replayOf: Any?,
    )

    private data class CheckpointState(
        val green: Boolean,
        val replayPositions: List<List<CheckpointReplayPosition>>,
    ) : Serializable

    private val buffer = ParkQueue<ParkedInvocation>()
    private val restoredReplayPositions = ArrayDeque<List<CheckpointReplayPosition>>()

    private fun park(invocation: Invocation) {
        val replayOf = ReplayProvenance.get()
        val checkpointPositions = restoredReplayPositions.pollFirst().orEmpty()
        if (replayOf != null) CheckpointReplayPositions.register(replayOf, checkpointPositions)
        buffer.park(
            ParkedInvocation(
                invocation = invocation,
                replayFrontier = ReplayScope.get(),
                replayOf = replayOf,
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

    override fun checkpointState(): Serializable {
        if (buffer.isEmpty()) return !isStopped
        return CheckpointState(
            green = !isStopped,
            replayPositions = buffer.snapshot().map { parked ->
                CheckpointReplayPositions.capture(
                    parked.replayOf,
                    parked.invocation.context?.timestamp,
                )
            },
        )
    }

    override fun restoreCheckpointState(state: Serializable) {
        check(buffer.isEmpty()) { "traffic light $ref restored over ${buffer.size} live parked invocation(s)" }
        restoredReplayPositions.clear()
        when (state) {
            is Boolean -> {
                if (state) setGreen() else setRed()
            }
            is CheckpointState -> {
                restoredReplayPositions.addAll(state.replayPositions)
                if (state.green) setGreen() else setRed()
            }
            else -> throw IllegalArgumentException(
                "traffic light $ref checkpoint state has unsupported type ${state.javaClass.name}",
            )
        }
    }

    override fun checkpointFrames(): List<HostedPortInvocation> = buffer.snapshot().map { parked ->
        HostedPortInvocation(
            cellRef = ref,
            portName = "dataInlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = parked.invocation,
            replayFrontier = parked.replayFrontier,
            replayOf = parked.replayOf,
        )
    }

    companion object {
        inline fun <reified T : Any> create(): TrafficLightCell<T> =
            TrafficLightCell(T::class.java)
    }
}
