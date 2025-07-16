package civictech.gen.async

import kotlinx.coroutines.CompletableDeferred

interface SendOperation<T> {
    suspend fun <R> send(op: AsyncRequest<T, R>): R
    suspend fun <R> send(op: Request<T, R>): R
    suspend fun send(op: FireAndForget<T>)
}

sealed interface Operations<T>
class FireAndForget<T>(val block: (T) -> Unit) : Operations<T>
class Request<T, R>(val deferred: CompletableDeferred<R>, val block: (T) -> R) : Operations<T>

class AsyncRequest<T, R>(val deferred: CompletableDeferred<R>, val block: suspend (T) -> R) : Operations<T>

