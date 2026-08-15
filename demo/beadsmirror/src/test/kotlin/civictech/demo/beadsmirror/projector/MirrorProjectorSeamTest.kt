package civictech.demo.beadsmirror.projector

import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.EdgeDiff
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

/**
 * The seams between computenet-dqj.2.1 (issue-field OR-map), .2.2
 * (dependency-edge SetCell) and .2.3 (cn_dot echo drop) — the interactions no
 * single task's suite owns, because each task tested only its own half of a
 * projector all three of them edit.
 *
 * Three properties, all feature-level (computenet-dqj.2):
 *
 * 1. [admits] gates **both** halves: a dropped record mints no field dot *and*
 *    no edge tag. `EchoDropTest` proves the field half only — every record it
 *    builds carries `edgeDiffs = emptyList()` and it never constructs the
 *    projector with a [SetCell] at all.
 * 2. Field dots and edge tags are genuinely disjoint, read off the **emitted
 *    deltas** rather than re-derived from [DotMinter] with the same formula the
 *    projector used. `MirrorEdgeProjectionTest`'s disjointness test recomputes
 *    the edge index as `minter.dot(position, fieldDots.size)`, so it agrees with
 *    an off-by-one in [MirrorProjector] instead of catching it.
 * 3. Replay idempotence of a **mixed** sequence — field edits and edge changes
 *    and an echoed record interleaved. Each task proved its own half replays;
 *    the two tombstone floors are implemented separately (`mintedLive` vs
 *    `mintedLiveEdges`) and the echo registry is shared across both, so the
 *    combination is its own property.
 *
 * Pure JVM — no `bd`, no `dolt`, no JUnit assumptions — so this is a real CI
 * gate like its three siblings.
 */
class MirrorProjectorSeamTest {

    private val minter = DotMinter("beads-scratch-42")

    private fun metadata(cnDot: String?): JsonObject? =
        cnDot?.let { buildJsonObject { put("cn_dot", JsonPrimitive(it)) } }

