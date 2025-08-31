package civictech.kernel.germ

import civictech.kernel.germ.port.Proxy
import civictech.kernel.germ.port.Serve
import civictech.kernel.germ.port.SimplePort
import civictech.kernel.germ.port.Use
import org.junit.jupiter.api.Test

class IncrementerTest {

    @Test
    fun `incrementer can be used without attached outlet`() {
        val incrementer = Incrementer.create()
        incrementer.inlet.use().provide(1)
    }

    @Test
    fun `incrementer should increment values`() {
        val buffer = mutableListOf<Proxy.Invocation>()
        val incrementer = Incrementer.create()

        incrementer.inlet.use().provide(1)
        incrementer.outlet.serve(Proxy.buffering(buffer))
        incrementer.inlet.use().provide(2)

        assert(buffer.map { it.args.first() } == listOf(3))
    }

    @Test
    fun `incrementer can be chained`() {
        val buffer = mutableListOf<Proxy.Invocation>()
        val inc1 = Incrementer.create()
        val inc2 = Incrementer.create()

        inc1.outlet.delegate(inc2.inlet)
        inc2.outlet.serve(Proxy.buffering(buffer))

        inc1.inlet.use().provide(1)
        inc1.inlet.use().provide(2)

        assert(buffer.map { it.args.first() } == listOf(3, 4))
    }
}

interface IncrementerApi {
    val inlet: Use<Consumer<Int>>
    val outlet: Serve<Consumer<Int>>
}

class Incrementer : IncrementerApi {
    override val inlet = SimplePort<Consumer<Int>>(Proxy.noop())
    override val outlet = SimplePort<Consumer<Int>>(Proxy.noop())

    init {
        inlet.serve(object : Consumer<Int> {
            override fun provide(input: Int) {
                outlet.use().provide(input + 1)
            }
        })
    }

    companion object {
        fun create(): IncrementerApi = Incrementer()
    }
}
