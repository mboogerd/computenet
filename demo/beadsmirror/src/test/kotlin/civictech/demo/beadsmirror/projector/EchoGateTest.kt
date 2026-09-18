package civictech.demo.beadsmirror.projector

import civictech.cell.data.OrMapCell
import civictech.demo.beadsmirror.baseline.MirrorEvent
import civictech.demo.beadsmirror.feed.ChangeRecord
import civictech.demo.beadsmirror.feed.DiffType
import civictech.demo.beadsmirror.feed.FeedPosition
import civictech.demo.beadsmirror.feed.FieldDiff
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Feature computenet-6wc.3, task computenet-6wc.3.1: the [EchoGate] classifies
 * each feed record from a **writer-registered token's two diff sides**, and
 * nothing else (decisions 6wc.3-D4, D6, D7).
 *
 * This file replaces `EchoDropTest`, which pinned the BDS1 rule this feature
 * removed: a `metadata.cn_dot` the projector already held dropped the record
 * whole. That rule is a permanent false-positive generator once anything writes
 * a stamp back into `bd` — the stamp persists in `metadata`, so every *later
 * genuine* edit of a stamped row carries it and was dropped (feature clause 3,
 * measured on the feature's comment thread 2026-09-18). `clause 3` below is the
 * test that rule cannot pass.
 *
 * Hand-built [ChangeRecord]s throughout — no `bd`, no `dolt` — so this is a real
 * CI gate.
 */
class EchoGateTest {

    private val events = mutableListOf<MirrorEvent>()
    private val gate = EchoGate("ws-identity") { events += it }

    private val classifications: List<Classification>
        get() = events.filterIsInstance<MirrorEvent.RecordClassified>().map { it.classification }

    private fun metadata(vararg entries: Pair<String, String>): JsonObject? =
        if (entries.isEmpty()) null else JsonObject(entries.associate { it.first to JsonPrimitive(it.second) })

    /**
     * One commit's change to one issue. [old]/[new] are the `from_metadata` /
     * `to_metadata` sides verbatim, because the whole decision turns on the
     * difference between them.
     */
    private fun record(
        issue: String,
        height: Long = 1,
        type: DiffType? = DiffType.MODIFIED,
        field: Pair<String, String?> = "status" to "open",
        old: JsonObject? = null,
        new: JsonObject? = null,
    ) = ChangeRecord(
        commitHash = "commit-$height",
        position = FeedPosition(height, 0),
        issueId = issue,
        diffType = type,
        fieldDiffs = listOf(FieldDiff(field.first, old = null, new = field.second?.let(::JsonPrimitive))),
        edgeDiffs = emptyList(),
        oldMetadata = old,
        newMetadata = new,
    )

    // -----------------------------------------------------------------
    // clause 1 — the echo: a pending token this commit WROTE
    // -----------------------------------------------------------------

    @Test
    fun `a record whose commit wrote a pending token is ECHO, consumed, and withheld from the projector`() {
        gate.expectEcho("A", "tok-1")

        val echo = record(
            "A",
            old = metadata("foo" to "bar"),
            new = metadata("foo" to "bar", "cn_dot" to "peerX:41", "cn_echo" to "tok-1"),
        )
        val admitted = gate.admit(listOf(echo))

        admitted shouldBe emptyList()
        gate.echoCount shouldBe 1
        gate.localCount shouldBe 0
        gate.pendingCount() shouldBe 0 // the expectation is consumed by the match
        classifications shouldContainExactly listOf(Classification.ECHO)
    }

    @Test
    fun `a consumed expectation does not suppress a second record carrying the same token`() {
        gate.expectEcho("A", "tok-1")
        val echo = record("A", old = metadata("k" to "v"), new = metadata("k" to "v", "cn_echo" to "tok-1"))

        gate.admit(listOf(echo))
        // The same token arriving again — a replay, or a second commit that
        // somehow rewrote it — is NOT suppressed: one announcement, one
        // suppression.
        val second = gate.admit(listOf(echo))

        second shouldContainExactly listOf(echo)
        gate.echoCount shouldBe 1
        gate.localCount shouldBe 1
    }

    @Test
    fun `a cancelled expectation no longer suppresses its record`() {
        gate.expectEcho("A", "tok-1")
        gate.cancelEcho("A", "tok-1")

        val admitted = gate.admit(
            listOf(record("A", new = metadata("cn_echo" to "tok-1"))),
        )

        admitted.size shouldBe 1
        gate.echoCount shouldBe 0
        gate.pendingCount() shouldBe 0
    }

    @Test
    fun `an expectation is keyed on the issue as well as the token`() {
        gate.expectEcho("A", "tok-1")

        // same token, different issue: not this mirror's announced import.
        val admitted = gate.admit(listOf(record("B", new = metadata("cn_echo" to "tok-1"))))

        admitted.size shouldBe 1
        gate.echoCount shouldBe 0
        gate.pendingCount() shouldBe 1 // A's expectation survives untouched
    }

    // -----------------------------------------------------------------
    // clause 3 — no false positive
    // -----------------------------------------------------------------

    /**
     * **The test the removed BDS1 rule cannot pass.** After the applier stamps
     * a row, the stamp lives in bd's `metadata` forever, so a later ordinary
     * `bd update` on that row produces a diff whose two metadata sides both
     * carry the token (and the cn_dot). The commit did not *write* the token,
     * so the edit is local — and a rule keyed on "have I seen this token/dot
     * before" would drop it, and every edit after it, permanently.
     */
    @Test
    fun `an edit on a stamped row whose token is unchanged on both sides is LOCAL`() {
        gate.expectEcho("A", "tok-1")
        val stamp = metadata("foo" to "bar", "cn_dot" to "peerX:41", "cn_echo" to "tok-1")

        val laterEdit = record("A", height = 9, field = "priority" to "2", old = stamp, new = stamp)
        val admitted = gate.admit(listOf(laterEdit))

        admitted shouldContainExactly listOf(laterEdit)
        gate.echoCount shouldBe 0
        gate.localCount shouldBe 1
        classifications shouldContainExactly listOf(Classification.LOCAL)
        gate.pendingCount() shouldBe 1 // and the still-pending expectation was not consumed by it
    }

    @Test
    fun `a record carrying cn_dot but no pending token is LOCAL`() {
        val admitted = gate.admit(
            listOf(record("A", new = metadata("cn_dot" to "peerX:41", "cn_echo" to "never-announced"))),
        )

        admitted.size shouldBe 1
        gate.echoCount shouldBe 0
        gate.localCount shouldBe 1
    }

    @Test
    fun `a record with no metadata at all is LOCAL`() {
        val plain = record("A", new = null)

        gate.admit(listOf(plain)) shouldContainExactly listOf(plain)
        gate.localCount shouldBe 1
    }

    @Test
    fun `a REMOVED record is LOCAL even when its from-side carries a pending token`() {
        gate.expectEcho("A", "tok-1")

        // A removal has no `to_` side, so no commit can have written a token
        // in it; the applier imposes values, it never deletes an issue.
        val removal = record("A", type = DiffType.REMOVED, old = metadata("cn_echo" to "tok-1"), new = null)
        val admitted = gate.admit(listOf(removal))

        admitted shouldContainExactly listOf(removal)
        gate.echoCount shouldBe 0
        gate.pendingCount() shouldBe 1
    }

    @Test
    fun `a non-string cn_echo is treated as absent rather than coerced`() {
        gate.expectEcho("A", "7")
        val numeric = JsonObject(mapOf("cn_echo" to JsonPrimitive(7)))

        gate.admit(listOf(record("A", new = numeric))).size shouldBe 1
        gate.echoCount shouldBe 0
    }

    // -----------------------------------------------------------------
    // clause 5 — one event per record, counts agreeing with the events
    // -----------------------------------------------------------------

    @Test
    fun `every record is reported once, echoes included, with its provenance and identity`() {
        gate.expectEcho("A", "tok-1")
        val echo = record(
            "A",
            height = 1,
            old = metadata("cn_dot" to "peerX:40"),
            new = metadata("cn_dot" to "peerX:41", "cn_echo" to "tok-1"),
        )
        val local = record("B", height = 2, new = null)

        val admitted = gate.admit(listOf(echo, local))

        admitted shouldContainExactly listOf(local) // order preserved, echo removed
        val reported = events.filterIsInstance<MirrorEvent.RecordClassified>()
        reported shouldContainExactly listOf(
            MirrorEvent.RecordClassified("commit-1", "A", Classification.ECHO, "peerX:41", "tok-1", "ws-identity"),
            MirrorEvent.RecordClassified("commit-2", "B", Classification.LOCAL, null, null, "ws-identity"),
        )
        gate.echoCount shouldBe reported.count { it.classification == Classification.ECHO }
        gate.localCount shouldBe reported.count { it.classification == Classification.LOCAL }
    }

    @Test
    fun `a REMOVED record reports the provenance of its from-side`() {
        gate.admit(
            listOf(record("A", type = DiffType.REMOVED, old = metadata("cn_dot" to "peerX:41"), new = null)),
        )

        val reported = events.filterIsInstance<MirrorEvent.RecordClassified>().single()
        reported.cnDot shouldBe "peerX:41"
        reported.classification shouldBe Classification.LOCAL
    }

    // -----------------------------------------------------------------
    // the projector's half: a LOCAL record projects exactly as before
    // -----------------------------------------------------------------

    /**
     * Feature clause 3's second half: a stamped record that reaches the
     * projector is projected byte-identically to the same record without the
     * stamp — apart from the `metadata` field itself, which is an ordinary
     * mirrored column and must differ. Proven on the two projectors' dot state,
     * not only on `view()`, because the removed rule's damage was invisible in
     * a view read alone.
     */
    @Test
    fun `a stamped LOCAL record projects exactly as an unstamped one`() {
        val stamped = OrMapCell<MirrorKey, String>()
        val plainCell = OrMapCell<MirrorKey, String>()
        val stampedProjector = MirrorProjector(DotMinter("ws"), stamped)
        val plainProjector = MirrorProjector(DotMinter("ws"), plainCell)

        val withStamp = record(
            "A",
            type = DiffType.ADDED,
            new = metadata("cn_dot" to "peerX:41", "cn_echo" to "tok-1"),
        )
        stampedProjector.applyAll(gate.admit(listOf(withStamp)))
        plainProjector.applyAll(gate.admit(listOf(record("A", type = DiffType.ADDED, new = null))))

        stampedProjector.view() shouldBe plainProjector.view()
        stamped.state().membership() shouldBe plainCell.state().membership()
        stamped.state().keys().forEach { key ->
            stamped.state().value(key) shouldBe plainCell.state().value(key)
        }
        gate.echoCount shouldBe 0
    }

    /**
     * Decision 6wc.3-D7's placement rule, at the unit level: the gate holds its
     * expectations itself, so replacing the projector beside it (what a
     * re-baseline does through `MirrorState.swap`) cannot lose one. The
     * mirror-level wiring that makes the gate outlive the swap is
     * [civictech.demo.beadsmirror.WorkspaceMirror.echoGate].
     */
    @Test
    fun `an expectation survives a projector being replaced beside the gate`() {
        gate.expectEcho("A", "tok-1")

        var projector = MirrorProjector(DotMinter("ws"))
        projector.applyAll(gate.admit(listOf(record("B", type = DiffType.ADDED, new = null))))
        projector = MirrorProjector(DotMinter("ws")) // the rebaseline swap

        val echo = record("A", height = 2, new = metadata("cn_echo" to "tok-1"))
        projector.applyAll(gate.admit(listOf(echo)))

        gate.echoCount shouldBe 1
        projector.view().keys shouldBe emptySet()
    }
}
