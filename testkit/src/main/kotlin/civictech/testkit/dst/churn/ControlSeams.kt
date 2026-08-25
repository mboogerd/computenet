package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartureMode
import civictech.testkit.dst.DstCheck
import civictech.testkit.dst.DstReport
import civictech.testkit.dst.DstRun
import civictech.testkit.dst.DstWorld
import civictech.testkit.dst.JoinEvent

/**
 * The four discrimination controls [CHA3-70]…[CHA3-73] and the harness's own self-test
 * ([CHA3-74], BS-20) — feature computenet-umx.2 §4.8, decomposition item umx.2-D5. "A green
 * churn sweep without these is not evidence": every control here is a scenario deliberately
 * engineered so a *correct* reconvergence/instrument check goes **red**, proving the check can
 * actually detect the property it claims to guard rather than passing vacuously.
 *
 * ## Three controls live here; the fourth (PN-0c) does not
 *
 * [CHA3-72] (BS-11, the PN-0c wedge, `Replication.evict(closeDepartedRow = false)`) is already
 * delivered by the departure-gates task as [StabilityObservables.stabilityCoversCheck] — a
 * [DstCheck] "exposed... so the controls task consumes this exact divergence result" (that
 * file's own KDoc). [selfTest] wires it in by calling it, unmodified; nothing here reimplements
 * it, per the bead's explicit non-goal.
 *
 * ## No kernel `main` edits ([CHA3-82])
 *
 * Every control below reaches its divergence through public harness surfaces —
 * [MeshPeer.suppressOutboundDeliveries], [MeshPeer.accumulateDuplicateSubscription],
 * [ReconvergenceCheck.disablingDepartedStreamRule] — or through hand-built plans replayed by the
 * unmodified rig. See each function's KDoc for the specific mechanism and, where relevant, why
 * an easier-looking mechanism was rejected.
 *
 * ## Window sizing ([CHA3-70]'s own scenario, and the sweep this task's bead warns about)
 *
 * CHA1 measured a diverging control going inert 0-of-100 seeds when its activation window was
 * *larger* than the traffic the mesh actually carried in it. The controls below sidestep that
 * failure mode by construction rather than by tuning a window against generated traffic: each
 * scenario is a **hand-built, minimal plan** (not a [ChurnGenerator] draw) in which the single
 * write the control must lose, and the single edge that must stay lost, are named explicitly —
 * there is no window to undersize. [ChurnControlsTest] additionally runs each control across a
 * small handful of seeds (varying only the mesh's derived ids, never the topology) and asserts
 * every seed diverges, which is the stronger, CHA1-precedented form of the same guarantee.
 */
object ControlSeams {

    // ---------------------------------------------------------------------------- fixtures
    // Mirrors DepartureGatesTest's own fixtures (config/roster/joinsAt/execute) rather than
    // sharing them: DepartureGatesTest.kt is outside this task's file claim, and a shared
    // private helper would require editing it (that file's own stated reason, repeated here).

    private fun config(peers: Int, stepBudget: Int) = ChurnConfig(
        peerCount = peers..peers,
        eventCount = 0,
        writeConcurrency = 0.0,
        partitionOverlap = 0.5,
        opScriptLength = 0,
        stepBudget = stepBudget,
        suspendWindow = 4,
    )

    private fun roster(peers: Int): List<String> = List(peers) { "peer$it" }

    private fun joinsAt(step: Int, peers: List<String>): List<ChurnEvent> =
        peers.map { peer -> JoinEvent("join-$peer", peer, step) }

