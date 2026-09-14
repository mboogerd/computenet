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

    @Test
    fun `DenialReason is append-only and ends with MALFORMED_ANNOUNCEMENT, UNVOUCHED, STATEMENT_EXPIRED`() {
        assertEquals(
            listOf(DenialReason.MALFORMED_ANNOUNCEMENT, DenialReason.UNVOUCHED, DenialReason.STATEMENT_EXPIRED),
            DenialReason.entries.takeLast(3),
        )
    }

    @Test
    fun `UNVOUCHED, STATEMENT_EXPIRED, NOT_ADMITTED and ID_MISMATCH are four distinct constants`() {
        val four = listOf(
            DenialReason.UNVOUCHED,
            DenialReason.STATEMENT_EXPIRED,
            DenialReason.NOT_ADMITTED,
            DenialReason.ID_MISMATCH,
        )
        assertEquals(4, four.toSet().size)
    }
}
