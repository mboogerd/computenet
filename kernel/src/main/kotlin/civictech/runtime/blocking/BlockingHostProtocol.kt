package civictech.runtime.blocking

import civictech.kernel.computelet.Computelet
import civictech.kernel.protocol.Message
import java.util.concurrent.CompletableFuture

sealed interface BlockingHostProtocol : Message {
    override val protocolId: Int
        get() = PROTOCOL_ID

    companion object Companion {
        const val PROTOCOL_ID: Int = 0
    }
}

data class AddPureComputelet(val computelet: Computelet, val result: CompletableFuture<Unit>) : BlockingHostProtocol
data class AddBlockingComputelet(val computelet: BlockingComputelet, val result: CompletableFuture<Unit>) : BlockingHostProtocol
