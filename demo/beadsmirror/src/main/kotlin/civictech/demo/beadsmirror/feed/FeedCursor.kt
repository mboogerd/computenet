package civictech.demo.beadsmirror.feed

import civictech.cell.host.DurableInput
import civictech.cell.host.ManagedHost
import java.time.Duration

/** The Dolt commit through which one mirror input has durably driven its hosted fold. */
interface FeedCursor {
    fun committed(): String?

    /** Atomically drive one feed batch and commit [head] as the cursor covering it. */
    fun commit(head: String, drive: () -> Unit)

    /** Called after one whole feed poll, including any condition handler, completes successfully. */
    fun pollCompleted() = Unit
}

/**
 * A [FeedCursor] backed by the kernel's atomic durable-input record.
 *
 * [settled] is published only after every hosted invocation captured by the commit has drained.
 * A re-baseline commits through a freshly derived [DurableInput] rather than this wrapper, so
 * [committed] also notices such a cursor change and fences it before publishing the new value.
 * [checkpointEveryRecords] bounds a locally active workspace by complete durable-input records;
 * [checkpointInterval] also bounds a locally idle workspace whose journal grows through replica
 * frames. Both triggers run on the poller thread: a record trigger runs after that record drains,
 * and the elapsed trigger runs from [pollCompleted], after any same-ref rebaseline condition handler
 * has completed its checkpoint, topology swap and replacement input. A live checkpoint may still
 * meet concurrent replica frames, which the host checkpoint carries as its accepted tail.
 */
class DurableFeedCursor(
    private val input: DurableInput,
    private val host: ManagedHost,
    private val label: String,
    private val checkpointEveryRecords: Int? = null,
    private val checkpointInterval: Duration? = null,
    private val checkpoint: (() -> Unit)? = null,
    private val nanoTime: () -> Long = System::nanoTime,
) : FeedCursor {

    init {
        require((checkpointEveryRecords != null || checkpointInterval != null) == (checkpoint != null)) {
            "a checkpoint cadence and checkpoint must be supplied together"
        }
        require(checkpointEveryRecords == null || checkpointEveryRecords > 0) {
            "checkpointEveryRecords must be positive, was $checkpointEveryRecords"
        }
        require(checkpointInterval == null || (!checkpointInterval.isZero && !checkpointInterval.isNegative)) {
            "checkpointInterval must be positive, was $checkpointInterval"
        }
    }

    @Volatile
    private var settled: String? = input.committed() as String?
    private var recordsSinceCheckpoint = 0
    private val checkpointIntervalNanos = checkpointInterval?.toNanos()
    private var checkpointedAtNanos = nanoTime()

    override fun committed(): String? {
        val durable = input.committed() as String?
        if (durable != settled) {
            host.quiescence().await(30_000, label)
            settled = durable
        }
        return settled
    }

    override fun commit(head: String, drive: () -> Unit) {
        input.commit {
            drive()
            head
        }
        host.quiescence().await(30_000, label)
        checkpointEveryRecords?.let { cadence ->
            recordsSinceCheckpoint += 1
            if (recordsSinceCheckpoint >= cadence) {
                checkpointNow()
            }
        }
        settled = head
    }

    override fun pollCompleted() {
        val interval = checkpointIntervalNanos ?: return
        if (nanoTime() - checkpointedAtNanos >= interval) checkpointNow()
    }

    private fun checkpointNow() {
        checkNotNull(checkpoint).invoke()
        recordsSinceCheckpoint = 0
        checkpointedAtNanos = nanoTime()
    }
}
