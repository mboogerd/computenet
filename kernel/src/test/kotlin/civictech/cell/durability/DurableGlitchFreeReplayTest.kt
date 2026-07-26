package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Consumer
import civictech.cell.CurrentContext
import civictech.cell.MapperCell
import civictech.cell.Timestamp
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.LinkResult
import civictech.cell.port.PortIdentities
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.host.HostedCellProxy
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * PN-2 exit (plan §3 Rule of recovery, §4 PN-2; closes matrix cell A–D:
 * durable + glitch-free). Journal replay is a **baseline**, not a wave.
 *
 * A WAIT fork-join diamond: a shared source `A` fans one wave to two arms and
 * they rejoin at a [GlitchFreeCell]. One arm runs through a **journaled
 * mid-graph** cell `J` (its frames carry a non-null wave context — the case no
 * prior test covers); the sibling arm runs through a **volatile** cell `V`. The
 * whole graph crashes and is rebuilt; `J`'s journal is replayed; live traffic
 * resumes.
 *
 * The rebuilt join's frontier is empty, so a replayed arm-1 wave re-entering as
 * an ordinary *live* wave would forever await an arm-2 contribution the volatile
 * arm can never replay — an asymmetric-diamond stall (plan §4: "transient floors
 * make every sibling arm expected while a volatile arm never advances"). PN-2
 * stamps every replayed emission as a catch-up baseline, which the frontier
 * excludes from every wave-completeness set and releases immediately: a recovery
 * produces no waves and therefore no glitches.
 *
 * Over 100 seeds the released sequence equals a batch recompute, and
 * `released == journal.replay().size` (silent drops fail loudly). Controls:
 * (a) `replayAsBaseline = false` → the replayed cone stalls on every seed;
 * (b) PN-1's derivation reverted while the baseline stays on → still green,
 * proving PN-2's baseline path carries recovery independently of PN-1.
 */
class DurableGlitchFreeReplayTest {

    private val consumerInt = @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Int>>)
    private val consumerPair =
        @Suppress("UNCHECKED_CAST") (Consumer::class.java as Class<Consumer<Pair<String, Int>>>)

    /** An observation released past the join; [baseline] records whether it arrived as a catch-up baseline. */
    data class Obs(val label: String, val n: Int, val ts: Timestamp, val baseline: Boolean)

    /** Shared source of the diamond — survives the crash (drives resume traffic on a continuous source lane). */
    private class Source(clazz: Class<Consumer<Int>>, override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet(clazz))
        fun emit(n: Int) = outlet.call.provide(n)
    }

    private class Observer(
        clazz: Class<Consumer<Pair<String, Int>>>,
        private val sink: MutableList<Obs>,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        val inlet = registerPort("inlet", FanInlet(clazz))

        init {
            inlet.serve(object : Consumer<Pair<String, Int>> {
                override fun provide(input: Pair<String, Int>) {
                    val ctx = CurrentContext.get()!!
                    sink += Obs(input.first, input.second, ctx.timestamp, ctx.baseline != null)
                }
            })
        }
    }

    private interface MapperProxy {
        val inlet: Use<Consumer<Int>>
    }

    private interface JoinProxy {
        val inlet: Use<Consumer<Pair<String, Int>>>
    }

    private val preCrashWaves = 6      // waves 1..6, all flushed, all journaled on the durable arm
    private val resumeWaves = 4        // waves 7..10, live after recovery

    private fun runSession(seed: Long, replayAsBaseline: Boolean, deriveStableRefs: Boolean): List<Obs> {
        val previousDerive = PortIdentities.deriveRefs
        PortIdentities.deriveRefs = deriveStableRefs
        try {
            val controller = SimulationController(seed)
            val rnd = Random(seed)
            val journal = InMemoryJournal() // "the disk": the only thing that survives the crash

            val jRef = CellRef(UUID.randomUUID())
            val vRef = CellRef(UUID.randomUUID())
            val dRef = CellRef(UUID.randomUUID())
            val oRef = CellRef(UUID.randomUUID())
            val observations = mutableListOf<Obs>()

            // per-cell tee: only the mid-graph mapper J is journaled; V is volatile.
            val selector: (CellRef) -> Journal? = { if (it == jRef) journal else null }

            /**
             * Build (or rebuild) the hosts + cells + links; returns the durable host and a
             * fresh source. The source is minted per-build: after the crash, resume traffic
             * therefore rides a NEW source lane, so it cannot advance the volatile arm's
             * watermark for the replayed (old-source) waves — a replayed wave that re-enters
             * the wave plane genuinely stalls (the control), instead of being rescued by a
             * later live wave's monotone watermark.
             */
            fun build(): Pair<ManagedHost, Source> {
                val hostDur = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
                val hostVol = ManagedHost(scheduler = controller.scheduler())

                val a = Source(consumerInt)
                val j = MapperCell<Int, Pair<String, Int>>(f = { "J" to it }, ref = jRef)
                val v = MapperCell<Int, Pair<String, Int>>(f = { "V" to it }, ref = vRef)
                val d = GlitchFreeCell(consumerPair, ref = dRef, mode = GlitchFreeCell.WaveMode.WAIT)
                val o = Observer(consumerPair, observations, ref = oRef)

                hostDur.managementInlet.call.spawn(j)
                hostDur.managementInlet.call.spawn(v)
                hostVol.managementInlet.call.spawn(d)
                hostVol.managementInlet.call.spawn(o)
                controller.runToIdle()

                // A fans one wave to both arms through the durable host's queue — so BOTH
                // arms' intakes are on the crashing host (the volatile arm's in-flight
                // deliveries are lost with it), and A→J is written to the WAL.
                for (ref in listOf(jRef, vRef)) {
                    a.outlet.subscribe(Use.fixed(hostDur.lookup<MapperProxy>(ref)!!.inlet.call, PortRef.generate()))
                }

                // Each arm's outlet hands off to the join through the volatile host's queue.
                // Handshake first (fires EdgeOpen so the frontier knows the arm), then reroute
                // delivery over the queue (the diamond wiring of GlitchFreeDiamondTest).
                val routedJoin = hostVol.lookup<JoinProxy>(dRef)!!.inlet.call
                for (arm in listOf(j, v)) {
                    (arm.outlet.linkTo(d.inlet as LinkFrom<Consumer<Pair<String, Int>>>)
                        is LinkResult.Connected).shouldBeTrue()
                    arm.outlet.unsubscribe(d.inlet.ref)
                    arm.outlet.subscribe(Use.fixed(routedJoin, d.inlet.ref))
                }
                // the observer sits behind the join, direct on the volatile host's task
                d.outlet.subscribe(Use.fixed(o.inlet.call, o.inlet.ref))
                controller.runToIdle()
                return hostDur to a
            }

            val (_, aPre) = build()

            // pre-crash: full diamond waves, drained so the join flushes each as a {J,V} pair
            for (n in 1..preCrashWaves) {
                aPre.emit(n)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()

            // CRASH: every host, cell, queue, and link is discarded — only the journal survives.
            observations.clear() // measure the post-recovery world only
            val (recoveredDur, aPost) = build()

            recoveredDur.replayAsBaseline = replayAsBaseline
            recoveredDur.recoverFrom(journal) // replay the durable arm's frames
            controller.runToIdle()

            // resume live traffic on the rebuilt source lane
            for (n in (preCrashWaves + 1)..(preCrashWaves + resumeWaves)) {
                aPost.emit(n)
                repeat(rnd.nextInt(4)) { controller.step() }
            }
            controller.runToIdle()

            return observations.toList()
        } finally {
            PortIdentities.deriveRefs = previousDerive
        }
    }

    /** The batch-recompute oracle: durable arm re-emitted for every recovered wave, both arms for every live wave. */
    private fun oracle(): List<Pair<String, Int>> =
        (1..preCrashWaves).map { "J" to it } +
            ((preCrashWaves + 1)..(preCrashWaves + resumeWaves)).flatMap { listOf("J" to it, "V" to it) }

    @Test
    fun `replay re-enters as a baseline - durable glitch-free diamond recovers under 100 seeds`() {
        val journalFrames = preCrashWaves // one WAL frame per journaled arm-1 wave
        for (seed in 0L until 100L) {
            val obs = runSession(seed, replayAsBaseline = true, deriveStableRefs = true)

            val replay = obs.filter { it.baseline }
            val live = obs.filter { !it.baseline }

            // every replayed frame is released exactly once as a baseline — no silent drop
            // (released + suppressed == journal.replay().size; nothing is suppressed here)
            replay.size shouldBe journalFrames
            replay.map { it.n }.toSet() shouldBe (1..preCrashWaves).toSet()
            // a baseline needs no sibling: only the durable arm re-emits on recovery
            replay.all { it.label == "J" }.shouldBeTrue()

            // resume traffic is glitch-free: each live wave releases a complete {J,V} pair
            // under one timestamp, in per-source counter order
            live.groupBy { it.n }.forEach { (_, group) ->
                group.map { it.label }.toSet() shouldBe setOf("J", "V")
                group.map { it.ts }.toSet().size shouldBe 1
            }
            live.map { it.n }.toSet() shouldBe ((preCrashWaves + 1)..(preCrashWaves + resumeWaves)).toSet()

            // released sequence equals the batch recompute
            obs.map { it.label to it.n }.sortedWith(compareBy({ it.second }, { it.first })) shouldBe
                oracle().sortedWith(compareBy({ it.second }, { it.first }))
        }
    }

    @Test
    fun `control a - replay as ordinary waves stalls the asymmetric diamond on every seed`() {
        for (seed in 0L until 100L) {
            val obs = runSession(seed, replayAsBaseline = false, deriveStableRefs = true)
            // the replayed arm-1 waves await an arm-2 contribution the volatile arm can never
            // replay: they stall in the join, never released. None of 1..preCrashWaves surface.
            obs.none { it.baseline }.shouldBeTrue()
            obs.filter { it.n in 1..preCrashWaves }.isEmpty().shouldBeTrue()
            // live resume still works — the divergence is confined to the recovered cone
            obs.map { it.n }.toSet() shouldBe ((preCrashWaves + 1)..(preCrashWaves + resumeWaves)).toSet()
        }
    }

    @Test
    fun `control b - PN-1 derivation reverted but baseline on - recovery still green`() {
        val journalFrames = preCrashWaves
        for (seed in 0L until 100L) {
            val obs = runSession(seed, replayAsBaseline = true, deriveStableRefs = false)

            val replay = obs.filter { it.baseline }
            // the baseline path releases the replayed cone without matching a frontier edge,
            // so recovery is green even with non-replay-stable port identity
            replay.size shouldBe journalFrames
            replay.map { it.n }.toSet() shouldBe (1..preCrashWaves).toSet()
            obs.map { it.label to it.n }.sortedWith(compareBy({ it.second }, { it.first })) shouldBe
                oracle().sortedWith(compareBy({ it.second }, { it.first }))
        }
    }
}
