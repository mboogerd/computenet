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
     *
     * T04 finding 1 (ABBA deadlock): returns the announce as a **deferred
     * action** instead of running it here. [Protocols.sendUpstream] runs
     * registered handlers and a hop-by-hop relay traversal of the upstream
     * graph — reaching, e.g., another host's `enqueueHostedInvocation` and
     * *its* `dataLock` — so it must never run while this host's `dataLock`
     * is held. The caller invokes the returned action after releasing the
     * lock, mirroring [lowWaterCheck]'s existing listener-deferral pattern.
     */
    fun checkSaturationOnAccept(hostedInvocation: HostedPortInvocation, isManagement: Boolean): (() -> Unit)? {
        intakeBound?.let { bound ->
            if (!isManagement && dataQueuedCount() >= bound.highWater) {
                if (intakeState != IntakeState.SATURATED) {
                    intakeState = IntakeState.SATURATED
                    val origin = hostedInvocation.cellRef to hostedInvocation.portName
                    saturationOrigin = origin
                    return { announceSaturation(true, origin) }
                }
            }
        }
        return null
    }

    /**
     * Runs inside the caller's `dataLock` critical section — `ManagedHost`
     * wires this in as [AttentionScheduler]'s `intakeLowWaterCheck` callback,
     * invoked from `AttentionScheduler.dispatchOne`'s own dequeue critical
     * section (RS-8.1), preserving the original atomicity of the low-water
     * intake transition. T04 finding 1: the retraction announce is likewise
     * returned as a deferred action, in the SAME list [AttentionScheduler]
     * already fires post-unlock (`AttentionScheduler.kt`) — no caller change
     * needed there, only here.
     */
    fun lowWaterCheck(): List<() -> Unit> {
        intakeBound?.let { bound ->
            if (intakeState == IntakeState.SATURATED && dataQueuedCount() <= bound.lowWater) {
                intakeState = IntakeState.OPEN
                val origin = saturationOrigin
                saturationOrigin = null
                val listeners = lowWaterListeners.toList()
                lowWaterListeners.clear()
                val announce: List<() -> Unit> = origin?.let { listOf<() -> Unit>({ announceSaturation(false, it) }) } ?: emptyList()
                return announce + listeners
            }
        }
        return emptyList()
    }

    /**
     * Emits the host intake state on every inbound data edge; G-36 relays it
     * producer-ward. [origin] is passed explicitly (T04 finding 1) rather
     * than re-read from [saturationOrigin] — by the time a caller invokes
     * this deferred action, [saturationOrigin] may already have moved on to
     * a later transition.
     */
    private fun announceSaturation(asserted: Boolean, origin: Pair<CellRef, String>) {
        val (cellRef, portName) = origin
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
