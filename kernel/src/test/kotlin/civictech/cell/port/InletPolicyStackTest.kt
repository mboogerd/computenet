package civictech.cell.port

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MessageContext
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-9: the inlet policy chain. Fixed tiers ADMIT → GATE → ALIGN → ACTIVATE run
 * in tier order regardless of install order; at most one ALIGN; a dropping ADMIT
 * that does not mint a Progress absorb-ack (the CP-A3 law) stalls a downstream
 * ALIGN frontier.
 *
 * The harness is a two-arm diamond feeding one policy-gated inlet: a shared
 * source (srcId, n) fans through relay A and relay B, each re-stamping its own
 * outlet as `sourcePort` while keeping the wave timestamp — so the frontier sees
 * two edges of the same wave, exactly as a real fan-in join does.
 */
class InletPolicyStackTest {

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
     * in [order] (installed in that order). Arm delivery order per wave and gate
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

        val gate = Gate()
        val policies: Map<String, InletPolicy> = mapOf(
            "admit" to admit(mintsProgressAck, dropB),
            "gate" to gate,
            "align" to WaveFrontier(GlitchFreeCell.WaveMode.WAIT),
        )
        order.forEach { inlet.install(policies.getValue(it)) }

        @Suppress("UNCHECKED_CAST")
        relayA.outlet.linkTo(inlet as LinkFrom<Consumer<Pair<String, Int>>>)
        @Suppress("UNCHECKED_CAST")
        relayB.outlet.linkTo(inlet as LinkFrom<Consumer<Pair<String, Int>>>)

        val srcId = UUID.randomUUID()
        val dummy = PortRef.generate()
        for (n in 1..waves) {
            // GATE exercise: occasionally hold, then drain in FIFO before the next wave
            val cycleGate = "gate" in order && rnd.nextInt(3) == 0
            if (cycleGate) gate.close()
            val arms = if (rnd.nextBoolean()) listOf(relayA, relayB) else listOf(relayB, relayA)
            val ctx = MessageContext(Timestamp(srcId, n.toLong()), dummy)
            arms.forEach { relay -> CurrentContext.with(ctx) { relay.inlet.call.provide(n) } }
            if (cycleGate) gate.open()
        }
        return out
    }

    /** The glitch-free reference: per wave, the admitted arm-label set, waves in counter order. */
    private fun reference(waves: Int, dropB: (Int) -> Boolean): Map<Long, Set<String>> =
        (1..waves).associate { n ->
            n.toLong() to (if (dropB(n)) setOf("A") else setOf("A", "B"))
        }

    private fun byWave(obs: List<Obs>): Map<Long, Set<String>> =
        obs.groupBy { it.counter }.mapValues { (_, v) -> v.map { it.label }.toSet() }

    @Test
    fun `ADMIT + GATE + ALIGN stack equals the three tiers applied in series`() {
        val waves = 40
        val dropB = { n: Int -> n % 5 == 0 }
        for (seed in 0L until 100L) {
            val obs = run(seed, waves, listOf("admit", "gate", "align"), dropB = dropB)
            byWave(obs) shouldBe reference(waves, dropB)
            // glitch-free ordering: waves surface in non-decreasing counter order
            val counters = obs.map { it.counter }
            counters shouldBe counters.sorted()
        }
    }

    @Test
    fun `install order is irrelevant - every permutation yields identical output`() {
        val waves = 40
        val dropB = { n: Int -> n % 7 == 0 }
        val perms = listOf(
            listOf("admit", "gate", "align"),
            listOf("align", "gate", "admit"),
            listOf("gate", "admit", "align"),
            listOf("align", "admit", "gate"),
            listOf("admit", "align", "gate"),
            listOf("gate", "align", "admit"),
        )
        for (seed in 0L until 100L) {
            val outputs = perms.map { byWave(run(seed, waves, it, dropB = dropB)) }
            outputs.forEach { it shouldBe reference(waves, dropB) }
        }
    }

    @Test
    fun `installing a second ALIGN policy throws`() {
        val inlet = FanInlet(consumerPair)
        inlet.install(WaveFrontier(GlitchFreeCell.WaveMode.WAIT))
        shouldThrow<IllegalArgumentException> {
            inlet.install(WaveFrontier(GlitchFreeCell.WaveMode.WAIT))
        }
    }

    // ------------------------------------------------------------------
    // Control (b): the CP-A3 law. A dropping ADMIT above an ALIGN must mint a
    // Progress absorb-ack, or the dropped edge never settles and its wave stalls.
    // ------------------------------------------------------------------

    @Test
    fun `a dropping ADMIT that mints a progress ack releases every wave`() {
        val waves = 30
        val dropB = { n: Int -> n % 4 == 0 }
        for (seed in 0L until 50L) {
            val obs = run(seed, waves, listOf("admit", "align"), mintsProgressAck = true, dropB = dropB)
            byWave(obs) shouldBe reference(waves, dropB)
        }
    }

    @Test
    fun `control - a dropping ADMIT without a progress ack stalls the downstream ALIGN`() {
        val waves = 20
        // Drop only the FINAL wave's B arm: nothing arrives on that edge after it,
        // so the "later wave / monotone max" watermark advance can never rescue it.
        // The ONLY thing that can settle the dropped edge is the CP-A3 Progress ack.
        val dropLast = { n: Int -> n == waves }
        val healthy = run(3L, waves, listOf("admit", "align"), mintsProgressAck = true, dropB = dropLast)
        val stalled = run(3L, waves, listOf("admit", "align"), mintsProgressAck = false, dropB = dropLast)
        // with the ack, the final (dropped-B) wave still releases — arm A only
        byWave(healthy).keys.contains(waves.toLong()) shouldBe true
        // without it, the dropped edge never settles and no later wave rescues it: stall
        byWave(stalled).keys.contains(waves.toLong()) shouldBe false
        byWave(stalled).size shouldBe (byWave(healthy).size - 1)
    }
}
