package civictech.compute

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ComputeletBuilderTest {

    @Test
    fun `build computelet with single port and verify ownership`() {
        val builder = ComputeletBuilder("SinglePort")
        val port = builder.inputPort("in")

        builder.onMessage { _, _ -> emptyList() }

        val computelet = builder.build()

        computelet.ports().size shouldBe 1
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
            listOf(Broadcast(testMessage.payload, outlet, msg.protocolId))
        }

        val computelet = builder.build()

        val result = computelet.process(outlet, TestMessage(1, Link(inlet, inlet), "hello"))
        result.single().let {
            it.port shouldBe outlet
            it.payload shouldBe "hello"
        }
    }

    @Test
    fun `computelet emits targeted unicast`() {
        val builder = ComputeletBuilder("Unicaster")
        val inlet = builder.inputPort("in")
        val out = builder.outputPort("out")
        val dummyLink = Link(out, out)

        builder.onMessage { _, msg ->
            val testMessage = msg as? TestMessage ?: return@onMessage listOf()
            listOf(Unicast(testMessage.payload, inlet, msg.protocolId, dummyLink))
        }

        val computelet = builder.build()

        val result = computelet.process(out, TestMessage(99, Link(inlet, inlet), "target"))
        result.single().let {
            it as Unicast
            it.link shouldBe dummyLink
            it.payload shouldBe "target"
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
                "in1" -> listOf(Broadcast("from1:${testMessage.payload}", out,msg.protocolId))
                else -> listOf(Broadcast("from2:${testMessage.payload}", out, msg.protocolId))
            }
        }

        val computelet = builder.build()

        val msg1 = computelet.process(in1, TestMessage(42, Link(in1, in1), "msg1"))
        val msg2 = computelet.process(in2, TestMessage(42, Link(in2, in2), "msg2"))

        (msg1.single() as Broadcast).payload shouldBe "from1:msg1"
        (msg2.single() as Broadcast).payload shouldBe "from2:msg2"
    }

    private data class TestMessage(
        override val protocolId: Int,
        override val fromLink: Link,
        val payload: Any,
    ) : Message
}
