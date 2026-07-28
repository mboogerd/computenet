package civictech.cell.control

import civictech.cell.CellRef
import civictech.cell.proxy.HostedPortInvocation
import java.util.LinkedHashMap

/**
 * Data-plane dispatch (spec 34, M6.3/M17), extracted from [civictech.cell.host.ManagedHost]
 * (RS-8.1): messages stage in per-cell FIFO queues; each staged message submits
 * one dispatcher task at data priority, and each dispatch picks the next cell
 * by attention band. Per-cell FIFO (a superset of per-link FIFO, spec 31 rule 3)
 * holds because band selection happens BETWEEN cells, never within one — and the
 * one-task-per-message shape keeps drain's phase 2 (priority 30) behind every
 * accepted message.
 *
 * Shares [dataLock] with the owning host rather than owning an independent lock:
 * several host-only critical sections (intake saturation transitions, teardown
 * of attention-parked traffic) synchronize on the SAME monitor around direct
 * reads/writes of this class's state ([dataQueues], [attentionParked]) — using
 * one shared lock object keeps those critical sections byte-identical to the
 * pre-extraction code, where everything lived behind one `synchronized(dataLock)`.
 *
 * Collaborators are passed as narrow callbacks/refs rather than a back-reference
 * to the whole host: [bandOf] and [suspensionRegionOf] read host-only state
 * (`cells`, link topology) this class has no business holding; [notifyParked]/
 * [notifyResumed] emit the typed stall notices (host-owned protocol traffic);
 * [intakeLowWaterCheck] runs the host's intake-saturation bookkeeping from
 * *inside* the same critical section a dequeue happens in (preserving the
 * original atomicity), returning any low-water listeners to fire once the lock
 * is released; [deliver] performs the actual per-invocation dispatch; [submit]
 * re-enters the host's intake for parked-then-unparked replay.
 */
