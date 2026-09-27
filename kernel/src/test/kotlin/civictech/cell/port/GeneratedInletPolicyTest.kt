package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import civictech.cell.data.Aggregators
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.GroupByCell
import civictech.cell.link.LinkResult
import civictech.gen.wire.CellBase
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** A non-Propagate ops Api whose generated `opsHandler()` binding returns a SAM-converted lambda. */
fun interface TallyOps {
    fun tally(n: Int)
}

/**
 * The other two generated inlet shapes (`ContractProcessor.kt`'s inlet branch):
 * a `Use<Propagate<T>>` inlet (bound through `onEach`, like `Serve`) and a
 * `Serve<Api>` inlet bound through `<name>Handler()`.
 */
@CellBase
interface TallyApi {
    val inlet: Use<Propagate<Int>>
    val ops: Serve<TallyOps>
}

class TallyCell(ref: CellRef = CellRef(UUID.randomUUID())) : TallyCellBase(ref) {
    val seen = mutableListOf<Int>()

    override fun onInlet(value: Int) {
        seen += value
    }

    // A fun-interface SAM conversion: the JVM class behind it is not public.
    override fun opsHandler(): TallyOps = TallyOps { seen += -it }
}

/**
 * computenet-mdvgt: an [InletPolicy] installed on a `@CellBase`-generated inlet
 * routes every delivery through [FanInlet]'s reflective terminal
 * (`Invocation.invoke`). The generated handler's runtime class is a
 * Kotlin-synthesized, non-public SAM adapter (`onEach(this::onX)` →
 * `Propagate(handler)`), so reflecting on the concrete class threw
 * `IllegalAccessException` on the first real delivery. Each test here installs
 * a policy on a generated inlet and delivers for real.
 */
class GeneratedInletPolicyTest {

    /** One outlet that originates a fresh wave per [add]. */
    private class Source(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Long>>>())

        fun add(vararg events: Long) = outlet.originate {
            propagate(SetDelta(adds = events.associate { it to setOf(Timestamp(UUID.randomUUID(), it)) }))
        }
    }

    private fun groupBy(): GroupByCell<Long, Long, Long, Long> = GroupByCell(
        keyFn = { e: Long -> e / 10 * 10 },
        aggregator = Aggregators.count(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun link(outlet: FanOutlet<Propagate<SetDelta<Long>>>, inlet: FanInlet<Propagate<SetDelta<Long>>>) {
        (outlet.linkTo(inlet as LinkFrom<Propagate<SetDelta<Long>>>) is LinkResult.Connected).shouldBeTrue()
    }

    private fun outputOf(gb: GroupByCell<Long, Long, Long, Long>): MutableList<MapDelta<Long, Long>> {
        val seen = mutableListOf<MapDelta<Long, Long>>()
        gb.outlet.subscribe(Use.fixed(object : Propagate<MapDelta<Long, Long>> {
            override fun propagate(value: MapDelta<Long, Long>) {
                seen += value
            }
        }, PortRef.generate()))
        return seen
    }

    /** Deliver one real wave through [policy] on `GroupByCell.inlet` and assert it folded. */
    private fun deliversThrough(policy: InletPolicy) {
        val gb = groupBy()
        val src = Source()
        gb.inlet.install(policy)
        link(src.outlet, gb.inlet)
        val seen = outputOf(gb)

        src.add(3, 15)

        seen shouldBe listOf(MapDelta(mapOf(0L to 1L, 10L to 1L), emptySet()))
    }

    @Test
    fun `WaveFrontier installed directly on a generated Serve-Propagate inlet delivers`() =
        deliversThrough(WaveFrontier(GlitchFreeCell.WaveMode.WAIT))

    @Test
    fun `a WaveFrontier arm on a generated Serve-Propagate inlet delivers`() =
        deliversThrough(WaveFrontier(GlitchFreeCell.WaveMode.WAIT).arm())

    @Test
    fun `Admit on a generated Serve-Propagate inlet delivers`() =
        deliversThrough(Admit(admits = { true }))

    @Test
    fun `PullOnOpen on a generated Serve-Propagate inlet delivers`() =
        deliversThrough(PullOnOpen())

    @Test
    fun `a policy on a generated Use-Propagate inlet delivers`() {
        val cell = TallyCell()
        cell.inlet.install(Admit(admits = { true }))

        cell.inlet.call.propagate(7)

        cell.seen shouldBe listOf(7)
    }

    @Test
    fun `a policy on a generated Handler-bound inlet whose handler is a SAM lambda delivers`() {
        val cell = TallyCell()
        cell.ops.install(Admit(admits = { true }))

        cell.ops.call.tally(2)

        cell.seen shouldBe listOf(-2)
    }
}
