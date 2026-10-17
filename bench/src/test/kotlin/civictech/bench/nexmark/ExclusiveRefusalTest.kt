package civictech.bench.nexmark

import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.host.ManagedHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExclusiveRefusalTest {
    @Test
    fun `exclusive event payloads are refused before a host exists`() {
        listOf(Owned::class.java, Leased::class.java).forEach { payload ->
            var hostConstructions = 0
            val failure = assertThrows(IllegalArgumentException::class.java) {
                NexmarkRun.run(
                    query = NexmarkQuery.Q1,
                    seed = 7L,
                    scale = NexmarkScale(2_000, 50),
                    drive = Drive.SIM,
                    env = nexmarkTestEnvironment(),
                    eventPayload = payload,
                    hooks = NexmarkRunHooks(
                        rigHooks = NexmarkRigHooks(
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

            assertTrue(failure.message.orEmpty().contains("[BB-NEX-27]"))
            assertEquals(0, hostConstructions, payload.name)
        }
    }
}
