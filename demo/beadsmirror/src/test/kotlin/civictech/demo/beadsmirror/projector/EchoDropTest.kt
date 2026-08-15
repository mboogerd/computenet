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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

/**
 * computenet-dqj.2.3: a record whose `metadata.cn_dot` the mirror already
 * holds is dropped whole, before any dot is minted (epic computenet-dqj
 * acceptance rule 5; feature computenet-dqj.2's r6 example).
 *
 * Hand-built [ChangeRecord]s with synthetically stamped `metadata.cn_dot` —
 * there is no live echo source single-node (BDS1) — over an in-process
 * [OrMapCell]. No `bd`, no `dolt`, so this is a real CI gate.
 */
class EchoDropTest {

    private val minter = DotMinter("beads-scratch-42")

    private fun projector(cell: OrMapCell<MirrorKey, String> = OrMapCell()) = MirrorProjector(minter, cell)

    private fun metadata(cnDot: String?): JsonObject? =
        cnDot?.let { buildJsonObject { put("cn_dot", JsonPrimitive(it)) } }

    /** A record of one commit's changes to one issue, optionally carrying `metadata.cn_dot`. */
    private fun issueRecord(
        height: Long,
        issue: String,
        type: DiffType?,
        vararg fields: Pair<String, String?>,
        ordinal: Int = 0,
        cnDot: String? = null,
    ) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, ordinal),
        issueId = issue,
        diffType = type,
        fieldDiffs = fields.map { (column, value) ->
            FieldDiff(column, old = null, new = value?.let(::JsonPrimitive))
        },
        edgeDiffs = emptyList(),
        oldMetadata = if (type == DiffType.REMOVED) metadata(cnDot) else null,
        newMetadata = if (type != DiffType.REMOVED) metadata(cnDot) else null,
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

    // -----------------------------------------------------------------
    // rule 5 — an already-held cn_dot drops the whole record
    // -----------------------------------------------------------------

    @Test
    fun `a record whose cn_dot is already held applies as a no-op`() {
        val cell = OrMapCell<MirrorKey, String>()
        val projector = projector(cell)

        // seeds "peerX:41" into the held-dot set: the projector applies a
        // first record carrying it (no live echo source single-node, so this
        // is how the set comes to hold a peer's cn_dot before the duplicate
        // arrives — BDS1 tests stamp it synthetically either way).
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "status" to "open", cnDot = "peerX:41"))
        val stateBefore = cell.state()
        val emitted = emissions(cell)

        // a record carrying the SAME already-held cn_dot, touching a
        // different issue and field, must not leave any trace at all.
        val dropped = projector.apply(
            issueRecord(2, "B", DiffType.ADDED, "status" to "closed", cnDot = "peerX:41")
        )

        dropped shouldBe null
        cell.state() shouldBe stateBefore // byte-equal: no cell state change
        emitted.isEmpty() shouldBe true // no delta was even attempted
        projector.view().keys shouldBe setOf("A") // "B" never entered the map
        projector.echoDropCount shouldBe 1
    }

    @Test
    fun `an unheld cn_dot applies normally, and a second delivery of it is then dropped`() {
        val cell = OrMapCell<MirrorKey, String>()
        val projector = projector(cell)
        val record = issueRecord(1, "A", DiffType.ADDED, "status" to "open", cnDot = "peerX:41")

        val first = projector.apply(record)

        first.shouldNotBeNull()
        projector.view() shouldBe mapOf("A" to mapOf("status" to JsonPrimitive("open").toString()))
        projector.echoDropCount shouldBe 0

        val stateAfterFirst = cell.state()
        val emitted = emissions(cell)
        val second = projector.apply(record) // exact redelivery: same commit, same cn_dot

        second shouldBe null
        cell.state() shouldBe stateAfterFirst
        emitted.isEmpty() shouldBe true
        projector.echoDropCount shouldBe 1
    }

    @Test
    fun `a record without cn_dot is unaffected by the registry`() {
        val cell = OrMapCell<MirrorKey, String>()
        val projector = projector(cell)

        // one record carrying a cn_dot, one that carries none at all — the
        // second must apply on its own merits, untouched by the registry.
        projector.apply(issueRecord(1, "A", DiffType.ADDED, "status" to "open", cnDot = "peerX:41"))
        val plain = projector.apply(issueRecord(2, "B", DiffType.ADDED, "status" to "open"))

        plain.shouldNotBeNull()
        projector.view() shouldBe mapOf(
            "A" to mapOf("status" to JsonPrimitive("open").toString()),
            "B" to mapOf("status" to JsonPrimitive("open").toString()),
        )
        projector.echoDropCount shouldBe 0
    }
}