    /**
     * Build and run a bare (joins-only) mesh, reconvergence-observed throughout, and return the
     * settled [DstWorld] for a control to drive by hand. [MeshConvergences.observing] wraps the
     * whole build+run because [MeshConvergences.onSpawn] only attaches at spawn time ([BS-4]'s
     * and [BS-3]'s checks would otherwise find no declared observation at all).
     */
    private fun settledMesh(seed: Long, peers: Int, stepBudget: Int = 4000): Pair<DstReport, DstWorld> =
        MeshConvergences.observing {
            val roster = roster(peers)
            val plan = ChurnPlan(
                seed = seed,
                config = config(peers = peers, stepBudget = stepBudget),
                peers = roster,
                events = joinsAt(1, roster),
                writeSchedule = emptyList(),
            )
            var captured: DstWorld? = null
            val spec = ChurnMesh.spec(plan, payload = MeshPayload.SET, maxPeers = peers)
            val report = DstRun(
                graph = spec,
                plan = plan.toFaultPlan(),
                budget = stepBudget,
                check = DstCheck { world -> captured = world },
            ).execute()
            val world = captured ?: throw IllegalStateException(
                "the bare mesh never quiesced (${report.outcome}, ${report.steps}/${report.budget} steps)",
            )
            report to world
        }

    // ---------------------------------------------------------------------------- BS-3 [CHA3-70]

    /**
     * [CHA3-70], BS-3: suppress a departing replica's final push-catch-up and assert the
     * reconvergence check goes red with a fold mismatch naming the lost element.
     *
     * ## The scenario, and why every piece of it is load-bearing
     *
     * Three peers. `peer0`'s own outbound delivery to `peer1` is parked directly on `peer0`'s
     * [MeshPeer.registry] (`LocationRegistry.hold` — the delivery-park layer) — **not** the
     * `peer0<->peer1` edge's [LinkControl]: severing that edge was tried first and rejected,
     * because [LinkControl.severing] also un-mirrors each side's membership entry for the
     * other — measured directly, it turns this into a *membership* disagreement
     * ([ReconvergenceCheck.MEMBERSHIP]) before the check ever reaches a fold, which proves the
     * wrong property. `hold`/`release` is deliberately the *other* layer of `LocationRegistry`
     * — delivery only, membership untouched — so `peer0` and `peer1` still agree they are both
     * live throughout. So `peer1` has exactly one path to anything `peer0` writes from then on:
     * relayed through
     * `peer2` (full-mesh gossip means `peer0` and `peer2` are still directly linked, and so are
     * `peer1` and `peer2`). `peer2` then has [MeshPeer.suppressOutboundDeliveries] called on it
     * **before** `peer0` writes — required by the window-sizing note on the class KDoc: calling
     * it only right before eviction, after the write has already drained, would suppress
     * nothing observable, because ordinary per-write gossip and `evict`'s redundant catch-up
     * share one channel and the ordinary one would already have delivered it. With the peer
     * parked from before the write, `peer0`'s write reaches `peer2` (that channel is untouched)
     * but never leaves `peer2` again — not via ordinary relay, not via `evict`'s own catch-up —
     * because both route through the one parked channel. `peer2` then departs cleanly
     * (`evictClean`). `peer1`, still live and still required, can now never receive `peer0`'s
     * write by any path this mesh offers.
     *
     * The reconvergence check reads this as [ReconvergenceCheck.DIVERGED]: `peer0`'s and
     * `peer1`'s judged folds disagree (`values.size > 1`) before the check ever reaches the
     * lost/invented comparison, and [ReconvergenceCheck]'s own `divergence()` detail names which
     * replicas hold which folds and what differs between them — the "fold mismatch naming the
     * lost elements" [CHA3-70] asks for.
     *
     * @return the thrown [ChurnCheckFailure], or `null` if the run converged anyway (an inert
     *   control at this seed — [ChurnControlsTest] is what asserts this never happens).
     */
    fun suppressedFinalCatchUp(seed: Long): ChurnCheckFailure? {
        val (_, world) = settledMesh(seed, peers = 3)
        val peer0 = MeshPeers.require(world, "peer0")
        val peer1 = MeshPeers.require(world, "peer1")
        val peer2 = MeshPeers.require(world, "peer2")

        peer0.registry.hold(peer1.ref)
        peer2.suppressOutboundDeliveries()

        check(peer0.write(9001)) { "peer0 must still be a member to issue the write the control loses" }
        world.controller.runToIdle()

        check(peer2.evictClean()) { "peer2 must have had a reachable peer (peer0) to despawn cleanly" }
        world.controller.runToIdle()

        return try {
            ReconvergenceCheck.of(MeshPayload.SET).verify(world)
            null
        } catch (e: ChurnCheckFailure) {
            e
        }
    }

