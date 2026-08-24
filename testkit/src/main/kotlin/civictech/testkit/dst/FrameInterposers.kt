package civictech.testkit.dst

/**
 * A half-open range of controller step indices, `[from, until)` — the activation window every
 * windowed fault is configured with ([CHA1-02]).
 *
 * Half-open on purpose: `until` is the step at which the fault **heals**, so two windows
 * `0..10` and `10..20` are adjacent with no overlapping step and no gap, and "partition until
 * step N" reads the same way in the configuration, in the report and in the assertion.
 *
 * There is no wall clock here and there must never be one: [contains] takes the step index the
 * rig is currently driving, which is the only clock [Fault.onStep] and [FrameInterposer] are
 * given.
 */
data class StepWindow(val from: Int, val until: Int = Int.MAX_VALUE) {
    init {
        require(from >= 0) { "a step window starts at a step index, got from=$from" }
        require(until > from) { "an empty window fires nothing — got [$from, $until)" }
    }

    fun contains(step: Int): Boolean = step in from until until

    /** True once the window has closed, i.e. at and after the healing step. */
    fun healedAt(step: Int): Boolean = step >= until

    override fun toString(): String =
        if (until == Int.MAX_VALUE) "steps $from..(end)" else "steps $from..<$until"

    companion object {
        /** Open at the first step and never healing — a fault that is on for the whole run. */
        val ALWAYS: StepWindow = StepWindow(0, Int.MAX_VALUE)
    }
}

/**
 * The composable building blocks every frame-plane fault is assembled from ([CHA1-10]).
 *
 * A [FrameInterposer] is a *transform*: it is handed one frame and the step it is in flight
 * during, and returns the frames to deliver in its place. Everything here is a small total
 * function of that shape, so a fault class is configuration plus one call into this object
 * rather than a hand-rolled lambda per fault.
 *
 * **This file is shared, and it is meant to grow.** `PartitionFault` (computenet-umx.3.2)
 * contributed [pass], [drop], [windowed], [tracing], [chain] and [then]; the reorder and
 * duplicate faults (computenet-umx.3.3) extend the same object with their own primitives.
 * Two rules keep that extension safe for whoever comes second:
 *
 *  - **Every primitive is a pure factory returning a fresh interposer.** Anything stateful —
 *    a delay buffer, a duplication counter — keeps its state inside the returned instance, so
 *    two edges configured from the same factory call cannot share it.
 *  - **Composition is [chain] / [then], and only that.** A new primitive never needs to know
 *    what else is on the edge; `Edge.intercept` already chains registrations, and [chain]
 *    exists for the case where one fault wants several stages under one registration.
 *
 * The seam's known limit is [FrameInterposer]'s and is repeated here because it constrains
 * what a primitive may promise: **an edge is a transform, not a transport**. A held frame can
 * only leave a buffer when another frame traverses the same edge; nothing — not a step hook,
 * not a fault's `onStep` — can flush one. A primitive that holds frames must release on a
 * condition traffic itself produces (a full window, a following frame), never on a step
 * deadline, and must say in its own KDoc what happens to frames still held when traffic stops.
 */
object FrameInterposers {

    /** Deliver every frame unchanged. The identity of [then] and the default of [windowed]. */
    val pass: FrameInterposer = FrameInterposer { frame, _ -> listOf(frame) }

    /**
     * Destroy every frame handed to it, and call [onDrop] once per destroyed frame.
     *
     * **Destroyed, not delayed**: nothing replays a dropped frame, at heal or ever. That is
     * the whole difference between [PartitionMode.DROP] and [PartitionMode.PARK] ([CHA1-12]),
     * and it is what makes the drop control diverge ([CHA1-63]).
     */
    fun drop(onDrop: (ByteArray) -> Unit = {}): FrameInterposer =
        FrameInterposer { frame, _ ->
            onDrop(frame)
            emptyList()
        }

