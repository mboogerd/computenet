package civictech.inspect

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Where one attached client's frames go. Implementations write to a socket and
 * throw when the client is gone; [SseBroadcaster] treats any throw as "detach".
 */
fun interface FrameWriter {
    fun write(frame: String)
}

/**
 * Fan-out of pre-serialized SSE frames to the attached clients, with the
 * viz-never-blocks-the-graph rule built in (10-target-v3 §Constraints 6):
 * [publish] runs on whatever thread produced the event — in practice a host's
 * management thread inside a `LocationRegistry` hook — so it must never wait on
 * a socket. Each client therefore owns
 *
 * - a **bounded** queue ([capacity] frames) and its own virtual-thread pump, and
 * - a **drop-oldest** policy: a client too slow to drain loses its stalest
 *   pending frames, never the newest state.
 *
 * The dropped frames are what the client must recover from, and the recovery is
 * the contract's own: every delta frame carries a strictly increasing `seq`, so
 * a drop shows up downstream as a gap and the client refetches
 * `GET /api/inspect/topology` (contract §SSE "on gap or reconnect, refetch the
 * snapshot"). Heartbeats deliberately re-send the *current* seq without
 * consuming one, so an idle client whose only loss was a heartbeat sees no gap,
 * while a client that missed a delta learns about it within one heartbeat even
 * if the graph has gone quiet.
 */
class SseBroadcaster(private val capacity: Int = DEFAULT_CAPACITY) : AutoCloseable {

    private val clients = CopyOnWriteArrayList<Client>()
    private val dropped = AtomicLong()

    /** Attached clients — diagnostics and tests. */
    val clientCount: Int get() = clients.size

    /** Frames discarded by the drop-oldest policy since construction — diagnostics and tests. */
    val droppedFrames: Long get() = dropped.get()

    /**
     * Start pumping frames to [writer]. [onDetach] runs once, on the pump
     * thread, after the client is removed (a failed write, or [close]) — the
     * place to close the underlying exchange.
     */
    fun attach(writer: FrameWriter, onDetach: () -> Unit = {}): AutoCloseable {
        val client = Client(writer, onDetach)
        clients += client
        client.start()
        return AutoCloseable { detach(client) }
    }

    /** Hand [frame] to every attached client. Never blocks, never throws. */
    fun publish(frame: String) {
        clients.forEach { it.offer(frame) }
    }

    private fun detach(client: Client) {
        if (clients.remove(client)) client.stop()
    }

    override fun close() {
        clients.toList().forEach(::detach)
    }

    private inner class Client(private val writer: FrameWriter, private val onDetach: () -> Unit) {
        private val queue = ArrayBlockingQueue<String>(capacity)

        // created unstarted, so an attach racing close() has a thread to interrupt
        private val pump: Thread = Thread.ofVirtual().name("inspector-sse-client").unstarted {
            try {
                while (true) writer.write(queue.take())
            } catch (_: InterruptedException) {
                // stop() — an orderly detach
            } catch (_: Exception) {
                // the client is gone; drop it rather than retry
            } finally {
                clients.remove(this)
                runCatching { onDetach() }
            }
        }

        fun start() = pump.start()

        fun stop() = pump.interrupt()

        /**
         * Enqueue [frame], evicting the oldest pending frames until it fits.
         * Bounded by [capacity] attempts: only the pump removes concurrently,
         * so an eviction cannot be undone faster than it is made, and the loop
         * cannot spin.
         */
        fun offer(frame: String) {
            repeat(capacity + 1) {
                if (queue.offer(frame)) return
                if (queue.poll() != null) dropped.incrementAndGet()
            }
            // unreachable with a single producer; counted rather than hidden
            dropped.incrementAndGet()
        }
    }

    companion object {
        /**
         * Frames a client may fall behind by. Deltas are small and rare
         * (topology changes, not messages — invariant P2), so this is generous:
         * a client this far behind is not slow, it is broken.
         */
        const val DEFAULT_CAPACITY = 256
    }
}
