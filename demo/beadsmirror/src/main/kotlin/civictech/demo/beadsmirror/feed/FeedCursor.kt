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
 */
class DurableFeedCursor(
    private val input: DurableInput,
    private val host: ManagedHost,
    private val label: String,
) : FeedCursor {

    @Volatile
    private var settled: String? = input.committed() as String?

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
        settled = head
    }
}
