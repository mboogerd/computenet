package civictech.cell.observe

import civictech.cell.Timestamp
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The outcome of a write-visibility handle (KE2 §5.5; spec 20/22 §The
 * observation frontier, `[22-OBS-01]`/`[22-OBS-02]`): the moment a sink's
 * per-source frontier reached a writer's wave.
 *
 * [wave] is **the sink's per-source frontier position at completion**, never
 * the handle's own wave: `wave.sourceId` equals the requested source and
 * `wave.counter` is at least the requested counter. A handle for `(s, 5)`
 * completed by the retirement of `(s, 7)` names `(s, 7)`.
 */
sealed interface Visibility {
    val wave: Timestamp
}

/** The frontier reached the write and a composite reflecting it was published. */
data class Visible(override val wave: Timestamp) : Visibility

/**
 * The frontier reached the write, but the wave that retired it changed no
 * view — it arrived only as absorb-acks or non-effective deltas at every
 * contributing arm — so no composite was published for it. The current
 * composite nevertheless reflects every wave up to [wave].
 */
data class VisibleVacuously(override val wave: Timestamp) : Visibility

/**
 * The frontier reached the write only after [droppedEdges] were excluded from
 * the wave's completeness condition — [AlignedCompositeCell] shrunk it, never
 * dropped it silently. Produced when the wave that retired the write was
 * released with a non-empty dropped set: a DEGRADE-mode suspension still in
 * effect at release (including one still disclosed as "behind" because its
 * edge has not yet caught its watermark up to the flushed high-water for its
 * source, `[22-OBS-01]`), a terminal re-scope, or an edge closing while the
 * wave was held. [droppedEdges] names every excluded edge — see
 * [AlignedCompositeCell] and [AlignedComposite.droppedEdges] for how a wave's
 * dropped set is assembled and disclosed.
 *
 * A handle for a wave published **before** the shrink is never retroactively
 * degraded: it completed [Visible] or [VisibleVacuously] when its own
 * (undropped) wave retired.
 */
data class VisibleDegraded(override val wave: Timestamp, val droppedEdges: Set<DroppedEdge>) : Visibility

/** One contributing edge dropped from a wave's completeness condition: the view it fed and its link. */
data class DroppedEdge(val view: String, val linkId: UUID)

/**
 * A handle that will never reach a [Visibility]: the sink stopped (or refused
 * to) witness its frontier before [wave] retired. Delivered as the handle's
 * exceptional completion, unwrapped (never inside a `CompletionException`).
 */
class VisibilityAbandoned(val reason: Reason, val wave: Timestamp) :
    Exception("write-visibility handle for $wave abandoned: $reason") {

    enum class Reason {
        /** The sink was closed (or was closed when the handle was requested). */
        SINK_CLOSED,

        /**
         * The host deactivated this sink — drain/shutdown, despawn, or a
         * supervised restart; the sink cannot tell these apart, so a writer
         * treats it as [SINK_CLOSED].
         */
        HOST_SHUTDOWN,

        /** Registering would have exceeded [FrontierWitness.maxOutstandingHandles]; nothing was registered. */
        BOUND_EXCEEDED,
    }
}

/**
 * A sink that witnesses a per-source frontier and can therefore say when a
 * write became visible through it. Implemented by the wave-aligned sink
 * ([AlignedCompositeCell]); a point-consistent sink ([CompositeSink]) has no
 * such frontier and offers no handle.
 */
interface FrontierWitness {

    /**
     * A handle that completes when this sink's frontier for `wave.sourceId`
     * reaches `wave.counter` — see [Visibility] for what the completed value
     * names — or completes exceptionally with [VisibilityAbandoned]. Callable
     * from any thread.
     *
     * A handle for a wave this sink never witnesses (the writer drove a cell
     * upstream of no arm, or an ack-less cell swallowed the wave) stays
     * outstanding until a later wave of the same source retires, the sink
     * closes, or the caller's own timeout — by design, not a defect.
     *
     * Completion runs on the JDK default async pool, never on the host
     * scheduler thread nor under the sink's lock, so a dependent registered
     * with plain `thenAccept` runs there too: a dependent that blocks occupies
     * one default-pool worker. A caller needing isolation uses
     * `thenAcceptAsync(fn, ownExecutor)`.
     *
     * That "runs there" has two exceptions, not one. First, a handle can come
     * back already complete — a closed sink (`SINK_CLOSED`), a wave already at
     * or behind the flushed frontier, or the outstanding-handle bound
     * exceeded (`BOUND_EXCEEDED`) — and a plain dependent registered on an
     * already-complete future runs synchronously on the *registering* thread,
     * per plain `CompletableFuture` semantics, never touching the async pool.
     * Second, a thread *waiting* on the handle with `get`/`join` may itself run
     * a still-pending plain dependent, because those calls help drain the
     * dependents once they see the result (`CompletableFuture`'s "any other
     * caller of a completion method"). So never block on a handle from the
     * scheduler thread — that would stall the host anyway — and poll
     * `isDone`/`getNow` where a waiter must not run dependents
     * (computenet-517q4, computenet-kwhx8).
     */
    fun visibilityOf(wave: Timestamp): CompletableFuture<Visibility>

    /** Handles registered and not yet completed; readable without blocking a cell's execution context. */
    val outstandingHandles: Int

    /** The bound on [outstandingHandles]; a registration past it is refused with `BOUND_EXCEEDED`. */
    val maxOutstandingHandles: Int
}
