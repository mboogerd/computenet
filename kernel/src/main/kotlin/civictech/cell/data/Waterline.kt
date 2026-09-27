package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.control.absorbAck
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.*

/**
 * The event-time waterline of spec 24 §Lateness and waterlines (KE4.2): an
 * ordinary data cell (`[24-WL-03]`) that folds the per-source maxima of an
 * inlet's event times into one monotone floor. It is **not** [WatermarkCell],
 * the delivered-counter lattice of spec 40/42 over (replica slot, sourceId) —
 * the two share a word stem and nothing else.
 *
 * - **Floor** (`[24-WL-02]`): the running maximum of the **candidate**, which
 *   is `min` over contributing sources of (max observed
 *   [Windows.Lateness.timeFn] − [Windows.Lateness.lateness]), keyed on the
 *   arriving *wave's* `sourceId`. Before any source contributes the floor is
 *   the identity, reported as `null`, and nothing is emitted.
 * - **Monotone, effective-only** (`[24-WL-03]`): the floor never falls — a new
 *   source joining with a low maximum leaves it where it is — and a delta that
 *   does not strictly raise it emits nothing and absorb-acks instead (22
 *   §Interaction "Waterline floor (24)", CP-A3). No wall clock is read.
 * - **A value, never a wave** (`[24-WL-04]`): a raising delta emits one
 *   [WaterlineDelta] through the ordinary transparent flow, so it rides the
 *   wave whose data advanced the floor (the outlet preserves the incoming
 *   context).
 * - **No wave position, no contribution** (sjqat-D1): a delivery under a null
 *   context or a catch-up baseline context (`MessageContext.baseline != null`)
 *   carries no wave's `sourceId` and contributes nothing. A missed
 *   contribution only holds the floor back — the safe direction.
 * - **Retire** (`[24-WL-15]`): [retire] drops a source's maximum.
 *
 * Delta-only (no `@Contract`, nothing through gen/), like [WatermarkCell].
 * [Stateful], not `BoundedStateful` (sjqat-D2): state is O(sources).
 *
 * [combine] is the combine over per-source maxima; it exists only so the
 * `[KE4-33]` test control can substitute `max`. Production passes nothing.
 */
class WaterlineCell<E> internal constructor(
    override val ref: CellRef,
    val lateness: Windows.Lateness<E>,
    private val combine: (Collection<Long>) -> Long,
) : Cell, Stateful {

    constructor(ref: CellRef = CellRef(UUID.randomUUID()), lateness: Windows.Lateness<E>) :
        this(ref, lateness, { it.min() })

    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<E>>>))
    val outlet = registerPort("outlet", FanOutlet.create<Propagate<WaterlineDelta>>())

    private val maxima: MutableMap<UUID, Long> = mutableMapOf()
    private var floor: Long? = null

    /** The current floor, or `null` while no source has contributed (`[24-WL-02]`'s identity). */
    fun floor(): Long? = floor

    /** Per-source maximum observed event time (a copy). */
    fun maxima(): Map<UUID, Long> = maxima.toMap()

    init {
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) = onDelta(value)
        })
        // [KE4-24]: a late consumer receives the current floor as its baseline.
        outlet.catchUpOnLinked { floor?.let { WaterlineDelta(it) } }
    }

    private fun onDelta(delta: SetDelta<E>) {
        val ctx = CurrentContext.get()
        if (ctx == null || ctx.baseline != null) return // sjqat-D1: no wave position
        val src = ctx.timestamp.sourceId
        // dels are ignored: a retraction never lowers an observed maximum.
        for (e in delta.adds.keys) {
            val t = lateness.timeFn(e)
            val prev = maxima[src]
            if (prev == null || t > prev) maxima[src] = t
        }
        if (!raiseTo(candidate())) outlet.absorbAck()
    }

    private fun candidate(): Long? =
        if (maxima.isEmpty()) null else combine(maxima.values) - lateness.lateness

    /** Set and emit [candidate] iff it strictly raises the floor; true iff it emitted. */
    private fun raiseTo(candidate: Long?): Boolean {
        if (candidate == null) return false
        val current = floor
        if (current != null && candidate <= current) return false
        floor = candidate
        outlet.call.propagate(WaterlineDelta(candidate))
        return true
    }

    /**
     * Retire [sourceId] (`[24-WL-15]`, sjqat-D4): drop its maximum and emit iff
     * the floor strictly rises; the floor is never lowered, and an emptied
     * source set leaves it as it is. Called outside any wave, the outlet mints
     * a fresh one. Callers (EdgeClose, ReBaselineNotice) and churn semantics
     * are KE4.4's (computenet-6gkou).
     */
    fun retire(sourceId: UUID) {
        if (maxima.remove(sourceId) == null) return
        raiseTo(candidate())
    }

    override fun snapshot(): Serializable =
        HashMap(mapOf<String, Serializable?>("maxima" to HashMap(maxima), "floor" to floor))

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val map = state as Map<String, Serializable?>
        maxima.clear()
        maxima.putAll(map.getValue("maxima") as Map<UUID, Long>)
        floor = map["floor"] as Long?
    }
}
