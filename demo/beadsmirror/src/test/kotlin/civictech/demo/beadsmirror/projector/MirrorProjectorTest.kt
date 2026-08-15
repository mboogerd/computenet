package civictech.demo.beadsmirror.projector

import civictech.cell.Propagate
import civictech.cell.data.OrMapCell
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.2.1: the projector folds issue-field change records into a
 * composite-key OR-map with feed-position-deterministic dots.
 *
 * Everything here is hand-built [ChangeRecord]s over an in-process
 * [OrMapCell] — no `bd`, no `dolt`, no JUnit assumptions — so the suite is a
 * real CI gate rather than a green-but-skipped one.
 */
class MirrorProjectorTest {

    private val minter = DotMinter("beads-scratch-42")

    private fun projector(cell: OrMapCell<MirrorKey, String> = OrMapCell()) = MirrorProjector(minter, cell)

    /** A record of one commit's changes to one issue. `null` field value = cleared. */
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

    /** Records what the cell emits — one emission per *effective* delta. */
    private fun emissions(cell: OrMapCell<MirrorKey, String>): MutableList<TaggedMapDelta<MirrorKey, String>> {
        val out = mutableListOf<TaggedMapDelta<MirrorKey, String>>()
        cell.outlet.subscribe(
            Use.fixed(
                object : Propagate<TaggedMapDelta<MirrorKey, String>> {
                    override fun propagate(value: TaggedMapDelta<MirrorKey, String>) {
                        out += value
                    }
                },
                PortRef.generate(),
            )
        )
        return out
    }

    /** The JSON string form the projector stores a scalar under. */
    private fun json(value: String) = JsonPrimitive(value).toString()

    // -----------------------------------------------------------------
    // rule 1 — one record, one delta, exactly the keys it touched
    // -----------------------------------------------------------------

    @Test
    fun `a create with N field diffs applies as one delta over the N field keys plus presence`() {
        val cell = OrMapCell<MirrorKey, String>()
        val emitted = emissions(cell)

        projector(cell).apply(
            issueRecord(1, "A", DiffType.ADDED, "status" to "open", "notes" to "x")
        )

        val delta = emitted.single() // exactly ONE delta — the commit is not re-split
        delta.keys() shouldBe setOf(
            MirrorKey.presence("A"),
            MirrorKey("A", "notes"),
            MirrorKey("A", "status"),
        )
        delta.value(MirrorKey("A", "status")) shouldBe json("open")
        delta.value(MirrorKey.presence("A")) shouldBe MirrorKey.PRESENT_VALUE
    }

