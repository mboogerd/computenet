package civictech.cell

import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.proxy.Invocation
import civictech.cell.proxy.buffering
import civictech.cell.port.PortRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IncrementerTest {

    @Test
    fun `incrementer can be used without attached outlet`() {
        val incrementer = Incrementer.create()
        incrementer.inlet.call.provide(1)
    }

    @Test
    fun `incrementer should increment values`() {
        val buffer = mutableListOf<Invocation>()
        val incrementer = Incrementer.create()

        incrementer.inlet.call.provide(1)
        incrementer.outlet.subscribe(Use.fixed(buffering(buffer), PortRef.generate()))
        incrementer.inlet.call.provide(2)

        assertEquals(buffer.map { it.args.first() }, listOf(3))

    }

    @Test
    fun `incrementer can be chained`() {
        val buffer = mutableListOf<Invocation>()
        val inc1 = Incrementer.create()
        val inc2 = Incrementer.create()

        inc1.outlet.subscribe(inc2.inlet)
        inc2.outlet.subscribe(Use.fixed(buffering(buffer), PortRef.generate()))

        inc1.inlet.call.provide(1)
        inc1.inlet.call.provide(2)

        assertEquals(buffer.map { it.args.first() }, listOf(3, 4))
    }
}

interface IncrementerApi {
    val inlet: Use<Consumer<Int>>
    val outlet: Subscribe<Consumer<Int>>
}

class Incrementer : IncrementerApi {
    override val inlet = FanInlet.create<Consumer<Int>>()
    override val outlet = FanOutlet.create<Consumer<Int>>()

    init {
        inlet.serve(object : Consumer<Int> {
            override fun provide(input: Int) {
                outlet.call.provide(input + 1)
            }
        })
    }

    companion object {
        fun create(): IncrementerApi = Incrementer()
    }
}
