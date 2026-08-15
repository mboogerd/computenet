package civictech.demo.beadsmirror.projector

import java.io.Serializable

/**
 * The mirror's OR-map key: **one key per (issue, field)**, never one per issue.
 *
 * The composite is what makes per-field convergence free of any kernel change
 * (spec 20/24 §Tagged maps, `[24-TMAP-03]`): two commits editing *different*
 * fields of the same issue write different keys, so their dots never compete
 * and last-writer-wins never has to choose between them. Keying by issue id
 * alone would put both edits on one key and silently drop the loser — the
 * divergence control `MirrorProjectorTest` asserts.
 *
 * [Serializable] because `civictech.cell.data.OrMapCell.snapshot()` requires
 * Serializable keys and values (G-25 seam). The same requirement is why field
 * *values* are stored as their JSON **string** form rather than as
 * `kotlinx.serialization.json.JsonElement`, which is not `java.io.Serializable`.
 */
data class MirrorKey(val issueId: String, val field: String) : Serializable {

    override fun toString(): String = "$issueId/$field"

    companion object {
        /**
         * The reserved [field] of an issue's **presence** key.
         *
         * Membership of the materialized view is decided by this key alone, so
         * a removal cannot leave a partial zombie behind: a remove is
         * tag-precise (`[24-TMAP-04]`) and therefore cannot tombstone a put-dot
         * it never observed — a later or concurrently-arriving field put
         * survives it. With presence gated on this key such a straggler is a
         * dead field key rather than a resurrected issue.
         *
         * `bd`'s own column space cannot collide with it: the diff columns are
         * plain SQL identifiers (`status`, `priority`, …), never
         * double-underscore-prefixed.
         */
        const val PRESENT: String = "__present"

        /** The value written to a presence key; the key's existence is the signal. */
        const val PRESENT_VALUE: String = "true"

        fun presence(issueId: String): MirrorKey = MirrorKey(issueId, PRESENT)
    }
}
