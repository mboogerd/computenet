package civictech.demo.beadsmirror.projector

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
 * [Serializable] for the same reason as [MirrorKey]: `civictech.cell.data.SetCell`
 * requires Serializable elements for its `snapshot()`/`restore()` seam (G-25).
 */
data class MirrorEdge(
    val issueId: String,
    val dependsOnIssueId: String,
    val type: String,
) : Serializable {

    override fun toString(): String = "$issueId->$dependsOnIssueId:$type"
}
