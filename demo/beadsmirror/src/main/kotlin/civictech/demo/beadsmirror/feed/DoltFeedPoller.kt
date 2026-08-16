package civictech.demo.beadsmirror.feed

import java.time.Duration

/**
 * Wraps [DoltCommitFeed] in a checkpointed poll loop: there is no Dolt commit
 * watch (BDS0), so the read half is polling on a bounded, configurable
 * interval.
 *
 * Each tick:
 * 1. Reads the persisted checkpoint (or `null` for genesis) from [checkpoint].
 * 2. Calls [DoltCommitFeed.readFrom] with it. A [DoltCommitFeed] refuses an
 *    `afterCommit` that has fallen out of `dolt_log` with
 *    [CheckpointNotInHistoryException] — the history-truncation precondition
 *    computenet-dqj.1.2 left for this task to convert into the feature's
 *    typed condition (see that task's comment). This poller is the seam that
 *    does the converting: it catches exactly that exception type — not the
 *    broader [IllegalArgumentException] it extends — and calls [onCondition]
 *    with [FeedCondition.CheckpointGone] instead, emitting nothing. Any other
 *    [IllegalArgumentException] a tick's read raises propagates uncaught,
 *    rather than being folded into the same compaction condition.
 * 3. Otherwise, if the read produced records, hands the whole batch to
 *    [onBatch] and ONLY THEN persists the last record's commit hash as the
 *    new checkpoint — so a crash between steps 3's two halves re-delivers the
 *    batch next tick (acceptable, replay is idempotent downstream) rather
 *    than ever skipping it (not acceptable).
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
     */
    @Volatile
    var failure: Throwable? = null
        private set

    /** Starts the background poll loop. Not reentrant: call [stop] before calling [start] again. */
    fun start() {
        check(thread == null) { "already started" }
        running = true
        failure = null
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
                failure = t
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
     * checkpoint, read the feed, hand any records to [onBatch], persist the
     * new checkpoint. Raises via [onCondition] (default: throws
     * [FeedConditionException]) on history truncation, emitting nothing. Any
     * other exception a tick's read raises — including a plain
     * [IllegalArgumentException] that is not [CheckpointNotInHistoryException]
     * — propagates out of this call unconverted.
     */
    fun pollOnce() {
        val after = checkpoint.read()
        val records = try {
            feed.readFrom(after)
        } catch (e: CheckpointNotInHistoryException) {
            onCondition(FeedCondition.CheckpointGone(e.checkpoint))
            return
        }
        if (records.isEmpty()) return
        onBatch(records)
        checkpoint.write(records.last().commitHash)
    }

    /**
     * Stops the poll loop and joins its thread, so this returns only once the
     * loop has fully exited and released it. Safe to call when not started,
     * or more than once.
     */
    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join()
        thread = null
    }

    override fun close() = stop()
}
