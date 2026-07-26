package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.MergeablePayload
import civictech.cell.link.Linked
import civictech.cell.port.Port
import civictech.cell.port.PortRegistry
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation

/**
 * Intake closed/saturated gating (spec 33, G-5), coalescing, and saturation
 * announce, extracted from [ManagedHost] (RS-8.3).
 *
 * Shares [dataLock] with the host rather than owning an independent lock —
 * exactly the [AttentionScheduler]/[HostDurability] pattern from RS-8.1/8.2:
 * [onIntakeAvailable]'s check-and-register and [coalesce]/[checkSaturationOnAccept]
 * (called from inside `ManagedHost.enqueueHostedInvocation`'s two
 * `synchronized(dataLock)` blocks) all synchronize on the SAME monitor as
 * [AttentionScheduler]'s queue state, and [lowWaterCheck] runs from *inside*
 * `AttentionScheduler.dispatchOne`'s own dequeue critical section (the
 * `intakeLowWaterCheck` callback wiring stays on `ManagedHost`, unchanged
 * from RS-8.1, to avoid a construction-order cycle between this class and
 * `AttentionScheduler` — see [ManagedHost]'s `intakeLowWaterCheck`).
 *
 * [dataQueuedCount]/[dataQueueFor] read [AttentionScheduler]'s queues via
 * narrow accessors (`attentionScheduler::dataQueuedCount`, a queue-by-ref
 * lookup) rather than a back-reference to the scheduler — `coalesce` mutates
 * the SAME live `ArrayDeque` instance the scheduler dispatches from, exactly
 * as before this extraction.
 */
internal class IntakeControl(
    private val dataLock: Any,
    private val intakeBound: IntakeBound?,
    private val cellsView: () -> Map<CellRef, Cell>,
    private val dataQueuedCount: () -> Int,
    private val dataQueueFor: (CellRef) -> ArrayDeque<Pair<Long, HostedPortInvocation>>?,
) {

    /**
     * Closable intake (spec 33, G-5): while closed, data and router sends fail
     * fast with [IntakeClosedException] — the sender's re-resolution signal.
     * Management stays open (a closed host must remain administrable).
     */
    @Volatile
    var intakeState = IntakeState.OPEN
        private set

    private var saturationOrigin: Pair<CellRef, String>? = null

    private val lowWaterListeners = mutableListOf<() -> Unit>()

    fun onIntakeAvailable(listener: () -> Unit) {
        val runNow = synchronized(dataLock) {
            if (intakeState == IntakeState.SATURATED) {
                lowWaterListeners += listener
                false
            } else true
        }
        if (runNow) listener()
    }

    fun closeIntake() {
        intakeState = IntakeState.CLOSED
    }

    fun openIntake() {
        intakeState = IntakeState.OPEN
    }

    /** Caller holds dataLock. Same source+wave slot preserves wave identity and source FIFO. */
    fun coalesce(incoming: HostedPortInvocation): Boolean {
        val payload = incoming.invocation.args.singleOrNull() as? MergeablePayload ?: return false
        val context = incoming.invocation.context ?: return false
        val queue = dataQueueFor(incoming.cellRef) ?: return false
        val index = queue.indexOfLast { (_, old) ->
            old.portName == incoming.portName &&
                old.invocation.methodId == incoming.invocation.methodId &&
                old.invocation.context?.timestamp == context.timestamp &&
                old.invocation.context?.sourcePort == context.sourcePort &&
                old.invocation.args.singleOrNull() is MergeablePayload
        }
        if (index < 0) return false
        val entries = queue.toMutableList()
        val (sequence, old) = entries[index]
        val merged = (old.invocation.args.single() as MergeablePayload).mergeWith(payload)
        entries[index] = sequence to old.copy(invocation = old.invocation.copy(args = listOf(merged)))
        queue.clear()
        queue.addAll(entries)
        return true
    }

    /**
     * Caller holds dataLock — `ManagedHost.enqueueHostedInvocation`'s second
     * `synchronized(dataLock)` block, right after staging, exactly where this
     * check ran inline before the extraction.
     */
    fun checkSaturationOnAccept(hostedInvocation: HostedPortInvocation, isManagement: Boolean) {
        intakeBound?.let { bound ->
            if (!isManagement && dataQueuedCount() >= bound.highWater) {
                if (intakeState != IntakeState.SATURATED) {
                    intakeState = IntakeState.SATURATED
                    saturationOrigin = hostedInvocation.cellRef to hostedInvocation.portName
                    announceSaturation(true)
                }
            }
        }
    }

    /**
     * Runs inside the caller's `dataLock` critical section — `ManagedHost`
     * wires this in as [AttentionScheduler]'s `intakeLowWaterCheck` callback,
     * invoked from `AttentionScheduler.dispatchOne`'s own dequeue critical
     * section (RS-8.1), preserving the original atomicity of the low-water
     * intake transition.
     */
    fun lowWaterCheck(): List<() -> Unit> {
        intakeBound?.let { bound ->
            if (intakeState == IntakeState.SATURATED && dataQueuedCount() <= bound.lowWater) {
                intakeState = IntakeState.OPEN
                announceSaturation(false)
                saturationOrigin = null
                val listeners = lowWaterListeners.toList()
                lowWaterListeners.clear()
                return listeners
            }
        }
        return emptyList()
    }

    /** Emits the host intake state on every inbound data edge; G-36 relays it producer-ward. */
    private fun announceSaturation(asserted: Boolean) {
        val (cellRef, portName) = saturationOrigin ?: return
        val port = cellsView()[cellRef]?.let { PortRegistry.of(it)[portName] } as? Linked ?: return
        port.linking.links.forEach { link ->
            if (link.toPort === port) {
                Protocols.sendUpstream(
                    link,
                    Protocols.Saturation,
                    SaturationSignal((port as Port).ref, asserted),
                )
            }
        }
    }
}
