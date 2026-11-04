package civictech.runtime

import civictech.nature.ContractRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class StackedGateSimTest {
    @Test
    @Timeout(60)
    fun `seed zero runs the complete stacked gate through promotion and recovery`() {
        assertNotNull(ContractRegistry.descriptor(GatePush::class.java))

        val checkpoints = StackedGateFixture.Harness(seed = 0).runFull()
        val violations = buildList {
            StackedGateFixture.Oracle.violations(checkpoints.p1).forEach { add("P1 $it") }
            StackedGateFixture.Oracle.violations(checkpoints.p2).forEach { add("P2 $it") }
        }
        assertEquals(emptyList(), violations, "P1: ${checkpoints.p1}\nP2: ${checkpoints.p2}")
        assertTrue(checkpoints.p2.recoveredCandidate, "recovered node is not running TotalCellV2")
        assertTrue(checkpoints.p2.incumbentRetired, "recovered topology retained total")
    }
}
