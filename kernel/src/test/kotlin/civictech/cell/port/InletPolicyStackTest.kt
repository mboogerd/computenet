package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-9: the inlet policy chain. Fixed tiers ADMIT → ACTIVATE run in tier
 * order regardless of install order.
 *
 * The harness is a two-arm diamond feeding one policy-gated inlet: a shared
 * source fans through relay A and relay B into the same policy-gated inlet.
 *
 * [HoldingProbe] is a local, test-only ADMIT-tier double (the production
 * GATE tier of `PolicyTier` this stress-test originally exercised via `Gate`
 * had zero production installs and was deleted, T03) that holds invocations
 * FIFO and releases them on demand — kept here only to exercise
 * multi-ADMIT-tier chain composition (two ADMIT-tier stages)
 * and to confirm install-order independence still holds with more than one
 * ADMIT policy.
 */
class InletPolicyStackTest {

    /** ADMIT-tier test double: holds invocations FIFO after [close], draining them in arrival order on [open]. */
    private class HoldingProbe : InletPolicy {
        override val tier get() = PolicyTier.ADMIT

        private lateinit var release: (Invocation) -> Unit
        private val held = ArrayDeque<Invocation>()
        private var isOpen = true

        override fun attach(inlet: FanInlet<*>, release: (Invocation) -> Unit) {
            this.release = release
        }

        override fun offer(invocation: Invocation) {
            if (isOpen) release(invocation) else held.addLast(invocation)
        }

        fun close() {
            isOpen = false
        }

        fun open() {
            isOpen = true
            while (held.isNotEmpty()) release(held.removeFirst())
        }

        override fun reset() {
            held.clear()
        }
    }

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    private val consumerPair = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Pair<String, Int>>>)

    private data class Obs(val label: String, val n: Int, val counter: Long)

    /** Eager relay: serves in init, re-emits ("label", n) under the incoming wave context. */
    private inner class Relay(val label: String, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet(consumerInt))
        val outlet = registerPort("outlet", FanOutlet(consumerPair))

        init {
            inlet.serve(object : Consumer<Int> {
                override fun provide(input: Int) {
                    outlet.call.provide(label to input)
                }
            })
        }
    }

    /** ADMIT policy dropping arm-B contributions for waves where [dropB] holds. */
    private fun admit(mintsProgressAck: Boolean, dropB: (Int) -> Boolean): Admit {
        return Admit(mintsProgressAck = mintsProgressAck, admits = { inv ->
            val pair = inv.args[0] as Pair<*, *>
            val drop = pair.first == "B" && dropB(pair.second as Int)
            drop.not()
        })
    }

    /**
     * Runs [waves] through the diamond into one inlet carrying the policies named
     * in [order] (installed in that order). Arm delivery order per wave and hold
     * cycling are driven by [seed]. Returns the served observations.
     */
    private fun run(
        seed: Long,
        waves: Int,
        order: List<String>,
        mintsProgressAck: Boolean = true,
        dropB: (Int) -> Boolean = { false },
    ): List<Obs> {
        val rnd = Random(seed)
        val relayA = Relay("A")
        val relayB = Relay("B")
        val out = mutableListOf<Obs>()
        val inlet = FanInlet(consumerPair)
        inlet.serve(object : Consumer<Pair<String, Int>> {
            override fun provide(input: Pair<String, Int>) {
                out += Obs(input.first, input.second, CurrentContext.get()!!.timestamp.counter)
            }
        })

        val hold = HoldingProbe()
        val policies: Map<String, InletPolicy> = mapOf(
            "admit" to admit(mintsProgressAck, dropB),
            "hold" to hold,
        )
        order.forEach { inlet.install(policies.getValue(it)) }

        @Suppress("UNCHECKED_CAST")
        relayA.outlet.linkTo(inlet as LinkFrom<Consumer<Pair<String, Int>>>)
        @Suppress("UNCHECKED_CAST")
        relayB.outlet.linkTo(inlet as LinkFrom<Consumer<Pair<String, Int>>>)

        val srcId = UUID.randomUUID()
        val dummy = PortRef.generate()
        for (n in 1..waves) {
            // HoldingProbe exercise: occasionally hold, then drain in FIFO before the next wave
            val cycleHold = "hold" in order && rnd.nextInt(3) == 0
            if (cycleHold) hold.close()
            val arms = if (rnd.nextBoolean()) listOf(relayA, relayB) else listOf(relayB, relayA)
            val ctx = MessageContext(Timestamp(srcId, n.toLong()), dummy)
            arms.forEach { relay -> CurrentContext.with(ctx) { relay.inlet.call.provide(n) } }
            if (cycleHold) hold.open()
        }
        return out
    }

    /** Per input, the admitted arm-label set. */
    private fun reference(waves: Int, dropB: (Int) -> Boolean): Map<Long, Set<String>> =
        (1..waves).associate { n ->
            n.toLong() to (if (dropB(n)) setOf("A") else setOf("A", "B"))
        }

    private fun byWave(obs: List<Obs>): Map<Long, Set<String>> =
        obs.groupBy { it.counter }.mapValues { (_, v) -> v.map { it.label }.toSet() }

    @Test
    fun `ADMIT plus a holding ADMIT double equals the policies applied in series`() {
        val waves = 40
        val dropB = { n: Int -> n % 5 == 0 }
        for (seed in 0L until 100L) {
            val obs = run(seed, waves, listOf("admit", "hold"), dropB = dropB)
            byWave(obs) shouldBe reference(waves, dropB)
        }
    }

    @Test
    fun `install order is irrelevant - every permutation yields identical output`() {
        val waves = 40
        val dropB = { n: Int -> n % 7 == 0 }
        val perms = listOf(
            listOf("admit", "hold"),
            listOf("hold", "admit"),
        )
        for (seed in 0L until 100L) {
            val outputs = perms.map { byWave(run(seed, waves, it, dropB = dropB)) }
            outputs.forEach { it shouldBe reference(waves, dropB) }
        }
    }

}
