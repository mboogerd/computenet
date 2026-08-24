package civictech.testkit.dst

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * *Which* order a [ReorderFault] is allowed to disturb ([CHA1-15]).
 *
 * The two are not two settings of one knob: one is the fault the epic asks for and the other
 * is its diverging control. Keeping them one class is what lets a suite run the *same* graph,
 * seed and window under both and attribute the difference to the scope alone.
 */
enum class ReorderScope {

    /**
     * **Per-link FIFO is preserved.** The buffer is released in arrival order, so the order of
     * frames *on the targeted edge* is exactly the order the graph produced them in; what
     * moves is when that burst lands relative to traffic on other edges and other hosts.
     *
     * This is the CHA1 reorder fault ([CHA1-14], [CHA1-15] default half): a real transport
     * reorders across links, and a link that reordered its own frames would be a broken
     * transport rather than a slow one.
     *
     * Intra-*host* (scheduler-band) reorder is a different thing again and is **deliberately
     * absent from CHA1** — epic computenet-umx risk 1, recommendation (a):
     * `SimulationController.SimulatedScheduler` is a private inner class and no seam for it
     * exists. Nothing here needs one: this scope permutes the interleaving of links, not of
     * tasks within a host.
     */
    CROSS_LINK,

    /**
     * **Per-link FIFO is broken on purpose**: the buffered window is released in a
     * seed-derived permutation ([CHA1-14]) of frames that all travelled the same edge.
     *
     * The explicit opt-in [CHA1-15] requires, and the [CHA1-62]/[CHA1-63] control for this
     * fault class: a graph that tolerates [CROSS_LINK] reorder is only *shown* to tolerate it
     * if some stronger reordering can be made to break it. A control that cannot be made to
     * fail proves nothing, so a suite asserting the default is safe must also assert this one
     * is not.
     */
    INTRA_LINK,
}

/**
 * Delay frames on one named edge and release them in bursts, reordering traffic across links
 * by default and within the link only on request ([CHA1-14], [CHA1-15]).
 *
 * ## Configuration
 *
 * @property edge the *one direction* this fault buffers. [DstWorld.edges] declares each
 *   direction of a wire separately, so a fault on `"a->b"` leaves `"b->a"` untouched — the
 *   same property that makes a one-way partition expressible without a direction parameter.
 * @property window how many frames the buffer may hold, i.e. the size of the permutation in
 *   [ReorderScope.INTRA_LINK] and the largest burst in [ReorderScope.CROSS_LINK].
 * @property activation the step window the buffering is live for. Frames outside it pass
 *   straight through — and the first frame at or after `until` also flushes whatever the
 *   buffer still holds, in arrival order (see below).
 * @property scope which order may be disturbed. See [ReorderScope].
 *
 * ## Two things this fault cannot promise, and how a suite checks them
 *
 * Both come from [FrameInterposer]'s known limit — **an edge is a transform, not a transport**,
 * so nothing but a later frame on the same edge can flush the buffer:
 *
 *  1. **Reorder without loss is a property of the workload, not of this fault.** Frames still
 *     held when traffic on the edge stops are stranded for the rest of the run, and a stranded
 *     frame has the same effect on the graph as a dropped one. A suite that means to test
 *     reordering rather than loss must keep the edge busy past `activation.until` and then
 *     assert [strandedFrames] is zero. It is exposed for exactly that assertion, and a suite
 *     that does not check it is testing an unknown mixture of reorder and drop.
 *  2. **The interposer is never deregistered**, unlike [PartitionFault]'s drop. Deregistering
 *     at the healing step would discard the buffer's contents at the moment they were due to
 *     be released, turning the end of every window into a silent drop.
 *
 * ## Determinism
 *
 * All randomness — the permutation and the burst thresholds — comes from
 * `world.rng("reorder:$id")`, so it derives from the run seed and from nothing else
 * ([CHA1-14], [CHA1-30]). Two faults with different ids draw independent streams, so adding a
 * second reorder fault to a plan does not change what the first one does.
 */
