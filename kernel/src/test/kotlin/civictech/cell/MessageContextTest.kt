package civictech.cell

import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.output
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.*

class MessageContextTest {

    class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet by output<Consumer<Int>>()
        fun emit(n: Int) = outlet.call.provide(n)
    }

    private fun capture(): Pair<MutableList<Invocation>, Use<Consumer<Int>>> {
        val invocations = mutableListOf<Invocation>()
        return invocations to Use.fixed(buffering<Consumer<Int>>(invocations), PortRef.generate())
    }

    @Test
    fun `spontaneous emissions mint monotonic per-outlet waves`() {
        val source = Source()
        val (invocations, sink) = capture()
        source.outlet.subscribe(sink)

        source.emit(1)
        source.emit(2)
        source.emit(3)

        val timestamps = invocations.map { it.context!!.timestamp }
        timestamps.map { it.sourceId }.toSet() shouldBe setOf(source.outlet.ref.id)
        timestamps.map { it.counter } shouldBe listOf(1L, 2L, 3L)
        invocations.forEach { it.context!!.sourcePort shouldBe source.outlet.ref }
    }

    @Test
    fun `reactive propagation preserves the origin timestamp and rewrites the source port`() {
        // source -> mapper1 -> mapper2 -> buffer (all direct, same thread)
        val source = Source()
        val mapper1 = MapperCell<Int, Int>(f = { it + 1 })
        val mapper2 = MapperCell<Int, Int>(f = { it * 2 })
        mapper1.onActivate(object : CellContext {})
        mapper2.onActivate(object : CellContext {})

        source.outlet.subscribe(mapper1.inlet)
        mapper1.outlet.subscribe(mapper2.inlet)
        val (invocations, sink) = capture()
        mapper2.outlet.subscribe(sink)

        source.emit(10)

        invocations.size shouldBe 1
        val ctx = invocations[0].context.shouldNotBeNull()
        ctx.timestamp shouldBe Timestamp(source.outlet.ref.id, 1L)   // origin wave, unchanged
        ctx.sourcePort shouldBe mapper2.outlet.ref                    // last hop's port
        invocations[0].args[0] shouldBe 22
    }

    @Test
    fun `fan-out delivers the same wave to every subscriber`() {
        val source = Source()
        val (left, leftSink) = capture()
        val (right, rightSink) = capture()
        source.outlet.subscribe(leftSink)
        source.outlet.subscribe(rightSink)

        source.emit(7)

        left.single().context shouldBe right.single().context
    }

    @Test
    fun `context does not leak between tasks (poison test)`() {
        val a = Source()
        val b = Source()
        val (fromA, sinkA) = capture()
        val (fromB, sinkB) = capture()
        a.outlet.subscribe(sinkA)
        b.outlet.subscribe(sinkB)

        a.emit(1)
        b.emit(2) // spontaneous: must get its own wave, not A's

        val ctxA = fromA.single().context.shouldNotBeNull()
        val ctxB = fromB.single().context.shouldNotBeNull()
        ctxB.timestamp.sourceId shouldBe b.outlet.ref.id
        ctxB.timestamp shouldNotBe ctxA.timestamp
    }

    @Test
    fun `wave context crosses host boundaries unchanged`() {
        val controller = SimulationController()
        val host1 = ManagedHost(scheduler = controller.scheduler())
        val host2 = ManagedHost(scheduler = controller.scheduler())

        val source = Source()
        val collector = ContextCollectingCell()
        host1.managementInlet.call.spawn(source)
        host2.managementInlet.call.spawn(collector)

        val proxy = host2.lookup<CollectorInterface>(collector.ref)!!
        source.outlet.linkTo(proxy.inlet)

        source.emit(42)
        controller.runToIdle()

        collector.contexts.size shouldBe 1
        val ctx = collector.contexts[0].shouldNotBeNull()
        ctx.timestamp shouldBe Timestamp(source.outlet.ref.id, 1L)
    }

    interface CollectorInterface {
        val inlet: Use<Consumer<Int>>
    }

    class ContextCollectingCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by civictech.cell.port.input<Consumer<Int>>()
        val contexts = mutableListOf<MessageContext?>()

        override fun onActivate(ctx: CellContext) {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    contexts += CurrentContext.get()
                }
            })
        }
    }

    @Test
    fun `invocation with context survives java serialization`() {
        val ctx = MessageContext(Timestamp(UUID.randomUUID(), 3L), PortRef.generate(CellRef(UUID.randomUUID())))
        val invocation = Invocation("provide", listOf("java.lang.Object"), listOf("payload"), ctx)

        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).writeObject(invocation) }.toByteArray()
        val back = ObjectInputStream(ByteArrayInputStream(bytes)).readObject() as Invocation

        back shouldBe invocation
        back.context shouldBe ctx
    }
}
