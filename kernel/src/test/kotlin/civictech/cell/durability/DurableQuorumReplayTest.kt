package civictech.cell.durability

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.PresenceCountCell
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.view.MapView
import civictech.cell.data.view.SetView
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.forEachSeed
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * D-REPLAY / `[24-REPLAY-01]` (spec 20/24 §Durable replay of a mid-graph data
 * cell, PN-2) for the **lane-counting** fan-in. `DurableGlitchFreeReplayTest`
 * proves the baseline path for the *generic* glitch-free join
 * (`WaveFrontier.offer()`); `QuorumSetCell`/`PresenceLanes` are an
 * architecturally different mechanism — one `TagState` per open source link,
 * admission by presence count against a threshold — and PN-2 patched only the
 * former.
 *
 * The graph is the asymmetric diamond in SET form:
 *
 * ```
 *   A (root SetCell)  →[durable host queue]→  J (journaled relay)  →  lane 1
 *                                                                      quorum(k = 2)  → SetView
 *   B (root SetCell)  ──────────────────────────────────────────────→  lane 2
 * ```
 *
 * Only `J` is journaled (per-cell tee), and its frames are *mid-graph* — they
 * carry `A`'s wave context, the only kind `HostDurability` stamps
 * `MessageContext.baseline`. The whole graph then crashes; only the journal
 * survives; the graph is rebuilt and `J`'s frames replay.
 *
 * The rebuilt quorum's lanes are empty and the volatile arm can never replay,
 * so a replayed delta evaluated against the *live* threshold sticks at
 * lane-count 1 < 2 forever and the recovered state is silently dropped (the
 * `24-REPLAY-01` `kernel-gap` in `concord/corpus/DISPUTES.md`). Under PN-2 the
 * replayed delta is a baseline, and a baseline installs its arm state
 * regardless of the threshold — the SET-fan-in analogue of `WaveFrontier`'s
 * baseline branch.
 *
 * `k = 2` is load-bearing: a `k = 1` quorum is met by any single lane and would
 * pass with or without the fix (uninformative). Controls: replay as ordinary
 * waves (`replayAsBaseline = false`) — the recovered view stays empty on every
 * seed, which is exactly the pre-fix behavior; and `PresenceCountCell`, the
 * *other* consumer of `PresenceLanes`, recovers identically with and without
 * the stamp because it has no threshold to bypass.
 */
class DurableQuorumReplayTest {

