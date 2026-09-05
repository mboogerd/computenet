package civictech.cell.durability

import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.HostScheduler
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.Use
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.testkit.dst.CrashFault
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.HostRebuild
import civictech.testkit.dst.StableRefs
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*

/**
 * M10 exit, sim level (spec 24 durability, G-25): a three-peer replicated session where one peer
 * CRASHES mid-run — its host, scheduler, queues, and links all discarded; only its journal
 * survives. (Before the DST-rig retrofit below the peer's `LocationRegistry` was discarded too;
 * under the rig it is [DstWorld.registry] and deliberately survives, per [HostBuildContext]'s
 * "the scheduler is fresh per generation … while `registry` and the journals are the same
 * objects, because surviving the crash is precisely their job". The rebuilt cells re-register at
 * the *same* `CellRef`s, so this changes no assertion — every seed's outcome is unchanged — but
 * it is a real difference from what this sentence used to claim.) The peer rebuilds its graph, recovers by checkpoint-restore + frame replay through
 * the ordinary intake, re-peers, and all replicas converge under 100 seeds — including the burst
 * of writes accepted (journaled) but still in flight at the crash. The control run (recovery
 * without the journal) still converges — replicated state is recoverable from peers via
 * anti-entropy catch-up, which is replication doing its job — but the burst is LOST on every
 * seed: accepted writes that never left the process are exactly what only a write-ahead journal
 * can protect. The control proves the harness detects that loss.
 *
 * ## Retrofit onto the DST rig ([CHA1-60], computenet-umx.3.10)
 *
 * peer 0 — the crash target — is declared through [DstWorld]'s host seam
 * ([civictech.testkit.dst.HostSlots]) so the crash-and-rebuild is [CrashFault]'s own algorithm
 * (discard the in-flight scheduler, rebuild at the **same `CellRef`** via a caller-supplied
 * deterministic function, then `recoverFrom` the surviving journal — see [CrashFault]'s KDoc)
 * rather than a hand-rolled re-construction, and the rebuilt ref comes from
 * [HostRebuild.StableRefs] — "the rig's rebuild contract" — instead of a captured `var`. The
 * no-journal control is `CrashFault.journal = null`, [CrashFault]'s own [CHA1-63] diverging
 * control, exactly as the epic asks for.
 *
 * **Why this drives [DstWorld] directly rather than through [civictech.testkit.dst.DstRun]/
 * `GraphSpec`.** Two structural mismatches, found while designing this retrofit and neither
 * closable without a rig change (out of this task's scope — "no rig API changes"):
 *
 *  1. `DstRun.execute()`'s loop (`DstRun.kt:74-81`) breaks the run the *first* time
 *     `SimulationController.step()` returns `false` — i.e. the first true idle point — and
 *     never resumes ("A hook must not itself call `SimulationController.step`" / "the driving
 *     loop re-checks for work after every hook" only covers work a hook injects *within the same
 *     iteration*, not a later one). This session's workload deliberately drains to idle between
 *     writes (`repeat(rnd.nextInt(4)) { controller.step() }`, sometimes 0 extra steps) before
 *     injecting the next one — DstRun would report `BUDGET_EXHAUSTED`-free "quiescence" the
 *     first time that gap is hit and silently stop driving the rest of the 40-op session.
 *  2. [DstWorld.hosts]' `HostSlots.declare` builds every host against the *one* shared
 *     `DstWorld.registry` (`DstWorld.kt:151`, `HostBuildContext.registry` at `:316`), matching
 *     "the rig's rebuild contract" for a single durable host. This session models three
 *     *independent* peers, each with its own `LocationRegistry`, connected only through
 *     `Peering.loopback` wire links — the isolation `DstWorld`'s single-registry model does not
 *     represent for more than one declared host.
 *
 * So [DstWorld] and [CrashFault] are used directly — `world.controller` is the same
 * `SimulationController(seed)` the un-retrofitted test drove, and this session's own loop
 * (`runToIdle` / `step()`) is unchanged — while peer 1 and peer 2, which never crash, stay
 * outside the host seam with their own registries exactly as before. [CrashFault.install] and
 * [CrashFault.onStep] are called directly rather than through `DstRun`, since the "call `onStep`
 * once at the step that matches `atStep`" contract does not require driving a step-indexed loop
 * through the rig at all — only that `onStep` see `step == atStep` exactly once, which this test
 * arranges by construction (the crash always lands on the still-undrained burst).
 *
 * **The MID_DRAIN precondition here is unchecked.** No [civictech.testkit.dst.CrashWitness] is
 * declared for `"peer0"`, so [CrashFault]'s own precondition check sees `pendingWork() == null`
 * and passes unconditionally — the rig's `[unwitnessed: MID_DRAIN is the caller's assertion, not
 * an observation]` case, which a `DstReport` would say out loud and a direct drive has nowhere to
 * print. "Still in flight at the crash" therefore rests on this session's construction (two
 * `ops0.add` calls with no intervening `step()`), exactly as it did before the retrofit — not on
 * an observation the rig made.
 */
class CrashRecoveryTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    private fun runSession(seed: Long, journaled: Boolean): List<Set<String>> {
        val world = DstWorld(seed) // world.controller === the same SimulationController(seed) as before
        val controller = world.controller
        val rnd = Random(seed)

        val journalName = "wal"
        val journalView = world.journals.declare(journalName) // "the disk": survives the crash

        // peer 0's deterministic post-crash refs — HostRebuild's "rig's rebuild contract"
        // (StableRefs) in place of a hand-captured logicalId var. Same namespace, different
        // instanceId per replica — see StableRefs' KDoc for why that reproduces the same
        // logical cell across peers and across generations.
        val refNamespace = "crash-recovery-$seed"
        val refs0 = HostRebuild.refs(refNamespace) // instanceId 0
        val refs1 = StableRefs(refNamespace, instanceId = 1)
        val refs2 = StableRefs(refNamespace, instanceId = 2)

        var priorBridgeScheduler: HostScheduler? = null
        lateinit var bridgeHost0: ManagedHost
        lateinit var side0: Peering.Side
        lateinit var replication0: Replication
        val slot0 = world.hosts.declare("peer0") { ctx ->
            // The host seam's HostSlot only discards ctx.scheduler on crash ([CrashFault]'s
            // KDoc: "in-flight tasks, queues and links go with it" is scoped to that one
            // scheduler) — peer 0 needs a second scheduler for its bridge host, so this rebuild
            // function discards the prior one itself, the same way the un-retrofitted test's
            // `Peer(...)` re-construction discarded it by abandoning the whole object.
            priorBridgeScheduler?.shutdown()
            val bridgeScheduler = ctx.world.controller.scheduler()
            priorBridgeScheduler = bridgeScheduler
            val host = ManagedHost(
                scheduler = ctx.scheduler,
                registry = ctx.registry,
                journal = if (journaled) journalView else null,
            )
            bridgeHost0 = ManagedHost(scheduler = bridgeScheduler, registry = ctx.registry)
            side0 = Peering.Side(ctx.registry, bridgeHost0)
            replication0 = Replication(ctx.registry)
            host
        }

        val peer1 = Peer(controller)
        val peer2 = Peer(controller)
        var links0 = listOf(Peering.loopback(side0, peer1.side), Peering.loopback(side0, peer2.side))
        Peering.loopback(peer1.side, peer2.side)

        var replica0 = SetCell<String>(refs0.ref("replica")).also { replication0.replicate(it, slot0.host) }
        val replica1 = SetCell<String>(refs1.ref("replica")).also { peer1.replication.replicate(it, peer1.host) }
        val replica2 = SetCell<String>(refs2.ref("replica")).also { peer2.replication.replicate(it, peer2.host) }
        controller.runToIdle()

        fun ops(registry: LocationRegistry, replica: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(replica.ref, registry, SetInletProxy::class.java)
                    as SetInletProxy).inlet.call

        var ops0 = ops(world.registry, replica0)
        val ops1 = ops(peer1.registry, replica1)
        val ops2 = ops(peer2.registry, replica2)

        // disjoint universes: pre-crash elements are never touched again, so
        // their post-recovery fate depends ONLY on the journal — post-crash
        // traffic cannot wash the loss out of the membership comparison
        val preUniverse = listOf("apple", "banana", "cherry", "date", "elder")
        val postUniverse = listOf("fig", "grape", "kiwi", "lime", "mango")
        val totalOps = 40

        // Installed once, fired once (guarded by CrashFault's own `crashed` flag) at the step
        // this session names below — the whole reason a step-indexed loop through DstRun is not
        // needed: `onStep` only has to see step == atStep exactly once, which happens by
        // construction where this session calls it, on the still-undrained burst.
        val crashFault = CrashFault.midDrain("boom", "peer0", atStep = 0, journal = if (journaled) journalName else null)
        crashFault.install(world)

        for (op in 1..totalOps) {
            if (op == 8 && journaled) slot0.host.checkpoint(journalView) // compaction mid-history

            if (op == 12) {
                // a burst of peer-0 writes with no scheduling in between:
                // accepted (and journaled) but guaranteed still in flight...
                ops0.add("p0-burst-a")
                ops0.add("p0-burst-b")
                // ...when the CRASH hits: peer 0's in-flight scheduler work, its bridge queue
                // and its links are all discarded — only the journal survives.
                links0.forEach { it.partition() }
                crashFault.onStep(world, 0) // CrashFault.midDrain: slot0.crash() + recoverFrom
                replica0 = SetCell<String>(refs0.ref("replica")).also { replication0.replicate(it, slot0.host) }
                controller.runToIdle() // spawn is management-band async: graph first…
                links0 = listOf(
                    Peering.loopback(side0, peer1.side),
                    Peering.loopback(side0, peer2.side),
                )
                ops0 = ops(world.registry, replica0)
                controller.runToIdle()
            }

            val who = rnd.nextInt(3)
            val universe = if (op < 12) preUniverse else postUniverse
            val element = universe[rnd.nextInt(universe.size)]
            val target = listOf(ops0, ops1, ops2)[who]
            if (rnd.nextBoolean()) target.add(element) else target.remove(element)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        controller.runToIdle()
        return listOf(replica0, replica1, replica2).map { it.membership() }
    }