    // ---------------------------------------------------------------------------- BS-4 [CHA3-71]

    /**
     * [CHA3-71], BS-4: disable the departed-stream rule and assert the reconvergence check goes
     * red — proving [CHA3-13]'s exclusion of a departed replica's frozen fold is load-bearing,
     * not vacuous (an implementation that excluded *too much* would pass every sweep trivially;
     * this is the guard against that, per feature computenet-umx.2's umx.2-D5).
     *
     * `peer2` departs cleanly (its own catch-up unsuppressed — this control is about the
     * *judgement*, not the delivery), then the two survivors keep writing. Their live fold
     * necessarily grows past `peer2`'s now-frozen snapshot. [ReconvergenceCheck.of] (the
     * departed-stream rule ON) excludes `peer2`'s frozen fold from the judged set and passes;
     * [ReconvergenceCheck.disablingDepartedStreamRule] judges it alongside the still-growing
     * live folds and the two disagree — [ReconvergenceCheck.DIVERGED].
     *
     * @return the thrown [ChurnCheckFailure] from the *variant* (rule-disabled) check, or `null`
     *   if it converged anyway (an inert control).
     */
    fun departedStreamRuleDisabled(seed: Long): ChurnCheckFailure? {
        val (_, world) = settledMesh(seed, peers = 3)
        val peer0 = MeshPeers.require(world, "peer0")
        val peer2 = MeshPeers.require(world, "peer2")

        check(peer2.evictClean()) { "peer2 must have a reachable peer to despawn cleanly" }
        world.controller.runToIdle()

        // The live survivors move on: their fold must outgrow peer2's frozen snapshot for the
        // variant judgement to have anything to disagree about.
        repeat(5) { i -> check(peer0.write(9100 + i)) { "peer0 must still be live to write post-departure" } }
        world.controller.runToIdle()

        // The control is honest about what it changes: the DEFAULT check must still pass here,
        // or a failure below would prove nothing about the rule specifically.
        ReconvergenceCheck.of(MeshPayload.SET).verify(world)

        return try {
            ReconvergenceCheck.disablingDepartedStreamRule(MeshPayload.SET).verify(world)
            null
        } catch (e: ChurnCheckFailure) {
            e
        }
    }

    // -------------------------------------------------------------------------- [CHA3-73]

    /**
     * [CHA3-73]: a rejoin that re-links without dropping the prior subscription fails the
     * instruments' [CHA3-43] check.
     *
     * `peer1` departs and rejoins through the ordinary path (which — per T21 — is idempotent and
     * would NOT itself diverge; that is what [RejoinSubscriptionTest] pins for the real
     * implementation). [MeshPeer.accumulateDuplicateSubscription] then installs one *additional*
     * subscription from `peer1` to `peer0` alongside the properly re-derived one, reproducing
     * the pre-T21 defect deliberately. [GossipInstruments.subscriptionsBoundedByMembership]
     * reads this on the `links` observable (`outlet.linking.links.size > liveMembership`) — the
     * observable this control targets, because the *other* observable
     * ([GossipInstruments.SubscriptionReading.gossipConsumers]) collapses two consumers
     * attributed to the same target [civictech.cell.CellRef] into one set entry and would not
     * see the duplicate; `links` counts registrations, not distinct targets, and does.
     *
     * @return the thrown [ChurnCheckFailure], or `null` if the check passed anyway (an inert
     *   control).
     */
    fun accumulatingRejoin(seed: Long): ChurnCheckFailure? {
        val (_, world) = settledMesh(seed, peers = 3)
        val peer0 = MeshPeers.require(world, "peer0")
        val peer1 = MeshPeers.require(world, "peer1")

        check(peer1.evictClean()) { "peer1 must have a reachable peer to despawn cleanly" }
        world.controller.runToIdle()
        peer1.rejoin()
        world.controller.runToIdle()
        check(peer1.member) { "peer1 must be live again after rejoin for the duplicate to attach to anything" }

        peer1.accumulateDuplicateSubscription(peer0)

        GossipInstruments.armOn(world)
        return try {
            GossipInstruments.subscriptionsBoundedByMembership().verify(world)
            null
        } catch (e: ChurnCheckFailure) {
            e
        }
    }

