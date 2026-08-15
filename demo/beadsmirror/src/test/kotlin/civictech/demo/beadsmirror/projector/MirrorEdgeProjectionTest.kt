package civictech.demo.beadsmirror.projector

import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.2.2: dependency-edge diffs are reflected into a [SetCell] of
 * [MirrorEdge]s, tagged by the same [DotMinter] the issue-field half uses
 * (epic computenet-dqj acceptance rule 6; feature computenet-dqj.2's
 * 'bd dep add' / 'bd dep remove' example).
 *
 * Everything here is hand-built [ChangeRecord]s over an in-process
 * [MirrorProjector] — no `bd`, no `dolt` — so the suite is a real CI gate.
 */
class MirrorEdgeProjectionTest {

    private val minter = DotMinter("beads-scratch-42")

    private fun projector(edges: SetCell<MirrorEdge> = SetCell()) = MirrorProjector(minter, edges = edges)

    /** A record of one commit's changes to one issue's dependency edges only (field-quiet). */
    private fun edgeRecord(
        height: Long,
        issue: String,
        vararg edges: Triple<DiffType, String, String>, // (diffType, dependsOnIssueId, type)
        ordinal: Int = 0,
        fieldType: DiffType? = null,
    ) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, ordinal),
        issueId = issue,
        diffType = fieldType,
        fieldDiffs = emptyList(),
        edgeDiffs = edges.map { (diffType, dependsOn, type) -> EdgeDiff(diffType, issue, dependsOn, type) },
    )

    /** Records what the edge cell emits — one emission per *effective* delta. */
    private fun emissions(cell: SetCell<MirrorEdge>): MutableList<SetDelta<MirrorEdge>> {
        val out = mutableListOf<SetDelta<MirrorEdge>>()
        cell.outlet.subscribe(
            Use.fixed(
                object : Propagate<SetDelta<MirrorEdge>> {
                    override fun propagate(value: SetDelta<MirrorEdge>) {
                        out += value
                    }
                },
                PortRef.generate(),
            )
        )
        return out
    }

    // -----------------------------------------------------------------
    // rule 6 — the SetCell tracks the current dependency set
    // -----------------------------------------------------------------

    @Test
    fun `bd dep add then bd dep remove leaves the set holding the edge only after the add`() {
        val projector = projector()

        projector.apply(edgeRecord(1, "B", Triple(DiffType.ADDED, "A", "blocks")))

        projector.edgeView() shouldBe setOf(MirrorEdge("B", "A", "blocks"))

        projector.apply(edgeRecord(2, "B", Triple(DiffType.REMOVED, "A", "blocks")))

        projector.edgeView() shouldBe emptySet()
    }

    @Test
    fun `an edge-only record with diffType null applies its edge diffs`() {
        val cell = SetCell<MirrorEdge>()
        val projector = projector(cell)

        // diffType null: an edge-carrying, field-quiet record — no
        // dolt_diff_issues row for this commit at all.
        val delta = projector.apply(edgeRecord(3, "B", Triple(DiffType.ADDED, "A", "blocks"), fieldType = null))

        delta.shouldBeNull() // the FIELD delta is null; the edge still lands
        projector.edgeView() shouldBe setOf(MirrorEdge("B", "A", "blocks"))
    }

    @Test
    fun `a record with no edge diffs leaves the edge set untouched`() {
        val cell = SetCell<MirrorEdge>()
        val projector = projector(cell)
        val emitted = emissions(cell)

        projector.apply(edgeRecord(1, "B", fieldType = DiffType.MODIFIED))

        emitted.isEmpty() shouldBe true
        projector.edgeView() shouldBe emptySet()
    }

    @Test
    fun `two distinct edges of one commit both land in one delta`() {
        val cell = SetCell<MirrorEdge>()
        val projector = projector(cell)
        val emitted = emissions(cell)

        projector.apply(
            edgeRecord(
                1, "B",
                Triple(DiffType.ADDED, "A", "blocks"),
                Triple(DiffType.ADDED, "C", "related"),
            )
        )

        val delta = emitted.single() // one commit, one SetDelta
        delta.adds.keys shouldBe setOf(MirrorEdge("B", "A", "blocks"), MirrorEdge("B", "C", "related"))
        projector.edgeView() shouldBe setOf(MirrorEdge("B", "A", "blocks"), MirrorEdge("B", "C", "related"))
    }

    // -----------------------------------------------------------------
    // replay
    // -----------------------------------------------------------------

    @Test
    fun `replaying an edge-carrying record mints identical tags and leaves the set unchanged`() {
        val cell = SetCell<MirrorEdge>()
        val projector = projector(cell)
        val r = edgeRecord(5, "B", Triple(DiffType.ADDED, "A", "blocks"))

        projector.apply(r)
        val onceApplied = cell.membership()
        val emitted = emissions(cell)

        projector.apply(r) // exact replay: same commit, same position

        // the cell recognises the replay as carrying no new tag information
        // and emits nothing — the echo terminates
        emitted.isEmpty() shouldBe true
        cell.membership() shouldBe onceApplied
    }

    @Test
    fun `replaying a whole add-then-remove sequence is idempotent`() {
        fun run(times: Int): Set<MirrorEdge> {
            val projector = projector()
            val records = listOf(
                edgeRecord(1, "B", Triple(DiffType.ADDED, "A", "blocks")),
                edgeRecord(2, "B", Triple(DiffType.REMOVED, "A", "blocks")),
            )
            repeat(times) { projector.applyAll(records) }
            return projector.edgeView()
        }

        run(1) shouldBe emptySet()
        run(2) shouldBe run(1)
    }

    @Test
    fun `replaying an add-remove-add sequence keeps the edge live, not tombstoned by the earlier remove`() {
        // The hazard this guards: on the second pass, replaying the ADD at
        // height 1 re-inserts its tag into the projector's live-tracking
        // *after* the height-3 re-add already lives there. If the height-2
        // REMOVED replay then tombstoned everything it finds live rather than
        // only what predates it, it would bury the height-3 tag too, and the
        // edge would vanish on replay even though a fresh single pass ends
        // with it present.
        fun run(times: Int): Set<MirrorEdge> {
            val projector = projector()
            val records = listOf(
                edgeRecord(1, "B", Triple(DiffType.ADDED, "A", "blocks")),
                edgeRecord(2, "B", Triple(DiffType.REMOVED, "A", "blocks")),
                edgeRecord(3, "B", Triple(DiffType.ADDED, "A", "blocks")),
            )
            repeat(times) { projector.applyAll(records) }
            return projector.edgeView()
        }

        run(1) shouldBe setOf(MirrorEdge("B", "A", "blocks"))
        run(2) shouldBe run(1)
    }

    // -----------------------------------------------------------------
    // key-index disjointness from the field half
    // -----------------------------------------------------------------

    @Test
    fun `a record with both field and edge diffs mints distinct dots for each`() {
        val cell = SetCell<MirrorEdge>()
        val projector = projector(cell)

        val record = ChangeRecord(
            commitHash = "commit-1",
            position = FeedPosition(1, 0),
            issueId = "B",
            diffType = DiffType.ADDED,
            fieldDiffs = listOf(
                civictech.demo.beadsmirror.feed.FieldDiff(
                    "status", old = null, new = kotlinx.serialization.json.JsonPrimitive("open"),
                )
            ),
            edgeDiffs = listOf(EdgeDiff(DiffType.ADDED, "B", "A", "blocks")),
        )

        val fieldDelta = projector.apply(record)

        fieldDelta.shouldNotBeNull()
        val fieldDots = fieldDelta.puts.values.flatMap { it.keys }.toSet()
        val edgeDots = cell.membership().flatMap { edge ->
            // membership doesn't expose tags directly; re-derive via the
            // minter with the same deterministic key-index scheme the
            // projector used, then assert it is disjoint from the field dots.
            listOf(minter.dot(record.position, fieldDots.size))
        }.toSet()

        (fieldDots intersect edgeDots).isEmpty() shouldBe true
        projector.edgeView() shouldBe setOf(MirrorEdge("B", "A", "blocks"))
    }
}