    /**
     * Apply [inside] to frames in flight during [window] and [outside] to every other frame.
     *
     * The window is evaluated per frame against the step it is in flight during, so a fault
     * needs no `onStep` bookkeeping to open and close: registering this interposer once at
     * install is enough, and a fault that additionally deregisters at [StepWindow.until] is
     * only tidying up its own cost and its `Edge.intercepted` reading.
     */
    fun windowed(
        window: StepWindow,
        inside: FrameInterposer,
        outside: FrameInterposer = pass,
    ): FrameInterposer = FrameInterposer { frame, step ->
        if (window.contains(step)) inside.apply(frame, step) else outside.apply(frame, step)
    }

    /**
     * Record a fault firing on [edge] once per frame [inner] is *applied to*, then apply it.
     *
     * Traces before delegating, so a frame that [inner] destroys is still counted: a drop that
     * fired is exactly a frame that did not arrive, and a fault whose only effect is deletion
     * would otherwise report itself inert ([CHA1-24]). One call, so a fault cannot be counted
     * without being traced or traced without being counted — see [TraceSink.fault].
     */
    fun tracing(
        world: DstWorld,
        faultId: String,
        edge: String,
        inner: FrameInterposer,
    ): FrameInterposer = FrameInterposer { frame, step ->
        world.trace.fault(faultId, port = edge)
        inner.apply(frame, step)
    }

    /**
     * Run [stages] in order under one registration, innermost first — each stage is handed
     * every frame the previous stage produced, so a duplicate upstream becomes two frames
     * downstream, and a drop upstream leaves the rest of the chain nothing to do.
     *
     * The same rule `Edge.intercept` chains separate registrations by; this is for a single
     * fault that is naturally several stages.
     */
    fun chain(vararg stages: FrameInterposer): FrameInterposer =
        if (stages.isEmpty()) pass
        else FrameInterposer { frame, step ->
            stages.fold(listOf(frame)) { frames, stage -> frames.flatMap { stage.apply(it, step) } }
        }

    /**
     * Re-deliver each frame [copies] extra times, with probability [probability] per frame,
     * while the step is inside [window] ([CHA1-16]).
     *
     * The copies are **byte-identical and distinct arrays** (`ByteArray.copyOf`), not the same
     * object handed over twice: a receiver that mutated the frame it decoded would otherwise
     * corrupt its own "second" delivery, and the property under test — that a system tolerates
     * seeing the same bytes twice — would be tested against something it is not.
     *
     * The original comes first, then its copies, so a duplicating stage never changes which
     * frame arrives *first*. Ordering is [reordering]'s job, and keeping the two separable is
     * what lets a suite attribute a failure to one of them.
     *
     * [probability] is consulted **once per frame, only inside the window**, from [rng] — so
     * two runs on the same seed duplicate exactly the same frames ([CHA1-30]), and adding a
     * window to a fault does not re-roll the frames outside it into different decisions.
     * [onDuplicate] is called once per frame that was actually duplicated, with the number of
     * copies made; that is the count [CHA1-24] reports.
     */
    fun duplicating(
        copies: Int = 1,
        probability: Double = 1.0,
        rng: java.util.Random,
        window: StepWindow = StepWindow.ALWAYS,
        onDuplicate: (Int) -> Unit = {},
    ): FrameInterposer {
        require(copies >= 1) { "a duplicate makes at least one extra copy, got copies=$copies" }
        require(probability > 0.0 && probability <= 1.0) {
            "probability is a per-frame chance in (0, 1], got $probability"
        }
        return FrameInterposer { frame, step ->
            if (!window.contains(step)) {
                listOf(frame)
            } else if (probability < 1.0 && rng.nextDouble() >= probability) {
                listOf(frame)
            } else {
                onDuplicate(copies)
                listOf(frame) + List(copies) { frame.copyOf() }
            }
        }
    }

