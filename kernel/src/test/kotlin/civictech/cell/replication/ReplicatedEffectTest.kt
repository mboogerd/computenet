package civictech.cell.replication

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.evolve.Effectful
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedCellProxy
import civictech.gen.wire.Contract
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * PN-17: effect authority on an instance set (spec 31 §Effects on instance
 * sets, plan §3b/PN-17). `Effectful` × replicated is otherwise undefined —
 * every replica fires the external effect. Two spec-grounded rules:
 *
 *  - **Total/overlapping interest** needs a declared effect **authority**: the
 *    [SingleWriterReplication] leader fires; followers suppress via the Shadow
 *    NoOp-serve machinery ([civictech.cell.evolve.Shadow.suppress]); the
 *    [LeaderMark] epoch fold fences the handoff.
 *  - **Disjoint interest** is effect-once *by construction* — each logical
 *    delta reaches exactly one covering instance — so it needs no authority.
 *
 * The external effect is a shared append log; "fires once per logical delta"
 * is one entry per broadcast.
 */
class ReplicatedEffectTest {

    /** The effectful sink's write API — one method that fires the external effect. */
    @Contract
    interface SinkOps {
        fun emit(delta: Long)
    }

    interface SinkWriteInletHolder {
        val writeInlet: Use<SinkOps>
    }

    /**
     * An [Effectful] single-writer replicated sink. Its write inlet fires the
     * external effect (an append to [effectLog]). The leader serves the real,
     * effect-firing api; a follower NoOp-serves it via the Shadow suppression
     * machinery — so the delta rides every replica (the replication setting)
     * but only the leader acts on the world.
     */
    class EffectfulSinkCell(override val ref: CellRef, private val effectLog: MutableList<Long>) :
        SingleWriterReplicable<Long>, Effectful, Cell {

        val writeInlet = registerPort("writeInlet", FanInlet.create<SinkOps>())
        override val deltaOutlet = registerPort("deltaOutlet", FanOutlet.create<Propagate<Stamped<Long>>>())
        private val deltaInletPort = registerPort("deltaInlet", FanInlet.create<Propagate<Stamped<Long>>>())
        override val deltaInlet: Use<Propagate<Stamped<Long>>> get() = deltaInletPort

        var leading: Boolean = false
            private set
        var currentEpoch: Long = -1
            private set

        private val realApi = object : SinkOps {
            override fun emit(delta: Long) {
                effectLog.add(delta) // the external effect — fired by the authority only
            }
        }

        init {
            // a sink has no state to keep in sync; the shipped-delta path is inert
            deltaInletPort.serve(Propagate<Stamped<Long>> { })
        }

        override fun becomeLeader(epoch: Long) {
            leading = true
            currentEpoch = epoch
            writeInlet.serve(realApi) // authority: the leader fires the effect
        }

        override fun becomeFollower(leaderRef: CellRef, epoch: Long, registry: LocationRegistry) {
            leading = false
            currentEpoch = epoch
            // follower: suppress via the Shadow NoOp-serve machinery — the delta
            // still arrives (replication), but the effect does not fire (spec 52 reuse)
            civictech.cell.evolve.Shadow.suppress(writeInlet)
        }

        override fun currentState(): Long = 0
        override fun adoptState(state: Long) {}
    }

    /** An ordinary, non-replicated [Effectful] sink — the non-opting baseline. */
    class PlainEffectfulSink(override val ref: CellRef, effectLog: MutableList<Long>) : Cell, Effectful {
        val writeInlet = registerPort("writeInlet", FanInlet.create<SinkOps>())
        init {
            writeInlet.serve(object : SinkOps {
                override fun emit(delta: Long) { effectLog.add(delta) }
            })
        }
    }

    private class Peer {
        val controller = SimulationController()
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val replication = SingleWriterReplication(registry)

        fun spawn(cell: Cell): CellRef = host.managementInlet.call.spawn(cell)

        fun sink(ref: CellRef): SinkOps =
            (HostedCellProxy.create(ref, registry, SinkWriteInletHolder::class.java) as SinkWriteInletHolder)
                .writeInlet.call
    }

