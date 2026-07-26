package civictech.cell.control

import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
import civictech.nature.ProtocolCardinality
import civictech.nature.ProtocolDirection
import java.util.UUID

/**
 * Metadata-plane absorb-ack (spec 20/22): an upstream emits [Progress] at its
 * own quiescence boundary to advance a downstream join's per-source
 * watermark past waves it silently absorbed without a real delta — the
 * second of the three watermark-advance mechanisms (delta, Progress, later
 * wave / monotone max).
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("Progress")
data class Progress(
    @kotlinx.serialization.Serializable(with = civictech.cell.UuidSerializer::class) val sourceId: UUID,
    val thru: Long,
)

@Contract(management = true)
@Protocol("progress", ProtocolDirection.DOWNSTREAM, band = 0, lane = "progress", cardinality = ProtocolCardinality.FAN_OUT_BROADCAST)
fun interface ProgressProtocol { fun progress(message: Progress) }

/**
 * Per-emitter monotonic version minter (93 I-4 rule 2, G-58 core): every
 * outgoing [Attention] update from one aggregating cell is stamped with a
 * strictly increasing version from this minter. Wraparound is a non-event —
 * [Long] overflow wraps silently to [Long.MIN_VALUE], and [isNewer]'s
 * signed-difference comparison (the classic TCP sequence-number trick) stays
 * correct across the wrap, as long as fewer than 2^63 updates separate two
 * compared versions.
 */
class VersionMinter(start: Long = 0L) {
    private val current = java.util.concurrent.atomic.AtomicLong(start)

    /** Mints and returns the next version, strictly newer than every prior one. */
    fun next(): Long = current.incrementAndGet()

    companion object {
        /** Wraparound-safe "is [candidate] newer than [stored]" (93 I-4 rule 2). */
        fun isNewer(candidate: Long, stored: Long): Boolean = candidate - stored > 0
    }
}