    /**
     * The journaled mid-graph cell: a stateless `SetDelta` pass-through. Like
     * `MixedDurabilityTest`'s `TallyCell` it is neither `Stateful` nor
     * `Effectful`, so frame replay is its *entire* recovery path — the replayed
     * frames are re-emitted downstream verbatim, inheriting the baseline stamp
     * through the ordinary reactive context copy (`FanOutlet`).
     */
    private class Relay(override val ref: CellRef) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Int>>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<Int>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<Int>> {
                override fun propagate(value: SetDelta<Int>) {
                    outlet.call.propagate(value)
                }
            })
        }
    }

    private interface RelayProxy {
        val inlet: Use<Propagate<SetDelta<Int>>>
    }

    /**
     * Build / crash / rebuild / `recoverFrom`, in the shape
     * `DurableGlitchFreeReplayTest.runSession` uses: an [InMemoryJournal] as
     * "the disk", a per-cell `journalFor` selector, a [SimulationController]
     * for bounded, seeded scheduling (no sleeps anywhere).
     *
     * [wireFanIn] builds the fan-in under test and hangs it off both arms; it
     * runs on every (re)build, so the test's view is re-minted after the crash
     * exactly as a rebuilt graph's would be.
     */
    private class ReplaySession(
        seed: Long,
        private val replayAsBaseline: Boolean = true,
        private val wireFanIn: (durableArm: FanOutlet<Propagate<SetDelta<Int>>>, volatileArm: SetCell<Int>) -> Unit,
    ) {
        val controller = SimulationController(seed)
        private val rnd = Random(seed)
        private val journal = InMemoryJournal() // "the disk": the only thing that survives the crash
        private val relayRef = CellRef(UUID.randomUUID())

        // per-cell tee (CP-C1): only the mid-graph relay is journaled; every
        // other cell in the graph is volatile and comes back empty.
        private val selector: (CellRef) -> Journal? = { if (it == relayRef) journal else null }

        private lateinit var host: ManagedHost

        /** Root of the journaled arm — re-minted per build (a root never survives a crash). */
        lateinit var durableRoot: SetCell<Int>

        /** Root of the volatile arm — re-minted per build; it replays nothing, ever. */
        lateinit var volatileRoot: SetCell<Int>

        init {
            build()
        }

        /** Seeded scheduler interleaving between two driving calls (bounded, never a sleep). */
        fun jitter() = repeat(rnd.nextInt(4)) { controller.step() }

        fun drain() {
            controller.runToIdle()
        }

        private fun build() {
            host = ManagedHost(scheduler = controller.scheduler(), journalFor = selector)
            val relay = Relay(relayRef)
            host.managementInlet.call.spawn(relay)
            controller.runToIdle()

            // Fresh roots per build: nothing about a root survives the crash, and a
            // rebuilt SetCell mints its tags under a fresh ref, so replayed (old) tags
            // and resumed (new) tags can never collide.
            durableRoot = SetCell()
            volatileRoot = SetCell()

            // The journaled arm: the root's deltas ride the durable host's queue into the
            // relay — that queue is what writes them to the WAL, and they carry the root's
            // wave context, so they are the MID-GRAPH frames PN-2 stamps as baseline.
            durableRoot.outlet.subscribe(
                Use.fixed(host.lookup<RelayProxy>(relayRef)!!.inlet.call, PortRef.generate())
            )
            wireFanIn(relay.outlet, volatileRoot)
            controller.runToIdle()
        }

        /**
         * CRASH: hosts, cells, queues, links and both roots are discarded — only the
         * journal survives. The graph is rebuilt (the relay under its original ref, so
         * PN-1's derived port identity rebuilds the same lane) and replayed.
         */
        fun crashAndRecover() {
            build()
            host.replayAsBaseline = replayAsBaseline
            host.recoverFrom(journal)
            controller.runToIdle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun wireQuorum(
        k: Int,
        durableArm: FanOutlet<Propagate<SetDelta<Int>>>,
        volatileArm: SetCell<Int>,
    ): SetView<Int> {
        val quorum = QuorumSetCell.kOfN<Int>(k)
        val view = SetView<Int>()
        quorum.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<Int>> {
            override fun propagate(value: SetDelta<Int>) {
                view.apply(value)
            }
        }, PortRef.generate()))
        (durableArm.linkTo(quorum.inlet as LinkFrom<Propagate<SetDelta<Int>>>) is LinkResult.Connected)
            .shouldBeTrue()
        (volatileArm.outlet.linkTo(quorum.inlet as LinkFrom<Propagate<SetDelta<Int>>>) is LinkResult.Connected)
            .shouldBeTrue()
        return view
    }

    @Suppress("UNCHECKED_CAST")
    private fun wireCount(
        durableArm: FanOutlet<Propagate<SetDelta<Int>>>,
        volatileArm: SetCell<Int>,
    ): MapView<Int, Int> {
        val counts = PresenceCountCell<Int>()
        val view = MapView<Int, Int>()
        counts.outlet.subscribe(Use.fixed(object : Propagate<MapDelta<Int, Int>> {
            override fun propagate(value: MapDelta<Int, Int>) {
                view.apply(value)
            }
        }, PortRef.generate()))
        (durableArm.linkTo(counts.inlet as LinkFrom<Propagate<SetDelta<Int>>>) is LinkResult.Connected)
            .shouldBeTrue()
        (volatileArm.outlet.linkTo(counts.inlet as LinkFrom<Propagate<SetDelta<Int>>>) is LinkResult.Connected)
            .shouldBeTrue()
        return view
    }

    /** A non-empty subset of the domain, asserted by BOTH arms so the pre-crash view is exactly it. */
    private fun sharedElements(rnd: Random): Set<Int> =
        (1..5).filterTo(LinkedHashSet()) { rnd.nextBoolean() }.ifEmpty { linkedSetOf(3) }

    @Test
    fun `k-of-2 quorum recovers its pre-crash view from the journaled arm's replayed baseline - 50 seeds`() {
        forEachSeed(0L until 50L) { seed ->
            val rnd = Random(seed)
            var view = SetView<Int>()
            val session = ReplaySession(seed) { durableArm, volatileArm ->
                view = wireQuorum(k = 2, durableArm = durableArm, volatileArm = volatileArm)
            }

            // pre-crash: both arms assert every shared element, in a seeded interleaving
            val shared = sharedElements(rnd)
            val ops = shared.flatMap { listOf(true to it, false to it) }.shuffled(rnd)
            ops.forEach { (onDurableArm, element) ->
                if (onDurableArm) session.durableRoot.inlet.call.add(element)
                else session.volatileRoot.inlet.call.add(element)
                session.jitter()
            }
            session.drain()

            val preCrash = view.current()
            preCrash shouldBe shared // count 2 >= k on every shared element

            session.crashAndRecover()

            // the replayed frames are baselines: the quorum installs them as recovered
            // arm state instead of stalling at lane-count 1 < 2 with an empty view
            view.current() shouldBe preCrash
        }
    }

    @Test
    fun `control - replay as ordinary waves drops the recovered arm state on every seed`() {
        forEachSeed(0L until 50L) { seed ->
            val rnd = Random(seed)
            var view = SetView<Int>()
            val session = ReplaySession(seed, replayAsBaseline = false) { durableArm, volatileArm ->
                view = wireQuorum(k = 2, durableArm = durableArm, volatileArm = volatileArm)
            }

            val shared = sharedElements(rnd)
            val ops = shared.flatMap { listOf(true to it, false to it) }.shuffled(rnd)
            ops.forEach { (onDurableArm, element) ->
                if (onDurableArm) session.durableRoot.inlet.call.add(element)
                else session.volatileRoot.inlet.call.add(element)
                session.jitter()
            }
            session.drain()
            view.current() shouldBe shared

            session.crashAndRecover()

            // without the baseline stamp the replayed deltas are ordinary live waves: they
            // fold into their lane, stick at count 1 < 2, and the recovered view is empty.
            // This is the confirmed defect verbatim — the fix is the stamp's disposition,
            // not the fold, so this control stays red-by-construction after the fix.
            view.current() shouldBe emptySet()
        }
    }

    @Test
    fun `recovered elements stay subject to the live threshold once traffic resumes`() {
        var view = SetView<Int>()
        val session = ReplaySession(seed = 11L) { durableArm, volatileArm ->
            view = wireQuorum(k = 2, durableArm = durableArm, volatileArm = volatileArm)
        }
        listOf(1, 2).forEach {
            session.durableRoot.inlet.call.add(it)
            session.volatileRoot.inlet.call.add(it)
        }
        session.drain()
        view.current() shouldBe setOf(1, 2)

        session.crashAndRecover()
        view.current() shouldBe setOf(1, 2)

        // the volatile arm rebuilds its own state live: the elements are already
        // advertised, so re-entry is idempotent (advertise-once) — no churn
        listOf(1, 2).forEach { session.volatileRoot.inlet.call.add(it) }
        session.drain()
        view.current() shouldBe setOf(1, 2)

        // ...and live semantics are untouched: dropping a lane back below k retracts,
        // baseline-installed or not
        session.volatileRoot.inlet.call.remove(1)
        session.drain()
        view.current() shouldBe setOf(2)
    }

    @Test
    fun `a baseline installs the whole recovered arm and converges back under live re-evaluation`() {
        var view = SetView<Int>()
        val session = ReplaySession(seed = 12L) { durableArm, volatileArm ->
            view = wireQuorum(k = 2, durableArm = durableArm, volatileArm = volatileArm)
        }
        listOf(1, 2, 3).forEach { session.durableRoot.inlet.call.add(it) }
        listOf(1, 2).forEach { session.volatileRoot.inlet.call.add(it) } // 3 is the durable arm's alone
        session.drain()
        view.current() shouldBe setOf(1, 2) // 3 never met k = 2

        session.crashAndRecover()

        // The quorum is volatile: it has no record of its own pre-crash view, so the
        // replayed arm's state is the only recovery information it has and the install
        // over-approximates by exactly the elements that arm alone asserted (3).
        view.current() shouldBe setOf(1, 2, 3)

        // A live delta touching 3 re-evaluates it under the ordinary threshold — count 1
        // < 2 — and it leaves. Convergence back to live semantics after recovery is the
        // decided behavior, not a bug: baseline-installed elements are not pinned.
        session.durableRoot.inlet.call.add(3)
        session.drain()
        view.current() shouldBe setOf(1, 2)
    }

    @Test
    fun `presence count needs no baseline treatment - recovered lane counts are truthful either way`() {
        for (replayAsBaseline in listOf(true, false)) {
            var view = MapView<Int, Int>()
            val session = ReplaySession(seed = 13L, replayAsBaseline = replayAsBaseline) { durableArm, volatileArm ->
                view = wireCount(durableArm = durableArm, volatileArm = volatileArm)
            }
            listOf(1, 2).forEach {
                session.durableRoot.inlet.call.add(it)
                session.volatileRoot.inlet.call.add(it)
            }
            session.drain()
            view.current() shouldBe mapOf(1 to 2, 2 to 2)

            session.crashAndRecover()

            // The count cell has no threshold to bypass: once the replayed delta lands in
            // its lane, `recompute` emits the count that is TRUE of the recovered world —
            // one live lane asserting each element, the volatile lane having genuinely
            // lost its state. Identical with and without the baseline stamp, so nothing
            // is dropped and nothing needs the quorum's install rule.
            view.current() shouldBe mapOf(1 to 1, 2 to 1)
        }
    }
}
