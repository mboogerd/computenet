package civictech.timetravel.reconstruct

import civictech.timetravel.fidelity.ReplayStable
import civictech.timetravel.journal.JournalReading
import civictech.timetravel.timeline.Position
import civictech.timetravel.timeline.RunTimeline

/**
 * A [Reconstructor] that keeps ONE reconstruction session alive between requests, so scrubbing
 * forward through a run feeds only the new records instead of rebuilding the graph each time
 * (TTD1 F5, computenet-yhvlz D3 as amended by yhvlz-D9; epic computenet-ocv `[TTD1-30]`,
 * `[TTD1-31]`, BS-14).
 *
 * [at] resolves `n' = timeline.resolve(position)` and takes one of five paths:
 *
 * - **same position** — the previous request's [Position] again: the cached [Reconstruction]
 *   instance is returned. The cache is keyed on the `Position`, not on `n'`, because
 *   [ResolvedPosition.requested] carries the caller's position (yhvlz-D9).
 * - **re-observe** — a different `Position` resolving to the live prefix end: the live session is
 *   observed again, with no replay and no rebuild.
 * - **forward** (`[TTD1-30]`) — `n'` beyond the live prefix end: only records `[n, n')` are fed to
 *   the live session's host through [replayInto]; the host, registry and graph are the same
 *   instances as before.
 * - **restart** (`[TTD1-31]`) — `n'` before the live prefix end, the first request, or any request
 *   after an incomplete replay: the live session is closed and a fresh one is opened at
 *   `timeline.nearestAnchorAtOrBefore(n' - 1)` (0 when `n' == 0`), then replayed to `n'`. There is
 *   no rewind in place and there are no periodic in-memory anchors (epic §9.5 item 5).
 * - **after an incomplete replay** — the kernel's `recoverFrom` abandons the rest of a step's
 *   records at the failing one, so a partially fed host is never advanced further: the next
 *   request at a different position restarts. A from-scratch replay of the longer prefix throws at
 *   the same record, so restart is equivalent to from-scratch.
 *
 * Fidelity is recomputed per request over the full prefix `[0, n')` by the inherited [observe].
 * A forward step's `RecoveryIncomplete` is normalized to `total = n' - sessionAnchor` — the
 * records fed to this session, which is what a from-scratch [stateAt] reports. In the landed
 * journal format a checkpoint is only ever record 0 (F2 D2), so the session anchor equals
 * `nearestAnchorAtOrBefore(n' - 1)` for every `n'` and each result equals a from-scratch
 * [stateAt] of the same position as a whole data class. That equality rests on this format
 * fact: a journal with a checkpoint past record 0 would make a forward step keep the older
 * anchor where from-scratch would take the newer one.
 *
 * When the graph source spawns cells it does not hand back (the `GRAPH_SOURCE_INCOMPLETE`
 * refusal, 6tm33-D11), the fresh session is closed, no session stays live, and the result is the
 * inherited [stateAt]'s refusal — a refusal, not a scrub.
 *
 * [close] shuts the live session's scheduler down (no reconstruction host outlives the cursor)
 * and is idempotent; [at] afterwards throws [IllegalStateException]. The inherited, final
 * [stateAt] is untouched by the cursor: it still builds and tears down a throwaway session of its
 * own on every call, and keeps working after [close].
 *
 * `open` so a test can observe session identity through [openSession] and [close]. Not
 * thread-safe: one caller scrubs at a time.
 */
open class ScrubCursor(
    reading: JournalReading,
    private val timeline: RunTimeline,
    graph: GraphSource,
    seed: Long = 0,
    replayStable: ReplayStable = ReplayStable.DEFAULT,
    onBuilt: (GraphBuild) -> Unit = {},
) : Reconstructor(reading, timeline, graph, seed, replayStable, onBuilt), AutoCloseable {

    /** The live session, where replay into it started ([anchor]), its prefix end [n], and what [at] last returned. */
    private class Live(
        val session: Session,
        val anchor: Int,
        val n: Int,
        val recovery: RecoveryIncomplete?,
        val last: Reconstruction,
    )

    private var live: Live? = null
    private var closed = false

    /** The live session's exclusive prefix end; `null` before the first [at] and after [close]. */
    val prefixEnd: Int?
        get() = live?.n

    /**
     * The run's state after the prefix [position] resolves to, reusing the live session where it
     * can (see the class KDoc for the five cases).
     *
     * @throws IllegalStateException after [close].
     */
    fun at(position: Position): Reconstruction {
        check(!closed) { "ScrubCursor is closed" }
        val target = timeline.resolve(position)
        val current = live
        if (current != null) {
            if (position == current.last.position.requested) return current.last
            if (target == current.n) {
                val result = observe(current.session, position, target, current.anchor, current.recovery)
                live = Live(current.session, current.anchor, current.n, current.recovery, result)
                return result
            }
            if (target > current.n && current.recovery == null) {
                val step = replayInto(current.session, from = current.n, until = target)
                val recovery = step?.let { RecoveryIncomplete(it.recordIndex, total = target - current.anchor, cause = it.cause) }
                val result = observe(current.session, position, target, current.anchor, recovery)
                live = Live(current.session, current.anchor, target, recovery, result)
                return result
            }
        }
        return restart(position, target)
    }

    private fun restart(position: Position, target: Int): Reconstruction {
        live?.let { close(it.session) }
        live = null
        val anchor = if (target == 0) 0 else timeline.nearestAnchorAtOrBefore(target - 1)
        val session = openSession(anchor)
        val returned = session.build.cells.mapTo(mutableSetOf()) { it.ref }
        if ((session.localRefs - returned).isNotEmpty()) {
            // The GRAPH_SOURCE_INCOMPLETE refusal (6tm33-D11) is private to the base class:
            // close this session and let the inherited stateAt rebuild once more to report it.
            close(session)
            return stateAt(position)
        }
        try {
            val recovery = replayInto(session, from = anchor, until = target)
            val result = observe(session, position, target, anchor, recovery)
            live = Live(session, anchor, target, recovery, result)
            return result
        } catch (e: Throwable) {
            close(session)
            throw e
        }
    }

    /** Closes the live session, if any; idempotent. [at] afterwards throws [IllegalStateException]. */
    override fun close() {
        if (closed) return
        closed = true
        live?.let { close(it.session) }
        live = null
    }
}
