package civictech.compute.blocking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class MovableMailboxTest {

    private class RecordingSink<M> : MessageSink<M> {
        val received = CopyOnWriteArrayList<M>()
        override fun enqueue(message: M) {
            received.add(message)
        }
    }

    @Test
    fun `enqueue in attached state goes directly to host`() {
        val sink = RecordingSink<String>()
        val mailbox = MovableMailbox(sink)

        mailbox.enqueue("msg1")
        mailbox.enqueue("msg2")

        assertEquals(listOf("msg1", "msg2"), sink.received)
    }

    @Test
    fun `detach switches to buffering and stops enqueueing to old sink`() {
        val sink = RecordingSink<String>()
        val mailbox = MovableMailbox(sink)

        mailbox.enqueue("before")
        mailbox.detach()
        mailbox.enqueue("after1")
        mailbox.enqueue("after2")

        // Only the pre-detach message is in the sink
        assertEquals(listOf("before"), sink.received)

        // Buffering messages are not lost: they should appear in drainBuffer
        val drained = mutableListOf<String>()
        mailbox.drainBuffer { drained.add(it) }
        assertEquals(listOf("after1", "after2"), drained)
    }

    @Test
    fun `drainBuffer drains all buffered messages in order`() {
        val sink = RecordingSink<String>()
        val mailbox = MovableMailbox(sink)

        mailbox.detach()
        mailbox.enqueue("a")
        mailbox.enqueue("b")
        mailbox.enqueue("c")

        val drained = mutableListOf<String>()
        mailbox.drainBuffer { drained.add(it) }
        assertEquals(listOf("a", "b", "c"), drained)

        // Subsequent drain should find nothing
        val drainedAgain = mutableListOf<String>()
        mailbox.drainBuffer { drainedAgain.add(it) }
        assertTrue(drainedAgain.isEmpty())
    }

    @Test
    fun `attach flushes buffered messages to new sink and resumes direct enqueueing`() {
        val oldSink = RecordingSink<String>()
        val newSink = RecordingSink<String>()
        val mailbox = MovableMailbox(oldSink)

        mailbox.enqueue("old1")
        mailbox.detach()
        mailbox.enqueue("buf1")
        mailbox.enqueue("buf2")

        // Old sink only saw old1
        assertEquals(listOf("old1"), oldSink.received)

        // Attach to new sink
        mailbox.attach(newSink)

        // Buffered messages should be flushed to new sink
        assertEquals(listOf("buf1", "buf2"), newSink.received)

        // New messages go directly to new sink
        mailbox.enqueue("new1")
        mailbox.enqueue("new2")
        assertEquals(listOf("buf1", "buf2", "new1", "new2"), newSink.received)
    }

    @Test
    fun `detach is idempotent`() {
        val sink = RecordingSink<String>()
        val mailbox = MovableMailbox(sink)

        mailbox.enqueue("before")
        mailbox.detach()
        mailbox.detach()
        mailbox.enqueue("after")

        assertEquals(listOf("before"), sink.received)
        val drained = mutableListOf<String>()
        mailbox.drainBuffer { drained.add(it) }
        assertEquals(listOf("after"), drained)
    }

    @Test
    fun `attach is idempotent`() {
        val sink1 = RecordingSink<String>()
        val sink2 = RecordingSink<String>()
        val mailbox = MovableMailbox(sink1)

        mailbox.detach()
        mailbox.enqueue("buf")
        mailbox.attach(sink2)
        mailbox.attach(sink2) // second attach should have no effect

        assertEquals(listOf("buf"), sink2.received)
    }

    @Test
    fun `attach without prior detach swaps sinks`() {
        val sink1 = RecordingSink<String>()
        val sink2 = RecordingSink<String>()
        val mailbox = MovableMailbox(sink1)

        mailbox.enqueue("m1")
        mailbox.attach(sink2)
        mailbox.enqueue("m2")

        assertEquals(listOf("m1"), sink1.received)
        assertEquals(listOf("m2"), sink2.received)
    }

    @Test
    fun `concurrent enqueues during detach are buffered`() {
        val sink = RecordingSink<Int>()
        val mailbox = MovableMailbox(sink)

        val threads = (1..10).map {
            Thread {
                mailbox.enqueue(it)
            }
        }
        threads.forEach { it.start() }
        mailbox.detach()
        threads.forEach { it.join() }

        // Messages sent after detach may appear in buffer, not in sink
        val buffered = mutableListOf<Int>()
        mailbox.drainBuffer { buffered.add(it) }

        // The union of sink.received and buffered should contain all messages 1..10
        val all = sink.received + buffered
        assertEquals((1..10).toSet(), all.toSet())
    }

    @Test
    fun `concurrent enqueues during attach preserve ordering`() {
        val sink1 = RecordingSink<Int>()
        val sink2 = RecordingSink<Int>()
        val mailbox = MovableMailbox(sink1)

        mailbox.detach()
        val buffered = (1..5).onEach { mailbox.enqueue(it) }
        mailbox.attach(sink2)

        // After attach, new messages go directly to sink2
        val postAttach = (6..10).onEach { mailbox.enqueue(it) }

        // Buffered should be in sink2 in order, followed by postAttach
        assertEquals(buffered + postAttach, sink2.received)
    }

    @Test
    fun `ordering is preserved under concurrent enqueues`() {
        val sink1 = RecordingSink<Int>()
        val sink2 = RecordingSink<Int>()
        val mailbox = MovableMailbox(sink1)

        // Send some before detach
        (1..5).forEach { mailbox.enqueue(it) }
        mailbox.detach()

        // Concurrent enqueues during migration
        val threads = (6..15).map { i ->
            Thread { mailbox.enqueue(i) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Attach to new sink and flush
        mailbox.attach(sink2)
        (16..20).forEach { mailbox.enqueue(it) }

        // Verify old sink has first batch, new sink has buffered + post attach in order
        assertEquals((1..5).toList(), sink1.received)
        assertEquals((6..20).toList(), sink2.received)
    }
}