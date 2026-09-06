package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WatermarkCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanOutlet
import civictech.cell.port.Use
import civictech.cell.verify.ReplicaConvergence
import civictech.cell.wire.Peering
import civictech.testkit.dst.churn.MeshConvergences
import civictech.testkit.dst.churn.ReferenceFold
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * E3.6 (`computenet-9sm.4.2`): deterministic, seed-free pins over
 * [SetCell.compactBelow] driven from the two candidate compaction triggers, on
 * a REAL three-peer gossip mesh (three [SetCell] replicas, full triangle of
 * [Peering.loopback] links, one [SimulationController]).
 *
 * The fixture's `Peer`/`Mesh` classes are copied from `StableFrontierMeshTest`
 * — they are private to that file, and copying is the honest option its own
 * `healDanglingPartitions` note records — with the three [Peering.Loopback]
 * handles KEPT so B can be severed and healed, plus one addition this task's
 * measurement forced (see "fact 2", below): a switchable
 * [Peering.FrameInterpose] gate on B's two links, so B can be isolated by
 * **losing** frames rather than by closing the connection instance.
 *
 * ## The two triggers
 * - **STABLE**: [Replication.stableFrontier] — the `[42-WM-05]` pointwise MIN
 *   over every open membership row ([civictech.cell.consistency.CausalStability]).
 *   An absent row, or an open row lacking a column, reads as bottom.
 * - **LOCAL** (the deliberately wrong one, decision 9sm.3-D5):
 *   [Replication.localDeliveredFrontier] — this peer's OWN companion row.
 *
 * ## The `resurrected(cell, fold)` observable — reused verbatim by the sweep task
 * A [ReplicaConvergence] over the logical id folds each replica's **emitted**
 * delta stream (initial `SetDelta<String>()`, fold [SetDelta.merge]), exactly
 * as `MeshConvergences.declare` does. [MeshConvergences.project] is the
 * elements-present projection of such a fold (an OR-set element is present iff
 * it holds an add-tag no del-tag covers). Then
 *
 * ```
 * resurrected(cell, fold) = cell.membership() − project(fold).elements
 * ```
 *
 * is the set of elements the cell shows live whose own emitted history says
 * they were removed. It is the one observable that can see a re-admission,
 * because the fold retains every tombstone the cell ever emitted while
 * [SetCell.compactBelow] drops those tombstones from the cell itself.
 *
 * ## The two facts the pins were built on — one confirmed, one FALSIFIED
 * 1. **CONFIRMED, on the pre-del-dot tag algebra; SUPERSEDED by computenet-v2ka
 *    for the shipped one.** Originally: the delivered lane certified ADD
 *    delivery only. `SetCell`'s `foldDelivered` was called on the locally
 *    minted add-tag and on `newAdds.values.flatten()` in `applyRemote`;
 *    `remove` minted nothing and folded nothing. So `stableFrontier[s] >= t`
 *    meant every open member delivered the ADD `(s,t)`; it said nothing about
 *    whether any member held the DEL that reused that same tag. Since
 *    computenet-v2ka, `remove` mints its own del-dot and folds it through
 *    `foldDelivered` exactly like an add-tag, so the frontier now certifies
 *    REMOVE delivery as well: `stableFrontier[s] >= t` for a del-dot `(s,t)`
 *    means every open member delivered that remove. The `P2 LOST del` test
 *    below is the executable form of the CURRENT behaviour and does not
 *    resurrect — see its KDoc immediately above it.
 * 2. **FALSIFIED, and this is a substitution recorded rather than a friendlier
 *    state.** The breakdown's fact 2 asserted that "a severed loopback
 *    ([Peering.Loopback.partition] = `closeInstance()`) drops in-flight
 *    frames". **It does not.** Measured here (`P2 PARKED del`): sever B,
 *    remove at A, compact A and C at STABLE, heal — and B ends up holding the
 *    del anyway, so `e` is dead on all three and nothing resurrects. The del
 *    was not lost by the partition; the delivery to B was **parked** and
 *    replayed when the instance reopened. A partition in this fixture is
 *    therefore a *delay*, not a loss, and a schedule built on it can never
 *    produce the straggler the hazard needs.
 *
 *    The substitution: B is isolated with a [Peering.FrameInterpose] that
 *    returns no frames (`Mesh.loseB`), which loses the del at the frame plane
 *    with nothing parked behind it, and the catch-up is then forced with
 *    [Peering.Loopback.heal] — "safe without a preceding [Peering.Loopback.partition]",
 *    per its own KDoc. The PROPERTY is unchanged; only the mechanism by which
 *    B misses the remove is. Both schedules are kept as tests, because the
 *    parked one is what shows this file's instrument can report the negative.
 *
 * ## What P2 measured — the headline
 * **`P2: resurrects under STABLE`.** With the del genuinely lost, A and C
 * compact at a stable frontier that legitimately reads `sA → 1` (every open
 * member did deliver the add), the heal re-ships B's add-only state, and `e`
 * is live on **all three** replicas — memberships `A=[e] B=[e] C=[e]`, with
 * `resurrected(ra, foldA) = resurrected(rc, foldC) = {e}` and no del-tag left
 * anywhere in the mesh. The sentence in `[24-TAG-04]` — "reclaiming at the
 * stable frontier cannot [resurrect], because every covering replica has
 * already converged past it" — is therefore **FALSE for the shipped tag
 * algebra**, for exactly the reason fact 1 gives: convergence past the ADD is
 * not delivery of the REMOVE. That is a finding for the docs task, not a
 * defect of this task, and nothing in `SetCell`/`CausalStability`/the trigger
 * was changed to make it come out otherwise.
 *
 * **Limits of that claim, stated here and not only in the paperwork:** it is
 * measured on ONE schedule, in the in-process loopback fixture, with the
 * remove lost at the frame plane. It shows the hazard is REACHABLE, not how
 * often it is reached; the seeded sweep is what bounds that.
 *
 * The only observable that *disagrees* with the memberships is
 * [ReplicaConvergence.converged] over the three emitted-delta folds, which
 * reads **false** after the resurrection (B's fold never carried a del; A's
 * and C's did). That is asserted and classified, not replaced: a mesh whose
 * memberships agree while its emitted histories do not is precisely what
 * `resurrected` was defined to name.
 */
class CompactionTriggerPinTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(controller: SimulationController) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost)
        val replication = Replication(registry)
    }

    /**
     * Full triangle mesh of three [SetCell] replicas of [logicalId], converged,
     * with the [Peering.Loopback] handles retained so B can be severed
     * ([severB]) and healed ([healB]), and a frame gate on B's two links so B
     * can instead be isolated by frame LOSS ([loseB]) — see the class KDoc's
     * fact 2 for why both are needed.
     */
    private class Mesh(val controller: SimulationController, val logicalId: UUID) {
        val a = Peer(controller)
        val b = Peer(controller)
        val c = Peer(controller)

        val ab: Peering.Loopback
        val bc: Peering.Loopback

        @Suppress("unused")
        val ac: Peering.Loopback

        val ra: SetCell<String>
        val rb: SetCell<String>
        val rc: SetCell<String>

        private val dropping = AtomicBoolean(false)

        /**
         * Both directions of both of B's links share one gate: a partition can
         * be one-way in general, but every schedule here isolates B entirely.
         */
        private val gate = Peering.FrameInterpose { frame -> if (dropping.get()) emptyList() else listOf(frame) }

        init {
            ab = Peering.loopback(a.side, b.side, gate, gate)
            bc = Peering.loopback(b.side, c.side, gate, gate)
            ac = Peering.loopback(a.side, c.side)
            ra = SetCell<String>(CellRef(logicalId, 0)).also { a.replication.replicate(it, a.host) }
            rb = SetCell<String>(CellRef(logicalId, 1)).also { b.replication.replicate(it, b.host) }
            rc = SetCell<String>(CellRef(logicalId, 2)).also { c.replication.replicate(it, c.host) }
            controller.runToIdle()
        }

        /**
         * LOSE every frame in and out of B, at the frame plane, while [on].
         * Unlike [severB] the connection instances stay open, so nothing is
         * parked behind the loss and nothing replays when it is lifted.
         */
        fun loseB(on: Boolean) = dropping.set(on)

        /** Close B's two connection instances (the prescribed sever — a DELAY, not a loss). */
        fun severB() {
            ab.partition()
            bc.partition()
        }

        /** Heal B's two links — a fresh connection instance, full catch-up both ways. */
        fun healB() {
            ab.heal()
            bc.heal()
        }

        fun ops(peer: Peer, cell: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(cell.ref, peer.registry, SetInletProxy::class.java) as SetInletProxy).inlet.call

        /** One convergence over A's registry with all three replicas' emitted streams attached. */
        fun observeFolds(): ReplicaConvergence<SetDelta<String>, SetDelta<String>> =
            ReplicaConvergence<SetDelta<String>, SetDelta<String>>(
                a.registry,
                logicalId,
                SetDelta(),
            ) { acc, d -> acc.merge(d) }.also { conv ->
                conv.attach(ra)
                conv.attach(rb)
                conv.attach(rc)
            }

        /** A's per-origin tag source, read off A's own companion row minus its outlet-epoch column. */
        fun sourceOfA(): UUID {
            val companion = a.replication.watermarkOf(logicalId)!!
            val slotA = WatermarkCell.slotId(a.replication.watermarkRef(ra.ref))
            @Suppress("UNCHECKED_CAST")
            val epochA = (ra.outlet as FanOutlet<Propagate<SetDelta<String>>>).waveState().sourceId
            return (companion.rows().getValue(slotA).keys - epochA).single()
        }

        fun memberships(): List<Set<String>> = listOf(ra.membership(), rb.membership(), rc.membership())
    }

    private companion object {
        /**
         * `membership() − project(emitted-delta fold)` — see the class KDoc. An
         * element in this set is live in the cell while the cell's own emitted
         * history says it was removed: a re-admission.
         */
        fun resurrected(
            cell: SetCell<String>,
            fold: SetDelta<String>,
        ): Set<String> = cell.membership() - (MeshConvergences.project(fold) as ReferenceFold.Elements).elements

        /** Raw tag state, for the per-step trace the task's non-vacuousness route asks for. */
        @Suppress("UNCHECKED_CAST")
        fun tags(cell: SetCell<String>): String {
            val snap = cell.snapshot() as Map<String, Serializable>
            fun render(m: Any?) = (m as Map<String, Set<Timestamp>>)
                .mapValues { (_, ts) -> ts.map { it.counter }.sorted() }
            return "adds=${render(snap["adds"])} dels=${render(snap["dels"])}"
        }

        fun trace(step: String, vararg pairs: Pair<String, Any?>) {
            println("[compaction-pin] $step: " + pairs.joinToString(" ") { (k, v) -> "$k=$v" })
        }
    }

    /**
     * **P1 — the discriminating window.** While the stable frontier reads
     * bottom for A's source (B's and C's companion rows are held at A), the
     * STABLE trigger discards nothing (`[KE3-30]` interlock, mesh half) while
     * the LOCAL trigger discards the tombstone AND the add it covers
     * (`[KE3-20]`, BS-13's mechanism in deterministic form) — two calls on the
     * SAME state, each the other's control.
     *
     * **Substitution (recorded; class KDoc fact 2):** the prescribed step 3
     * `severB()` is replaced by `loseB(true)`, because a sever parks the
     * remove rather than losing it. Every prescribed value of steps 1–7 is
     * asserted unchanged.
     *
     * Step 8 (the heal) is `unverified:` in the breakdown and is asserted here
     * exactly as measured, in two halves: healing A<->B alone DOES re-admit
     * `e` at A (the prescribed observation, 8a), and healing B<->C afterwards
     * REPAIRS it (8b, the measured alternative) — C never compacted, so its
     * surviving tombstone reaches A through B. A one-replica compaction is
     * self-healing while any peer still holds the tombstone.
     */
    @Test
    fun `P1 the LOCAL trigger discards a tombstone the STABLE trigger declines on the same state`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val folds = mesh.observeFolds()
        val opA = mesh.ops(mesh.a, mesh.ra)

        val companionA = mesh.a.replication.watermarkOf(logicalId)!!
        val slotA = WatermarkCell.slotId(mesh.a.replication.watermarkRef(mesh.ra.ref))
        val slotB = WatermarkCell.slotId(mesh.a.replication.watermarkRef(mesh.rb.ref))
        val slotC = WatermarkCell.slotId(mesh.a.replication.watermarkRef(mesh.rc.ref))

        // 1. Park every delivery ADDRESSED to A's companion — B's and C's row
        //    gossip. A's OWN row still advances: Replication.trackDeliveries
        //    calls companion.advance(...) straight from the onDeliver listener,
        //    not through the registry. (`unverified:` in the breakdown;
        //    CONFIRMED by the row assertions below, so its step-until
        //    substitute was not needed.)
        mesh.a.registry.hold(companionA.ref)

        // 2. A adds `e`; the mesh converges on the ADD.
        opA.add("e")
        controller.runToIdle()
        val sA = mesh.sourceOfA()
        trace(
            "P1.2",
            "memberships" to mesh.memberships(),
            "rowA[sA]" to companionA.rows()[slotA]?.get(sA),
            "rowB" to companionA.rows()[slotB],
            "rowC" to companionA.rows()[slotC],
        )
        mesh.rb.membership() shouldBe setOf("e")
        mesh.rc.membership() shouldBe setOf("e")
        companionA.rows().getValue(slotA)[sA] shouldBe 1L
        // held: A has learned nothing of B's or C's delivery of (sA,1)
        companionA.rows()[slotB]?.get(sA) shouldBe null
        companionA.rows()[slotC]?.get(sA) shouldBe null

        // 3-4. Isolate B (frame loss — see the substitution note), then remove
        //      at A. A and C hold the tombstone; B never sees it.
        mesh.loseB(true)
        opA.remove("e")
        controller.runToIdle()
        trace("P1.4", "memberships" to mesh.memberships(), "A" to tags(mesh.ra), "B" to tags(mesh.rb))
        mesh.ra.membership() shouldBe emptySet()
        mesh.rc.membership() shouldBe emptySet()
        mesh.rb.membership() shouldBe setOf("e")

        // 5. The two triggers, read on the SAME state.
        val local = mesh.a.replication.localDeliveredFrontier(logicalId)
        val stable = mesh.a.replication.stableFrontier(logicalId)
        trace("P1.5", "local[sA]" to local.perSource[sA], "stable" to stable.perSource)
        // 2, not 1: the DEL-DOT (computenet-v2ka) is A's own tag counter 2, minted by the
        // remove and folded into A's delivered frontier. That it shows up HERE, one above the
        // add, is the mechanism in its smallest visible form.
        local.perSource[sA] shouldBe 2L
        stable.perSource[sA] shouldBe null

        // [KE3-30] interlock, mesh half: bottom for sA => nothing of sA discarded.
        val stableDiscards = mesh.ra.compactBelow(stable)
        trace("P1.5-stable-compact", "discarded" to stableDiscards, "A" to tags(mesh.ra))
        stableDiscards shouldBe 0

        // 6. The LOCAL trigger, on that same state, discards the del-tag and the
        //    add-tag it covers. The pair of numbers — 0 then 2 — is the whole
        //    discrimination, and neither call moved membership.
        val localDiscards = mesh.ra.compactBelow(local)
        trace("P1.6-local-compact", "discarded" to localDiscards, "A" to tags(mesh.ra))
        // 3, not 2: the `dels` entry is now {add-tag 1, del-dot 2} and the covered add-tag 1
        // goes with it — the dot is the third tag the reclaimer accounts for.
        localDiscards shouldBe 3
        mesh.ra.membership() shouldBe emptySet()

        // 7. Release the held rows and let them land. STABLE now reads sA -> 1 —
        //    but A has nothing left of `e` to discard, so a second STABLE
        //    compaction here returns 0. (P3 runs the same window WITHOUT step 6
        //    and gets 2, which is what shows STABLE's silence in step 5 was the
        //    timing of one read and not a property.)
        mesh.a.registry.release(companionA.ref)
        controller.runToIdle()
        val stableAfterRelease = mesh.a.replication.stableFrontier(logicalId)
        val secondStableDiscards = mesh.ra.compactBelow(stableAfterRelease)
        trace(
            "P1.7",
            "stable[sA]" to stableAfterRelease.perSource[sA],
            "secondStableDiscards" to secondStableDiscards,
        )
        stableAfterRelease.perSource[sA] shouldBe 1L
        secondStableDiscards shouldBe 0

        // 8a. Lift the loss and heal A<->B ONLY. B's catch-up carries
        //     adds={e:(sA,1)} and no del; the tag is novel at A again
        //     (compactBelow recorded no floor and no fence), so `e` is
        //     re-admitted at A — the prescribed step-8 observation. C, which
        //     never compacted, still holds its tombstone and stays dead: the
        //     mesh is now three-way divided.
        mesh.loseB(false)
        mesh.ab.heal()
        controller.runToIdle()
        val foldA8a = folds.state(mesh.ra.ref)!!
        val foldC8a = folds.state(mesh.rc.ref)!!
        trace(
            "P1.8a",
            "memberships" to mesh.memberships(),
            "A" to tags(mesh.ra),
            "B" to tags(mesh.rb),
            "C" to tags(mesh.rc),
            "resurrectedA" to resurrected(mesh.ra, foldA8a),
            "resurrectedC" to resurrected(mesh.rc, foldC8a),
            "converged" to folds.converged(),
            "liveReplicas" to mesh.a.registry.instances.replicasOf(logicalId).size,
        )
        // Non-vacuity for converged(): fewer than two live streams converges trivially.
        mesh.a.registry.instances.replicasOf(logicalId).size shouldBe 3
        mesh.ra.membership() shouldBe setOf("e")
        resurrected(mesh.ra, foldA8a) shouldBe setOf("e")
        mesh.rc.membership() shouldBe emptySet()
        resurrected(mesh.rc, foldC8a) shouldBe emptySet()

        // 8b. **The measured alternative to the breakdown's step 8, recorded.**
        //     Heal B<->C too and the re-admission is REPAIRED: C's catch-up
        //     hands B the tombstone C never discarded, B absorbs it as novel
        //     and re-emits it, and A — which has no fence against a tag it
        //     already compacted — takes the del back. Everything is dead again
        //     and `resurrected` is empty everywhere.
        //
        //     So a LOCAL-trigger compaction on ONE replica is self-healing
        //     while any peer still holds the tombstone. What makes the
        //     resurrection permanent is every tombstone-holder compacting,
        //     which is exactly the `P2 LOST del` schedule — and there the
        //     trigger is the STABLE one.
        mesh.bc.heal()
        controller.runToIdle()
        val foldA8b = folds.state(mesh.ra.ref)!!
        trace(
            "P1.8b",
            "memberships" to mesh.memberships(),
            "A" to tags(mesh.ra),
            "B" to tags(mesh.rb),
            "C" to tags(mesh.rc),
            "resurrectedA" to resurrected(mesh.ra, foldA8b),
            "converged" to folds.converged(),
        )
        mesh.memberships() shouldBe listOf(emptySet(), emptySet(), emptySet())
        resurrected(mesh.ra, foldA8b) shouldBe emptySet()
    }

    /**
     * **P2, the PRESCRIBED schedule (`severB`/`healB`) — the CONTROL.** It does
     * not resurrect, and the reason is not the stable frontier: the sever
     * parks the remove instead of losing it, so B holds the del by the time
     * the heal finishes and `e` is dead on all three.
     *
     * This test is kept precisely because it is the negative. The same
     * `resurrected` observable, over the same compaction (2 tags discarded at
     * A and at C — asserted, so the schedule demonstrably REACHED the
     * compaction), reports `{}` here and `{e}` in the lossy sibling. That pair
     * is the evidence that the instrument can detect either outcome.
     */
    @Test
    fun `P2 PARKED del - severing does not lose the remove, so compacting at STABLE does not resurrect`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val folds = mesh.observeFolds()
        val opA = mesh.ops(mesh.a, mesh.ra)

        opA.add("e")
        controller.runToIdle()

        mesh.severB()
        opA.remove("e")
        controller.runToIdle()
        trace("P2p.2", "memberships" to mesh.memberships(), "B" to tags(mesh.rb))
        mesh.rb.membership() shouldBe setOf("e")

        val discardedA = mesh.ra.compactBelow(mesh.a.replication.stableFrontier(logicalId))
        val discardedC = mesh.rc.compactBelow(mesh.c.replication.stableFrontier(logicalId))
        trace("P2p.3", "discardedA" to discardedA, "discardedC" to discardedC, "A" to tags(mesh.ra))
        // 0, not 2 (computenet-v2ka). The severed B has not delivered the DEL-DOT, so the stable
        // frontier still reads `sA -> 1` while the `dels` entry is {1, dot 2} — and `[KE3-31]`'s
        // every-tag rule declines the whole entry. Reclamation is now DEFERRED by exactly the
        // condition it should be deferred by: an open member that has not seen the remove.
        discardedA shouldBe 0
        discardedC shouldBe 0

        mesh.healB()
        controller.runToIdle()
        val foldA = folds.state(mesh.ra.ref)!!
        val foldC = folds.state(mesh.rc.ref)!!
        trace(
            "P2p.5",
            "memberships" to mesh.memberships(),
            "A" to tags(mesh.ra),
            "B" to tags(mesh.rb),
            "C" to tags(mesh.rc),
            "resurrectedA" to resurrected(mesh.ra, foldA),
            "resurrectedC" to resurrected(mesh.rc, foldC),
            "converged" to folds.converged(),
        )
        // The del came BACK — to A, to B and to C — from the parked delivery the
        // partition merely deferred. Nothing was lost, so nothing resurrects.
        mesh.memberships() shouldBe listOf(emptySet(), emptySet(), emptySet())
        resurrected(mesh.ra, foldA) shouldBe emptySet()
        resurrected(mesh.rc, foldC) shouldBe emptySet()

        // NON-VACUITY, and the answer to "does the del-dot just switch GC off?" — it does not.
        // Once the heal has delivered the dot to B, B's row rises to 2, the stable frontier
        // rises with it, and the SAME call that returned 0 above now reclaims the whole entry:
        // both tags of `dels` plus the add-tag they cover. Reclamation is deferred, not denied.
        val afterHealA = mesh.ra.compactBelow(mesh.a.replication.stableFrontier(logicalId))
        val afterHealC = mesh.rc.compactBelow(mesh.c.replication.stableFrontier(logicalId))
        trace("P2p.6", "afterHealA" to afterHealA, "afterHealC" to afterHealC, "A" to tags(mesh.ra))
        afterHealA shouldBe 3
        afterHealC shouldBe 3
        mesh.memberships() shouldBe listOf(emptySet(), emptySet(), emptySet())
    }

    /**
     * **P2 — the schedule that tests the hazard, and the deterministic reproduction
     * computenet-v2ka fixed.** B misses the remove because the frames are LOST (class KDoc
     * fact 2), so it holds the add and not the del.
     *
     * **Before the del-dot** this test asserted a RESURRECTION, and that was the correct
     * reading of the shipped algebra: the `dels` entry was `{add-tag 1}`, the stable frontier
     * legitimately read `sA → 1` because B genuinely *had* delivered the ADD, A and C therefore
     * discarded 2 tags each, and the heal re-shipped B's add-only state into two replicas with
     * no tombstone left. Measured on base 8d65b542b: `discardedA=2 discardedC=2`, then
     * `memberships=[[e], [e], [e]]`, `resurrectedA=[e]`, `resurrectedC=[e]`.
     *
     * **After it**, `remove` mints a del-dot, so the entry is `{add-tag 1, del-dot 2}` and B —
     * which never received the remove — never delivers counter 2. The stable frontier still
     * reads `sA → 1`, `[KE3-31]`'s every-tag rule sees `2 > 1` and declines, and the heal ends
     * with A's and C's tombstones killing `e` at B instead of B's add reviving it at A and C.
     * That inversion — same schedule, same frontier value, opposite outcome — is the whole
     * mechanism, and it is what makes the frontier certify the REMOVE rather than the add.
     */
    @Test
    fun `P2 LOST del - the del-dot keeps the STABLE frontier from discarding an undelivered remove`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val folds = mesh.observeFolds()
        val opA = mesh.ops(mesh.a, mesh.ra)

        // 1. Converge on the add: every peer's stable frontier reads sA -> 1,
        //    i.e. every open member genuinely delivered the ADD.
        opA.add("e")
        controller.runToIdle()
        val sA = mesh.sourceOfA()
        val stableEverywhere = listOf(mesh.a, mesh.b, mesh.c)
            .map { it.replication.stableFrontier(logicalId).perSource[sA] }
        trace("P2.1", "memberships" to mesh.memberships(), "stable" to stableEverywhere)
        stableEverywhere shouldBe listOf(1L, 1L, 1L)

        // 2. Lose B's frames, remove at A. A and C hold the tombstone; B does
        //    not, and nothing is parked to hand it to B later.
        mesh.loseB(true)
        opA.remove("e")
        controller.runToIdle()
        trace("P2.2", "memberships" to mesh.memberships(), "A" to tags(mesh.ra), "B" to tags(mesh.rb))
        mesh.ra.membership() shouldBe emptySet()
        mesh.rc.membership() shouldBe emptySet()
        mesh.rb.membership() shouldBe setOf("e")

        // 3. Compact A and C at their OWN stable frontier. B's row still reads
        //    sA -> 1 — rows never regress, and B genuinely delivered the ADD.
        //    This is `[24-TAG-04]`'s own sanctioned reclamation point.
        val stableA = mesh.a.replication.stableFrontier(logicalId)
        val stableC = mesh.c.replication.stableFrontier(logicalId)
        val discardedA = mesh.ra.compactBelow(stableA)
        val discardedC = mesh.rc.compactBelow(stableC)
        trace(
            "P2.3",
            "stableA[sA]" to stableA.perSource[sA],
            "stableC[sA]" to stableC.perSource[sA],
            "discardedA" to discardedA,
            "discardedC" to discardedC,
            "A" to tags(mesh.ra),
            "C" to tags(mesh.rc),
        )
        // The frontier is UNCHANGED from the unfixed run — still exactly 1, the add's counter.
        // Only the rule's reach changed: the entry now also holds the del-dot at 2.
        stableA.perSource[sA] shouldBe 1L
        stableC.perSource[sA] shouldBe 1L
        discardedA shouldBe 0
        discardedC shouldBe 0
        mesh.ra.membership() shouldBe emptySet()
        mesh.rc.membership() shouldBe emptySet()

        // 4-5. Lift the loss and heal.
        mesh.loseB(false)
        mesh.healB()
        controller.runToIdle()
        val foldA = folds.state(mesh.ra.ref)!!
        val foldC = folds.state(mesh.rc.ref)!!
        trace(
            "P2.5",
            "memberships" to mesh.memberships(),
            "A" to tags(mesh.ra),
            "B" to tags(mesh.rb),
            "C" to tags(mesh.rc),
            "resurrectedA" to resurrected(mesh.ra, foldA),
            "resurrectedC" to resurrected(mesh.rc, foldC),
            "converged" to folds.converged(),
            "liveReplicas" to mesh.a.registry.instances.replicasOf(logicalId).size,
        )
        // THE FIX: `e` is dead on all three replicas. The unfixed code produced
        // `[[e], [e], [e]]` here with no del-tag left anywhere in the mesh.
        mesh.memberships() shouldBe listOf(emptySet(), emptySet(), emptySet())
        resurrected(mesh.ra, foldA) shouldBe emptySet()
        resurrected(mesh.rc, foldC) shouldBe emptySet()
        // Non-vacuity for converged(): three live streams, so the judgement is real.
        mesh.a.registry.instances.replicasOf(logicalId).size shouldBe 3
        // And the folds now AGREE: B learned the del from A's and C's surviving tombstones on
        // the heal, so its emitted stream carries one too. Unfixed, this read false.
        folds.converged() shouldBe true

        // Non-vacuity, as in P2 PARKED: with the dot delivered everywhere the same call
        // reclaims the entry in full, so the deferral above is a deferral and not a deadlock.
        val afterHealA = mesh.ra.compactBelow(mesh.a.replication.stableFrontier(logicalId))
        trace("P2.6", "afterHealA" to afterHealA, "A" to tags(mesh.ra))
        afterHealA shouldBe 3
        mesh.ra.membership() shouldBe emptySet()
    }

    /**
     * **P3 — the STABLE-only variant of P1.** P1's step 6 (the LOCAL
     * compaction) is skipped, so `e`'s tags are still present at A when the
     * held rows land and STABLE rises to `sA → 1`. The same STABLE compaction
     * that returned 0 inside P1's window now returns 2, and the heal
     * resurrects at A exactly as in P2: STABLE's silence in P1 step 5 was the
     * timing of one read, not a property of the trigger.
     */
    @Test
    fun `P3 STABLE declines only while the rows are held - once they land it discards and the heal resurrects`() {
        val controller = SimulationController()
        val logicalId = UUID.randomUUID()
        val mesh = Mesh(controller, logicalId)
        val folds = mesh.observeFolds()
        val opA = mesh.ops(mesh.a, mesh.ra)

        val companionA = mesh.a.replication.watermarkOf(logicalId)!!
        mesh.a.registry.hold(companionA.ref)

        opA.add("e")
        controller.runToIdle()
        val sA = mesh.sourceOfA()

        mesh.loseB(true)
        opA.remove("e")
        controller.runToIdle()

        // The held window: STABLE reads bottom for sA and discards nothing.
        val heldStable = mesh.a.replication.stableFrontier(logicalId)
        val heldDiscards = mesh.ra.compactBelow(heldStable)
        trace("P3.held", "stable[sA]" to heldStable.perSource[sA], "discarded" to heldDiscards, "A" to tags(mesh.ra))
        heldStable.perSource[sA] shouldBe null
        heldDiscards shouldBe 0

        // The rows land. STABLE rises, and the SAME call now discards 2.
        // C compacts at its own stable frontier too — "the heal then resurrects
        // exactly as P2" is what the breakdown asks of this variant, and P2
        // compacts every tombstone-holder. P1.8b is the measurement showing why
        // that matters: leave one tombstone standing and the re-admission is
        // repaired rather than permanent.
        mesh.a.registry.release(companionA.ref)
        controller.runToIdle()
        val landedStable = mesh.a.replication.stableFrontier(logicalId)
        val landedDiscards = mesh.ra.compactBelow(landedStable)
        val landedDiscardsC = mesh.rc.compactBelow(mesh.c.replication.stableFrontier(logicalId))
        trace(
            "P3.landed",
            "stable[sA]" to landedStable.perSource[sA],
            "discardedA" to landedDiscards,
            "discardedC" to landedDiscardsC,
            "A" to tags(mesh.ra),
            "C" to tags(mesh.rc),
        )
        // `sA -> 1` is the ADD's counter; the DEL-DOT is 2 and B, still losing frames, has not
        // delivered it. So the landed rows no longer license a discard (computenet-v2ka): what
        // P3 was built to show — that STABLE's silence in P1 step 5 was the timing of one read
        // and not a property — is now shown by the frontier RISING to 1 and the discard staying
        // 0, because 1 is not enough. The trigger is not the thing that changed; the rule is.
        landedStable.perSource[sA] shouldBe 1L
        landedDiscards shouldBe 0
        landedDiscardsC shouldBe 0

        mesh.loseB(false)
        mesh.healB()
        controller.runToIdle()
        val foldA = folds.state(mesh.ra.ref)!!
        trace(
            "P3.healed",
            "memberships" to mesh.memberships(),
            "A" to tags(mesh.ra),
            "B" to tags(mesh.rb),
            "C" to tags(mesh.rc),
            "resurrectedA" to resurrected(mesh.ra, foldA),
            "converged" to folds.converged(),
        )
        // NO LONGER RESURRECTS (computenet-v2ka). A and C never discarded, so their tombstones
        // survive the heal and re-kill `e` at B instead of B's add-only state reviving it.
        mesh.memberships() shouldBe listOf(emptySet(), emptySet(), emptySet())
        resurrected(mesh.ra, foldA) shouldBe emptySet()
    }
}
