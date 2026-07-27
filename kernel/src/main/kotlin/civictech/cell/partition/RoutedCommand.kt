package civictech.cell.partition

import civictech.cell.CellRef
import civictech.cell.TagFrontier
import civictech.cell.data.delta.SetDelta
import java.io.Serializable

/**
 * A routed shard command (spec 20/24 §Partitioned state, 40/42 §Interest-scoped
 * instance sets, CP-D3): a key-range slice of a [SetDelta]. A shard whose
 * interest no longer admits a key drops the slice, so an in-flight command
 * crossing a repartition flip neither loses nor double-counts — admission checks
 * the shard's CURRENT interest, established by the journaled
 * [civictech.cell.replication.Assignment].
 *
 * [epoch] is **deprecated (PN-6)**: it was decorative at the point of use
 * (admission never read it — the current interest is the authority), and PN-6
 * makes the assignment epoch durable on the [civictech.cell.replication.Assignment]
 * lattice instead. The
 * field is retained for one release (old frames still decode); `WireCodec` no
 * longer sniffs it onto `WireFrame.routingEpoch`, and no reader consults it.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("RoutedCommand")
data class RoutedCommand<E>(
    val epoch: Long,
    val delta: SetDelta<E>,
) : Serializable

/**
 * One leg of a scatter-gather pull (PN-5, spec 20/24 §Partitioned state, 40/42
 * §Interest-scoped instance sets): the [delta] slice one [instance] shard
 * answered a pull with, plus the [frontier] that slice is current to. The
 * consumer unions the deltas into the board and retains the frontier **per
 * instance** ([civictech.cell.protocol.RetainedFrontiers]) — merging one scalar
 * `since` across instances silently loses each shard's non-contiguous tags.
 */
data class PullReply<E>(
    val instance: CellRef,
    val delta: SetDelta<E>,
    val frontier: TagFrontier,
)
