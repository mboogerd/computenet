package civictech.demo.beadsmirror.feed

import java.time.Duration

/**
 * A [DoltFeedPoller]'s background loop has exited on [failure], with
 * [checkpoint] the last commit it had persisted (`null` if it had persisted
 * none, or if reading the checkpoint failed too).
 *
 * The loop does not restart itself, so this value is terminal: every fold
 * downstream of that poller is frozen at [checkpoint] from here on. That is
 * why it carries the position as well as the throwable — "it broke" without
 * "and it stopped here" does not tell an operator how stale the served state
 * is (computenet-dqj.12).
 */
data class PollLoopStopped(val failure: Throwable, val checkpoint: String?)

/**
 * Wraps [DoltCommitFeed] in a checkpointed poll loop: there is no Dolt commit
 * watch (BDS0), so the read half is polling on a bounded, configurable
 * interval.
 *
 * Each tick:
 * 1. Reads the persisted checkpoint (or `null` for genesis) from [checkpoint].
 * 2. Calls [DoltCommitFeed.readFromWithHead] with it — one `dolt_log` read
 *    that bounds both the records and the head to persist (computenet-yspa5;
 *    see "Why step 4 is sound" below). A [DoltCommitFeed] refuses an
 *    `afterCommit` that has fallen out of `dolt_log` with
 *    [CheckpointNotInHistoryException] — the history-truncation precondition
 *    computenet-dqj.1.2 left for this task to convert into the feature's
 *    typed condition (see that task's comment). This poller is the seam that
 *    does the converting: it catches exactly that exception type — not the
 *    broader [IllegalArgumentException] it extends — and calls [onCondition]
 *    with [FeedCondition.CheckpointGone] instead, emitting nothing. Any other
 *    [IllegalArgumentException] a tick's read raises propagates uncaught,
 *    rather than being folded into the same compaction condition.
 *    A [HistoryMergedException] — a `bd dolt pull` merged peer history into
 *    the walked range (task computenet-7em.4.1) — is converted the same way,
 *    into [FeedCondition.HistoryMerged], and likewise emits nothing. The two
 *    are separate conditions because the checkpoint survives a pull, so a
 *    merge never presents as truncation.
 * 3. Otherwise, if the read produced records, hands the whole batch to
 *    [onBatch] and ONLY THEN persists the new checkpoint — so a crash between
 *    steps 3's two halves re-delivers the batch next tick (acceptable, replay
 *    is idempotent downstream) rather than ever skipping it (not acceptable).
 * 4. The new checkpoint is [FeedRead.head] — the tail of the same `dolt_log`
 *    that bounded the read — so a commit that carries no `issues`/
 *    `dependencies` row (an `events`-only no-op `bd update`, a `comments`- or
 *    `labels`-only commit: none of those tables feed the fold) is passed over
 *    rather than pinning the checkpoint below head until some later commit
 *    happens to carry a record (bug computenet-btt30). An empty read
 *    therefore still advances the checkpoint, to that head.
 *
 * ## Why step 4 is sound, and why one read is enough
 *
 * The checkpoint means "every record at or below this commit has been
 * handed to [onBatch]", not "the commit of the last record" — and nothing
 * reads it as the latter: [Rebaseline][civictech.demo.beadsmirror.baseline.Rebaseline]
 * already persists the captured head, whatever that commit touched, and a
 * record's [FeedPosition] comes from its height in the whole `dolt_log`, never
 * from the checkpoint, so where the checkpoint sits cannot move a dot. Resume
 * from a record-less commit is an ordinary [DoltCommitFeed.readFromWithHead]
 * of the commits after it.
 *
 * The head persisted must come from the *same* `dolt_log` read that bounded
 * the records, which is exactly what [DoltCommitFeed.readFromWithHead]
 * guarantees: `wanted` and both diff queries are scoped to precisely that
 * log ([DoltCommitFeed] KDoc, RESOLVED note), so any commit in it — including
 * one trailing the last record with no record of its own — was covered by
 * this same pass. A head from a *later*, independent read could name a
 * commit that landed after this tick's own log was read, carrying records
 * this tick never saw, and persisting it would skip them; that is why this
 * poller no longer reads the head via a separate [DoltCommitFeed.history]
 * call before the feed read (computenet-btt30 did; computenet-yspa5 dropped
 * it once `readFromWithHead` could hand back a head from its own single
 * read). The cost is one `dolt_log` read per tick either way — one whose
 * `afterCommit` already equals the tail it reads short-circuits before any
 * diff query, so an idle tick still costs exactly that one read.
 *
 * Threading: [start] runs the loop on one daemon background thread; [stop]
 * (also reachable via [close]) requests it to stop and joins that thread
 * before returning, so a caller that has called [stop] knows the loop has
 * fully exited — no more ticks can be in flight. [pollOnce] runs one tick
 * synchronously on the calling thread, with no polling loop involved; tests
 * use it to assert resume/truncation behaviour without waiting out an
 * interval.
 */