    @Test
    fun `an edit applies as one delta touching only the edited field keys`() {
        val cell = OrMapCell<MirrorKey, String>()
        val projector = projector(cell)
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "status" to "open"))
        val emitted = emissions(cell)

        projector.apply(issueRecord(5, "A", DiffType.MODIFIED, "status" to "closed", "notes" to "y"))

        val delta = emitted.single()
        // the presence key is untouched by an edit — it is set on create only
        delta.keys() shouldBe setOf(MirrorKey("A", "notes"), MirrorKey("A", "status"))
        projector.view() shouldBe mapOf("A" to mapOf("notes" to json("y"), "status" to json("closed")))
    }

    @Test
    fun `an edge-only record touches no field key`() {
        val cell = OrMapCell<MirrorKey, String>()
        val emitted = emissions(cell)

        // diffType null: the commit touched this issue's edges without leaving
        // a dolt_diff_issues row. Edges are computenet-dqj.2.2's business.
        projector(cell).apply(issueRecord(3, "A", null)).shouldBeNull()

        emitted.isEmpty() shouldBe true
    }

    @Test
    fun `a cleared field removes its key without disturbing its siblings`() {
        val projector = projector()
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "status" to "open", "notes" to "x"))

        projector.apply(issueRecord(2, "A", DiffType.MODIFIED, "notes" to null))

        projector.view() shouldBe mapOf("A" to mapOf("status" to json("open")))
    }

    // -----------------------------------------------------------------
    // rule 2 — presence decides membership, on create and on remove
    // -----------------------------------------------------------------

    @Test
    fun `a removal tombstones the presence key in the same delta and the issue leaves the view`() {
        val cell = OrMapCell<MirrorKey, String>()
        val projector = projector(cell)
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "status" to "open"))
        projector.apply(issueRecord(1, "B", DiffType.ADDED, "status" to "open", ordinal = 1))
        val emitted = emissions(cell)

        projector.apply(issueRecord(4, "A", DiffType.REMOVED))

        val delta = emitted.single()
        delta.dels.keys shouldBe setOf(MirrorKey.presence("A"), MirrorKey("A", "status"))
        delta.puts.isEmpty() shouldBe true
        projector.view().keys shouldBe setOf("B")
    }

    @Test
    fun `a stale field key left live by a tag-precise remove does not resurrect the issue`() {
        // [24-TMAP-04]: a remove tombstones exactly the dots it observed, so a
        // put it never observed survives it. Gating membership on the presence
        // key is what stops that straggler reappearing as a partial issue.
        val projector = projector()
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "status" to "open"))
        projector.apply(issueRecord(4, "A", DiffType.REMOVED))

        projector.apply(issueRecord(6, "A", DiffType.MODIFIED, "status" to "closed"))

        projector.rawValue(MirrorKey("A", "status")).shouldNotBeNull() shouldBe json("closed")
        // ... and yet the materialized view has no A at all
        projector.view() shouldBe emptyMap()
    }

    // -----------------------------------------------------------------
    // rule 3 — replay mints identical dots
    // -----------------------------------------------------------------

    @Test
    fun `replaying a record re-mints identical dots and leaves the state unchanged`() {
        val cell = OrMapCell<MirrorKey, String>()
        val projector = projector(cell)
        val r5 = issueRecord(5, "A", DiffType.ADDED, "status" to "open", "notes" to "x")

        val first = projector.apply(r5)
        val onceApplied = cell.state()
        val emitted = emissions(cell)
        val second = projector.apply(r5)

        second shouldBe first // byte-identical dots, not merely a convergent set
        cell.state() shouldBe onceApplied
        // the cell recognises the replay as carrying no new dot information and
        // emits nothing — the echo terminates
        emitted.isEmpty() shouldBe true
    }

    @Test
    fun `replaying a whole removal sequence is idempotent`() {
        fun run(times: Int): TaggedMapDelta<MirrorKey, String> {
            val cell = OrMapCell<MirrorKey, String>()
            val projector = projector(cell)
            val records = listOf(
                issueRecord(1, "A", DiffType.ADDED, "status" to "open"),
                issueRecord(2, "A", DiffType.MODIFIED, "status" to "closed"),
                issueRecord(3, "A", DiffType.REMOVED),
            )
            repeat(times) { projector.applyAll(records) }
            return cell.state()
        }

        run(2) shouldBe run(1)
    }

    @Test
    fun `replaying an edit sequence keeps the field the last commit wrote`() {
        // The removal sequence above is idempotent partly by luck: it ends with
        // everything tombstoned, so a spurious tombstone cannot be seen. Here
        // the sequence ends LIVE, which is what catches a re-put that
        // tombstones dots outside its own past: the earlier record's replay
        // would bury the later record's live dot, and the later record's replay
        // would re-mint a dot already covered — leaving no live dot at all.
        fun run(times: Int): Map<String, Map<String, String>> {
            val projector = projector()
            val records = listOf(
                issueRecord(1, "A", DiffType.ADDED, "status" to "open"),
                issueRecord(2, "A", DiffType.MODIFIED, "status" to "closed"),
            )
            repeat(times) { projector.applyAll(records) }
            return projector.view()
        }

        run(1) shouldBe mapOf("A" to mapOf("status" to json("closed")))
        run(2) shouldBe run(1)
    }

    @Test
    fun `replaying a clear-then-reset sequence keeps the field`() {
        // Same hazard on the tombstone-only path: the clear at height 2 must
        // cover the dot minted at height 1 and nothing later, or the replayed
        // reset at height 3 re-mints a dot the replayed clear just buried.
        fun run(times: Int): Map<String, Map<String, String>> {
            val projector = projector()
            val records = listOf(
                issueRecord(1, "A", DiffType.ADDED, "status" to "open"),
                issueRecord(2, "A", DiffType.MODIFIED, "status" to null),
                issueRecord(3, "A", DiffType.MODIFIED, "status" to "closed"),
            )
            repeat(times) { projector.applyAll(records) }
            return projector.view()
        }

        run(1) shouldBe mapOf("A" to mapOf("status" to json("closed")))
        run(2) shouldBe run(1)
    }

    // -----------------------------------------------------------------
    // rule 4 — composite keying keeps concurrent per-field edits
    // -----------------------------------------------------------------

    @Test
    fun `two records editing different fields of one issue both survive`() {
        val projector = projector()
        projector.apply(issueRecord(1, "A", DiffType.ADDED))
        val statusEdit = issueRecord(2, "A", DiffType.MODIFIED, "status" to "closed")
        val notesEdit = issueRecord(3, "A", DiffType.MODIFIED, "notes" to "hi")

        projector.apply(statusEdit)
        projector.apply(notesEdit)

        projector.view() shouldBe mapOf("A" to mapOf("notes" to json("hi"), "status" to json("closed")))
    }

    @Test
    fun `divergence control - keying by issue id alone loses one of the two edits`() {
        // The same two records, folded under the mutated keying the composite
        // key exists to prevent: one key per issue, so both edits are puts on
        // "A" and DOT_ORDER must pick a winner. This is the assertion that
        // makes the previous test non-vacuous: it fails under the mutation.
        val statusEdit = issueRecord(2, "A", DiffType.MODIFIED, "status" to "closed")
        val notesEdit = issueRecord(3, "A", DiffType.MODIFIED, "notes" to "hi")
        val broken = OrMapCell<String, String>()

        listOf(statusEdit to json("closed"), notesEdit to json("hi")).forEach { (record, value) ->
            val dot = minter.dot(record.position, 1)
            broken.deltaInlet.call.propagate(TaggedMapDelta(puts = mapOf(record.issueId to mapOf(dot to value))))
        }

        broken.membership() shouldBe setOf("A")
        // only the later edit is readable; the status edit is unrecoverable —
        // there is no second key to hold it
        broken.value("A") shouldBe json("hi")
    }
}
