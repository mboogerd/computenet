package civictech.kernel.germ

import civictech.kernel.germ.port.FanInPort
import civictech.kernel.germ.port.FanOutPort
import civictech.kernel.germ.port.Use
import civictech.kernel.germ.proxy.Invocation
import civictech.kernel.germ.proxy.buffering
import civictech.kernel.port.PortRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IncrementerTest {

    @Test
    fun `incrementer can be used without attached outlet`() {
        val incrementer = Incrementer.create()
        incrementer.inlet.use { provide(1) }
    }

    @Test
    fun `incrementer should increment values`() {
        val buffer = mutableListOf<Invocation>()
        val incrementer = Incrementer.create()

        incrementer.inlet.use { provide(1) }
        incrementer.outlet.subscribe(Use.fixed(buffering(buffer), PortRef.generate()))
        incrementer.inlet.use { provide(2) }

        assertEquals(buffer.map { it.args.first() }, listOf(3))

    }

    @Test
    fun `incrementer can be chained`() {
        val buffer = mutableListOf<Invocation>()
        val inc1 = Incrementer.create()
        val inc2 = Incrementer.create()

        inc1.outlet.subscribe(inc2.inlet)
        inc2.outlet.subscribe(Use.fixed(buffering(buffer), PortRef.generate()))

        inc1.inlet.use { provide(1) }
        inc1.inlet.use { provide(2) }

        assertEquals(buffer.map { it.args.first() }, listOf(3, 4))
    }
}

interface IncrementerApi {
    val inlet: FanInPort<Consumer<Int>>
    val outlet: FanOutPort<Consumer<Int>>
}

class Incrementer : IncrementerApi {
    override val inlet = FanInPort<Consumer<Int>>()
    override val outlet = FanOutPort<Consumer<Int>>()

    init {
        inlet.serve(object : Consumer<Int> {
            override fun provide(input: Int) {
                outlet.use { provide(input + 1) }
            }
        })
    }

    companion object {
        fun create(): IncrementerApi = Incrementer()
    }
}
