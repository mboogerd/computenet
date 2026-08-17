package civictech.demo.beadsmirror.projector

import kotlinx.serialization.SerialName
import java.io.Serializable

/**
 * One dependency edge in the mirror's materialized dependency set
 * (computenet-dqj.2.2): "[issueId] depends on [dependsOnIssueId]", with
 * relation [type] (`bd`'s dependency-type column, e.g. `"blocks"`).
 *
 * The whole triple is the [SetCell][civictech.cell.data.SetCell] element —
 * there is no separate identity key the way [MirrorKey] gives the issue-field
 * map one. An edge whose [type] changes is therefore a different element from
 * the mirror's point of view, not an update to an existing one (see the note
 * on [MirrorProjector]'s edge projection for why a `type` change cannot be
 * observed as a removal of the old triple with the data this record carries).
 *
 * **[dependsOnIssueId] is a reference, not a membership claim** — it need not
 * name an issue this mirror holds (computenet-dqj.11). `bd`'s `dependencies`
 * table carries an edge's far side in one of three columns —
 * `depends_on_issue_id` for a target in this workspace, `depends_on_wisp_id`
 * for an ephemeral one, `depends_on_external` for an id `bd` resolved as
 * foreign — and the mirror carries whichever one the row holds, verbatim,
 * under this single field. That is what `bd export` does too: it renders all
 * three the same way, as one `dependencies` entry whose `depends_on_id` names
 * the target (verified live 2026-08-16 for the external case), so an edge
 * dropped for having a foreign target would be a permanent divergence from
 * the export the mirror is compared against. It matters in ordinary use, not
 * only in principle: a workspace seeded from another tracker's `bd export`
 * (epic computenet-dqj §4) gives every seeded issue a foreign prefix, so an
 * `bd dep add` onto one of them is exactly this case.
 *
 * [Serializable] for the same reason as [MirrorKey]: `civictech.cell.data.SetCell`
 * requires Serializable elements for its `snapshot()`/`restore()` seam (G-25).
 * And `@kotlinx.serialization.Serializable` + registration in
 * [civictech.demo.beadsmirror.wire.BeadsMirrorWireSerializers] for the same
 * reason as [MirrorKey] (computenet-7em.1.5): this triple is the edge
 * `SetCell`'s element, so it rides a `SetDelta` through `WireCodec`'s
 * `polymorphic(Any)` scope whenever the two-node mode gossips one.
 */
@kotlinx.serialization.Serializable
@SerialName("beadsmirror.MirrorEdge")
data class MirrorEdge(
    val issueId: String,
    val dependsOnIssueId: String,
    val type: String,
) : Serializable {

    override fun toString(): String = "$issueId->$dependsOnIssueId:$type"
}
