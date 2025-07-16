package civictech.gen.async

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach

class ChanneledDataOwner<T>(private val owned: T, private val channel: Channel<Operations<T>>) : SendOperation<T> {

    suspend fun run() {
        channel.consumeEach {
            when (it) {
                is Request<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val request = it as Request<T, Any>
                    val returnValue = request.block(owned)
                    request.deferred.complete(returnValue)
                }

                is FireAndForget<*> -> {
                    (it as FireAndForget<T>).block(owned)
                }
            }
        }
    }

    override suspend fun <R> send(op: Request<T, R>): R {
        channel.send(op)
        return op.deferred.await()
    }

    override suspend fun send(op: FireAndForget<T>) {
        channel.send(op)
    }
}