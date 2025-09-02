package civictech.kernel.germ

import civictech.kernel.germ.port.Serve
import civictech.kernel.germ.port.SimplePort
import civictech.kernel.germ.port.Use
import civictech.kernel.germ.proxy.Invocation
import civictech.kernel.germ.proxy.buffering
import civictech.kernel.germ.proxy.noop
import org.junit.jupiter.api.Test

class MapperTest {

    @Test
    fun `mapper can be used without attached outlet`() {
        val mapper = MapperCell.create<Int, String> { it.toString() }
        mapper.inlet.use().provide(1)
    }

    @Test
    fun `mapper propagates transformed value`() {
        val mapper = MapperCell.create<Int, String> { it.toString() }
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Consumer<String>>(invocationBuffer)
        mapper.outlet.serve(buffer)
        mapper.inlet.use().provide(1337)

        assert(invocationBuffer.size == 1)
        assert(invocationBuffer[0].args.size == 1)
        assert(invocationBuffer[0].args[0] == "1337")
    }

    @Test
    fun `propagation is transitive`() {
        val mapper1 = MapperCell.create<Int, String> { it.toString() }
        val mapper2 = MapperCell.create<String, Long> { it.toLong() }
        val invocationBuffer = mutableListOf<Invocation>()
        val buffer = buffering<Consumer<Long>>(invocationBuffer)
        mapper2.outlet.serve(buffer)
        mapper1.outlet.delegate(mapper2.inlet)
        mapper1.inlet.use().provide(1337)

        assert(invocationBuffer.size == 1)
        assert(invocationBuffer[0].args.size == 1)
        assert(invocationBuffer[0].args[0] == 1337L)
    }

}

interface MapperApi<A, B> {
    val inlet: Use<Consumer<A>>
    val outlet: Serve<Consumer<B>>
}

class MapperCell<A, B>(f: (A) -> B) : MapperApi<A, B> {
    override val inlet = SimplePort<Consumer<A>>(noop())
    override val outlet = SimplePort<Consumer<B>>(noop())

    init {
        inlet.serve(object : Consumer<A> {
            override fun provide(input: A) {
                outlet.use().provide(f(input))
            }
        })
    }

    companion object {
        inline fun <reified A: Any, reified B: Any> create(noinline f: (A) -> B): MapperApi<A, B> = MapperCell(f)
    }
}