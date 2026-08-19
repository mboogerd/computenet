package civictech.demo.beadsmirror.ready

import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import civictech.demo.beadsmirror.projector.DotMinter
import civictech.demo.beadsmirror.projector.MirrorProjector
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * computenet-98u.1.3: the per-modelled-clause membership-flip battery
 * (feature rule 5) — `demo/beadsmirror/READY-COVERAGE.md` enumerates the
 * clauses [ReadySetCell] models (default status set, derived not-blocked,
 * pinned, ephemeral, type exclusions); this class carries one focused test
 * per clause, each flipping exactly that clause's input through a synthetic
 * feed delta and asserting [ReadySetCell.readySet] membership flips.
 *
 * Cell-level throughout — driven through [ReadySetCell.derivedFrom] and
 * [MirrorProjector.apply] like `ReadySetCellTest`, never calling
 * [ReadyPredicate] directly (that predicate-level truth table belongs to
 * task computenet-98u.1.1). Fixture shape matches `ReadySetCellTest`: a
 * hand-built [ChangeRecord] feed over an in-process [MirrorProjector], no
 * `bd`, no `dolt`, no workspace on disk.
 *
 * Deliberately does not re-cover 98u.1.2's `unblock` (closing the blocker
 * moves both the blocker and its dependent in the same record) or
 * `edge-remove` (structural edge deletion) single-delta cases — this
 * class's derived-not-blocked test below flips the blocker's *status* in
 * both directions across separate records instead, which is the different
 * thing the task calls for.
 */
class ReadyClauseCoverageTest {

    // ------------------------------------------------------------------
    // fixture (same shape as ReadySetCellTest)
    // ------------------------------------------------------------------

    private val minter = DotMinter("beads-scratch-98u-clause")

    /** Projector plus a ready cell attached before the first record. */
    private fun rig(): Pair<MirrorProjector, ReadySetCell> {
        val projector = MirrorProjector(minter)
        return projector to ReadySetCell.derivedFrom(projector)
    }

    private fun record(
        height: Long,
        issue: String,
        type: DiffType?,
        vararg fields: Pair<String, JsonElement?>,
        edges: List<EdgeDiff> = emptyList(),
        ordinal: Int = 0,
    ) = ChangeRecord(
        commitHash = "commit-$height-$ordinal",
        position = FeedPosition(height, ordinal),
        issueId = issue,
        diffType = type,
        fieldDiffs = fields.map { (column, value) -> FieldDiff(column, old = null, new = value) },
        edgeDiffs = edges,
    )

    /** An `ADDED` record for an ordinary open task — ready but for whatever blocks it. */
    private fun openTask(
        height: Long,
        issue: String,
        vararg extra: Pair<String, JsonElement?>,
        edges: List<EdgeDiff> = emptyList(),
    ) = record(
        height,
        issue,
        DiffType.ADDED,
        "status" to JsonPrimitive("open"),
        "issue_type" to JsonPrimitive("task"),
        *extra,
        edges = edges,
    )

    private fun blocks(issue: String, target: String, type: String = "blocks") =
        EdgeDiff(DiffType.ADDED, issue, target, type)

    // ------------------------------------------------------------------
    // status: default status set (READY-COVERAGE row 3)
    // ------------------------------------------------------------------

    @Test
    fun `an open ready issue leaves the set when a record sets status=closed`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "A"))
        ready.readySet() shouldBe setOf("A")

        projector.apply(record(2, "A", DiffType.MODIFIED, "status" to JsonPrimitive("closed")))

        ready.readySet() shouldBe emptySet()
    }

    @Test
    fun `an in_progress issue is in the ready set`() {
        val (projector, ready) = rig()
        projector.apply(
            record(
                1,
                "A",
                DiffType.ADDED,
                "status" to JsonPrimitive("in_progress"),
                "issue_type" to JsonPrimitive("task"),
            )
        )

        ready.readySet() shouldBe setOf("A")
    }

    // ------------------------------------------------------------------
    // pinned (Ex/pinned; READY-COVERAGE row 4)
    // ------------------------------------------------------------------

    @Test
    fun `a ready issue leaves the set when pinned is set true, and re-enters when unpinned`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "C"))
        ready.readySet() shouldBe setOf("C")

        // Driven through a synthetic feed record, the shape production
        // deltas take (READY-COVERAGE §3: pinned/ephemeral render as bare
        // JSON integers, never as JSON booleans) — this workspace's export
        // has zero pinned rows to draw a real one from (verified 2026-08-18).
        projector.apply(record(2, "C", DiffType.MODIFIED, "pinned" to JsonPrimitive(1)))
        ready.readySet() shouldBe emptySet()

        projector.apply(record(3, "C", DiffType.MODIFIED, "pinned" to JsonPrimitive(0)))
        ready.readySet() shouldBe setOf("C")
    }

    // ------------------------------------------------------------------
    // ephemeral (same flip shape as pinned; READY-COVERAGE row 6)
    // ------------------------------------------------------------------

    @Test
    fun `a ready issue leaves the set when ephemeral is set true, and re-enters when unset`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "D"))
        ready.readySet() shouldBe setOf("D")

        projector.apply(record(2, "D", DiffType.MODIFIED, "ephemeral" to JsonPrimitive(1)))
        ready.readySet() shouldBe emptySet()

        projector.apply(record(3, "D", DiffType.MODIFIED, "ephemeral" to JsonPrimitive(0)))
        ready.readySet() shouldBe setOf("D")
    }

    // ------------------------------------------------------------------
    // type exclusions (Ex/type-exclusion; READY-COVERAGE row 8b / §1.1):
    // the base four plus DefaultInfraTypes' three, one parameterized loop.
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = ["merge-request", "gate", "molecule", "rig", "agent", "role", "message"])
    fun `an issue of an excluded type, otherwise ready, is never in the set`(excludedType: String) {
        val (projector, ready) = rig()
        projector.apply(
            record(
                1,
                "E",
                DiffType.ADDED,
                "status" to JsonPrimitive("open"),
                "issue_type" to JsonPrimitive(excludedType),
            )
        )

        ready.readySet() shouldBe emptySet()
    }

    // ------------------------------------------------------------------
    // derived not-blocked (READY-COVERAGE row 5 / §2): the blocker's own
    // STATUS flipping in both directions across separate records — not
    // 98u.1.2's single-delta unblock/edge-remove cases.
    // ------------------------------------------------------------------

    @Test
    fun `dependent membership flips on the blocker's status flip, in both directions`() {
        val (projector, ready) = rig()
        projector.apply(openTask(1, "Blocker"))
        projector.apply(openTask(2, "Dependent", edges = listOf(blocks("Dependent", "Blocker"))))

        // Blocker open: Dependent is held out by the live blocking edge;
        // Blocker itself has no blocker of its own, so it is in.
        ready.readySet() shouldBe setOf("Blocker")

        // Blocker closes (its own separate record): it leaves the status
        // set AND stops being an open blocker, so Dependent enters.
        projector.apply(record(3, "Blocker", DiffType.MODIFIED, "status" to JsonPrimitive("closed")))
        ready.readySet() shouldBe setOf("Dependent")

        // Blocker reopens (a second, later record, no structural edge
        // change at all): it re-enters the status set AND becomes an open
        // blocker again, so Dependent leaves once more.
        projector.apply(record(4, "Blocker", DiffType.MODIFIED, "status" to JsonPrimitive("open")))
        ready.readySet() shouldBe setOf("Blocker")
    }
}
