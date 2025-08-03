package civictech.runtime.blocking

import civictech.kernel.computelet.ComputeletRef
import civictech.kernel.port.PortRef
import civictech.kernel.protocol.Message
import civictech.runtime.blocking.DefaultBlockingPort.Companion.blockingInput
import civictech.runtime.blocking.DefaultBlockingPort.Companion.blockingOutput
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

class BlockingComputeletHostTest {

    @Test
    fun `it should allow adding a blocking computelet`() {
        val host = BlockingComputeletHost()
        val computelet: BlockingComputelet = object : BlockingComputelet {
            override val ref: ComputeletRef = ComputeletRef.generate()
            override val ports: Map<PortRef, BlockingPort> =
                listOf(blockingInput(), blockingOutput()).associateBy { it.ref }
            private val portsByName: Map<String, BlockingPort> = ports.values.associateBy { it.name }

            override fun port(name: String): BlockingPort? = portsByName[name]

            override fun process(
                port: BlockingPort,
                link: BlockingLink,
                message: Message
            ) {
                TODO("Not yet implemented")
            }
        }


        val hostCommand = async { host.host(computelet) }
        val processHostCommand = async { host.processMessage() }
        val handle: BlockingComputeletHandle = hostCommand.get()
    }

    private val virtualThreadPerTaskExecutor = Executors.newVirtualThreadPerTaskExecutor()

    fun <T> async(block: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(block, virtualThreadPerTaskExecutor)
}