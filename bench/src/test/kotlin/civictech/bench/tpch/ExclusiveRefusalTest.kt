package civictech.bench.tpch

import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.host.ManagedHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExclusiveRefusalTest {

    @Test
    fun `exclusive row payloads are refused before a host exists`() {
        listOf(Owned::class.java, Leased::class.java).forEach { payload ->
            var hostConstructions = 0
            val failure = assertThrows(IllegalArgumentException::class.java) {
                TpchRun.run(
                    query = TpchQuery.Q03,
                    seed = 7L,
                    plan = RefreshPlan(seed = 7L, sf = 0.001, batches = emptyList()),
                    drive = Drive.SIM,
                    env = tpchTestEnvironment(),
                    rowPayload = payload,
                    hooks = TpchRunHooks(
                        rigHooks = TpchRigHooks(
                            hostFactory = { scheduler, journal, dispatchBatch ->
                                hostConstructions++
                                ManagedHost(
                                    scheduler = scheduler,
                                    journal = journal,
                                    dispatchBatch = dispatchBatch,
                                )
                            },
                        ),
                    ),
                )
            }

            assertTrue(failure.message.orEmpty().contains("[BB-TPCH-28]"))
            assertEquals(0, hostConstructions, payload.name)
        }
    }
}
