package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.repro.ExpectedFailure
import civictech.cell.repro.withSignature
import civictech.cell.wire.Peering
import civictech.cell.wire.RegistryAnnounce
import civictech.cell.wire.WireCodec
import civictech.nature.ContractRegistry
import civictech.testkit.forEachSeed
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The stable token `computenet-f7h.6.2`'s expected failure carries — the
 * dual-claim divergence recorded for computenet-f7h.7. A `const val` because
 * an annotation argument must be one.
 */
private const val MEM1_52_DUAL_CLAIM_DIVERGENCE = "MEM1-52-DUAL-CLAIM-DIVERGENCE"

/**
 * `computenet-f7h.4.1` — the **posture** half of automatic leader election
 * ([MEM1-05], [MEM1-25]; spec 42 §Single-writer replication, decisions
 * f7h.4-D1/D7).
 *
 * This file owns three things: that a [DetectionWindow] refuses a
 * zero-observation window, that the DEFAULT engine is byte-for-byte the
 * pre-election one, and that two engines with different postures on ONE
 * registry stay independent. The five behavioural examples of an
 * [LeaderElection.EpochClaim] engine actually electing ([MEM1-07],
 * [MEM1-17], [MEM1-23], [MEM1-14]) belong to computenet-f7h.4.2 and
 * computenet-f7h.4.3.
 *
 * **Spawn a peer's replica BEFORE loopbacking that peer**, for
 * [LeaderMarkAnnounceTest]'s reason: [SingleWriterReplication.replicate]
 * registers the local replica and *then* folds its mark, and a replica spawned
 * after its peer already mirrored the mark would fold a rejected duplicate and
 * silently get no role.
 */
class LeaderElectionTest {

    // --------------------------------------------------------------- fixture

    /**
     * A peer: registry, application host, bridge host and a single-writer
     * engine on one controller. Shaped after [LeaderMarkAnnounceTest]'s
     * private `Peer` (copied rather than shared — it is private there, and
     * that file is not this task's claim), plus an [election] parameter and
     * [engine], which builds an ADDITIONAL engine on this same registry:
     * f7h.4-D7's coexistence pin needs two postures behind one fold.
     *
     * [election] is **nullable, and null means "construct the way every
     * pre-F4 call site does"** — `SingleWriterReplication(registry)`, with no
     * third argument at all — rather than passing
     * [LeaderElection.Manual] explicitly. That distinction is load-bearing
     * and was measured: with the fixture passing `Manual` explicitly, flipping
     * the production default to `EpochClaim(DetectionWindow(1))` left all four
     * tests here green, because nothing exercised the default. Clause 1 of
     * this task is a statement about the DEFAULT, so the default is what the
     * fixture has to construct.
     */
    private class Peer(
        controller: SimulationController,
        election: LeaderElection? = null,
    ) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication =
            if (election == null) SingleWriterReplication(registry)
            else SingleWriterReplication(registry, election = election)

        /** Every adopted fold on this registry — local designation, claim, or mirrored announcement. */
        var leaderMarkFires = 0
            private set

        init {
            registry.onLeaderMark { leaderMarkFires++ }
        }

        /** A second engine on the SAME registry, with its own posture (f7h.4-D7). */
        fun engine(election: LeaderElection): SingleWriterReplication =
            SingleWriterReplication(registry, election = election)

        fun replica(
            logicalId: UUID,
            instanceId: Long,
            mark: LeaderMark,
            on: SingleWriterReplication = replication,
        ): SingleWriterReplicationTest.SwCounterCell =
            SingleWriterReplicationTest.SwCounterCell(CellRef(logicalId, instanceId))
                .also { on.replicate(it, host, mark) }

