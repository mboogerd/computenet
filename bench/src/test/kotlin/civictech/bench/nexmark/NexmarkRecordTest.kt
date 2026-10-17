package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NexmarkRecordTest {
    @Test
    fun `run header records the required metadata and rejects invalid guards`() {
        val header = nexmarkTestHeader(query = NexmarkQuery.Q1)
        assertEquals(SPEC_REVISION, header.specRevision)
        assertEquals(FRAMING, header.framing)
        assertEquals("test-sha", header.harnessSha)
        assertEquals(StateLabel.NOT_WINDOWED, header.stateLabel)
        assertEquals(header.stateSeries.single(), header.retainedState)
        assertFalse(header.fanOutCovered.not())

        assertThrows(IllegalArgumentException::class.java) {
            header.copy(specRevision = "different")
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(framing = "different")
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(scheduler = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(deviations = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(deviations = listOf(" "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(stateSeries = emptyList(), peak = header.peak)
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(peak = RetainedState(mapOf("outlet" to 2L), mapOf("fold" to 1L)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(lateness = LatenessAccounting(0L, 0L, 0L, 0L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(policy = LatenessPolicy(1L), stateLabel = StateLabel.NOT_WINDOWED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(stateLabel = StateLabel.UNBOUNDED_STATE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(query = NexmarkQuery.Q7)
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(maxFanOut = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(comparedAtQuiescence = false)
        }
    }

    @Test
    fun `header couples policy accounting and evicting state`() {
        val header = nexmarkTestHeader(
            query = NexmarkQuery.Q7,
            policy = LatenessPolicy(5L),
        )
        assertEquals(StateLabel.Evicting(5L), header.stateLabel)
        assertEquals(5L, header.policy?.lateness)
        assertEquals(0L, header.lateness?.droppedLate)

        assertThrows(IllegalArgumentException::class.java) {
            header.copy(stateLabel = StateLabel.Evicting(4L))
        }
        assertThrows(IllegalArgumentException::class.java) {
            header.copy(policy = null, stateLabel = StateLabel.UNBOUNDED_STATE)
        }
    }

    @Test
    fun `verified keeps insert and retract figures separate and rejects crossed phases`() {
        val record = verifiedNexmark()
        assertEquals(Status.verified, record.status)
        assertEquals(listOf(Phase.INSERT), record.insert.map { it.phase })
        assertEquals(listOf(Phase.RETRACT), record.retract.map { it.phase })

        assertThrows(IllegalArgumentException::class.java) {
            record.copy(insert = listOf(record.retract.single()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(retract = listOf(record.insert.single()))
        }
    }

    @Test
    fun `failure and skipped records carry no figure field`() {
        val failed = NexmarkRecord.Failed(nexmarkTestHeader(), 2, emptyList())
        val unverified = NexmarkRecord.Unverified(nexmarkTestHeader(), "comparison was skipped")
        val stateBound = NexmarkRecord.StateBound(nexmarkTestHeader(), "q7", 10L, 3)
        assertEquals(Status.failed, failed.status)
        assertEquals(Status.unverified, unverified.status)
        assertEquals(Status.state_bound, stateBound.status)
        listOf(failed, unverified, stateBound).forEach { record ->
            assertTrue(record::class.java.declaredFields.none { field ->
                Figure::class.java.isAssignableFrom(field.type)
            })
        }
    }

    @Test
    fun `unreportable causes and not expressible query enforce their boundaries`() {
        val header = nexmarkTestHeader()
        assertEquals(
            Status.unreportable,
            NexmarkRecord.Unreportable(
                header,
                NexmarkRecord.UnreportableCause.Variance(0.5),
            ).status,
        )
        assertEquals(
            Status.unreportable,
            NexmarkRecord.Unreportable(
                header,
                NexmarkRecord.UnreportableCause.GenerationOnMeasuredPath(0.25),
            ).status,
        )
        assertThrows(IllegalArgumentException::class.java) {
            NexmarkRecord.UnreportableCause.Variance(VARIANCE_THRESHOLD)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexmarkRecord.UnreportableCause.GenerationOnMeasuredPath(0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexmarkRecord.UnreportableCause.GenerationOnMeasuredPath(1.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexmarkRecord.NotExpressible(NexmarkQuery.Q1, "wrong tier")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexmarkRecord.Unverified(header, " ")
        }
    }

    @Test
    fun `summarise folds equal repeats and preserves non verified members`() {
        val first = verifiedNexmark(seed = 7L)
        val second = verifiedNexmark(seed = 8L).copy(
            insert = listOf(
                BatchFigure(Phase.INSERT, 0, Figure.Work(110L, unit = WorkUnit.COLLECTOR_APPLIED_DELTA_ELEMENTS)),
            ),
        )
        val summary = assertInstanceOf(NexmarkRecord.Verified::class.java, summarise(listOf(first, second)))
        assertEquals(105L, (summary.insert.single().figure as Figure.Work).steps)
        assertEquals(10L, (summary.insert.single().figure as Figure.Work).dispersionSteps)
        assertEquals(10.0 / 105.0, summary.header.variance)

        val failed = NexmarkRecord.Failed(nexmarkTestHeader(), 1, emptyList())
        assertEquals(failed, summarise(listOf(failed, first)))
        val unverified = NexmarkRecord.Unverified(nexmarkTestHeader(), "not compared")
        assertEquals(unverified, summarise(listOf(first, unverified)))
        val stateBound = NexmarkRecord.StateBound(nexmarkTestHeader(), "q7", 2L, 1)
        assertEquals(stateBound, summarise(listOf(first, stateBound)))
    }

    @Test
    fun `summarise marks a fifty percent spread unreportable`() {
        val first = verifiedNexmark()
        val second = first.copy(
            insert = listOf(
                BatchFigure(Phase.INSERT, 0, Figure.Work(150L, unit = WorkUnit.COLLECTOR_APPLIED_DELTA_ELEMENTS)),
            ),
        )
        val result = assertInstanceOf(NexmarkRecord.Unreportable::class.java, summarise(listOf(first, second)))
        assertEquals(0.4, (result.cause as NexmarkRecord.UnreportableCause.Variance).variance)
        assertThrows(IllegalArgumentException::class.java) {
            summarise(listOf(first))
        }
    }
}
