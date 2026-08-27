package civictech.demo.beadsmirror.resolve

import civictech.cell.data.OrMapCell
import civictech.demo.beadsmirror.MirrorState
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorKey
import civictech.demo.beadsmirror.projector.MirrorProjector
import civictech.demo.beadsmirror.ready.ReadyPredicate
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * computenet-3bso.2.1: unit coverage of [EdgeResolver] over in-process
 * [MirrorProjector]/[MirrorState] fixtures — no `bd`, no `dolt` (idiom from
 * [civictech.demo.beadsmirror.projector.MirrorProjectorTest] and
 * [civictech.demo.beadsmirror.ready.ReadySetCellTest]).
 *
 * Covers feature computenet-3bso.2's acceptance rules 1-4 at unit level, per
 * the task's rule partition (AC1-AC4 -> this task; AC5 e2e -> computenet-3bso.2.2).
 */
class EdgeResolutionTest {

    /** A dedicated [DotMinter] per workspace identity — mirrors production, one per fold. */
    private fun minterFor(identity: String) = DotMinter(identity)

    private fun projector(identity: String, cell: OrMapCell<MirrorKey, String> = OrMapCell()) =
        MirrorProjector(minterFor(identity), cell)

    /** A single-issue-create record, mirroring the idiom in MirrorProjectorTest. */
    private fun issueRecord(
        height: Long,
        issue: String,
        type: DiffType?,
        vararg fields: Pair<String, String?>,
        ordinal: Int = 0,
    ) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, ordinal),
        issueId = issue,
        diffType = type,
        fieldDiffs = fields.map { (column, value) ->
            FieldDiff(column, old = null, new = value?.let(::JsonPrimitive))
        },
        edgeDiffs = emptyList(),
    )

    /** Builds a [MirrorState] over a fresh projector for [identity], seeded with [issues]. */
    private fun workspace(identity: String, vararg issues: Pair<String, String>): Pair<String, MirrorState> {
        val projector = projector(identity)
        issues.forEachIndexed { i, (issueId, status) ->
            projector.apply(issueRecord(1L + i, issueId, DiffType.ADDED, "status" to status))
        }
        return identity to MirrorState(projector)
    }

    // -----------------------------------------------------------------
    // R1 — a resolved edge reads the target workspace's mirrored fields
    // -----------------------------------------------------------------

    @Test
    fun `an edge whose target belongs to exactly one sibling fold resolves to that workspace with its fields readable`() {
        val wsA = workspace("wsA")
        val wsB = workspace("wsB", "wsB-abc" to "open")
        val resolver = EdgeResolver.ofStates(listOf(wsA, wsB))

        val edge = MirrorEdge(issueId = "wsA-xyz", dependsOnIssueId = "wsB-abc", type = "blocks")
        val result = resolver.resolve(edge)

        val resolved = result as? EdgeResolution.Resolved
        checkNotNull(resolved) { "expected Resolved, got $result" }
        resolved.workspaceIdentity shouldBe "wsB"
        ReadyPredicate.stringField(resolved.fields, "status") shouldBe "open"
    }

    // -----------------------------------------------------------------
    // R2 — an id no hosted workspace holds is unresolved, verbatim
    // -----------------------------------------------------------------

    @Test
    fun `an edge whose target belongs to no hosted workspace is unresolved and carries the verbatim id`() {
        val wsA = workspace("wsA")
        val wsB = workspace("wsB", "wsB-abc" to "open")
        val resolver = EdgeResolver.ofStates(listOf(wsA, wsB))

        val edge = MirrorEdge(issueId = "wsA-xyz", dependsOnIssueId = "external-999", type = "blocks")
        val result = resolver.resolve(edge)

        result shouldBe EdgeResolution.Unresolved("external-999")
    }

    // -----------------------------------------------------------------
    // R3 — reads go through state.current, including across a swap
    // -----------------------------------------------------------------

    @Test
    fun `a status change on B's projector is visible on a later read, including after a MirrorState swap of B`() {
        val wsA = workspace("wsA")
        val bProjector = projector("wsB")
        bProjector.apply(issueRecord(1, "wsB-abc", DiffType.ADDED, "status" to "open"))
        val bState = MirrorState(bProjector)
        val resolver = EdgeResolver.ofStates(listOf(wsA, "wsB" to bState))
        val edge = MirrorEdge(issueId = "wsA-xyz", dependsOnIssueId = "wsB-abc", type = "blocks")

        ReadyPredicate.stringField(
            (resolver.resolve(edge) as EdgeResolution.Resolved).fields,
            "status",
        ) shouldBe "open"

        // Update applied to the SAME projector instance: no swap yet.
        bProjector.apply(issueRecord(2, "wsB-abc", DiffType.MODIFIED, "status" to "closed"))
        ReadyPredicate.stringField(
            (resolver.resolve(edge) as EdgeResolution.Resolved).fields,
            "status",
        ) shouldBe "closed"

        // Now swap B's whole projector (a re-baseline) for a fresh one that
        // already carries a different status — proving the resolver reads
        // state.current at read time rather than a captured MirrorProjector.
        val rebuiltB = projector("wsB")
        rebuiltB.apply(issueRecord(1, "wsB-abc", DiffType.ADDED, "status" to "in_progress"))
        bState.swap(rebuiltB)

        ReadyPredicate.stringField(
            (resolver.resolve(edge) as EdgeResolution.Resolved).fields,
            "status",
        ) shouldBe "in_progress"
    }

    // -----------------------------------------------------------------
    // R4 — two sibling folds holding the same id: ambiguous, naming both
    // -----------------------------------------------------------------

    @Test
    fun `two hosted workspaces holding the same id resolve as ambiguous naming both candidates`() {
        val wsB = workspace("wsB", "shared-1" to "open")
        val wsC = workspace("wsC", "shared-1" to "open")
        val resolver = EdgeResolver.ofStates(listOf(wsB, wsC))

        val edge = MirrorEdge(issueId = "wsA-xyz", dependsOnIssueId = "shared-1", type = "blocks")
        val result = resolver.resolve(edge)

        result shouldBe EdgeResolution.Ambiguous("shared-1", setOf("wsB", "wsC"))
    }
}
