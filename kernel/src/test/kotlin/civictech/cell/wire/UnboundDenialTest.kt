package civictech.cell.wire

import civictech.cell.DenialReason
import civictech.cell.link.UnboundReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `denialReasonFor` and the two DSC4 seam-1 constants (task
 * `computenet-5y8t.3.1`).
 */
class UnboundDenialTest {

    @Test
    fun `every UnboundReason maps, and the two partitions are exactly UNVOUCHED and STATEMENT_EXPIRED`() {
        val mapped = UnboundReason.entries.associateWith { denialReasonFor(it) }

        assertEquals(
            setOf(
                UnboundReason.NO_BINDING,
                UnboundReason.NO_STATEMENT,
                UnboundReason.ISSUER_NOT_ACCEPTED,
                UnboundReason.BAD_SIGNATURE,
                UnboundReason.KEY_MISMATCH,
            ),
            mapped.filterValues { it == DenialReason.UNVOUCHED }.keys,
        )
        assertEquals(
            setOf(UnboundReason.EXPIRED, UnboundReason.NOT_YET_VALID),
            mapped.filterValues { it == DenialReason.STATEMENT_EXPIRED }.keys,
        )
        assertEquals(
            setOf(DenialReason.UNVOUCHED, DenialReason.STATEMENT_EXPIRED),
            mapped.values.toSet(),
            "no UnboundReason maps outside the two DSC4 constants",
        )
    }

    /**
     * The tail grows by one per feature that adds a constant, and the
     * assertion is rewritten each time — `computenet-ktn1l.2` appended
     * [DenialReason.IDENTITY_MISMATCH] and lengthened it from three to four.
     *
     * What it protects, precisely: the constants that were last keep their
     * order and stay last, so a new constant can only be APPENDED — never
     * inserted among them, and never reordered, either of which would
     * renumber ordinals that persisted records and the Inspector UI's union
     * are read against. What it does NOT protect is the order of the
     * constants before this tail; nothing here pins those, and a reordering
     * confined to them would pass. Lengthening the tail is the way this test
     * is updated; shortening it, or replacing it with a set comparison, would
     * drop the only order guarantee in the file.
     */
    @Test
    fun `DenialReason is append-only and ends with MALFORMED_ANNOUNCEMENT, UNVOUCHED, STATEMENT_EXPIRED, IDENTITY_MISMATCH`() {
        assertEquals(
            listOf(
                DenialReason.MALFORMED_ANNOUNCEMENT,
                DenialReason.UNVOUCHED,
                DenialReason.STATEMENT_EXPIRED,
                DenialReason.IDENTITY_MISMATCH,
            ),
            DenialReason.entries.takeLast(4),
        )
    }

    /**
     * [DenialReason.IDENTITY_MISMATCH] joined this list in `computenet-ktn1l.2`
     * for the reason the list exists: it is the constant most at risk of being
     * merged into [DenialReason.ID_MISMATCH] by a later reader who sees two
     * near-identical names. They are different facts — a claim disagreeing
     * with its own hello's resolution, versus one key resolving to two
     * identities across two live links (`[DSC2-ID-05]`).
     */
    @Test
    fun `UNVOUCHED, STATEMENT_EXPIRED, NOT_ADMITTED, ID_MISMATCH and IDENTITY_MISMATCH are five distinct constants`() {
        val five = listOf(
            DenialReason.UNVOUCHED,
            DenialReason.STATEMENT_EXPIRED,
            DenialReason.NOT_ADMITTED,
            DenialReason.ID_MISMATCH,
            DenialReason.IDENTITY_MISMATCH,
        )
        assertEquals(5, five.toSet().size)
    }
}