class AttentionScheduler(
    /** Attention → resources mapping (spec 34, M6.3); null = pre-M6 FIFO scheduling. */
    private val attention: AttentionPolicy?,
    /** The host's `dataLock` monitor — shared, not owned; see class doc. */
    private val dataLock: Any,
    private val bandOf: (CellRef) -> AttentionBand,
    private val suspensionRegionOf: (CellRef) -> Set<CellRef>?,
    private val notifyParked: (CellRef) -> Unit,
    private val notifyResumed: (CellRef) -> Unit,
    /** Runs while [dataLock] is held, right after a dequeue; returns listeners to fire post-unlock. */
    private val intakeLowWaterCheck: () -> List<() -> Unit>,
    private val deliver: suspend (HostedPortInvocation) -> Unit,
    /** Re-enters the host's intake (`enqueueHostedInvocation`) for unparked replay. */
    private val submit: (HostedPortInvocation) -> Unit,
) {

    /** Callers hold [dataLock]. Visible to the host for the not-yet-moved `coalesce`/teardown paths. */
    internal val dataQueues = LinkedHashMap<CellRef, ArrayDeque<Pair<Long, HostedPortInvocation>>>()
    private var dataSequence = 0L
    var dispatchStep: Long = 0L
        private set
    private var strideCount = 0
    private val lastAttended = mutableMapOf<CellRef, Long>()

    /** Attention-parked traffic (spec 34 decision 2): parked, never dropped. Callers hold [dataLock]. */
    internal val attentionParked = mutableMapOf<CellRef, MutableList<HostedPortInvocation>>()

    /**
     * Magnitude-band boost (spec 34, M17): per cell, the band its largest
     * staged [Magnitude] payload maps to under the policy's `magnitudeBands`.
     * Lifetime is the pending queue — cleared when it drains (or parks), so a
     * despawned cell's entry drains out through ordinary dead-letter dispatch.
     */
    private val magnitudeBoost = mutableMapOf<CellRef, AttentionBand>()

    fun dataQueuedCount(): Int = dataQueues.values.sumOf { queue ->
        queue.count { (_, invocation) -> invocation.type != HostedPortInvocation.Type.PORT_MANAGEMENT }
    }

    /** Callers hold dataLock. Same source+wave slot preserves wave identity and source FIFO. */
    fun stage(hostedInvocation: HostedPortInvocation) {
        val cellRef = hostedInvocation.cellRef
        attentionParked[cellRef]?.let {
            it += hostedInvocation // parked cells accumulate in arrival order
            return // no boost: magnitude is urgency, interest owns park/resume
        }
        dataQueues.getOrPut(cellRef) { ArrayDeque() }.addLast(++dataSequence to hostedInvocation)
        attention?.magnitudeBands?.let { bands ->
            hostedInvocation.invocation.args.filterIsInstance<Magnitude>()
                .maxOfOrNull { it.size() }
                ?.let { magnitudeBoost.merge(cellRef, bands(it), ::maxOf) }
        }
    }

    /**
     * Run (at most) one staged message: highest band first, oldest head as
     * tiebreaker; the stride floor (spec 34 decision 2) bounds how long
     * lower-band work can be passed over; a cell at NONE past the policy
     * window parks instead of running (park, never drop).
     */
    suspend fun dispatchOne() {
        var toPark: Set<CellRef>? = null
        var listeners: List<() -> Unit> = emptyList()
        val next: HostedPortInvocation? = synchronized(dataLock) {
            if (dataQueues.isEmpty()) return
            dispatchStep++
            // effective band = interest ⊔ staged urgency (spec 34, M17): the
            // boost is a data-region sub-priority — it can never lift a task
            // above the router/management bands, and the stride floor below
            // bounds what it can starve
            val bands = dataQueues.keys.associateWith {
                maxOf(bandOf(it), magnitudeBoost[it] ?: AttentionBand.NONE)
            }
            bands.forEach { (cellRef, band) ->
                if (band > AttentionBand.NONE) lastAttended[cellRef] = dispatchStep
            }
            val maxBand = bands.values.max()
            val lower = bands.filterValues { it < maxBand }.keys
            val stride = attention?.stride ?: Int.MAX_VALUE
            val pickFrom: Collection<CellRef> = if (strideCount >= stride && lower.isNotEmpty()) {
                strideCount = 0
                lower
            } else {
                if (lower.isNotEmpty()) strideCount++ else strideCount = 0
                bands.filterValues { it == maxBand }.keys
            }
            val pick = pickFrom.minByOrNull { dataQueues.getValue(it).first().first }!!
            val suspendAfter = attention?.suspendAfter
            val region = if (suspendAfter != null && bands.getValue(pick) == AttentionBand.NONE &&
                dispatchStep - (lastAttended[pick] ?: 0L) > suspendAfter
            ) suspensionRegionOf(pick) else null
            if (region != null) {
                toPark = region
                null
            } else {
                // runnable — or its region vetoed suspension (spec 34 decision 3):
                // a vetoed cell keeps running, it does not strand its queue
                val queue = dataQueues.getValue(pick)
                val head = queue.removeFirst().second
                if (queue.isEmpty()) {
                    dataQueues.remove(pick)
                    magnitudeBoost.remove(pick) // boost lifetime = the pending queue
                }
                listeners = intakeLowWaterCheck()
                head
            }
        }
        listeners.forEach { it() }
        toPark?.forEach { parkForAttention(it) }
        next?.let { deliver(it) }
    }

    fun parkForAttention(cellRef: CellRef) {
        synchronized(dataLock) {
            val queue = dataQueues.remove(cellRef) ?: ArrayDeque()
            magnitudeBoost.remove(cellRef) // re-staged on unpark replay
            attentionParked[cellRef] = queue.map { it.second }.toMutableList()
        }
        notifyParked(cellRef)
    }

    fun unparkForAttention(cellRef: CellRef) {
        val parked = synchronized(dataLock) {
            attentionParked.remove(cellRef)?.also { lastAttended[cellRef] = dispatchStep }
        } ?: return
        notifyResumed(cellRef)
        parked.forEach { submit(it) }
    }
}