    /** A record carrying any mix of field diffs, edge diffs and a cn_dot. */
    private fun record(
        height: Long,
        issue: String,
        type: DiffType?,
        fields: List<Pair<String, String?>> = emptyList(),
        edges: List<Triple<DiffType, String, String>> = emptyList(),
        cnDot: String? = null,
        ordinal: Int = 0,
    ) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, ordinal),
        issueId = issue,
        diffType = type,
        fieldDiffs = fields.map { (column, value) ->
            FieldDiff(column, old = null, new = value?.let(::JsonPrimitive))
        },
        edgeDiffs = edges.map { (diffType, dependsOn, edgeType) ->
            EdgeDiff(diffType, issue, dependsOn, edgeType)
        },
        oldMetadata = if (type == DiffType.REMOVED) metadata(cnDot) else null,
        newMetadata = if (type != DiffType.REMOVED) metadata(cnDot) else null,
    )

    private fun mapEmissions(cell: OrMapCell<MirrorKey, String>): MutableList<TaggedMapDelta<MirrorKey, String>> {
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

    private fun setEmissions(cell: SetCell<MirrorEdge>): MutableList<SetDelta<MirrorEdge>> {
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

    private fun json(value: String) = JsonPrimitive(value).toString()

    // -----------------------------------------------------------------
    // seam 1 — admits() gates BOTH halves
    // -----------------------------------------------------------------

    @Test
    fun `an echo-dropped record mints neither field dots nor edge tags`() {
        val map = OrMapCell<MirrorKey, String>()
        val set = SetCell<MirrorEdge>()
        val projector = MirrorProjector(minter, map, set)

        // seed "peerX:41" as held, via a record that carries it.
        projector.apply(record(1, "A", DiffType.ADDED, fields = listOf("status" to "open"), cnDot = "peerX:41"))

        val mapBefore = map.state()
        val edgesBefore = set.membership()
        val mapEmitted = mapEmissions(map)
        val setEmitted = setEmissions(set)

        // a record carrying the SAME held cn_dot, this time with BOTH a field
        // diff and a dependency-edge diff: neither half may leave a trace.
        val dropped = projector.apply(
            record(
                2, "B", DiffType.ADDED,
                fields = listOf("status" to "closed"),
                edges = listOf(Triple(DiffType.ADDED, "A", "blocks")),
                cnDot = "peerX:41",
            )
        )

        dropped shouldBe null
        map.state() shouldBe mapBefore
        set.membership() shouldBe edgesBefore
        mapEmitted.isEmpty() shouldBe true
        setEmitted.isEmpty() shouldBe true
        projector.edgeView() shouldBe emptySet()
        projector.view().keys shouldBe setOf("A")
        projector.echoDropCount shouldBe 1
    }

    // -----------------------------------------------------------------
    // seam 2 — the two halves' dots are disjoint, read off the deltas
    // -----------------------------------------------------------------

    @Test
    fun `one record's field dots and edge tags are disjoint as actually emitted`() {
        val set = SetCell<MirrorEdge>()
        val projector = MirrorProjector(minter, OrMapCell(), set)
        val setEmitted = setEmissions(set)

        // ADDED reserves the presence key (index 0) plus one field key
        // (index 1); MODIFIED reserves index 0 without minting it. Both must
        // keep every edge tag clear of the whole reserved range.
        listOf(DiffType.ADDED, DiffType.MODIFIED).forEachIndexed { i, diffType ->
            val fieldDelta = projector.apply(
                record(
                    (i + 1).toLong(), "B$i", diffType,
                    fields = listOf("status" to "open", "priority" to "1"),
                    edges = listOf(
                        Triple(DiffType.ADDED, "A", "blocks"),
                        Triple(DiffType.ADDED, "C", "related"),
                    ),
                )
            )

            val fieldDots: Set<Timestamp> =
                fieldDelta?.puts?.values?.flatMap { it.keys }?.toSet().orEmpty()
            val edgeTags: Set<Timestamp> =
                setEmitted.last().adds.values.flatMap { it }.toSet()

            edgeTags.size shouldBe 2
            (fieldDots intersect edgeTags) shouldBe emptySet()
            // and the packed counters really are above every field slot the
            // record could have used, not merely different by accident.
            val ceiling = DotMinter.counter(FeedPosition((i + 1).toLong(), 0), 2) // presence + 2 fields - 1
            edgeTags.all { it.counter > ceiling } shouldBe true
        }
    }

    // -----------------------------------------------------------------
    // seam 3 — a MIXED sequence replays idempotently
    // -----------------------------------------------------------------

    /**
     * Field edits, edge add/remove/re-add and an echoed record interleaved.
     * Every record is replayed from the top on each pass, which is what a
     * crash-restart before the checkpoint advanced actually produces.
     */
    private fun mixedSequence() = listOf(
        record(1, "A", DiffType.ADDED, fields = listOf("status" to "open")),
        record(2, "B", DiffType.ADDED, fields = listOf("status" to "open"), edges = listOf(Triple(DiffType.ADDED, "A", "blocks"))),
        record(3, "A", DiffType.MODIFIED, fields = listOf("status" to "closed", "priority" to "1")),
        record(4, "B", DiffType.MODIFIED, edges = listOf(Triple(DiffType.REMOVED, "A", "blocks"))),
        // an echoed record: carries a cn_dot, touches both halves.
        record(5, "C", DiffType.ADDED, fields = listOf("status" to "open"), edges = listOf(Triple(DiffType.ADDED, "A", "related")), cnDot = "peerX:41"),
        record(6, "B", DiffType.MODIFIED, edges = listOf(Triple(DiffType.ADDED, "A", "blocks"))),
        record(7, "A", DiffType.MODIFIED, fields = listOf("priority" to null)),
    )

    private fun runPasses(times: Int): Triple<Map<String, Map<String, String>>, Set<MirrorEdge>, TaggedMapDelta<MirrorKey, String>> {
        val map = OrMapCell<MirrorKey, String>()
        val projector = MirrorProjector(minter, map, SetCell())
        repeat(times) { projector.applyAll(mixedSequence()) }
        return Triple(projector.view(), projector.edgeView(), map.state())
    }

    @Test
    fun `replaying a mixed field-edge-echo sequence leaves the same view and edge set`() {
        val once = runPasses(1)
        val twice = runPasses(2)
        val thrice = runPasses(3)

        // the once-applied answer is the reference: A closed with priority
        // cleared, B and C present, the B->A blocks edge re-added at commit 6.
        once.first shouldBe mapOf(
            "A" to mapOf("status" to json("closed")),
            "B" to mapOf("status" to json("open")),
            "C" to mapOf("status" to json("open")),
        )
        once.second shouldBe setOf(MirrorEdge("B", "A", "blocks"), MirrorEdge("C", "A", "related"))

        twice.first shouldBe once.first
        twice.second shouldBe once.second
        thrice.first shouldBe once.first
        thrice.second shouldBe once.second
    }

    @Test
    fun `replaying a mixed sequence leaves the OR-map's dot state unchanged`() {
        val once = runPasses(1).third
        val twice = runPasses(2).third

        twice.membership() shouldBe once.membership()
        twice.keys().forEach { key -> twice.value(key) shouldBe once.value(key) }
    }

    @Test
    fun `the echoed record in a mixed sequence is dropped once per replay and never twice-minted`() {
        val projector = MirrorProjector(minter, OrMapCell(), SetCell())
        projector.applyAll(mixedSequence())
        projector.echoDropCount shouldBe 0 // first pass: the cn_dot is new

        projector.applyAll(mixedSequence())
        projector.echoDropCount shouldBe 1 // second pass: it is held, dropped whole
    }
}