data class ReorderFault(
    override val id: String,
    val edge: String,
    val window: Int,
    val activation: StepWindow = StepWindow.ALWAYS,
    val scope: ReorderScope = ReorderScope.CROSS_LINK,
) : Fault {

    init {
        require(window >= 1) { "a reorder buffer holds at least one frame, got window=$window" }
    }

    /** Held for the whole run; see the KDoc's point 2 on why this is never closed. */
    private var installed: AutoCloseable? = null

    private var heldTotal: Int = 0
    private var releasedTotal: Int = 0

    override val targets: List<FaultTarget> get() = listOf(FaultTarget.Edge(edge))

    /** Frames buffered over the run. Equals [releasedFrames] iff nothing was stranded. */
    val heldFrames: Int get() = heldTotal

    /** Frames the buffer actually let go of, across every burst and every window flush. */
    val releasedFrames: Int get() = releasedTotal

    /**
     * Frames the buffer swallowed and never released — held when traffic on the edge stopped.
     *
     * **Not zero by construction.** A run whose workload ends inside the window strands its
     * whole buffer, and the graph then sees a drop rather than a reorder. Assert this is zero
     * in any suite whose claim is about ordering; see the KDoc's point 1.
     */
    val strandedFrames: Int get() = heldTotal - releasedTotal

    override fun describe(): String = when (scope) {
        ReorderScope.CROSS_LINK ->
            "reorder(edge=$edge, window=$window, $activation, CROSS_LINK): frames are released in " +
                "seed-sized bursts in arrival order — per-link FIFO preserved, cross-link order disturbed"

        ReorderScope.INTRA_LINK ->
            "reorder(edge=$edge, window=$window, $activation, INTRA_LINK): each full window is released " +
                "in a seed-derived permutation — per-link FIFO deliberately broken (opt-in control)"
    }

    override fun install(world: DstWorld) {
        heldTotal = 0
        releasedTotal = 0
        // Gated on `activation` *inside* the primitive, and never wrapped in
        // FrameInterposers.windowed: a stateful buffer that stops being applied at `until`
        // never releases what it holds. See FrameInterposers.reordering.
        installed = world.edges.intercept(
            edge,
            FrameInterposers.reordering(
                window = window,
                permute = scope == ReorderScope.INTRA_LINK,
                rng = world.rng("reorder:$id"),
                active = activation,
                onHold = {
                    heldTotal++
                    world.trace.fault(id, port = edge)
                },
                onRelease = { released -> releasedTotal += released },
            ),
        )
    }

    companion object {

        /**
         * Reorder across links only, preserving this edge's own FIFO ([CHA1-15] default).
         *
         * A diamond whose arms are on different hosts is reordered by putting one of these on
         * each arm's edge.
         */
        fun crossLink(
            id: String,
            edge: String,
            window: Int,
            from: Int = 0,
            until: Int = Int.MAX_VALUE,
        ): ReorderFault = ReorderFault(id, edge, window, StepWindow(from, until), ReorderScope.CROSS_LINK)

        /**
         * Permute this edge's own frames ([CHA1-15] opt-in) — the diverging control, not a
         * transport any real network models.
         */
        fun intraLink(
            id: String,
            edge: String,
            window: Int,
            from: Int = 0,
            until: Int = Int.MAX_VALUE,
        ): ReorderFault = ReorderFault(id, edge, window, StepWindow(from, until), ReorderScope.INTRA_LINK)

        /**
         * The artifact codec ([CHA1-31]), registered on first touch of this class.
         *
         * Registered here, in the fault's own file, so the class and its codec cannot drift
         * apart — and eagerly in a companion initialiser, because a codec that is only
         * registered when someone remembers to call something is a codec that is missing from
         * the one run that needed it.
         *
         * The residual limit is class initialisation: this registers when `ReorderFault` is
         * first loaded, which constructing one guarantees but *decoding* one does not. A JVM
         * whose only contact with this class is reading an artifact that names kind `reorder`
         * must touch the class first (`ReorderFault.CODEC`) or it sees the registry's "unknown
         * fault kind" error. Encoding is unaffected — a plan being written necessarily holds
         * instances. Fixing it properly means an eager registration list, which lives in
         * `DstArtifact.kt` and belongs to the rig core rather than to a fault class.
         *
         * Every parameter is a flat JSON primitive on purpose:
         * that is what makes `window`, `from` and `until` reachable by
         * `ReductionStrategies.numericParamToward`, which is the shrinker's only
         * parameter-reducing strategy ([CHA1-38]).
         */
        val CODEC: FaultCodec = FaultCodecs.register(
            kind = "reorder",
            owns = { it is ReorderFault },
            encode = { fault ->
                val f = fault as ReorderFault
                JsonObject(
                    mapOf(
                        "edge" to JsonPrimitive(f.edge),
                        "window" to JsonPrimitive(f.window),
                        "from" to JsonPrimitive(f.activation.from),
                        "until" to JsonPrimitive(f.activation.until),
                        "permuteWithinLink" to JsonPrimitive(f.scope == ReorderScope.INTRA_LINK),
                    ),
                )
            },
            decode = { id, params ->
                ReorderFault(
                    id = id,
                    edge = params.string("edge"),
                    window = params.getValue("window").jsonPrimitive.int,
                    activation = StepWindow(
                        params.getValue("from").jsonPrimitive.int,
                        params.getValue("until").jsonPrimitive.int,
                    ),
                    scope = if (params.getValue("permuteWithinLink").jsonPrimitive.boolean) {
                        ReorderScope.INTRA_LINK
                    } else {
                        ReorderScope.CROSS_LINK
                    },
                )
            },
        )
    }
}

/** A string parameter out of a codec's [JsonObject], with the field named when it is missing. */
internal fun JsonObject.string(param: String): String =
    (this[param] ?: throw IllegalArgumentException("fault params are missing \"$param\"; got ${keys.sorted()}"))
        .jsonPrimitive.content
