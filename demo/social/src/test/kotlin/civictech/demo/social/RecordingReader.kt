package civictech.demo.social

import civictech.cell.CellRef
import civictech.cell.StateRead
import civictech.cell.StateReadResult
import java.util.Collections
import java.util.concurrent.CompletableFuture

/**
 * A [BoundedReader] that delegates every read and records, per call, the ref,
 * the request as sent, and the result the future completed with (8eb53-D10).
 * Shared by the feed tests; the private fakes in `SocialShortReadTest` and
 * `SocialReadRefusalTest` are deliberately left as they are.
 */
class RecordingReader(private val delegate: BoundedReader) : BoundedReader {

    /** One recorded call; [result] is null until the read's future completes. */
    class Call(val ref: CellRef, val request: StateRead) {
        @Volatile
        var result: StateReadResult? = null
    }

    private val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> {
        val call = Call(ref, request)
        calls += call
        return delegate.read(ref, request).whenComplete { r, _ -> call.result = r }
    }

    /** Total reads recorded since the last [reset]. */
    val count: Int get() = calls.size

    /** Every recorded call, in issue order. */
    fun calls(): List<Call> = synchronized(calls) { calls.toList() }

    /** The requests sent to [ref], in issue order. */
    fun requestsFor(ref: CellRef): List<StateRead> = calls().filter { it.ref == ref }.map { it.request }

    /** The results [ref]'s reads completed with, in issue order (completed reads only). */
    fun resultsFor(ref: CellRef): List<StateReadResult> = calls().filter { it.ref == ref }.mapNotNull { it.result }

    fun reset() = calls.clear()
}

/**
 * Answers [StateReadResult.Unavailable] for any ref present in [refused],
 * delegating otherwise. [refused] is live: a test adds a ref once the cell it
 * names has been spawned, and removes it to let the leg answer again.
 * Mirrors `SocialReadRefusalTest`'s private fake (8eb53-D10).
 */
class RefusingReader(
    private val delegate: BoundedReader,
    private val refused: MutableMap<CellRef, StateReadResult.Reason>,
) : BoundedReader {
    override fun read(ref: CellRef, request: StateRead): CompletableFuture<StateReadResult> {
        val reason = refused[ref]
        return if (reason != null) {
            CompletableFuture.completedFuture(StateReadResult.Unavailable(reason))
        } else {
            delegate.read(ref, request)
        }
    }
}
