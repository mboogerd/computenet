package civictech.bench.nexmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class NoLatencyFieldTest {
    @Test
    fun `record model has no latency field property or nested type`() {
        val types = listOf(
            RunHeader::class.java,
            NexmarkRecord::class.java,
            NexmarkRecord.UnreportableCause::class.java,
            Figure::class.java,
            NexmarkRecord.Verified::class.java,
            NexmarkRecord.Failed::class.java,
            NexmarkRecord.Unverified::class.java,
            NexmarkRecord.Unreportable::class.java,
            NexmarkRecord.NotExpressible::class.java,
            NexmarkRecord.StateBound::class.java,
            Figure.Work::class.java,
            Figure.Duration::class.java,
            BatchFigure::class.java,
        )
        types.forEach { type ->
            assertFalse(type.declaredFields.any { it.name.contains("latency", ignoreCase = true) }, type.name)
            assertFalse(type.declaredMethods.any { it.name.contains("latency", ignoreCase = true) }, type.name)
            assertFalse(type.declaredClasses.any { it.simpleName.contains("latency", ignoreCase = true) }, type.name)
        }
    }

    @Test
    fun `simulation header states its scheduler`() {
        assertEquals("SimulationController", nexmarkTestHeader().scheduler)
    }
}
