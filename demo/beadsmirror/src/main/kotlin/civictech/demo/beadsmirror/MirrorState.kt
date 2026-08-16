package civictech.demo.beadsmirror

import civictech.demo.beadsmirror.projector.MirrorProjector

/**
 * The one mutable cell of the mirror process: which [MirrorProjector] is
 * *currently* the mirror (computenet-dqj.3.3).
 *
 * **Why an indirection at all.** Re-baselining replaces state rather than
 * repairing it: a compacted workspace restarts its commit heights lower than
 * the pre-gap ones, so folding baseline deltas into the *old* projector would
 * mint dots that sort below tombstones and puts the old cell already holds, and
 * last-writer-wins would resolve in favour of dead state. The rebuild therefore
 * has to happen in a FRESH [MirrorProjector] which is then swapped in whole
 * ([civictech.demo.beadsmirror.baseline.Rebaseline]) — which means everything
 * downstream must reach the projector through a handle that can change, not
 * through a constructor-captured reference. That is this class, and it is why
 * [civictech.demo.beadsmirror.http.MirrorRoutes] takes a [MirrorState] rather
 * than a [MirrorProjector].
 *
 * **Threading: one writer, no lock.** [current] is written only by the
 * re-baseline operation, which runs synchronously on the poll thread — either
 * inside `DoltFeedPoller.pollOnce`'s condition path or, on first start, before
 * the poller thread exists at all. The poll thread is also the only thread that
 * calls `applyAll` on the current projector, so no two writers ever touch a
 * projector concurrently. HTTP handler threads only read [current], and they
 * read it through `@Volatile`, so each request sees either the whole old
 * projector or the whole new one — never a half-swapped mixture, and never a
 * stale reference indefinitely.
 */
class MirrorState(initial: MirrorProjector) {

    /** The projector every read and every applied batch goes through, right now. */
    @Volatile
    var current: MirrorProjector = initial
        private set

    /**
     * How many times [swap] has run — a supplement to the typed
     * [civictech.demo.beadsmirror.baseline.MirrorEvent.Rebaselined] event, not
     * a substitute for it: it says *that* a rebuild happened, never why or at
     * which head. Tests assert the event; this counter is for asserting the
     * *absence* of further rebuilds (an incremental resume must not silently
     * become another rebuild).
     */
    @Volatile
    var rebaselineCount: Int = 0
        private set

    /**
     * Makes [next] the current projector, discarding the previous one whole.
     *
     * The discard is the point: no key of the old projector survives, so a
     * pre-gap issue that is absent from the export cannot linger as a zombie.
     */
    fun swap(next: MirrorProjector) {
        current = next
        rebaselineCount++
    }
}
