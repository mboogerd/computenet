package civictech.kernel.germ

import civictech.kernel.germ.port.Serve
import civictech.kernel.germ.port.SimplePort
import civictech.kernel.germ.port.Use
import civictech.kernel.germ.proxy.Invocation
import civictech.kernel.germ.proxy.buffering
import civictech.kernel.germ.proxy.noop
import org.junit.jupiter.api.Test

class IncrementerTest {

    @Test
    fun `incrementer can be used without attached outlet`() {
        val incrementer = Incrementer.create()
        incrementer.inlet.use().provide(1)
    }

    @Test
    fun `incrementer should increment values`() {
        val buffer = mutableListOf<Invocation>()
        val incrementer = Incrementer.create()

        incrementer.inlet.use().provide(1)
        incrementer.outlet.serve(buffering(buffer))
        incrementer.inlet.use().provide(2)

        assert(buffer.map { it.args.first() } == listOf(3))
    }

    @Test
    fun `incrementer can be chained`() {
        val buffer = mutableListOf<Invocation>()
        val inc1 = Incrementer.create()
        val inc2 = Incrementer.create()

        inc1.outlet.delegate(inc2.inlet)
        inc2.outlet.serve(buffering(buffer))

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
    override val inlet = SimplePort<Consumer<Int>>(noop())
    override val outlet = SimplePort<Consumer<Int>>(noop())

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
