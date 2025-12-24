package civictech.runtime.blocking

import civictech.kernel.computelet.Computelet
import civictech.kernel.host.ComputeletHost
import civictech.kernel.link.DefaultLink
import civictech.kernel.port.Port
import civictech.kernel.protocol.Message
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

class BlockingComputeletHost(
    private val computeletHost: ComputeletHost<BlockingComputelet, BlockingPort, BlockingLink>,
    private val blockingQueue: BlockingQueue<Message>,
) {

    internal fun processMessage() {
        val multiplex = blockingQueue.take()

        when (multiplex.protocolId) {
            BlockingHostProtocol.PROTOCOL_ID -> {
                when(multiplex) {
                    is AddPureComputelet -> {
                        val adapter = BlockingComputeletAdapter(multiplex.computelet)
                        runCatching { computeletHost.host(adapter) }.fold(
                            { multiplex.result.complete(it) },
                            { multiplex.result.completeExceptionally(it) }
                        )
                    }
                    is AddBlockingComputelet -> {
                        runCatching { computeletHost.host(multiplex.computelet) }.fold(
                            { multiplex.result.complete(it) },
                            { multiplex.result.completeExceptionally(it) }
                        )
                    }
                }
            }
            else -> TODO("We need protocol stacking (filters)")
        }
    }

    fun host(computelet: Computelet): BlockingComputeletHandle {
        CompletableFuture<Unit>().also {
            blockingQueue.add(AddPureComputelet(computelet, it))
            it.get()
        }
        return computelet.createHandle()
    }

    fun host(computelet: BlockingComputelet): BlockingComputeletHandle {
        CompletableFuture<Unit>().also {
            blockingQueue.add(AddBlockingComputelet(computelet, it))
            it.get()
        }
        return computelet.createHandle()
    }

    private fun Computelet.createHandle(): BlockingComputeletHandle =
        BlockingComputeletHandle(ref, blockingQueue, ports.mapValues { it.value.createHandle() })

    private fun Port<DefaultLink>.createHandle(): BlockingPortHandle =
        BlockingPortHandle(ref, blockingQueue)

    private fun BlockingComputelet.createHandle(): BlockingComputeletHandle =
        BlockingComputeletHandle(ref, blockingQueue, ports.mapValues { it.value.createHandle() })

    private fun BlockingPort.createHandle(): BlockingPortHandle =
        BlockingPortHandle(ref, blockingQueue)

    companion object {
        operator fun invoke(): BlockingComputeletHost = BlockingComputeletHost(
            computeletHost = ComputeletHost(),
            blockingQueue = LinkedBlockingQueue()
        )
    }
}