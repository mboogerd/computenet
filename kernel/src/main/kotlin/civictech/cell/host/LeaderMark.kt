package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.UuidSerializer
import java.io.Serializable
import java.util.UUID

/**
 * Leadership announcement for a single-writer logical cell (spec 42
 * §Single-writer replication, decided 93 I-25). Folded into an
 * eventually-consistent membership index the same way as an ordinary
 * [LocationRegistry] publish (P4) — no view number, no quorum, no barrier:
 * [InstanceIndex.leaderOf] is simply the mark with the greatest `(epoch,
 * leaderRef.instanceId)` this peer has folded (f7h.1-D2). Automatic election
 * that *mints* these marks is the deferred liveness half (G-44 residual, 95
 * §R1); explicit/orchestrated designation is the spec's declared default.
 *
 * Lives in `civictech.cell.host` (f7h.1-D1) — the membership lane
 * [InstanceIndex] already owns, per epic computenet-f7h §2.1 and C-13 — not
 * in `civictech.cell.replication`, where it originated
 * (`SingleWriterReplication.kt` keeps a `typealias` at that name for source
 * compatibility). It is membership vocabulary: "one more announcement kind,
 * folded into the same membership index" (93 I-25 §4.1).
 *
 * A **wire type** (f7h.2-D3): registered in `WireCodec`'s `polymorphic(Any)`
 * block so it can cross a bridge as the argument of
 * `RegistryAnnounce.leaderMarked`. Ids only — [logicalId], [epoch] and
 * [leaderRef] — never state, exactly as the other four announcements are.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("LeaderMark")
data class LeaderMark(
    @kotlinx.serialization.Serializable(with = UuidSerializer::class) val logicalId: UUID,
    val epoch: Long,
    val leaderRef: CellRef,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
