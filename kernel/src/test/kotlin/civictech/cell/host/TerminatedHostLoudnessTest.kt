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
 * T06 §D: T04 finding 5 (Throwable backstop + terminated flag) under BOTH
 * real schedulers — a handler that throws a genuine `StackOverflowError`
 * must terminate the drain loop, and a subsequent `submit` must fail loudly
 * (`IllegalStateException`) instead of silently accepting traffic nothing
 * will ever drain. `civictech.cell.host.ThrowableBackstopTest` (T04) already
 * pins this for `VirtualThreadScheduler`; this suite adds `CoroutineScheduler`
 * and confirms both report through the ordinary supervision/dead-letter path
 * first (T04 finding 5.3) before the scheduler itself gives up.
 */
class TerminatedHostLoudnessTest {

    interface ConsumerCellProxy {
        val inlet: Use<Consumer<Int>>
    }

    private class FatalCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Consumer::class.java as Class<Consumer<Int>>))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    // deliberately constructed, not from real stack exhaustion —
                    // a safe, deterministic stand-in for a VirtualMachineError
                    throw StackOverflowError("t06-d: deliberate VirtualMachineError")
                }
            })
        }
    }

    private fun probe(cellRef: CellRef) = civictech.cell.proxy.HostedPortInvocation(
        cellRef, "inlet", civictech.cell.proxy.HostedPortInvocation.Type.PORT_API,
        civictech.cell.proxy.Invocation("provide", listOf("java.lang.Object"), listOf(0)),
    )

    @Test
    fun `VirtualThreadScheduler terminates on a StackOverflowError and a subsequent submit fails loudly`() {
        val host = ManagedHost(scheduler = VirtualThreadScheduler("t06-d-vt"))
        val cell = FatalCell()
        host.managementInlet.call.spawn(cell)
        val api = host.lookup<ConsumerCellProxy>(cell.ref)!!.inlet.call

        api.provide(1)

        awaitUntil("host scheduler terminates after the VirtualMachineError") {
            runCatching { host.enqueueHostedInvocation(probe(cell.ref)) }.isFailure
        }
        val failure = shouldThrow<IllegalStateException> { host.enqueueHostedInvocation(probe(cell.ref)) }
        failure.message shouldBe "host scheduler terminated"
    }

    @Test
    fun `CoroutineScheduler terminates on a StackOverflowError and a subsequent submit fails loudly`() {
        val host = ManagedHost(scheduler = CoroutineScheduler("t06-d-co"))
        val cell = FatalCell()
        host.managementInlet.call.spawn(cell)
        val api = host.lookup<ConsumerCellProxy>(cell.ref)!!.inlet.call

        api.provide(1)

        awaitUntil("host scheduler terminates after the VirtualMachineError") {
            runCatching { host.enqueueHostedInvocation(probe(cell.ref)) }.isFailure
        }
        val failure = shouldThrow<IllegalStateException> { host.enqueueHostedInvocation(probe(cell.ref)) }
        failure.message shouldBe "host scheduler terminated"
    }
}
