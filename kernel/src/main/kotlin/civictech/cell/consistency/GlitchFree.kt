package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellContext
import civictech.cell.CellError
import civictech.cell.CellRef
import civictech.cell.ErrorReporting
import civictech.cell.Timestamp
import civictech.cell.data.Propagate
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.PullOnOpen
import civictech.cell.port.registerPort
import civictech.cell.proxy.Invocation
import java.util.*

/**
 * A glitch-free join's frontier condition was violated: a contribution was lost
 * for good (dead-lettered) and the join advanced past it rather than waiting
 * forever (spec 20/22, 30/31 rule 5, decided in 93 I-18). Surfaced on
 * [GlitchFreeCell.errorOutlet] as the [CellError.cause].
 */
class GlitchViolation(message: String) : Exception(message)

/**
 * PN-12 structural marker (`Manifest.GLITCH_FREE`): the cell surfaces one aligned
 * wave per completeness step (an ALIGN-tier `WaveFrontier` on an inlet). KSP folds
 * it into [civictech.gen.wire.CellDescriptor.manifest] and stamps
 * `WaveParticipation.WAVED` onto the cell's outlets. A pure marker — no methods,
 * no new annotation.
 */
interface GlitchFree

/**
 * Opt-in glitch-freedom wrapper (spec 20/22): buffers per-wave inputs on [inlet]
 * until the wave's edge set is complete, then replays the wave's invocations to
 * [outlet] as one consistent group, each under its own context.
 *
 * Sugar over [WaveFrontier] (CP-A4): the cell is a bare inlet→outlet
 * pass-through whose [inlet] carries the frontier as its
 * [FanInlet.frontierPolicy]. Any plain cell can opt into the same completeness
 * gate by installing a [WaveFrontier] on one of its own inlets — this cell just
 * packages the common case (a whole-cell fan-in join with a matching outlet).
 *
 * Eager cell (C-7): serves in init, usable unhosted; safe without onActivate.
 */
class GlitchFreeCell<Api : Any>(
    clazz: Class<Api>,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
    mode: WaveMode = WaveMode.WAIT,
) : Cell, ErrorReporting, GlitchFree {

    /**
     * Recoverable-stall interaction (spec 34 decision 3): WAIT holds incomplete
     * waves until parked upstream traffic replays (park-not-drop makes that
     * correct, latency-unbounded); DEGRADE removes recoverably-stalled edges from
     * the wave frontier and restores them on resume, passing replayed stale waves
     * through as late catch-up. Terminal stalls always RE-SCOPE regardless of
     * mode — WAIT/DEGRADE only ever govern recoverable causes.
     */
    enum class WaveMode { WAIT, DEGRADE }

    val inlet = registerPort("inlet", FanInlet(clazz))
    val outlet = registerPort("outlet", FanOutlet(clazz))
    override val errorOutlet = registerPort("errorOutlet", FanOutlet.create<Propagate<CellError>>())

    private val frontier = WaveFrontier(mode) { violation ->
        errorOutlet.call.propagate(CellError(ref, violation))
    }

    init {
        // release target: a wave the frontier admits flows straight to the outlet,
        // each replayed invocation re-stamped reactively under its own context.
        inlet.serve(outlet.call)
        // PN-9: the sugar installs the ALIGN frontier + the ADMIT-slot PullOnOpen
        // together — pull-on-open was welded inside WaveFrontier before; the
        // emitted StateRequest sequence is identical (one StateRequest per inlink,
        // now issued from the link-lifecycle multicast rather than the frontier's
        // EdgeOpen handler). Frontier first so its edge is tracked before pull.
        inlet.install(frontier)
        inlet.install(PullOnOpen())
    }

    /**
     * Opt into the cross-replica settlement read (E3.4): waves settle only once
     * every ORIGIN tag [originTags] extracts from a buffered payload is
     * [ReplicaFrontier.completeAt] on [replicaFrontier] — a join over *replicas*
     * of one logical source no longer treats its own replica's delivery as
     * completeness. The owner must poke [recheck] when the merged watermark
     * advances (peer watermark gossip is invisible to this inlet's events).
     */
    fun useReplicaFrontier(
        replicaFrontier: ReplicaFrontier,
        originTags: (Invocation) -> Collection<Timestamp>,
        originKeys: (Invocation) -> Map<Any?, Collection<Timestamp>> = { emptyMap() },
    ) {
        frontier.installReplicaGate(WaveFrontier.ReplicaGate(replicaFrontier, originTags, originKeys))
    }

    /**
     * Declare a **single arm** replica-fed (E3.4): only the inlinks from
     * [fromOutlet] settle on [replicaFrontier]; every sibling arm keeps the
     * ordinary cross-inlink frontier, so a local fan-in diamond on this same cell
     * stays glitch-free. Use this instead of [useReplicaFrontier] whenever the
     * cell mixes replica-fed and local inlinks.
     */
    fun markReplicaFed(
        fromOutlet: civictech.cell.port.PortRef,
        replicaFrontier: ReplicaFrontier,
        originTags: (Invocation) -> Collection<Timestamp>,
        originKeys: (Invocation) -> Map<Any?, Collection<Timestamp>> = { emptyMap() },
    ) {
        frontier.markReplicaFed(fromOutlet, WaveFrontier.ReplicaGate(replicaFrontier, originTags, originKeys))
    }

    /** Re-run settlement after the merged replica watermark advanced (E3.4). */
    fun recheck() = frontier.recheck()

    override fun onDeactivate(ctx: CellContext) {
        frontier.reset()
    }

    companion object {
        inline fun <reified Api : Any> create(
            mode: WaveMode = WaveMode.WAIT,
        ): GlitchFreeCell<Api> = GlitchFreeCell(Api::class.java, mode = mode)
    }
}
