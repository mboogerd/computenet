package civictech.compute.blocking

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * A mailbox for a computelet that acts as a stable ingress point.
 * In the normal (hosted) state, messages are directly forwarded into the host's single queue.
 * During migration, the mailbox detaches, buffers new messages, and later drains them into the new host's queue.
 */
class MovableMailbox<M>(initialState: State<M>) : MessageSink<M> {

    sealed interface State<M> {
        class Attached<M>(val sink: MessageSink<M>) : State<M>
        class Detached<M>(val buffer: ConcurrentLinkedQueue<M>) : State<M>
    }

    private val state = AtomicReference<State<M>>(initialState)

    /**
     * Sends a message into this mailbox. In the hosted state, this directly enqueues into the host queue.
     * In the migrating state, the message is buffered locally until migration completes.
     */
    override fun enqueue(message: M) {
        when (val s = state.get()) {
            is State.Attached -> s.sink.enqueue(message)
            is State.Detached -> s.buffer.add(message)
        }
    }

    /**
     * Detaches this mailbox from its current host. New messages will be buffered locally.
     */
    fun detach() {
        state.getAndUpdate { s ->
            when (s) {
                is State.Attached -> State.Detached(ConcurrentLinkedQueue())
                is State.Detached -> s // already migrating
            }
        }
    }

    /**
     * Drains buffered messages (if any) into the provided consumer.
     */
    fun drainBuffer(consumer: (M) -> Unit) {
        val s = state.get()
        if (s is State.Detached) {
            while (true) {
                val msg = s.buffer.poll() ?: break
                consumer(msg)
            }
        }
    }

    /**
     * Attaches this mailbox to a new host sink. Buffered messages (if any) will be flushed before switching.
     */
    fun attach(newSink: MessageSink<M>) {
        val old = state.getAndUpdate { State.Attached(newSink) }
        if (old is State.Detached) {
            while (true) {
                val msg = old.buffer.poll() ?: break
                newSink.enqueue(msg)
            }
        }
    }

    companion object Companion {
        operator fun <M> invoke(messageSink: MessageSink<M>): MovableMailbox<M> =
            MovableMailbox(State.Attached(messageSink))

        operator fun <M> invoke(): MovableMailbox<M> =
            MovableMailbox(State.Detached(ConcurrentLinkedQueue()))
    }
}