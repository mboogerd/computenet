package civictech.cell.data

import java.io.Serializable

/**
 * Window assignment as key derivation (M11.6): there is no wall clock (P1) —
 * event time is an explicit attribute of the element, and the window is just
 * part of the group key. Tumbling = composite key via [tumbling]; sliding =
 * per-element expansion ([FlatMapSetCell]) into [sliding]'s windows, then
 * group. Windows never close: late elements are ordinary adds and
 * retractions flow (view semantics). Watermark-driven eviction is deferred
 * with trigger (24). Assigners are named serializable classes so they
 * survive graph-spec capture (51).
 */
object Windows {
    /** Event time → start of its tumbling window. */
    fun tumbling(size: Long): (Long) -> Long {
        require(size > 0) { "need size > 0" }
        return Tumbling(size)
    }

    /** Event time → starts of every sliding window containing it, ascending. */
    fun sliding(size: Long, slide: Long): (Long) -> List<Long> {
        require(size > 0 && slide in 1..size) { "need 0 < slide <= size" }
        return Sliding(size, slide)
    }

    private data class Tumbling(val size: Long) : (Long) -> Long, Serializable {
        override fun invoke(at: Long): Long = Math.floorDiv(at, size) * size
    }

    private data class Sliding(val size: Long, val slide: Long) : (Long) -> List<Long>, Serializable {
        override fun invoke(at: Long): List<Long> {
            val starts = mutableListOf<Long>()
            var start = Math.floorDiv(at, slide) * slide
            while (start + size > at) {
                starts += start
                start -= slide
            }
            return starts.reversed()
        }
    }
}