class DoltFeedPoller(
    private val feed: DoltCommitFeed,
    private val checkpoint: FeedCheckpoint,
    private val interval: Duration,
    private val onBatch: (List<ChangeRecord>) -> Unit,
    private val onCondition: (FeedCondition) -> Unit = { throw FeedConditionException(it) },
    private val onStopped: (PollLoopStopped) -> Unit = {},
) : AutoCloseable {

    init {
        require(!interval.isNegative) { "interval must not be negative, was $interval" }
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /**
     * Set if the background loop (started via [start]) exited because a tick
     * threw something other than the truncation condition (which
     * [onCondition]'s default already turns into a thrown
     * [FeedConditionException] — that counts too, unless the caller supplied
     * an [onCondition] that swallows it). `null` while the loop has not
     * failed. A background thread's uncaught exception has nowhere else to
     * go, so this is how a caller of [start] observes one after the fact.
     *
     * **Polling this field is not enough on its own** (computenet-dqj.12): a
     * caller that only reads it when it happens to look learns nothing at the
     * moment the loop dies, and the feed never resumes — that is what
     * [onStopped] is for, and why the app wires the served read surface to
     * [stopped] rather than trusting anyone to check.
     */
    @Volatile
    var stopped: PollLoopStopped? = null
        private set

    /** The throwable half of [stopped]; `null` while the loop has not failed. */
    val failure: Throwable? get() = stopped?.failure

    /** Starts the background poll loop. Not reentrant: call [stop] before calling [start] again. */
    fun start() {
        check(thread == null) { "already started" }
        running = true
        stopped = null
        thread = Thread({
            try {
                while (running) {
                    pollOnce()
                    if (!running) break
                    Thread.sleep(interval.toMillis())
                }
            } catch (_: InterruptedException) {
                // stop() requested — exit quietly, this is not a failure.
            } catch (t: Throwable) {
                // Record BEFORE reporting: an onStopped that throws must not
                // be able to leave the loop dead and `stopped` still null,
                // which is precisely the invisible state this exists to end.
                val exit = PollLoopStopped(t, checkpointOrNull())
                stopped = exit
                try {
                    onStopped(exit)
                } catch (reporting: Throwable) {
                    // Nowhere left to report to; keep the primary failure as
                    // the record and do not mask it with the reporter's.
                    t.addSuppressed(reporting)
                }
            } finally {
                running = false
            }
        }, "dolt-feed-poller").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Runs one poll tick synchronously on the calling thread: read the
     * checkpoint, read the feed and its bounding head in one call, hand any
     * records to [onBatch], persist the new checkpoint at that head (class
     * KDoc, step 4). Raises via [onCondition] (default: throws
     * [FeedConditionException]) on history truncation, emitting nothing. Any
     * other exception a tick's read raises — including a plain
     * [IllegalArgumentException] that is not [CheckpointNotInHistoryException]
     * — propagates out of this call unconverted.
     */
    fun pollOnce() {
        val after = checkpoint.read()
        // A single dolt_log read bounds both the records and the head: see
        // the class KDoc, "Why step 4 is sound" and
        // [DoltCommitFeed.readFromWithHead]'s KDoc. Any commit in that one log
        // was covered by this same pass, so its head is as safe to persist as
        // a head read before the feed would have been — without the second
        // `dolt_log` query that separate read cost (computenet-yspa5).
        val read = try {
            feed.readFromWithHead(after)
        } catch (e: CheckpointNotInHistoryException) {
            onCondition(FeedCondition.CheckpointGone(e.checkpoint))
            return
        } catch (e: HistoryMergedException) {
            onCondition(FeedCondition.HistoryMerged(e.mergeCommit))
            return
        }
        if (read.records.isNotEmpty()) onBatch(read.records)
        // read.head is the tail of the exact log that bounded read.records
        // (record-less commits trailing the last record included), so it is
        // always the right checkpoint to advance to — whether or not any
        // record was found.
        val advanceTo = read.head
        if (advanceTo != null && advanceTo != after) checkpoint.write(advanceTo)
    }

    /**
     * Stops the poll loop and joins its thread, so this returns only once the
     * loop has fully exited and released it. Safe to call when not started,
     * or more than once.
     */
    /**
     * The persisted checkpoint, or `null` when there is none — or when
     * reading it is itself what has just gone wrong. The loop is already
     * failing when this is called; a second failure here must not replace the
     * first, so it degrades to "unknown position" rather than propagating.
     */
    private fun checkpointOrNull(): String? =
        try {
            checkpoint.read()
        } catch (_: Throwable) {
            null
        }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join()
        thread = null
    }

    override fun close() = stop()
}