    @Test
    fun `a single-writer effectful sink fires the effect exactly once per delta across a leader handoff`() {
        val p = Peer()
        val log = mutableListOf<Long>()
        val id = UUID.randomUUID()
        val a = EffectfulSinkCell(CellRef(id, 0), log)
        val b = EffectfulSinkCell(CellRef(id, 1), log)
        val c = EffectfulSinkCell(CellRef(id, 2), log)

        val epoch0 = LeaderMark(id, epoch = 0, leaderRef = a.ref)
        p.replication.replicate(a, p.host, epoch0)
        p.replication.replicate(b, p.host, epoch0)
        p.replication.replicate(c, p.host, epoch0)
        p.controller.runToIdle()

        // apply roles to ALL local replicas at once via a fresh epoch fold
        p.replication.designateLeader(LeaderMark(id, epoch = 1, leaderRef = a.ref)) shouldBe true
        p.controller.runToIdle()
        a.leading shouldBe true
        b.leading shouldBe false
        c.leading shouldBe false

        fun broadcast(delta: Long) {
            listOf(a, b, c).forEach { p.sink(it.ref).emit(delta) }
            p.controller.runToIdle()
        }

        broadcast(1)
        broadcast(2)
        log shouldBe listOf(1L, 2L) // only the leader fired — once per logical delta

        // leader handoff: b takes over under a strictly greater epoch
        p.replication.designateLeader(LeaderMark(id, epoch = 2, leaderRef = b.ref)) shouldBe true
        p.controller.runToIdle()
        b.leading shouldBe true
        a.leading shouldBe false // old leader now suppressed

        broadcast(3)
        broadcast(4)
        log shouldBe listOf(1L, 2L, 3L, 4L) // exactly once each, across the handoff

        // LeaderMark fencing: a stale (<= current) re-designation is inert — the
        // deposed leader does not resurrect and double-fire
        p.replication.designateLeader(LeaderMark(id, epoch = 1, leaderRef = a.ref)) shouldBe false
        broadcast(5)
        log shouldBe listOf(1L, 2L, 3L, 4L, 5L) // still only the fenced current leader (b) fired
    }

    @Test
    fun `control a - authority off - every replica fires the effect N times`() {
        val p = Peer()
        val log = mutableListOf<Long>()
        val id = UUID.randomUUID()
        val replicas = listOf(0L, 1L, 2L).map { EffectfulSinkCell(CellRef(id, it), log) }

        // no single-writer authority: every replica applies (all "leaders") —
        // exactly the undefined-today behavior, made visible
        replicas.forEach { p.spawn(it); it.becomeLeader(epoch = 0) }
        p.controller.runToIdle()

        // ONE logical delta, broadcast to every replica (the replication setting)
        replicas.forEach { p.sink(it.ref).emit(7) }
        p.controller.runToIdle()

        log shouldBe listOf(7L, 7L, 7L) // N=3 fires for one logical delta — the bug, visible
    }

    @Test
    fun `control b - disjoint interest with no authority is still effect-once by construction`() {
        val p = Peer()
        val log = mutableListOf<Long>()
        val id = UUID.randomUUID()
        val replicas = listOf(0L, 1L, 2L).map { EffectfulSinkCell(CellRef(id, it), log) }

        // no authority declared, no suppression — every replica serves the real api
        replicas.forEach { p.spawn(it); it.becomeLeader(epoch = 0) }
        p.controller.runToIdle()

        // disjoint interest: each logical delta reaches exactly ONE covering
        // instance (partitioning), so no delta is fanned to a second firer
        p.sink(replicas[0].ref).emit(10)
        p.sink(replicas[1].ref).emit(20)
        p.sink(replicas[2].ref).emit(30)
        p.controller.runToIdle()

        // each logical delta fired exactly once — WITHOUT any authority: the
        // by-construction half (disjoint needs none, unlike Total/overlap)
        log.sorted() shouldBe listOf(10L, 20L, 30L)
    }

    @Test
    fun `a single non-replicated Effectful cell is unchanged - the non-opting path still fires`() {
        val p = Peer()
        val log = mutableListOf<Long>()
        val sink = PlainEffectfulSink(CellRef(UUID.randomUUID(), 0), log)
        p.spawn(sink)
        p.controller.runToIdle()

        p.sink(sink.ref).emit(1)
        p.sink(sink.ref).emit(2)
        p.controller.runToIdle()

        log shouldBe listOf(1L, 2L) // ordinary Effectful delivery, untouched by PN-17
    }

    @Test
    fun `formation refuses an effectful cell on a Total-overlapping instance set with no authority`() {
        // Total/overlapping + effectful + no authority → typed refusal at formation
        val refusal = shouldThrow<IllegalStateException> {
            SingleWriterReplication.requireEffectAuthority(effectful = true, disjoint = false, hasAuthority = false)
        }
        refusal.message shouldContain "Refused"

        // a declared authority (a single-writer leader) admits it
        SingleWriterReplication.requireEffectAuthority(effectful = true, disjoint = false, hasAuthority = true)
        // disjoint interest is effect-once by construction — admitted with no authority
        SingleWriterReplication.requireEffectAuthority(effectful = true, disjoint = true, hasAuthority = false)
        // a non-effectful cell is unconstrained — non-opting graphs never refuse
        SingleWriterReplication.requireEffectAuthority(effectful = false, disjoint = false, hasAuthority = false)

        SingleWriterReplication.effectAuthorityRequired(effectful = true, disjoint = false) shouldBe true
        SingleWriterReplication.effectAuthorityRequired(effectful = true, disjoint = true) shouldBe false
        SingleWriterReplication.effectAuthorityRequired(effectful = false, disjoint = false) shouldBe false
    }
}
