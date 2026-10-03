package civictech.cell.host

import java.io.Serializable

/**
 * A named external input whose source cursor and one batch of hosted invocations commit as one
 * journal record. [committed] is opaque to the kernel; the caller interprets it to resume its
 * source after recovery.
 */
class DurableInput internal constructor(
    private val readCommitted: () -> Serializable?,
    private val commitBatch: (() -> Serializable) -> Serializable,
) {
    /** The last committed or recovered source cursor, or `null` before the first commit. */
    fun committed(): Serializable? = readCommitted()

    /** Capture every invocation [drive] sends to this host and atomically commit it with its cursor. */
    fun commit(drive: () -> Serializable): Serializable = commitBatch(drive)
}
