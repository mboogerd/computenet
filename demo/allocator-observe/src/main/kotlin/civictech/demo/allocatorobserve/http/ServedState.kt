package civictech.demo.allocatorobserve.http

import civictech.demo.allocatorobserve.SpendRecord
import civictech.demo.allocatorobserve.declaration.DeclarationEvent
import civictech.demo.allocatorobserve.view.AllocatorReport
import java.time.Instant

/**
 * Per-reason ingest failure counts served over HTTP (fpml.4-D7): the two
 * reasons `SpendLogIngester.failures` (`computenet-fpml.1.3`) counts —
 * malformed lines and lines with an unknown `v` — plus
 * `DeclarationIngester.parseFailures` (`computenet-fpml.2`), carried together
 * so a served [ServedState] never pairs one ingester's counts with a moment
 * before or after the other's.
 */
data class IngestFailureCounts(
    val malformed: Long,
    val unknownVersion: Long,
    val declarationParseFailed: Long,
)

/**
 * Ingest health as served under `GET /state/ingest` and the `ingest` member of
 * `GET /state` (fpml.4-D7). Built by the poll driver (task `computenet-fpml.4.2`)
 * from `SpendLogIngester` and `DeclarationIngester`'s own counters, once per
 * poll tick, alongside the [AllocatorReport] published in the same
 * [ServedState] — so a served response never pairs a fresh report with stale
 * health or vice versa.
 *
 * @param recordCount the ingested spend-record set's size at this tick.
 * @param checkpointOffset the log's byte checkpoint offset, or null before any
 *   successful poll has advanced it — a `RecordingOffsetStore` concern (task
 *   `computenet-fpml.4.2`); this type only carries the value.
 * @param reBaselineCount the number of `TailReason.ReBaselined` polls this
 *   process has seen.
 * @param polls the number of poll ticks this process has run.
 * @param lastPollAt the clock reading at the most recent poll tick, or null
 *   before the first.
 * @param declarationEvents the size of the declaration history at this tick.
 */
data class IngestHealth(
    val recordCount: Int,
    val checkpointOffset: Long?,
    val reBaselineCount: Long,
    val polls: Long,
    val lastPollAt: Instant?,
    val failures: IngestFailureCounts,
    val declarationEvents: Int,
)

/**
 * One immutable served snapshot (fpml.4-D4): the report `AllocatorReportViews`
 * published at a poll tick, that tick's ingest health, and the live record and
 * declaration sets — everything an HTTP response or SSE frame needs. Built by
 * the poll driver (task `computenet-fpml.4.2`) in one pass AFTER
 * `views.publish()` returns, so [report] and [ingest] describe the very same
 * tick, then swapped into a [ServedStateHolder] as a single unit.
 *
 * Every field is a value or an immutable / defensively-copied container built
 * by the caller: [records] and [declarations] are expected to be the caller's
 * own snapshot copies (`SetCell.membership()` and `DeclarationIngester.history()`
 * already return such copies), never a live view over a mutable fold — this
 * type does not re-copy them.
 */
data class ServedState(
    val report: AllocatorReport,
    val ingest: IngestHealth,
    val records: Set<SpendRecord>,
    val declarations: List<DeclarationEvent>,
)

/**
 * The poll driver's terminal state after its background thread exits on a
 * throwable (fpml.4-D6 — the `DoltFeedPoller`/`PollLoopStopped` idiom
 * `demo/beadsmirror/src/main/kotlin/civictech/demo/beadsmirror/feed/DoltFeedPoller.kt`
 * uses). While present in a [ServedStateHolder], every `/state*` route
 * answers 503 with the last good [ServedState] preserved under `stale`
 * ([AllocatorRoutes]).
 */
data class PollLoopStopped(
    val failure: Throwable,
    val lastPollAt: Instant?,
)

/**
 * The single publication point for [ServedState] (fpml.4-D4/D6): a tiny
 * `@Volatile`-backed holder the poll driver swaps once per tick and every
 * `/state*` route (and the `/events` SSE frame, task `computenet-fpml.4.2`)
 * reads exactly once per request — the same idiom as beadsmirror's
 * `MirrorState.current` and the kernel's `ReadySetCell.published`. No locks:
 * the volatile write of a reference to an already-complete immutable value is
 * the happens-before edge a concurrent reader needs, so a reader sees the
 * previous state or the next one and never a mixture of the two
 * (`AllocatorReportViews`'s own Threading section documents the same pattern
 * one layer down, over [AllocatorReport] itself).
 */
class ServedStateHolder {
    @Volatile
    var current: ServedState? = null
        private set

    @Volatile
    var stopped: PollLoopStopped? = null
        private set

    /** Publish [state] as the current served snapshot. The single writer, per fpml.4-D4. */
    fun swap(state: ServedState) {
        current = state
    }

    /**
     * Record the poll loop's terminal failure. [current] is left at its last
     * good value — a stopped loop relabels the fold as stale, it never
     * withholds it (fpml.4-D6 / [AllocatorRoutes]).
     */
    fun stop(failure: PollLoopStopped) {
        stopped = failure
    }
}
