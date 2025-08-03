package civictech.kernel

import civictech.kernel.computelet.ComputeletBuilder
import civictech.kernel.link.DefaultLink
import civictech.kernel.protocol.Broadcast
import civictech.kernel.protocol.Message
import civictech.kernel.protocol.Unicast
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class ComputeletBuilderTest {

    @Test
    fun `build computelet with single port and verify ownership`() {
        val builder = ComputeletBuilder("SinglePort")
        val port = builder.inputPort("in")

        builder.onMessage { _, _ -> emptyList() }

        val computelet = builder.build()

        computelet.ports.size shouldBe 1
        computelet.port("in") shouldBe port
        (port.getOwner() === computelet) shouldBe true
    }

    @Test
    fun `computelet emits broadcast message`() {
        val builder = ComputeletBuilder("Broadcaster")
        val inlet = builder.privatePort("in")
        val outlet = builder.outputPort("out")

        builder.onMessage { _, msg ->
            val testMessage = msg as? TestMessage ?: return@onMessage listOf()
            listOf(Broadcast(testMessage, outlet))
        }

        val computelet = builder.build()

        val result = computelet.process(
            outlet,
            DefaultLink(inlet, inlet),
            TestMessage(1, "hello")
        )
        result.single().let {
            it.port shouldBe outlet
            it.payload shouldBe TestMessage(1, "hello")
        }
    }

    @Test
    fun `computelet emits targeted unicast`() {
        val builder = ComputeletBuilder("Unicaster")
        val inlet = builder.inputPort("in")
        val out = builder.outputPort("out")
        val dummyLink = DefaultLink(out, out)

        builder.onMessage { _, msg ->
            val testMessage = msg as? TestMessage ?: return@onMessage listOf()
            listOf(Unicast(testMessage, inlet, dummyLink))
        }

        val computelet = builder.build()

        val result = computelet.process(
            out,
            DefaultLink(inlet, inlet),
            TestMessage(99, "target")
        )
        result.single().let {
            it as Unicast
            it.link shouldBe dummyLink
            it.payload shouldBe TestMessage(99, "target")
        }
    }

    @Test
    fun `computelet with two ports processes messages differently`() {
        val builder = ComputeletBuilder("DualPort")
        val in1 = builder.inputPort("in1")
        val in2 = builder.inputPort("in2")
        val out = builder.outputPort("out")

        builder.onMessage { port, msg ->
            val testMessage = msg as? TestMessage ?: return@onMessage listOf()
            when (port.name) {
                "in1" -> listOf(Broadcast(TestMessage(msg.protocolId, "from1:${testMessage.payload}"), out))
                else -> listOf(Broadcast(TestMessage(msg.protocolId, "from2:${testMessage.payload}"), out))
            }
        }

        val computelet = builder.build()

        val msg1 =
            computelet.process(in1, DefaultLink(in1, in1), TestMessage(42, "msg1"))
        val msg2 =
            computelet.process(in2, DefaultLink(in2, in2), TestMessage(42, "msg2"))

        (msg1.single() as Broadcast).payload shouldBe TestMessage(42, "from1:msg1")
        (msg2.single() as Broadcast).payload shouldBe TestMessage(42, "from2:msg2")
    }

    @Test
    fun `computelets can receive messages`() {
        var received: TestMessage? = null

        val ba = ComputeletBuilder("")
        val aIn = ba.inputPort("in")
        ba.onMessage { port, msg ->
            val testMessage = msg as? TestMessage ?: return@onMessage listOf()
            when (port) {
                aIn -> {
                    received = testMessage
                    emptyList()
                }

                else -> fail("received message on unexpected port")
            }
        }
        val computeletA = ba.build()

        val testMessage = TestMessage(0, "test")

        // when
        computeletA.port("in")?.process(DefaultLink(aIn, aIn), testMessage)

        // then
        assertEquals(testMessage, received)
    }

    private data class TestMessage(
        override val protocolId: Int,
        val payload: Any,
    ) : Message
}
