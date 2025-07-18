package civictech.gen.async

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach

class ChanneledDataOwner(private val owned: Any, private val channel: Channel<Op>) : SendOperation {

    suspend fun run() {
        channel.consumeEach {
            when (it) {
                is SyncOp<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (it as SyncOp<Any>).op(owned)
                }
                is AsyncOp<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (it as AsyncOp<Any>).op(owned)
                }

                is SyncQuery<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val query = it as SyncQuery<Any, Any>
                    val result = query.op(owned)
                    query.deferred.complete(result)
                }

                is AsyncQuery<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val query = it as AsyncQuery<Any, Any>
                    val result = query.op(owned)
                    query.deferred.complete(result)
                }
            }
        }
    }

    override suspend fun <Q, O> query(op: Q): O where Q : Op, Q : Return<O> {
        channel.send(op)
        return op.deferred.await()
    }

    override suspend fun operate(op: Op) = channel.send(op)
}