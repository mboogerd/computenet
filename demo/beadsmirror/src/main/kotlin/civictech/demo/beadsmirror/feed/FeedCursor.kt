package civictech.demo.beadsmirror.feed

import civictech.cell.host.DurableInput
import civictech.cell.host.ManagedHost

/** The Dolt commit through which one mirror input has durably driven its hosted fold. */
interface FeedCursor {
    fun committed(): String?

    /** Atomically drive one feed batch and commit [head] as the cursor covering it. */
    fun commit(head: String, drive: () -> Unit)
}

/**
 * A [FeedCursor] backed by the kernel's atomic durable-input record.
 *
 * [settled] is published only after every hosted invocation captured by the commit has drained.
 * A re-baseline commits through a freshly derived [DurableInput] rather than this wrapper, so
 * [committed] also notices such a cursor change and fences it before publishing the new value.
 * When [checkpointEveryRecords] and [checkpoint] are supplied, the cursor checkpoints only after
 * a complete durable-input record has drained. The poller is the sole caller of [commit], so this
 * cadence cannot overlap its same-ref rebaseline swap; a live checkpoint may still meet concurrent
 * replica frames, which the host checkpoint carries as its accepted tail.
 */
class DurableFeedCursor(
    private val input: DurableInput,
    private val host: ManagedHost,
    private val label: String,
    private val checkpointEveryRecords: Int? = null,
    private val checkpoint: (() -> Unit)? = null,
) : FeedCursor {

    init {
        require((checkpointEveryRecords == null) == (checkpoint == null)) {
            "checkpointEveryRecords and checkpoint must be supplied together"
        }
        require(checkpointEveryRecords == null || checkpointEveryRecords > 0) {
            "checkpointEveryRecords must be positive, was $checkpointEveryRecords"
        }
    }

    @Volatile
    private var settled: String? = input.committed() as String?
    private var recordsSinceCheckpoint = 0

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
                checkNotNull(checkpoint).invoke()
                recordsSinceCheckpoint = 0
            }
        }
        settled = head
    }
}