    // --------------------------------------------------------------------------------- BS-20

    /** One control's outcome, for [selfTest]'s report. */
    data class ControlOutcome(val name: String, val diverged: Boolean, val detail: String)

    /**
     * [CHA3-74], BS-20: run every one of the four controls — the three above, plus the
     * departure-gates task's own PN-0c control, consumed unmodified — and report whether each
     * diverged.
     *
     * `seed` only varies the mesh's derived logical ids ([ChurnMesh.spec]'s `dataId`/
     * `assignmentId` salts); the hand-built topology and op-script of every control below are
     * fixed, by the class KDoc's window-sizing argument.
     */
    fun selfTest(seed: Long): List<ControlOutcome> = listOf(
        outcome("BS-3 suppressed final catch-up [CHA3-70]") { suppressedFinalCatchUp(seed) },
        outcome("BS-4 departed-stream rule disabled [CHA3-71]") { departedStreamRuleDisabled(seed) },
        outcome("accumulating rejoin [CHA3-73]") { accumulatingRejoin(seed) },
        outcome("PN-0c EVICT_NO_CLOSE wedge [CHA3-72]") { pn0cWedge(seed) },
    )

    private fun outcome(name: String, run: () -> ChurnCheckFailure?): ControlOutcome {
        val failure = run()
        return if (failure != null) {
            ControlOutcome(name, diverged = true, detail = "${failure.message}: ${failure.detail}")
        } else {
            ControlOutcome(name, diverged = false, detail = "ran to completion without the expected divergence")
        }
    }

    /**
     * [CHA3-72]/BS-11, consumed rather than reimplemented (see the class KDoc): an
     * `EVICT_NO_CLOSE` departure, checked with [StabilityObservables.stabilityCoversCheck]
     * exactly as `DepartureGatesTest`'s own BS-11 test does — `memberNames` **includes** the
     * departed `peer2`, because the wedge this control proves is precisely that its row stays
     * un-closed and therefore un-covered forever; omitting it from `memberNames` would check
     * nothing about the departed row at all.
     */
    private fun pn0cWedge(seed: Long): ChurnCheckFailure? {
        val (_, world) = settledMesh(seed, peers = 3)
        val peer0 = MeshPeers.require(world, "peer0")
        val peer2 = MeshPeers.require(world, "peer2")

        check(peer2.evictNoClose()) { "peer2 must have a reachable peer to despawn (PN-0c control seam)" }
        world.controller.runToIdle()

        StabilityObservables.watch(peer0)
        repeat(5) { i -> check(peer0.write(9200 + i)) { "peer0 must still be live to write post-departure" } }
        world.controller.runToIdle()
        val origin = StabilityObservables.originOf(peer0, "peer0-9204")

        return try {
            StabilityObservables.stabilityCoversCheck(
                observerName = "peer0",
                memberNames = listOf("peer0", "peer1", "peer2"),
                origin = origin,
            ).verify(world)
            null
        } catch (e: ChurnCheckFailure) {
            e
        }
    }

    /**
     * [CHA3-74]: fail loudly, naming every control that did NOT diverge — the harness's own
     * self-test failing rather than a silently-passed sweep proving nothing (mirrors
     * `PartitionFaultTest`'s [CHA1-63]).
     */
    fun assertAllDiverge(seed: Long) {
        val results = selfTest(seed)
        val inert = results.filterNot { it.diverged }
        check(inert.isEmpty()) {
            "churn controls self-test failed at seed $seed: the following controls did NOT diverge, " +
                "so their properties are UNPROVEN: ${inert.map { it.name }}; full results: $results"
        }
    }
}
