package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Stateful
import civictech.cell.control.absorbAck
import civictech.cell.control.relayAbsorbAcks
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.WaterlineDelta
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import civictech.cell.protocol.EdgeClose
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
 * - **Retirement** — three routes, each dropping a source's maximum from the
 *   minimum; the floor is never lowered by it (the candidate can only rise as
 *   the set shrinks, and an emptied set leaves the floor as it is):
 *   - `EdgeClose` (`[24-WL-12]`, `[KE4-22]`): when an inlet link closes, every
 *     `sourceId` whose deliveries arrived with `MessageContext.sourcePort ==
 *     link.from` is retired in one step, with at most one emission — except a
 *     source that has also arrived over another still-open link (a diamond:
 *     one `sourceId` reaching this inlet through two relays), which stays
 *     live until the last link carrying it closes, so the floor never passes
 *     the promise of a source that is still arriving. The
 *     link→source mapping relies on the emitting outlet stamping itself as
 *     `sourcePort`, which holds for **in-process** links (`FanOutlet`'s call
 *     proxy); whether a `:wire` bridge preserves `sourcePort == link.from` is
 *     not established here, so retirement-on-close is claimed for in-process
 *     links only. Registered through [FanInlet.onEdgeEvent] so it composes
 *     with a glitch-free policy on the same inlet.
 *   - `ReBaselineNotice` (`[24-WL-13]`, `[KE4-23]`): a delivery whose notice
 *     has `supersede = true` retires every superseded `sourceId` before its own
 *     adds fold under the fresh epoch's `sourceId`, one candidate evaluation
 *     riding that wave; `supersede = false` retires nothing.
 *   - Manual [retire] (`[24-WL-15]`): the management escape hatch.
 *
 *   A retirement raised by `EdgeClose` or [retire] is emitted **detached**: it
 *   runs under a null context, so the outlet mints a fresh wave under its own
 *   `sourceId` (the [WatermarkCell] republish shape) — an ordinary single-source
 *   wave, not a baseline. A retired source that contributes again is a fresh
 *   contributor under `[24-WL-20]`: below the floor it leaves the floor, emits
 *   nothing, and gates every later rise.
 * - **Idle-source residual** (`[24-WL-14]`): an open, linked source that stops
 *   emitting holds the floor at its promise (max − lateness) — and one that
 *   joined below the floor holds it where it is, with no advance at all —
 *   until it emits, its link closes, or it is retired. Frozen but correct;
 *   eviction and the memory bound stall meanwhile. Aging an idle source
 *   without a wall clock is research: `doc/spec/90-roadmap/95-research-plan.md`
 *   §R15.
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

    /** 6gkou.1-D1: which sources arrived over which upstream outlet — the key an `EdgeClose`'s `link.from` names. */
    private val sourcesByPort: MutableMap<PortRef, MutableSet<UUID>> = mutableMapOf()

    /** The current floor, or `null` while no source has contributed (`[24-WL-02]`'s identity). */
    fun floor(): Long? = floor

    /** Per-source maximum observed event time (a copy). */
    fun maxima(): Map<UUID, Long> = maxima.toMap()

    init {
        // WaterlineCell is a unary transparent hop for waves it does not see as
        // data. Preserve an upstream absorb-ack for the downstream waterline arm.
        inlet.relayAbsorbAcks()
        inlet.serve(object : Propagate<SetDelta<E>> {
            override fun propagate(value: SetDelta<E>) = onDelta(value)
        })
        // [24-WL-12]: retire what a closing link carried. EdgeOpen needs nothing.
        // A source still arriving over another open link (a diamond) stays live.
        inlet.onEdgeEvent { link, event ->
            if (event == EdgeClose) {
                val carried = sourcesByPort.remove(link.from).orEmpty()
                retireAll(carried.filter { id -> sourcesByPort.values.none { id in it } })
            }
        }
        // [KE4-24]: a late consumer receives the current floor as its baseline.
        outlet.catchUpOnLinked { floor?.let { WaterlineDelta(it) } }
    }

    private fun onDelta(delta: SetDelta<E>) {
        val ctx = CurrentContext.get()
        if (ctx == null || ctx.baseline != null) return // sjqat-D1: no wave position
        // [24-WL-13]: a superseding re-baseline retires the dead epochs first;
        // the combined step is one candidate evaluation riding this wave.
        ctx.reBaseline?.takeIf { it.supersede }?.supersedes?.forEach(::forget)
        val src = ctx.timestamp.sourceId
        // dels are ignored: a retraction never lowers an observed maximum.
        for (e in delta.adds.keys) {
            val t = lateness.timeFn(e)
            val prev = maxima[src]
            if (prev == null || t > prev) maxima[src] = t
        }
        if (src in maxima) sourcesByPort.getOrPut(ctx.sourcePort) { mutableSetOf() } += src
        if (!raiseTo(candidate())) outlet.absorbAck()
    }

    /** Drop [sourceId]'s maximum and its link bookkeeping; true iff it was contributing. */
    private fun forget(sourceId: UUID): Boolean {
        val it = sourcesByPort.values.iterator()
        while (it.hasNext()) {
            val set = it.next()
            set -= sourceId
            if (set.isEmpty()) it.remove()
        }
        return maxima.remove(sourceId) != null
    }

    /** Retire [sourceIds] in one step: one candidate evaluation, at most one detached emission (6gkou.1-D3). */
    private fun retireAll(sourceIds: Collection<UUID>) {
        var any = false
        for (id in sourceIds.toList()) any = forget(id) || any
        if (any) CurrentContext.with(null) { raiseTo(candidate()) }
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
     * Retire [sourceId] (`[24-WL-15]`, sjqat-D4): the manual route. Drop its
     * maximum and emit iff the floor strictly rises; the floor is never
     * lowered, and an emptied source set leaves it as it is. The emission is
     * detached — a fresh wave minted by the outlet, whatever context the
     * caller holds (6gkou.1-D3). The automatic routes (`EdgeClose`,
     * `ReBaselineNotice`) are described on the class.
     */
    fun retire(sourceId: UUID) = retireAll(listOf(sourceId))

    override fun snapshot(): Serializable =
        HashMap(
            mapOf<String, Serializable?>(
                "maxima" to HashMap(maxima),
                "floor" to floor,
                "sourcesByPort" to HashMap(sourcesByPort.mapValues { HashSet(it.value) }),
            )
        )

    @Suppress("UNCHECKED_CAST")
    override fun restore(state: Serializable) {
        val map = state as Map<String, Serializable?>
        maxima.clear()
        maxima.putAll(map.getValue("maxima") as Map<UUID, Long>)
        floor = map["floor"] as Long?
        sourcesByPort.clear()
        (map["sourcesByPort"] as Map<PortRef, Set<UUID>>?)?.forEach { (port, ids) ->
            sourcesByPort[port] = ids.toMutableSet()
        }
    }
}