    /**
     * Hold frames back and release them in bursts, optionally permuted ([CHA1-14], [CHA1-15]).
     *
     * ## What it does, and which reordering that produces
     *
     * Frames arriving while the step is inside [active] are appended to a buffer of at most
     * [window] frames. When the buffer reaches its release threshold the whole buffer is
     * returned at once, and the interposer returns *nothing* for the frames it is still
     * holding.
     *
     *  - **[permute] false — the default, and per-link FIFO ([CHA1-15]).** The buffer is
     *    released in arrival order, so the order *on this edge* is exactly the order the graph
     *    produced. What changes is when those frames arrive relative to frames on **other**
     *    edges, which is cross-link/cross-host reorder and nothing else. The threshold is
     *    re-drawn from [rng] in `1..window` after every release, so the burst boundaries — and
     *    therefore the cross-link interleaving — vary by seed while FIFO is preserved by
     *    construction, not by luck.
     *  - **[permute] true — the opt-in single-link permutation ([CHA1-15]).** The threshold is
     *    exactly [window] and the released buffer is shuffled with [rng], i.e. a permutation of
     *    up to [window] items derived solely from the run seed ([CHA1-14]). This breaks per-link
     *    FIFO, which is why it is opt-in and why it exists as a diverging control rather than a
     *    default.
     *
     * ## Release is traffic-driven, and the window is gated *inside* here
     *
     * Both are consequences of [FrameInterposer]'s known limit — an edge is a transform, not a
     * transport — and both are easy to get wrong:
     *
     *  - **Nothing but a later frame on this same edge can flush the buffer.** No step hook, no
     *    `onStep`, no healing step. Frames still held when traffic on the edge stops for good
     *    are **stranded for the rest of the run**, and a stranded frame is indistinguishable
     *    from a dropped one in its effect on the graph. A suite that wants reorder without loss
     *    must therefore keep traffic flowing past the end of [active] and assert that
     *    everything held was released — see `ReorderFault`'s KDoc for the accounting it exposes
     *    for exactly that assertion.
     *  - **[active] is checked in here, not by wrapping this in [windowed].** Wrapping a
     *    stateful buffer in an activation window strands its contents permanently: at
     *    `until` the buffer simply stops being applied, so whatever it holds is never seen
     *    again. Handled here, the first frame at or after `until` flushes the buffer in arrival
     *    order ahead of itself — the window closing releases what it held instead of losing it.
     *
     * [onHold] is called once per frame appended to the buffer: a held frame is the observable
     * effect of this fault, so that is what [CHA1-24] counts. [onRelease] is called once per
     * burst with the number of frames released, which is what lets a suite prove nothing was
     * stranded.
     */
    fun reordering(
        window: Int,
        permute: Boolean = false,
        rng: java.util.Random,
        active: StepWindow = StepWindow.ALWAYS,
        onHold: () -> Unit = {},
        onRelease: (Int) -> Unit = {},
    ): FrameInterposer {
        require(window >= 1) { "a reorder buffer holds at least one frame, got window=$window" }
        val held = mutableListOf<ByteArray>()
        var threshold = if (permute) window else 1 + rng.nextInt(window)
        return FrameInterposer { frame, step ->
            if (!active.contains(step)) {
                // The window has closed (or has not opened): release what is held, in arrival
                // order, ahead of the frame that woke us. Never strand it — see the KDoc.
                if (held.isEmpty()) {
                    listOf(frame)
                } else {
                    val flushed = held.toList()
                    held.clear()
                    onRelease(flushed.size)
                    flushed + frame
                }
            } else {
                held += frame
                onHold()
                if (held.size < threshold) {
                    emptyList()
                } else {
                    val released = held.toMutableList()
                    if (permute) java.util.Collections.shuffle(released, rng)
                    held.clear()
                    threshold = if (permute) window else 1 + rng.nextInt(window)
                    onRelease(released.size)
                    released
                }
            }
        }
    }
}

/** [FrameInterposers.chain] for two stages, infix: `tracing(...) then drop()`. */
infix fun FrameInterposer.then(next: FrameInterposer): FrameInterposer = FrameInterposers.chain(this, next)