        fun ops(replica: SingleWriterReplicationTest.SwCounterCell): SingleWriterReplicationTest.SwCounterOps =
            (civictech.cell.host.HostedCellProxy
                .create(replica.ref, registry, SingleWriterReplicationTest.WriteInletHolder::class.java)
                    as SingleWriterReplicationTest.WriteInletHolder).writeInlet.call
    }

    // ------------------------------------------------- [MEM1-25] window shape

    @Test
    fun `a detection window of zero observations is refused`() {
        DetectionWindow(1).observations shouldBe 1
        val refused = assertThrows(IllegalArgumentException::class.java) { DetectionWindow(0) }
        assertTrue(refused.message!!.contains("at least one observation")) {
            "unexpected refusal message: ${refused.message}"
        }
        assertThrows(IllegalArgumentException::class.java) { DetectionWindow(-1) }
    }

    // ------------------------------- [MEM1-25] no clock, thread or timer here

    /**
     * The identifier fence. [DetectionWindow] is counted in membership
     * observations and nothing in this package may reach for wall-clock time,
     * a thread, an executor or a periodic frame to drive it.
     *
     * Green on the tree this test landed on — the scan found nothing before
     * the detector was written either — so its value is entirely in what it
     * refuses NEXT. Proven non-vacuous by mutation (see the task report):
     * `private val t0 = System.nanoTime()` in `LeaderElection.kt` turns it
     * red at the `offending.isEmpty()` assertion.
     *
     * Limits, both textual, inherited from
     * [civictech.cell.architecture.ExtractionFenceTest]'s idiom: a forbidden
     * name inside a string literal containing comment punctuation could be
     * misjudged, and a reference reached indirectly (a typealias, a helper in
     * another package) is invisible. This makes the *textual* claim
     * build-breaking; it does not prove the absence of a clock.
     */
    @Test
    fun `every replication source is free of clock, thread and timer identifiers`() {
        val dir = File(repoRoot(), "kernel/src/main/kotlin/civictech/cell/replication")
        assertTrue(dir.isDirectory) { "Missing source directory: ${dir.path}" }
        val sources = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".kt") }.orEmpty().sortedBy { it.name }

        assertTrue(sources.size >= 3) {
            "expected at least three .kt sources under ${dir.path}, found ${sources.map { it.name }} " +
                "— the scan is broken, not the fence"
        }
        assertTrue(sources.any { it.name == "LeaderElection.kt" }) {
            "LeaderElection.kt was not among the scanned sources ${sources.map { it.name }} " +
                "— the fence would not see the very file it exists for"
        }

        val forbidden = listOf(
            "Thread", "ScheduledExecutorService", "ScheduledThreadPoolExecutor", "Executors",
            "Timer", "TimerTask", "nanoTime", "currentTimeMillis", "Clock", "Instant",
            "TimeUnit", "sleep", "delay", "schedule",
        )
        val patterns = forbidden.map { it to Regex("\\b${Regex.escape(it)}\\b") }

        var electionCodeLines = 0
        val offending = mutableListOf<String>()
        sources.forEach { file ->
            val codeLines = stripComments(file.readLines())
            if (file.name == "LeaderElection.kt") {
                electionCodeLines = codeLines.size
                assertTrue(codeLines.any { it.contains("class DetectionWindow") }) {
                    "comment stripping ate the DetectionWindow declaration — the scan is broken, " +
                        "not the fence (kept ${codeLines.size} code lines)"
                }
            }
            codeLines.forEach { line ->
                patterns.forEach { (name, pattern) ->
                    if (pattern.containsMatchIn(line)) offending += "${file.name}: $name in `${line.trim()}`"
                }
            }
        }
        assertTrue(electionCodeLines > 0) { "LeaderElection.kt produced no code lines — the scan is broken" }
        assertTrue(offending.isEmpty()) {
            "the detection window is counted in membership observations, never in time ([MEM1-25]); " +
                "these code lines reach for a clock, thread or timer: $offending"
        }
    }

    // ------------------------------------- [MEM1-05] the default path is inert

    /**
     * The default engine — constructed with no `election` argument, exactly as
     * all seven existing call sites do — neither arms nor claims, however many
     * times it is stepped.
     *
     * Non-vacuity: a totals-only assertion here would be worthless, because
     * "nothing happened" is also what a test that never partitioned anything
     * observes. The parked write is the control — it proves B really did lose
     * its leader and really is still following the folded epoch-1 mark, so the
     * unchanged [Peer.leaderMarkFires] and the zero
     * [SingleWriterReplication.missCount] are statements about an engine that
     * had every opportunity to elect.
     */
    @Test
    fun `a Manual engine observing the leader's departure past any window neither claims nor arms`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val mark1 = LeaderMark(id, 1, aRef)

        a.replica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        val loop = Peering.loopback(a.side, b.side)
        controller.runToIdle()

        onB.leading shouldBe false
        val bFiresBefore = b.leaderMarkFires

        loop.partition()
        repeat(5) { b.replication.observe() }
        controller.runToIdle()

        // the leader is gone from B's membership index — the observation the
        // detector would have read, had B been armed to read it
        (aRef in b.registry.replicasOf(id)) shouldBe false
        // ... and B did nothing with it
        b.replication.leaderOf(id) shouldBe mark1
        b.leaderMarkFires shouldBe bFiresBefore
        b.replication.missCount(id) shouldBe 0
        onB.leading shouldBe false
        onB.becomeLeaderCalls shouldBe 0

        // control: a write through the follower is redirected to the departed
        // leader and parks there, rather than being applied locally
        b.ops(onB).increment(7)
        controller.runToIdle()
        onB.total shouldBe 0L
        onB.leading shouldBe false
        // measured, not predicted: one parked invocation per forwarded write
        b.registry.parkedFor(aRef).size shouldBe 1
    }

    // --------------------------------------- f7h.4-D7 two postures, one registry

    /**
     * Peer B runs an [LeaderElection.EpochClaim] engine hosting its replica of
     * X and a [LeaderElection.Manual] engine hosting its replica of Y, on ONE
     * registry and therefore behind ONE fold. Partitioning the leader peer A
     * away from both survivors delivers the *same* membership departure to
     * both engines; only X's elects.
     *
     * The Manual half's non-vacuity is the parked write on Y: Y's leader is
     * genuinely gone and genuinely not replaced.
     */
    @Test
    fun `a Manual engine and an EpochClaim engine on one registry - only the EpochClaim id elects`() {
        val controller = SimulationController()
        val a = Peer(controller)
        val b = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(1)))
        val c = Peer(controller)
        val elects = b.replication
        val manual = b.engine(LeaderElection.Manual)

        val x = UUID.randomUUID()
        val y = UUID.randomUUID()
        val aX = CellRef(x, 0)
        val bX = CellRef(x, 1)
        val aY = CellRef(y, 0)
        val markX1 = LeaderMark(x, 1, aX)
        val markY1 = LeaderMark(y, 1, aY)

        // replicas first, peering second
        a.replica(x, 0, markX1)
        a.replica(y, 0, markY1)
        val onBX = b.replica(x, 1, markX1, on = elects)
        val onBY = b.replica(y, 1, markY1, on = manual)
        c.replica(x, 2, markX1)
        c.replica(y, 2, markY1)

        val abLoop = Peering.loopback(a.side, b.side)
        val acLoop = Peering.loopback(a.side, c.side)
        Peering.loopback(b.side, c.side)
        controller.runToIdle()

        onBX.leading shouldBe false
        onBY.leading shouldBe false

        abLoop.partition()
        acLoop.partition()
        controller.runToIdle()

        // The MANUAL half comes first deliberately. Both criteria are checked
        // here, but a mutation that makes Manual elect also makes the
        // default-constructed peers A and C elect, and if X were asserted
        // first that broader consequence would redden the X assertions and the
        // Manual criterion below would never be evaluated at all — the
        // mutation would "discriminate" while proving nothing about f7h.4-D7.
        // Measured: with the X block first, removing the posture guards failed
        // this test at `leaderOf(x)`, not here.
        //
        // Y did not elect: the Manual engine on the SAME registry saw the same
        // departure and arms nothing.
        b.replication.leaderOf(y) shouldBe markY1
        c.replication.leaderOf(y) shouldBe markY1
        onBY.leading shouldBe false
        onBY.becomeLeaderCalls shouldBe 0
        manual.missCount(y) shouldBe 0

        // X elected: B claimed the next epoch for its own replica, and the
        // claim reached C over the surviving B–C loopback
        val claimed = LeaderMark(x, 2, bX)
        b.replication.leaderOf(x) shouldBe claimed
        c.replication.leaderOf(x) shouldBe claimed
        onBX.leading shouldBe true
        onBX.becomeLeaderCalls shouldBe 1
        // the adoption disarmed X's window
        elects.missCount(x) shouldBe 0

        // control for the Manual half: Y's leader really is gone, so a write
        // through B's Y replica parks rather than being applied anywhere
        (aY in b.registry.replicasOf(y)) shouldBe false
        b.ops(onBY).increment(3)
        controller.runToIdle()
        onBY.total shouldBe 0L
        // measured, not predicted
        b.registry.parkedFor(aY).size shouldBe 1
    }

    // ------------------------------------------- [MEM1-07] claim after the window

    /**
     * Feature example 1. Three [LeaderElection.EpochClaim] peers with a
     * two-observation window; A leads at epoch 1. Partitioning A away from
     * both survivors is observation 1 on B and on C (the `onUnpublish` naming
     * the folded leaderRef arms the window, f7h.4.1-D1); one [observe] on B is
     * observation 2, and B claims epoch 2 for itself **synchronously**
     * (f7h.4-D5) — read before the next `runToIdle`.
     *
     * Non-vacuity: the "nothing happened yet" half after the partitions is
     * paired with `missCount == 1` on both survivors, which is the only signal
     * that distinguishes "armed and counting" from "never noticed"; the claim
     * half is paired with the crossing frame count, the fold deltas, the role
     * deltas, the shipping-link count and a write that actually lands.
     */
    @Test
    fun `a follower claims the next epoch once the window closes, and the claim reaches its surviving peer`() {
        val controller = SimulationController()
        val posture = LeaderElection.EpochClaim(DetectionWindow(2))
        val a = Peer(controller, posture)
        val b = Peer(controller, posture)
        val c = Peer(controller, posture)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, 1, aRef)

        // replicas FIRST, peering second (see the class KDoc)
        a.replica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        val onC = c.replica(id, 2, mark1)

        val aToB = Counting()
        val bToA = Counting()
        val aToC = Counting()
        val cToA = Counting()
        val bToC = Counting()
        val cToB = Counting()
        val abLoop = Peering.loopback(a.side, b.side, interposeAToB = aToB, interposeBToA = bToA)
        val acLoop = Peering.loopback(a.side, c.side, interposeAToB = aToC, interposeBToA = cToA)
        Peering.loopback(b.side, c.side, interposeAToB = bToC, interposeBToA = cToB)
        controller.runToIdle()

        val directions = listOf(aToB, bToA, aToC, cToA, bToC, cToB)
        directions.forEach { it.reset() }
        val firesBefore = Triple(a.leaderMarkFires, b.leaderMarkFires, c.leaderMarkFires)
        val bLeaderCallsBefore = onB.becomeLeaderCalls
        val cFollowerCallsBefore = onC.becomeFollowerCalls

        // observation 1: A departs from both survivors at once
        abLoop.partition()
        acLoop.partition()
        controller.runToIdle()

        b.replication.missCount(id) shouldBe 1
        c.replication.missCount(id) shouldBe 1
        // A itself never arms: the refs that left ITS index are the followers',
        // not the folded leaderRef, so the departure is not a leader failure
        a.replication.missCount(id) shouldBe 0
        b.replication.leaderOf(id) shouldBe mark1
        c.replication.leaderOf(id) shouldBe mark1
        a.replication.leaderOf(id) shouldBe mark1
        directions.forEach { it.count(leaderMarkedId) shouldBe 0 }

        // observation 2, on B only — the claim is folded before this returns
        b.replication.observe()
        b.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        b.replication.missCount(id) shouldBe 0

        controller.runToIdle()

        c.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        // adopting B's mark disarmed C, whose own count stood at 1
        c.replication.missCount(id) shouldBe 0
        // A is cut off from both survivors and cannot learn: its fold is
        // untouched. Nothing is asserted about A's ROLE — the dual-leader
        // window is F6's subject, not this test's.
        a.replication.leaderOf(id) shouldBe mark1

        // announced once, over the only direction still open from B
        bToC.count(leaderMarkedId) shouldBe 1
        listOf(aToB, bToA, aToC, cToA, cToB).forEach { it.count(leaderMarkedId) shouldBe 0 }

        (b.leaderMarkFires - firesBefore.second) shouldBe 1
        (c.leaderMarkFires - firesBefore.third) shouldBe 1
        (a.leaderMarkFires - firesBefore.first) shouldBe 0

        onB.leading shouldBe true
        (onB.becomeLeaderCalls - bLeaderCallsBefore) shouldBe 1
        onC.leading shouldBe false
        (onC.becomeFollowerCalls - cFollowerCallsBefore) shouldBe 1

        // one shipping link, not two: aRef left B's membership index at the
        // partition, so `applyRoles` never targets it
        b.replication.shipCountAmong(setOf(aRef, bRef, cRef)) shouldBe 1

        // the functional half — C's follower write is forwarded to the NEW
        // leader, applied once there, and shipped back
        c.ops(onC).increment(5)
        controller.runToIdle()
        onB.total shouldBe 5L
        onC.total shouldBe 5L
    }

    // --------------------------------- [MEM1-17] a present leader never triggers

    /**
     * Feature example 2. Ten [observe] calls on a follower whose leader is
     * present arm nothing, count nothing, fold nothing and send nothing:
     * [SingleWriterReplication.observe] counts only ids armed by a witnessed
     * departure (f7h.4.1-D1), and no departure has happened.
     *
     * Non-vacuity: a suite of "nothing happened" assertions is exactly what a
     * broken, inert engine also satisfies, so the test closes with a CONTROL —
     * the same three peers, the same posture and one more [observe] call, but
     * with A actually gone — and shows that this configuration does elect. The
     * ten inert observations are therefore a statement about the leader's
     * presence, not about a fixture that could never do anything.
     */
    @Test
    fun `ten observations while the leader is present neither arm, count nor claim`() {
        val controller = SimulationController()
        val posture = LeaderElection.EpochClaim(DetectionWindow(2))
        val a = Peer(controller, posture)
        val b = Peer(controller, posture)
        val c = Peer(controller, posture)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val mark1 = LeaderMark(id, 1, aRef)

        a.replica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        c.replica(id, 2, mark1)

        val aToB = Counting()
        val bToA = Counting()
        val aToC = Counting()
        val cToA = Counting()
        val bToC = Counting()
        val cToB = Counting()
        val abLoop = Peering.loopback(a.side, b.side, interposeAToB = aToB, interposeBToA = bToA)
        val acLoop = Peering.loopback(a.side, c.side, interposeAToB = aToC, interposeBToA = cToA)
        Peering.loopback(b.side, c.side, interposeAToB = bToC, interposeBToA = cToB)
        controller.runToIdle()

        val directions = listOf(aToB, bToA, aToC, cToA, bToC, cToB)
        directions.forEach { it.reset() }
        val firesBefore = Triple(a.leaderMarkFires, b.leaderMarkFires, c.leaderMarkFires)

        repeat(10) { b.replication.observe() }
        controller.runToIdle()

        a.replication.missCount(id) shouldBe 0
        b.replication.missCount(id) shouldBe 0
        c.replication.missCount(id) shouldBe 0
        (a.leaderMarkFires - firesBefore.first) shouldBe 0
        (b.leaderMarkFires - firesBefore.second) shouldBe 0
        (c.leaderMarkFires - firesBefore.third) shouldBe 0
        a.replication.leaderOf(id) shouldBe mark1
        b.replication.leaderOf(id) shouldBe mark1
        c.replication.leaderOf(id) shouldBe mark1
        directions.forEach { it.count(leaderMarkedId) shouldBe 0 }
        onB.leading shouldBe false

        // CONTROL: identical peers, identical posture, one more observe() —
        // only the leader's presence differs, and now it elects.
        abLoop.partition()
        acLoop.partition()
        controller.runToIdle()
        b.replication.missCount(id) shouldBe 1
        b.replication.observe()
        b.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        b.replication.missCount(id) shouldBe 0
        onB.leading shouldBe true
    }

    // ------------------------------- [MEM1-17] the leader's return resets the count

    /**
     * Feature example 3. A single claimant — B with a three-observation
     * window, A and C [LeaderElection.Manual] (default-constructed) — so the
     * outcome is deterministic and the count readings are B's alone.
     *
     * The sequence and B's count after each step:
     * `partition(A-B)` → 1, `observe()` → 2, `heal()` → 0 (the heal replays
     * `published(aRef)`, and a publish that restores the folded leaderRef
     * DISARMS), `partition(A-B)` → 1, `observe()` → 2, `observe()` → the
     * claim.
     *
     * Non-vacuity: each "no claim yet" reading is paired with the count that
     * proves the window really is advancing, and the heal step additionally
     * asserts that A's replayed mark crossed (`aToB.count == 1`) while B's
     * fold count did not move — the replay is inert as a mark and decisive as
     * a membership observation.
     */
    @Test
    fun `the leader's return resets the detection window, and a later departure claims from scratch`() {
        val controller = SimulationController()
        val a = Peer(controller) // Manual, by the production default
        val b = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(3)))
        val c = Peer(controller) // Manual
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val mark1 = LeaderMark(id, 1, aRef)

        a.replica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        c.replica(id, 2, mark1)

        val aToB = Counting()
        val bToA = Counting()
        val bToC = Counting()
        val cToB = Counting()
        val abLoop = Peering.loopback(a.side, b.side, interposeAToB = aToB, interposeBToA = bToA)
        Peering.loopback(a.side, c.side)
        Peering.loopback(b.side, c.side, interposeAToB = bToC, interposeBToA = cToB)
        controller.runToIdle()

        listOf(aToB, bToA, bToC, cToB).forEach { it.reset() }
        val bFiresBefore = b.leaderMarkFires

        abLoop.partition()
        b.replication.missCount(id) shouldBe 1
        b.replication.leaderOf(id) shouldBe mark1
        bToC.count(leaderMarkedId) shouldBe 0

        b.replication.observe()
        b.replication.missCount(id) shouldBe 2
        b.replication.leaderOf(id) shouldBe mark1
        bToC.count(leaderMarkedId) shouldBe 0

        abLoop.heal()
        controller.runToIdle()
        // the replayed `published(aRef)` restored the folded leader, and that
        // observation disarmed the window outright
        b.replication.missCount(id) shouldBe 0
        b.replication.leaderOf(id) shouldBe mark1
        bToC.count(leaderMarkedId) shouldBe 0
        // A's mark was replayed across the healed peering and was inert
        aToB.count(leaderMarkedId) shouldBe 1
        b.leaderMarkFires shouldBe bFiresBefore

        abLoop.partition()
        b.replication.missCount(id) shouldBe 1
        b.replication.leaderOf(id) shouldBe mark1

        b.replication.observe()
        b.replication.missCount(id) shouldBe 2
        b.replication.leaderOf(id) shouldBe mark1
        bToC.count(leaderMarkedId) shouldBe 0

        // the third observation closes the window; C is Manual but still
        // peered with B, so `reachable` is {cRef} and the claim fires
        b.replication.observe()
        b.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        b.replication.missCount(id) shouldBe 0
        onB.leading shouldBe true

        controller.runToIdle()
        c.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        bToC.count(leaderMarkedId) shouldBe 1
    }

    // ------------------------------------------- f7h.4.1-D1 unarmed ids never count

    /**
     * f7h.4.1-D1's own hazard, reproduced directly. B folds `mark1` — whose
     * `leaderRef` is A's — locally through [SingleWriterReplication.replicate]
     * (design happens before any peering, exactly the "mark folded before the
     * peering that would carry the leaderRef" case the KDoc on
     * [SingleWriterReplication]'s `misses` map names), but B and A are NEVER
     * peered: A's ref never once appears in B's `replicasOf(id)`. The absence
     * is real — A is alive and holds the fold, elsewhere — and is never a
     * *witnessed departure*, so B must never arm on it.
     *
     * C peering with B afterwards is the event this pins: C's replica of the
     * SAME logical id publishing into B's registry drives
     * [LocationRegistry.onPublish], which calls `observeIfArmed` for that id
     * UNCONDITIONALLY, regardless of arming — the only guard standing between
     * that call and an evaluation is `observeIfArmed`'s own
     * `misses.containsKey` check. `window = 1` makes the hazard immediate
     * under the mutation this task pins against (deleting that guard): C's
     * arrival would supply both the arm and the window's one required
     * observation in the same call, and B would claim epoch 2 against A while
     * A is still leading, live, unpartitioned. Under the fix, C's arrival is
     * inert for this id because B was never armed.
     *
     * Non-vacuity: a suite of "nothing happened" readings is exactly what an
     * engine that can never claim also produces, so the test closes with a
     * CONTROL on the SAME `b.replication` — peer B with A, then partition
     * them. That drives a genuine witnessed departure and B claims exactly as
     * F1's test shows, proving the earlier zero readings were about the
     * missing witness, not a b.replication that never claims anything.
     */
    @Test
    fun `an id whose leader was never witnessed departing stays unarmed through repeated observations`() {
        val controller = SimulationController()
        val a = Peer(controller) // Manual — A just holds the fold; never elects itself
        val b = Peer(controller, LeaderElection.EpochClaim(DetectionWindow(1)))
        val c = Peer(controller) // Manual — a live peer for B to be announced to/from
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val mark1 = LeaderMark(id, 1, aRef)

        // A exists and holds mark1, but is NEVER peered with B — A's ref never
        // appears in B's membership index, so the departure B would need to
        // arm on never happens.
        a.replica(id, 0, mark1)
        // B folds mark1 locally BEFORE any peering (replicate() designates it
        // synchronously) — the second f7h.4.1-D1 hazard shape.
        val onB = b.replica(id, 1, mark1)
        c.replica(id, 2, mark1)

        b.replication.missCount(id) shouldBe 0
        val bFiresBefore = b.leaderMarkFires

        // B and C peer — C's onPublish for the SAME logical id is exactly the
        // event `observeIfArmed`'s guard exists to make inert for an unarmed id.
        Peering.loopback(b.side, c.side)
        controller.runToIdle()

        (aRef in b.registry.replicasOf(id)) shouldBe false
        b.replication.missCount(id) shouldBe 0
        b.replication.leaderOf(id) shouldBe mark1
        b.leaderMarkFires shouldBe bFiresBefore
        onB.leading shouldBe false
        onB.becomeLeaderCalls shouldBe 0

        // Repeated observe() calls and more membership events change nothing:
        // the id was never armed, so there is nothing for either to count.
        repeat(10) { b.replication.observe() }
        controller.runToIdle()
        b.replication.missCount(id) shouldBe 0
        b.replication.leaderOf(id) shouldBe mark1
        b.leaderMarkFires shouldBe bFiresBefore
        onB.leading shouldBe false
        onB.becomeLeaderCalls shouldBe 0

        // CONTROL: this SAME b.replication elects once it actually WITNESSES
        // A's departure — peer B with A, then partition them.
        val abLoop = Peering.loopback(a.side, b.side)
        controller.runToIdle()
        (aRef in b.registry.replicasOf(id)) shouldBe true

        abLoop.partition()
        controller.runToIdle()
        b.replication.leaderOf(id) shouldBe LeaderMark(id, 2, bRef)
        b.replication.missCount(id) shouldBe 0
        onB.leading shouldBe true
        onB.becomeLeaderCalls shouldBe 1
    }

    // ================================================ computenet-f7h.6.2 — the seeded dual-claim sweep

    /**
     * The total order two claims are decided by (f7h.1-D2), recomputed here
     * because `InstanceIndex.ORDER` is private.
     */
    private val markOrder: Comparator<LeaderMark> =
        compareBy({ it.epoch }, { it.leaderRef.instanceId })

    /** Everything one peer shows at one observation point. */
    private class PeerView(
        val adopted: List<LeaderMark>,
        val leaderOf: LeaderMark?,
        val leading: Boolean,
        val total: Long,
        val realWrites: Int,
        val currentEpoch: Long,
        val becomeLeaderCalls: Int,
        val becomeFollowerCalls: Int,
        val receivedDeltas: Int,
        val fencedDeltas: Int,
        val baselinesAdopted: Int,
        val shippedPairs: Set<Pair<CellRef, CellRef>>,
        val parkedAtOld: Int,
    ) {
        override fun toString(): String =
            "leaderOf=$leaderOf leading=$leading total=$total realWrites=$realWrites epoch=$currentEpoch " +
                "L=$becomeLeaderCalls F=$becomeFollowerCalls recv=$receivedDeltas fenced=$fencedDeltas " +
                "base=$baselinesAdopted ships=$shippedPairs parked@old=$parkedAtOld adopted=$adopted"
    }

    /** One seed's whole timeline, as measured. */
    private class DualClaim(
        val seed: Long,
        val park: Boolean,
        val aRef: CellRef,
        val bRef: CellRef,
        val cRef: CellRef,
        val bMisses: Int,
        val cMisses: Int,
        val parkedAfterPark: Int,
        val bTotalAfterPark: Long,
        val bClaim: LeaderMark?,
        val cClaim: LeaderMark?,
        val parkedAtClaim: Int,
        val bRealWritesAtClaim: Int,
        val a: PeerView,
        val b: PeerView,
        val c: PeerView,
        val aHealed: PeerView,
        val bHealed: PeerView,
        val cHealed: PeerView,
        val aFinal: PeerView?,
        val bFinal: PeerView?,
        val cFinal: PeerView?,
        val emittedB: List<Stamped<Long>>,
        val emittedC: List<Stamped<Long>>,
        val bToCMarks: Int,
        val cToBMarks: Int,
    ) {
        /** The clue every assertion below carries — a failing seed is unreadable without it. */
        override fun toString(): String = buildString {
            append("seed=$seed park=$park\n")
            append("  step1 misses b=$bMisses c=$cMisses\n")
            append("  step2 parked@aRef=$parkedAfterPark bTotal=$bTotalAfterPark\n")
            append("  step3 bClaim=$bClaim cClaim=$cClaim parked@aRef=$parkedAtClaim bRealWrites=$bRealWritesAtClaim\n")
            append("  quiesced A: $a\n  quiesced B: $b\n  quiesced C: $c\n")
            append("  emittedB=$emittedB emittedC=$emittedC bToCMarks=$bToCMarks cToBMarks=$cToBMarks\n")
            append("  healed   A: $aHealed\n  healed   B: $bHealed\n  healed   C: $cHealed\n")
            if (aFinal != null) append("  final    A: $aFinal\n  final    B: $bFinal\n  final    C: $cFinal\n")
        }
    }

    /**
     * The rig both dual-claim sweeps drive, built fresh per [seed].
     *
     * Three peers on one seeded [SimulationController]: **A** default-
     * constructed (so [LeaderElection.Manual] — A is the leader that vanishes
     * and must never re-elect itself), **B** and **C** on
     * `EpochClaim(DetectionWindow(2))`. A leads `id` at `LeaderMark(id, 1,
     * aRef)`; instanceIds are A=0, B=1, C=2, so C is the one the total order
     * (f7h.1-D2) picks when both claim at counter 2. Replicas are spawned
     * BEFORE the three loopbacks, per this class's KDoc.
     *
     * **Fault model (f7h.6-D3): a symmetric `partition()`** on the A–B and A–C
     * loopbacks — both directions close at once, so A leaves B's and C's
     * membership index simultaneously and neither survivor can reach A. The
     * B–C loopback stays open throughout, which is what makes the two claims
     * *contested* rather than two independent partitions.
     *
     * The steps, in order:
     *
     * 1. partition A away from both survivors — observation 1 on B and on C.
     *    `missCount` is captured: it is the only signal that separates "armed
     *    and counting" from "nothing happened".
     * 2. if [park], a write of 7 through B's replica. B is a follower, so it
     *    command-forwards to `aRef`, which has no location on B's registry any
     *    more, so the invocation parks. This is the ONE moment F5's shipped
     *    release rule leaves it parked — no mark has been folded yet.
     * 3. `b.replication.observe()` then `c.replication.observe()`, with **no
     *    controller step between them**. Claims are synchronous on the
     *    observing thread (f7h.4-D5), so both are minted and folded before
     *    either announcement is drained: B holds `(2, bRef)` and C holds
     *    `(2, cRef)` at the same instant. That reading is the sweep's control
     *    — without it the sweep could be exercising one claim and be vacuous.
     * 4. `runToIdle()` — the seed decides how the two `leaderMarked` frames,
     *    the two promotion baselines and (under [park]) B's application of the
     *    released 7 interleave across the six hosts.
     * 5. everything is read at quiescence.
     * 6. `heal()` both loopbacks and read again; when NOT [park], a further
     *    write of 3 through B measures convergence directly (f7h.6-D2).
     *
     * Recording is by two mechanisms, both installed after the initial
     * `runToIdle` (so `mark1`'s own fold is not recorded) and before any
     * fault: `registry.onLeaderMark` per peer for the adopted sequence, and a
     * plain `deltaOutlet.subscribe` on B's and C's replicas for the [Stamped]
     * units they emit. A plain `subscribe` does not go through `linking`, fires
     * no catch-up baseline and is not a shipping attachment (f7h.6-D2), so it
     * does not perturb what it measures.
     */
    private fun dualClaimRig(seed: Long, park: Boolean): DualClaim {
        val controller = SimulationController(seed)
        val posture = LeaderElection.EpochClaim(DetectionWindow(2))
        val a = Peer(controller)
        val b = Peer(controller, posture)
        val c = Peer(controller, posture)
        val id = UUID.randomUUID()
        val aRef = CellRef(id, 0)
        val bRef = CellRef(id, 1)
        val cRef = CellRef(id, 2)
        val mark1 = LeaderMark(id, 1, aRef)

        val onA = a.replica(id, 0, mark1)
        val onB = b.replica(id, 1, mark1)
        val onC = c.replica(id, 2, mark1)

        val bToA = Counting()
        val cToA = Counting()
        val bToC = Counting()
        val cToB = Counting()
        val abLoop = Peering.loopback(a.side, b.side, interposeBToA = bToA)
        val acLoop = Peering.loopback(a.side, c.side, interposeBToA = cToA)
        Peering.loopback(b.side, c.side, interposeAToB = bToC, interposeBToA = cToB)
        controller.runToIdle()
        listOf(bToA, cToA, bToC, cToB).forEach { it.reset() }

        val adoptedA = CopyOnWriteArrayList<LeaderMark>()
        val adoptedB = CopyOnWriteArrayList<LeaderMark>()
        val adoptedC = CopyOnWriteArrayList<LeaderMark>()
        a.registry.onLeaderMark { adoptedA += it }
        b.registry.onLeaderMark { adoptedB += it }
        c.registry.onLeaderMark { adoptedC += it }

        val emittedB = CopyOnWriteArrayList<Stamped<Long>>()
        val emittedC = CopyOnWriteArrayList<Stamped<Long>>()
        onB.deltaOutlet.subscribe(Use.fixed(Propagate<Stamped<Long>> { emittedB += it }, PortRef.generate()))
        onC.deltaOutlet.subscribe(Use.fixed(Propagate<Stamped<Long>> { emittedC += it }, PortRef.generate()))

        fun view(peer: Peer, cell: SingleWriterReplicationTest.SwCounterCell, adopted: List<LeaderMark>) = PeerView(
            adopted = adopted.toList(),
            leaderOf = peer.replication.leaderOf(id),
            leading = cell.leading,
            total = cell.total,
            realWrites = cell.realWrites,
            currentEpoch = cell.currentEpoch,
            becomeLeaderCalls = cell.becomeLeaderCalls,
            becomeFollowerCalls = cell.becomeFollowerCalls,
            receivedDeltas = cell.receivedDeltas,
            fencedDeltas = cell.fencedDeltas,
            baselinesAdopted = cell.baselinesAdopted,
            shippedPairs = peer.replication.shippedPairs(setOf(aRef, bRef, cRef)),
            parkedAtOld = peer.registry.parkedFor(aRef).size,
        )

        // 1 — symmetric partition (f7h.6-D3)
        abLoop.partition()
        acLoop.partition()
        controller.runToIdle()
        val bMisses = b.replication.missCount(id)
        val cMisses = c.replication.missCount(id)

        // 2 — the parked write
        var parkedAfterPark = -1
        var bTotalAfterPark = -1L
        if (park) {
            b.ops(onB).increment(7)
            controller.runToIdle()
            parkedAfterPark = b.registry.parkedFor(aRef).size
            bTotalAfterPark = onB.total
        }

        // 3 — both claims minted synchronously, read before any scheduler turn
        b.replication.observe()
        c.replication.observe()
        val bClaim = b.replication.leaderOf(id)
        val cClaim = c.replication.leaderOf(id)
        val parkedAtClaim = b.registry.parkedFor(aRef).size
        val bRealWritesAtClaim = onB.realWrites

        // 4/5 — the seed decides the interleaving; read at quiescence
        controller.runToIdle()
        val quiescedA = view(a, onA, adoptedA)
        val quiescedB = view(b, onB, adoptedB)
        val quiescedC = view(c, onC, adoptedC)
        val emittedAtQuiescence = emittedB.toList() to emittedC.toList()
        val bToCMarks = bToC.count(leaderMarkedId)
        val cToBMarks = cToB.count(leaderMarkedId)

        // 6 — heal, then (park = false only) a convergence write through the loser
        abLoop.heal()
        acLoop.heal()
        controller.runToIdle()
        val healedA = view(a, onA, adoptedA)
        val healedB = view(b, onB, adoptedB)
        val healedC = view(c, onC, adoptedC)

        var finalA: PeerView? = null
        var finalB: PeerView? = null
        var finalC: PeerView? = null
        if (!park) {
            b.ops(onB).increment(3)
            controller.runToIdle()
            finalA = view(a, onA, adoptedA)
            finalB = view(b, onB, adoptedB)
            finalC = view(c, onC, adoptedC)
        }

        return DualClaim(
            seed = seed,
            park = park,
            aRef = aRef,
            bRef = bRef,
            cRef = cRef,
            bMisses = bMisses,
            cMisses = cMisses,
            parkedAfterPark = parkedAfterPark,
            bTotalAfterPark = bTotalAfterPark,
            bClaim = bClaim,
            cClaim = cClaim,
            parkedAtClaim = parkedAtClaim,
            bRealWritesAtClaim = bRealWritesAtClaim,
            a = quiescedA,
            b = quiescedB,
            c = quiescedC,
            aHealed = healedA,
            bHealed = healedB,
            cHealed = healedC,
            aFinal = finalA,
            bFinal = finalB,
            cFinal = finalC,
            emittedB = emittedAtQuiescence.first,
            emittedC = emittedAtQuiescence.second,
            bToCMarks = bToCMarks,
            cToBMarks = cToBMarks,
        )
    }

    /** The per-seed premise: two claims really were in flight, and the window really was armed. */
    private fun DualClaim.assertDualClaimPremise() {
        bMisses shouldBe 1
        cMisses shouldBe 1
        bClaim shouldBe LeaderMark(bRef.id, 2, bRef)
        cClaim shouldBe LeaderMark(cRef.id, 2, cRef)
    }

    /** Every peer's adopted sequence is strictly increasing under the total order (f7h.1-D2). */
    private fun assertStrictlyIncreasing(vararg views: PeerView) {
        views.forEach { v ->
            v.adopted.zipWithNext().forEach { (older, newer) ->
                assertTrue(markOrder.compare(older, newer) < 0) {
                    "adopted sequence is not strictly increasing: $older then $newer in ${v.adopted}"
                }
            }
        }
    }

    /**
     * The half of a dual-claim outcome that holds on EVERY seed in both arms:
     * who won, that the loser really led and then stepped all the way down,
     * that no peer ever adopted a mark out of order, and that the loser holds
     * no outbound shipping link. Measured, not predicted — every count here
     * was read off the rig before it was asserted.
     */
    private fun DualClaim.assertWinnerIsGreaterInstanceId() {
        val id = aRef.id
        val winner = LeaderMark(id, 2, cRef)
        val loserClaim = LeaderMark(id, 2, bRef)

        // [MEM1-02]/f7h.1-D2: same counter, greater instanceId takes it.
        b.leaderOf shouldBe winner
        c.leaderOf shouldBe winner
        c.leading shouldBe true
        b.leading shouldBe false
        // B DID lead, briefly — a `leading == false` that never flipped would
        // satisfy the line above while proving nothing about a step-down.
        b.becomeLeaderCalls shouldBe 1
        // one more than mark1's own demotion, inside the same runToIdle
        b.becomeFollowerCalls shouldBe 2
        // C won, so C never stepped down: still mark1's single demotion
        c.becomeFollowerCalls shouldBe 1
        c.becomeLeaderCalls shouldBe 1

        // [MEM1-15]: B folded its own claim and then C's; C REJECTED B's, so
        // its fold moved exactly once.
        b.adopted shouldBe listOf(loserClaim, winner)
        c.adopted shouldBe listOf(winner)
        // A is cut off from both survivors and learns nothing until the heal.
        a.leaderOf shouldBe LeaderMark(id, 1, aRef)
        a.adopted shouldBe emptyList()
        assertStrictlyIncreasing(a, b, c)

        // [MEM1-15]: the loser's outbound shipping links are torn down by its
        // own step-down, and the winner holds exactly the one link to it. A
        // is not in either survivor's membership index at this point, so no
        // link is ever formed towards aRef.
        assertTrue(b.shippedPairs.none { it.first == bRef }) {
            "the loser still sources shipping links: ${b.shippedPairs}"
        }
        c.shippedPairs shouldBe setOf(cRef to bRef)

        // one announcement each way, over the only surviving loopback
        bToCMarks shouldBe 1
        cToBMarks shouldBe 1
    }

    // -------------------------------------- [MEM1-02]/[MEM1-20]/[MEM1-15] — 5.2

    /**
     * Epic §5.2. Fifty seeds, and on each of them B and C mint a claim at the
     * SAME counter with no scheduler turn between them; the total order
     * (f7h.1-D2) settles it on `instanceId`, and the loser steps down inside
     * the same `runToIdle`.
     *
     * The seed range is `1L..50L` and stays that way: AGENTS.md forbids
     * replacing a discovered failing seed with a friendlier one, and that
     * applies to the range as much as to a single seed. It is green on all
     * fifty as of this task.
     *
     * **Non-vacuity.** The sweep's own control is
     * [assertDualClaimPremise]: `missCount == 1` on both survivors proves
     * the detection windows were armed rather than nothing having happened,
     * and reading `(2, bRef)` at B and `(2, cRef)` at C *before* any
     * scheduler turn proves the sweep exercised TWO claims. Without it every
     * assertion below is also satisfied by a run in which only C ever
     * claimed. The assertions trace to
     * `InstanceIndex.markLeader`'s total order (strictly-increasing, the
     * winner), `SingleWriterReplication.applyRoles` pass 1 (the loser's
     * torn-down outbound links, `becomeFollowerCalls`) and pass 2 (the
     * winner's link, `becomeLeaderCalls`).
     */
    @Test
    fun `two simultaneous claims converge on the greater instanceId across fifty seeds`() {
        forEachSeed(1L..50L) { seed ->
            val r = dualClaimRig(seed, park = false)
            withClue("$r") {
                r.assertDualClaimPremise()
                r.assertWinnerIsGreaterInstanceId()

                val winner = LeaderMark(r.aRef.id, 2, r.cRef)
                // After the heal the catch-up replays each peer's FOLDED mark,
                // which is (2, cRef) everywhere — B does not re-announce its
                // superseded claim, so A adopts exactly one mark, not two.
                r.aHealed.leaderOf shouldBe winner
                r.aHealed.adopted shouldBe listOf(winner)
                r.aHealed.leading shouldBe false

                // Convergence by direct state comparison (f7h.6-D2): a write
                // through the LOSER is forwarded to the winner, applied by
                // exactly one real api, and shipped to both followers.
                r.cFinal!!.realWrites shouldBe 1
                r.bFinal!!.realWrites shouldBe 0
                r.aFinal!!.realWrites shouldBe 0
                r.aFinal.total shouldBe 3L
                r.bFinal.total shouldBe 3L
                r.cFinal.total shouldBe 3L
                r.aFinal.currentEpoch shouldBe 2L
                r.bFinal.currentEpoch shouldBe 2L
                r.cFinal.currentEpoch shouldBe 2L
            }
        }
    }

    // -------------------------------------- [MEM1-16]/[MEM1-04]/[MEM1-31] — 5.6

    /**
     * Epic §5.6, the half that holds on every seed: a write command-forwarded
     * through B while the leader is gone parks at the dead ref, is released
     * onto the winner's ref, and is applied by **exactly one** real api.
     *
     * **What "stays parked while two claims are in flight" actually means
     * here.** The feature's acceptance (a) reads as though the write stays
     * parked for the whole contested window. F5 as SHIPPED does not do that
     * (f7h.5-D2, amended 2026-09-10): `releaseParked` runs on the FIRST
     * fold-maximal mark, unconditionally, which is B's own claim — before C's
     * claim exists. So the parked window pinned here is the honest one:
     * parked from the write until the first fold ([parkedAfterPark] == 1),
     * released synchronously by `b.observe()` ([parkedAtClaim] == 0) and not
     * yet applied at that instant ([bRealWritesAtClaim] == 0). The contested
     * window is covered by the apply-time epoch fence, not by the park.
     *
     * **"Stamped under the winning epoch" can only be asserted on the
     * counter.** Both claims are at counter 2 and [Stamped] carries no
     * tiebreak, so `Stamped(2, 7)` from B and from C are indistinguishable at
     * any inlet. The discriminator for "applied once" is therefore
     * `realWrites`, not the stamp — see [SingleWriterReplicationTest.SwCounterCell.realWrites].
     *
     * The clause this test does NOT carry is `b.total == c.total`; it fails on
     * some seeds and lives in
     * [`a parked write leaves the loser diverged from the winner on some seeds`].
     */
    @Test
    fun `a parked write under two claims in flight is applied exactly once at the winner across fifty seeds`() {
        forEachSeed(1L..50L) { seed ->
            val r = dualClaimRig(seed, park = true)
            withClue("$r") {
                r.assertDualClaimPremise()

                // [MEM1-16] the park, and the shipped release rule
                r.parkedAfterPark shouldBe 1
                r.bTotalAfterPark shouldBe 0L
                r.parkedAtClaim shouldBe 0
                r.bRealWritesAtClaim shouldBe 0

                r.assertWinnerIsGreaterInstanceId()

                // Applied by exactly one real api. BOTH branches are
                // admissible and both occur across these fifty seeds: B
                // applied it under (2, bRef) before C's mark demoted it, or B
                // was demoted first and its delegate forwarded it to C.
                (r.b.realWrites + r.c.realWrites) shouldBe 1
                // the winner holds it either way
                r.c.total shouldBe 7L

                // [MEM1-04]/[MEM1-31]: nothing carries a counter below the
                // contested one, anywhere.
                (r.emittedB + r.emittedC).forEach { it.epoch shouldBe 2L }
                r.b.currentEpoch shouldBe 2L
                r.c.currentEpoch shouldBe 2L

                // after the heal the departed leader adopts the winner's
                // baseline rather than replaying anything of its own
                r.aHealed.total shouldBe 7L
                r.aHealed.realWrites shouldBe 0
                assertTrue(r.aHealed.baselinesAdopted >= 1) {
                    "A adopted no baseline after the heal: ${r.aHealed}"
                }
            }
        }
    }

    /**
     * The one clause of epic §5.6 that **does not hold**, recorded as a
     * standing expected failure rather than weakened or deleted
     * (`MEM1-52-DUAL-CLAIM-DIVERGENCE`, owner computenet-f7h.7).
     *
     * ## What fails, and on which branch
     *
     * `dualClaimRig(seed, park = true)` reaches quiescence by one of two
     * scheduler interleavings, and the seed picks which:
     *
     * - **"forward-first"** — B's bridge host folds `(2, cRef)` and demotes B
     *   before B's application host dequeues the released write. B's delegate
     *   forwards it to C, C applies it (`c.realWrites == 1`) and ships
     *   `Stamped(2, 7)` back, so `b.total == c.total == 7`. Converged.
     * - **"write-first"** — B's application host runs first. B applies the 7
     *   under its own `(2, bRef)` (`b.realWrites == 1`, `b.total == 7`) and
     *   emits `Stamped(2, 7)`, which C applies at ITS epoch 2 because
     *   [applyTo] compares the COUNTER only and `Stamped` carries no
     *   tiebreak. C's promotion baseline `Stamped(2, 0, baseline = true)` then
     *   reaches B and replaces B's 7 with 0. B folds `(2, cRef)`, steps down,
     *   and nothing re-baselines it — C's C→B link already existed, so no
     *   `onLinked` fires again. Quiescent state: **`c.total == 7`,
     *   `b.total == 0`.**
     *
     * ## What kind of finding this is
     *
     * **Divergence without loss, and without duplication.** The write is
     * applied by exactly one real api on every seed and the winner always
     * holds it (both pinned, green, in the test above); what fails is that the
     * loser's replica state does not equal the winner's, and stays unequal
     * until C's next write. [MEM1-20]'s "the loser's deltas SHALL be fenced
     * inert at every follower" does not hold AT THE SAME COUNTER, because
     * `Stamped` carries only the counter — which is exactly the R1 question
     * (95 §R1) this suite exists to answer.
     *
     * The seed range is kept at `1L..50L` deliberately: a range chosen so the
     * failing seeds fell out would turn a real finding into a green suite.
     */
    @Test
    @ExpectedFailure(
        signature = MEM1_52_DUAL_CLAIM_DIVERGENCE,
        reason = "write-first branch: B applies the released write under its own (2,bRef), " +
            "C's promotion baseline then zeroes B, leaving b.total 0 against c.total 7 — " +
            "Stamped's counter-only fence has no tiebreak at an equal counter",
        owner = "computenet-f7h.7",
        filedAs = "doc/kernel-lane-findings.md#mem1-52-dual-claim-divergence",
    )
    fun `a parked write leaves the loser diverged from the winner on some seeds`() {
        val diverged = mutableListOf<Long>()
        try {
            withSignature(MEM1_52_DUAL_CLAIM_DIVERGENCE) {
                forEachSeed(1L..50L) { seed ->
                    val r = dualClaimRig(seed, park = true)
                    if (r.b.total != r.c.total) diverged += seed
                    withClue("$r") { r.b.total shouldBe r.c.total }
                }
            }
        } finally {
            println("MEM1-52 write-first (diverged) seeds: ${diverged.size} of 50 — $diverged")
        }
    }

    // ---------------------------------------------------------------- helpers

    private val leaderMarkedId: Long =
        ContractRegistry.idsOf(RegistryAnnounce::class.java.getMethod("leaderMarked", LeaderMark::class.java))!!.second

    /** One frame's identity, as [Counting] records it. */
    private data class FrameId(val contractId: Long, val methodId: Long)

    /**
     * Records every frame that crosses one direction and passes it through
     * unchanged. Copied from [LeaderMarkAnnounceTest], where it is private and
     * in another task's claim.
     */
    private class Counting : Peering.FrameInterpose {
        private val frames = CopyOnWriteArrayList<FrameId>()

        override fun apply(frame: ByteArray): List<ByteArray> {
            val decoded = WireCodec.decodeFrame(frame).frame
            frames += FrameId(decoded.contractId, decoded.methodId)
            return listOf(frame)
        }

        fun count(methodId: Long): Int = frames.count { it.methodId == methodId }
        fun reset() = frames.clear()
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return dir
    }

    /**
     * Drop KDoc/block comments and `//` tails, keeping only lines with code
     * left on them. Copied from
     * [civictech.cell.architecture.ExtractionFenceTest], where it is private
     * and in another package's claim.
     */
    private fun stripComments(lines: List<String>): List<String> {
        var inBlock = false
        val out = mutableListOf<String>()
        for (raw in lines) {
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/")
                if (end < 0) continue
                line = line.substring(end + 2)
                inBlock = false
            }
            while (true) {
                val start = line.indexOf("/*")
                if (start < 0) break
                val end = line.indexOf("*/", start + 2)
                if (end < 0) {
                    line = line.substring(0, start)
                    inBlock = true
                    break
                }
                line = line.substring(0, start) + line.substring(end + 2)
            }
            val slash = line.indexOf("//")
            if (slash >= 0) line = line.substring(0, slash)
            if (line.isNotBlank()) out += line
        }
        return out
    }
}