    @Test
    fun `a crashed peer recovers from its journal and all replicas converge under 100 seeds`() {
        for (seed in 0L until 100L) {
            val memberships = runSession(seed, journaled = true)
            memberships.toSet().size shouldBe 1 // all replicas identical
            // the in-flight burst was journaled at accept: write-ahead means
            // accepted-but-undispatched writes survive the crash everywhere
            memberships[0].containsAll(listOf("p0-burst-a", "p0-burst-b")).shouldBeTrue()
        }
    }

    @Test
    fun `control - without a journal, accepted-but-unflushed writes are lost on every seed`() {
        for (seed in 0L until 50L) {
            val memberships = runSession(seed, journaled = false)
            // replication's anti-entropy still converges the replicas...
            memberships.toSet().size shouldBe 1
            // ...but the accepted burst is gone — consistently, invisibly:
            // the loss only a write-ahead journal prevents
            memberships[0].none { it.startsWith("p0-burst") }.shouldBeTrue()
        }
    }

    /** Did the accepted-but-unflushed burst survive the crash on peer 0? */
    private fun burstSurvived(memberships: List<Set<String>>): Boolean =
        memberships[0].any { it.startsWith("p0-burst") }

    /**
     * **BS-16 — "the retrofit is behaviour-preserving" ([CHA1-61]), for this file.**
     *
     * computenet-umx.3.10 replaced this file's hand-rolled crash-and-rebuild with [CrashFault]
     * and swapped a captured `UUID.randomUUID()` `logicalId` for [HostRebuild]'s [StableRefs].
     * Two things the retrofit's own KDoc asserts and nothing re-checks:
     *
     *  - **Arm 1 — the rebuild hinge.** "The rebuilt cells re-register at the *same* `CellRef`s,
     *    so this changes no assertion." Pre-retrofit that held by construction: one `logicalId`
     *    captured in a `var`, reused. Post-retrofit it holds only because [StableRefs] is a
     *    deterministic function of `(namespace, instanceId, name)` — a property of the rig, on
     *    which every seed's outcome now depends. Its non-vacuity arm is that the ref is
     *    genuinely *derived*: a different `instanceId` or namespace must give a different ref,
     *    or "stable" would just mean "constant" and the three replicas would collide.
     *  - **Arm 2 — the per-seed outcome vector, and what decides it.** Pinned from the
     *    pre-retrofit assertions at `67399fc23^`, which recorded the burst present on every
     *    journaled seed and absent on every unjournaled one. The two vectors are complements,
     *    and that is the discriminating fact rather than either vector alone: a [CrashFault]
     *    that had stopped crashing, or a `journal = null` control that had stopped being a
     *    control, would leave the burst present in BOTH runs. So this arm cannot pass against a
     *    neutralised injector, where a bare "the vector is unchanged" assertion could.
     *
     * The seed range is a 20-seed prefix of the existing 0..99 / 0..49 ranges: two full sessions
     * per seed, and the existing tests already drive 150 of them in this class.
     */
    @Test
    fun `BS-16 CHA1-61 - the rebuilt peer keeps its pre-crash CellRef, and the per-seed burst-loss vector is journal-decided`() {
        // Arm 1 — stable across rebuild generations...
        val namespace = "crash-recovery-bs16"
        val refs0 = HostRebuild.refs(namespace)
        refs0.ref("replica") shouldBe refs0.ref("replica")
        HostRebuild.refs(namespace).ref("replica") shouldBe refs0.ref("replica")
        // ...and derived, not constant: the other replicas and other namespaces differ.
        (StableRefs(namespace, instanceId = 1).ref("replica") == refs0.ref("replica")) shouldBe false
        (HostRebuild.refs("other-$namespace").ref("replica") == refs0.ref("replica")) shouldBe false

        // Arm 2 — the per-seed outcome vector, pinned from the pre-retrofit assertions.
        val seeds = 0L until 20L
        seeds.map { burstSurvived(runSession(it, journaled = true)) } shouldBe seeds.map { true }
        seeds.map { burstSurvived(runSession(it, journaled = false)) } shouldBe seeds.map { false }
    }
}
