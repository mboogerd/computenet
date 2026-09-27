package civictech.cell.replication

import civictech.cell.wire.Peering
import civictech.cell.wire.WireCodec
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One frame's identity — contract id + method id — as [Counting] records it.
 */
data class FrameId(val contractId: Long, val methodId: Long)

/**
 * A [Peering.FrameInterpose] that records every frame it puts back onto the
 * wire and otherwise passes frames through unchanged.
 *
 * [count] answers "how many times did a frame with this method id cross the
 * wire", never "how many times was [apply] invoked". The two differ whenever
 * a frame is dropped or duplicated, because [Peering.FrameInterpose.apply]
 * returns a *list*: a drop returns zero elements, a duplication returns more
 * than one, and a naive "one record per invocation" interposer reports 1 for
 * a frame that in fact crossed zero or two times.
 *
 * This is the semantics every replication test in this package shares — see
 * computenet-fkcek, which found five independent private copies, four of
 * them counting invocations instead of deliveries, silently diverging the
 * moment a duplicator or a dropper was added. Consolidated here so there is
 * exactly one definition to get right and no nearby copy to accidentally
 * imitate the wrong one from.
 *
 * A subclass that changes what actually crosses the wire (a drop, a
 * duplication, a reordering swap — see [SplitBrainReconciliationTest]'s
 * `Gate`/`Duplicating` and [LeaderMarkAnnounceTest]'s `Duplicating`/
 * `HoldOneSwap`) MUST call [record] once per frame it actually returns from
 * its own `apply` override, not once per input, or [count] goes back to
 * measuring the wrong quantity for exactly the scenario it was written for.
 */
open class Counting : Peering.FrameInterpose {
    /** Frames recorded so far, in order. Exposed for tests that inspect shape, not just [count]. */
    val frames: CopyOnWriteArrayList<FrameId> = CopyOnWriteArrayList()

    override fun apply(frame: ByteArray): List<ByteArray> {
        record(frame)
        return listOf(frame)
    }

    /** Records one frame as having crossed the wire. Call once per delivered copy. */
    protected fun record(frame: ByteArray) {
        val decoded = WireCodec.decodeFrame(frame).frame
        frames += FrameId(decoded.contractId, decoded.methodId)
    }

    /** How many frames with [methodId] have been [record]ed so far. */
    fun count(methodId: Long): Int = frames.count { it.methodId == methodId }
    fun reset() = frames.clear()
}
