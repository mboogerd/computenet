package civictech.demo.beadsmirror.ready

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Truth-table coverage for [ReadyPredicate.isReady] (task computenet-98u.1.1):
 * one focused test per modelled `BuildReadyWorkWhere` clause, each flipping
 * exactly that clause's input, per computenet-98u.1's acceptance criteria.
 *
 * Field values are built with the same `JsonPrimitive(...).toString()`
 * rendering [ReadyPredicate.stringField] undoes — [str] and [num] mirror what
 * `MirrorProjector.fieldDelta` actually stores (a quoted JSON string for a
 * `varchar` column, a bare JSON number for a `tinyint(1)` one) rather than
 * hand-writing JSON literals that happen to parse the same way.
 *
 * Pure JVM — no `bd`, no `dolt`. [ReadyPredicate] never invokes either.
 */
class ReadyPredicateTest {

    private fun str(value: String): String = JsonPrimitive(value).toString()
    private fun num(value: Int): String = JsonPrimitive(value).toString()

    /** A ready baseline: open, unpinned, non-ephemeral, ordinary type, unblocked. */
    private fun readyFields(
        status: String = "open",
        pinned: Int = 0,
        ephemeral: Int = 0,
        issueType: String = "task",
    ): Map<String, String> = mapOf(
        "status" to str(status),
        "pinned" to num(pinned),
        "ephemeral" to num(ephemeral),
        "issue_type" to str(issueType),
    )

    // ---- baseline -----------------------------------------------------

    @Test
    fun `every clause satisfied and unblocked is ready`() {
        ReadyPredicate.isReady(readyFields(), blocked = false) shouldBe true
    }

    // ---- blocked (external flag, not read from fields) -----------------

    @Test
    fun `blocked issue is never ready regardless of every other clause`() {
        ReadyPredicate.isReady(readyFields(), blocked = true) shouldBe false
    }

    // ---- default status set ---------------------------------------------

    @Test
    fun `open status is ready`() {
        ReadyPredicate.isReady(readyFields(status = "open"), blocked = false) shouldBe true
    }

    @Test
    fun `in_progress status is ready`() {
        ReadyPredicate.isReady(readyFields(status = "in_progress"), blocked = false) shouldBe true
    }

    @Test
    fun `closed status is not ready`() {
        ReadyPredicate.isReady(readyFields(status = "closed"), blocked = false) shouldBe false
    }

    @Test
    fun `pinned status value is not ready`() {
        // ready.go's default status set is exactly {open, in_progress}; the
        // beads issue status enum's separate 'pinned' value (see the boolean
        // pinned column clause below for the OTHER "pinned") is outside it.
        ReadyPredicate.isReady(readyFields(status = "pinned"), blocked = false) shouldBe false
    }

    @Test
    fun `an arbitrary unrecognised status is not ready`() {
        ReadyPredicate.isReady(readyFields(status = "on_hold"), blocked = false) shouldBe false
    }

    @Test
    fun `missing status fails closed, not ready`() {
        val fields = readyFields().minus("status")
        ReadyPredicate.isReady(fields, blocked = false) shouldBe false
    }

    // ---- pinned (boolean column, falsy-or-absent) ------------------------

    @Test
    fun `pinned 0 is ready`() {
        ReadyPredicate.isReady(readyFields(pinned = 0), blocked = false) shouldBe true
    }

    @Test
    fun `pinned 1 is not ready`() {
        ReadyPredicate.isReady(readyFields(pinned = 1), blocked = false) shouldBe false
    }

    @Test
    fun `pinned absent defaults falsy and is ready`() {
        val fields = readyFields().minus("pinned")
        ReadyPredicate.isReady(fields, blocked = false) shouldBe true
    }

    @Test
    fun `pinned rendered as the defensive boolean literal true is not ready`() {
        val fields = readyFields() + ("pinned" to "true")
        ReadyPredicate.isReady(fields, blocked = false) shouldBe false
    }

    // ---- ephemeral (boolean column, falsy-or-absent, default call) -------

    @Test
    fun `ephemeral 0 is ready`() {
        ReadyPredicate.isReady(readyFields(ephemeral = 0), blocked = false) shouldBe true
    }

    @Test
    fun `ephemeral 1 is not ready`() {
        ReadyPredicate.isReady(readyFields(ephemeral = 1), blocked = false) shouldBe false
    }

    @Test
    fun `ephemeral absent defaults falsy and is ready`() {
        val fields = readyFields().minus("ephemeral")
        ReadyPredicate.isReady(fields, blocked = false) shouldBe true
    }

    // ---- issue_type exclusion set -----------------------------------------

    @Test
    fun `an ordinary issue_type such as task is ready`() {
        ReadyPredicate.isReady(readyFields(issueType = "task"), blocked = false) shouldBe true
    }

    @Test
    fun `feature and epic issue_types are ready`() {
        ReadyPredicate.isReady(readyFields(issueType = "feature"), blocked = false) shouldBe true
        ReadyPredicate.isReady(readyFields(issueType = "epic"), blocked = false) shouldBe true
    }

    @Test
    fun `merge-request type is excluded`() {
        ReadyPredicate.isReady(readyFields(issueType = "merge-request"), blocked = false) shouldBe false
    }

    @Test
    fun `gate type is excluded`() {
        ReadyPredicate.isReady(readyFields(issueType = "gate"), blocked = false) shouldBe false
    }

    @Test
    fun `molecule type is excluded`() {
        ReadyPredicate.isReady(readyFields(issueType = "molecule"), blocked = false) shouldBe false
    }

    @Test
    fun `rig type is excluded`() {
        ReadyPredicate.isReady(readyFields(issueType = "rig"), blocked = false) shouldBe false
    }

    @Test
    fun `agent type is excluded (DefaultInfraTypes)`() {
        ReadyPredicate.isReady(readyFields(issueType = "agent"), blocked = false) shouldBe false
    }

    @Test
    fun `role type is excluded (DefaultInfraTypes)`() {
        ReadyPredicate.isReady(readyFields(issueType = "role"), blocked = false) shouldBe false
    }

    @Test
    fun `message type is excluded (DefaultInfraTypes)`() {
        ReadyPredicate.isReady(readyFields(issueType = "message"), blocked = false) shouldBe false
    }

    @Test
    fun `EXCLUDED_TYPES is exactly the base four plus DefaultInfraTypes' three`() {
        ReadyPredicate.EXCLUDED_TYPES shouldBe setOf(
            "merge-request",
            "gate",
            "molecule",
            "rig",
            "agent",
            "role",
            "message",
        )
    }

    @Test
    fun `missing issue_type fails closed, not ready`() {
        val fields = readyFields().minus("issue_type")
        ReadyPredicate.isReady(fields, blocked = false) shouldBe false
    }

    // ---- stringField / isTruthyBoolean parsing -----------------------------

    @Test
    fun `stringField undoes the quoted-string rendering`() {
        ReadyPredicate.stringField(mapOf("status" to str("open")), "status") shouldBe "open"
    }

    @Test
    fun `stringField undoes the bare-number rendering`() {
        ReadyPredicate.stringField(mapOf("pinned" to num(1)), "pinned") shouldBe "1"
    }

    @Test
    fun `stringField is null for an absent key`() {
        ReadyPredicate.stringField(emptyMap(), "status") shouldBe null
    }

    @Test
    fun `DEFAULT_READY_STATUSES is exactly open and in_progress`() {
        ReadyPredicate.DEFAULT_READY_STATUSES shouldBe setOf("open", "in_progress")
    }
}
