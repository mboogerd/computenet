package civictech.gen.async

import kotlinx.coroutines.CompletableDeferred

sealed interface Op

sealed interface Return<O> : Op {
    val deferred: CompletableDeferred<O>
}

interface SyncOp<in I> : Op {
    fun op(input: I): Any

    companion object {
        class Default<I>(private val block: (I) -> Any) : SyncOp<I> {
            override fun op(input: I): Any = block(input)
        }
        operator fun <I> invoke(block: (I) -> Any): Default<I> = Default(block)
    }
}

interface SyncQuery<in I, O> : Op, Return<O> {
    fun op(input: I): O

    companion object {
        class Default<I, O>(override val deferred: CompletableDeferred<O>, private val block: (I) -> O) : SyncQuery<I, O> {
            override fun op(input: I): O = block(input)
        }
        operator fun <I, O> invoke(block: (I) -> O): Default<I, O> =
            Default(CompletableDeferred(), block)
    }
}

interface AsyncOp<in I> : Op {
    suspend fun op(input: I): Any

    companion object {
        class Default<I>(val block: suspend (I) -> Any) : AsyncOp<I> {
            override suspend fun op(input: I): Any = block(input)
        }
        operator fun <I, O> invoke(block: suspend (I) -> Any): Default<I> = Default(block)
    }
}

interface AsyncQuery<in I, O> : Op, Return<O> {
    suspend fun op(input: I): O

    companion object {
        class Default<I, O>(override val deferred: CompletableDeferred<O>, val block: suspend (I) -> O) : AsyncQuery<I, O> {
            override suspend fun op(input: I): O = block(input)
        }

        operator fun <I, O> invoke(block: suspend (I) -> O): Default<I, O> =
            Default(CompletableDeferred(), block)
    }
}