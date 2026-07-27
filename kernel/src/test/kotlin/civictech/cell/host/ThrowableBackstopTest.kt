package civictech.cell.host

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.awaitUntil
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * T04 finding 5: `catch (e: Exception)` in the drain loops / `deliver` /
 * `enqueue` did not catch `Error` — a `TODO()` (`NotImplementedError` IS an
 * `Error`), `StackOverflowError`, or `NoClassDefFoundError` escaped every
 * backstop, silently killed the drain loop, and the host kept accepting
 * traffic forever (`submit` succeeding, nothing draining, `Owned` payloads
 * accumulating unaccounted).
 *
 * Two scenarios, matching the ticket's own "either...or": an ordinary `Error`
 * ([NotImplementedError]) is now caught by `ManagedHost.deliver`'s widened
 * `catch (e: Throwable)` and goes through supervision exactly like any other
 * failure — the host survives and keeps processing later traffic. A
 * `VirtualMachineError` ([StackOverflowError]) is deliberately NOT caught —
 * it is rethrown, terminates the scheduler's drain loop, and a subsequent
 * `submit` fails loudly (`IllegalStateException`) instead of silently
 * accepting traffic nothing will ever drain.
 */
class ThrowableBackstopTest {

    interface ConsumerCellProxy {
        val inlet: Use<Consumer<Int>>
    }

    private class RestartableErrorCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        var handled = mutableListOf<Int>()

        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    if (input == 2) TODO("t04-finding-5: deliberately unimplemented")
                    handled += input
                }
            })
        }
    }

    @Test
    fun `a TODO() Error is caught, dead-lettered, and supervision restarts the cell instead of killing the host`() {
        val controller = SimulationController()
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(Use.fixed(object : civictech.cell.Propagate<DeadLetter> {
            override fun propagate(value: DeadLetter) {
                letters += value
            }
        }, civictech.cell.port.PortRef.generate()))

        val cell = RestartableErrorCell()
        host.managementInlet.call.spawn(cell)
        host.managementInlet.call.supervise(cell.ref, SupervisionPolicy.RESTART)
        val api = host.lookup<ConsumerCellProxy>(cell.ref)!!.inlet.call

        api.provide(1)
        controller.runToIdle()
        cell.handled shouldBe listOf(1)

        // the NotImplementedError (an Error, not an Exception) must be caught
        // here — pre-fix it escaped `catch (e: Exception)` entirely and killed
        // the drain loop silently.
        api.provide(2)
        controller.runToIdle()

        letters.size shouldBe 1
        host.supervisionAccounting().restarts shouldBe 1

        // the host is still alive and draining: post-restart traffic is
        // processed normally, not silently swallowed by a dead loop.
        api.provide(3)
        controller.runToIdle()
        cell.handled shouldBe listOf(1, 3) // RESTART reverts state (spawn-time checkpoint), input 2 was never replayed
    }

    private class FatalCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    // deliberately constructed, not from real stack exhaustion —
                    // a safe, deterministic stand-in for a VirtualMachineError
                    throw StackOverflowError("t04-finding-5: deliberate VirtualMachineError")
                }
            })
        }
    }

    @Test
    fun `a VirtualMachineError terminates the scheduler and a subsequent submit fails loudly instead of accepting traffic silently`() {
        val scheduler = VirtualThreadScheduler("t04-fatal-backstop")
        val host = ManagedHost(scheduler = scheduler)
        val cell = FatalCell()
        host.managementInlet.call.spawn(cell)
        val api = host.lookup<ConsumerCellProxy>(cell.ref)!!.inlet.call

        api.provide(1) // stages + dispatches; the scheduler thread throws and dies

        awaitUntil("host scheduler terminates after the VirtualMachineError") {
            runCatching { host.enqueueHostedInvocation(probe(cell.ref)) }.isFailure
        }

        val failure = shouldThrow<IllegalStateException> { host.enqueueHostedInvocation(probe(cell.ref)) }
        failure.message shouldBe "host scheduler terminated"
    }

    private fun probe(cellRef: CellRef) = civictech.cell.proxy.HostedPortInvocation(
        cellRef, "inlet", civictech.cell.proxy.HostedPortInvocation.Type.PORT_API,
        civictech.cell.proxy.Invocation("provide", listOf("java.lang.Object"), listOf(0)),
    )
}
