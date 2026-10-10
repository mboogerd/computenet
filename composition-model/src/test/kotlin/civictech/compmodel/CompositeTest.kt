package civictech.compmodel

import civictech.compmodel.composite.FlipModel
import civictech.compmodel.composite.FlipVariant
import civictech.compmodel.composite.IData
import civictech.compmodel.composite.PromLeaf
import civictech.compmodel.composite.PromotionModel
import civictech.compmodel.composite.PromotionVariant
import civictech.compmodel.composite.RegionModel
import civictech.compmodel.composite.RegionVariant
import civictech.compmodel.composite.RelocationModel
import civictech.compmodel.composite.RelocationVariant
import civictech.compmodel.composite.ReplicaSetModel
import civictech.compmodel.composite.ReplicaVariant
import civictech.compmodel.check.Explorer
import civictech.compmodel.check.Report
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Repartition flip (COH §3.2, PLP §5.6) with send, shard acceptance, sync (STABLE), STABLE ack,
 * R6, drain ack and R7 as separate steps and a crash of the router or a shard between any two.
 */
class FlipTest {
    private val design = FlipVariant()

    /**
     * Exhaustive configurations. Each covers a crash of one party at every step; crashes of both
     * parties in one run are covered by seeded walks (`RandomWalkTest`), because the product
     * space is out of exhaustive reach (> 4 M states at two frames).
     */
    private fun shardCrash(v: FlipVariant, frames: Int = 3) = FlipModel(v, routerCrashes = 0, shardCrashes = 1, frames = frames)
    private fun routerCrash(v: FlipVariant) = FlipModel(v, routerCrashes = 1, shardCrashes = 0, frames = 2)

    @Test fun `flip as revised keeps every invariant with a shard crash at every step (3 frames)`() {
        holds("flip/design/shard-crash", shardCrash(design))
    }

    @Test fun `flip as revised keeps every invariant with a router crash at every step (2 frames)`() {
        holds("flip/design/router-crash", routerCrash(design))
    }

    @Test fun `flip as revised keeps every invariant with BATCHED shard streams (crash loses the unsynced tail)`() {
        val m = shardCrash(design.copy(batched = true), frames = 2)
        holds("flip/design/batched-shard-crash", m)
        // Non-vacuity: a shard crash with a released slice accepted but unsynced is explored.
        val reached = Explorer.reach(m) { s ->
            s.shardCrash == 1 && listOf(s.a, s.b).any { sh -> sh.log.drop(sh.synced).any { it is IData && it.released } }
        }
        withClue("a released slice in a shard's unsynced tail, crash still available, must be reachable") { (reached != null) shouldBe true }
    }

    @Test fun `flip as revised keeps every invariant with a shard crash inside the act-to-X-record window`() {
        holds("flip/design/act-window-shard-crash", shardCrash(design.copy(actWindow = true), frames = 2))
    }

    /** FLIP-1's configuration is explored: ABORT decided before A has processed the fence. */
    @Test fun `FINDING FLIP-1 fixed - abort before the fence waits for FlipSettled, the old text diverges`() {
        val reached = Explorer.reach(shardCrash(design)) { s ->
            s.router.decision == 'A' && !s.a.settledFor && s.router.parked.isNotEmpty() && s.a.mem.highWater > s.router.pBegin
        }
        withClue("FLIP-1's configuration (abort before A settles, A's cursor past p_begin) must be reachable") { (reached != null) shouldBe true }
        Report.line("[finding/FLIP-1-config-reached] ${reached!!.size} steps: ${reached.joinToString(" | ")}")
        diverges("control/FLIP-1-old-text", shardCrash(design.copy(abortReleasesBeforeSettled = true)), "silent loss")
    }

    @Test fun `control blocker-1 - R6 advancing on send loses a released slice at a shard crash`() {
        val e = diverges("control/R6-on-send", shardCrash(design.copy(releaseCursorOnSend = true)), "silent loss")
        e.counterexample!!.trace.any { it.startsWith("CRASH shard") } shouldBe true
    }

    @Test fun `B2 - releasing before B acknowledges Committed (5820e5c1 text) loses a parked slice`() {
        diverges("control/B2-release-before-committed", shardCrash(design.copy(releaseAfterCommittedAck = false)), "silent loss", "I2 effect")
    }

    @Test fun `with control on the management band, release right after COMMIT is safe`() {
        holds("flip/preempting-control", shardCrash(design.copy(releaseAfterCommittedAck = false, asyncControl = false)))
    }

    @Test fun `control d2lue - volatile flip state loses parked frames`() {
        diverges("control/d2lue", routerCrash(design.copy(volatileRouter = true)), "silent loss")
    }

    @Test fun `control - gainer R scope from the loser's high-water suppresses a parked frame`() {
        diverges("control/flip-loser-hw", shardCrash(design.copy(gainerCursorFromLoserHighWater = true)), "silent loss")
    }

    @Test fun `control - one unscoped cursor drops the parked slice of a split boundary frame`() {
        diverges("control/flip-unscoped", shardCrash(design.copy(unscoped = true)), "silent loss")
    }

    @Test fun `control 8g7kg - moved-in state as an acting catch-up re-fires the moved range`() {
        diverges("control/8g7kg", shardCrash(design.copy(handoffActs = true)), "I2 effect")
    }
}

/**
 * Promotion swap (COH §3.4 as revised, PLP §7 T0/T1/T2 rows): PRECHECK, PREPARE, COMMIT,
 * green, each release, RETIRE and rollback as separate steps, a crash between any two.
 */
class PromotionTest {
    private val effect = PromotionVariant(leaf = PromLeaf.EFFECT)
    private val relay = PromotionVariant(leaf = PromLeaf.RELAY)
    private val emit = PromotionVariant(leaf = PromLeaf.EFFECT_EMIT)

    /** Two frames exhaustively (three exceed 4 M states for an emitting leaf); five under walks. */
    private fun prom(v: PromotionVariant) = PromotionModel(v, frames = 2)

    @Test fun `T1 and T2 promotions of an Effectful leaf keep every invariant`() {
        holds("promotion/effect-T1", prom(effect))
        holds("promotion/effect-T2-same-identity", prom(effect.copy(tier = 2)))
        holds("promotion/effect-T2-other-identity-effectFrom-COMMIT", prom(effect.copy(tier = 2, candidateIdentity = 2)))
    }

    @Test fun `T1 and T2 promotions of a relay keep positions`() {
        holds("promotion/relay-T1", prom(relay))
        holds("promotion/relay-T2", prom(relay.copy(tier = 2)))
    }

    @Test fun `T1 and T2 promotions of an emitting Effectful leaf with logged outputs keep every invariant`() {
        holds("promotion/effect-emit-T1", prom(emit))
        holds("promotion/effect-emit-T2", prom(emit.copy(tier = 2)))
    }

    /** SWAP-1's configuration is explored: a duplicate accepted while the original is parked. */
    @Test fun `FINDING SWAP-1 fixed - P parks outside X, Swap holding below X (old text) diverges`() {
        val reached = Explorer.reach(prom(effect)) { s -> s.parked.size != s.parked.toSet().size }
        withClue("a duplicate parked beside its original must be reachable") { (reached != null) shouldBe true }
        Report.line("[finding/SWAP-1-config-reached] ${reached!!.size} steps: ${reached.joinToString(" | ")}")
        diverges("control/SWAP-1-old-text", prom(effect.copy(swapHoldsBelowX = true)), "I2 effect")
    }

    @Test fun `FINDING SWAP-2 fixed - no rollback after COMMIT, the old text resumes the incumbent on a superseded lane`() {
        diverges("control/SWAP-2-old-text", prom(relay.copy(tier = 2, rollbackAfterCommit = true)), "lost downstream")
    }

    @Test fun `FINDING F9-X fixed - outputs through an X-suppressed inlet are logged, unlogged (old text) diverges`() {
        diverges("control/F9-X-old-text", prom(emit.copy(unloggedEffectOutputs = true)), "lost downstream")
    }

    @Test fun `control lzfr0 - T2 ReBaseline supersedes the candidate's lane`() {
        diverges("control/lzfr0", prom(relay.copy(tier = 2, supersedeCandidateLane = true)), "lost downstream")
    }

    @Test fun `control - unlogged swap window`() {
        diverges("control/unlogged-swap", prom(relay.copy(tier = 2, unloggedWindow = true)), "lost downstream")
    }

    @Test fun `control - candidate dedup starting empty`() {
        diverges("control/dedup-empty", prom(effect.copy(tier = 2, candidateIdentity = 2, candidateDedupStartsEmpty = true)), "I-P3 gap")
    }
}

/** Replica-set effect authority and failover (COH §3.1). */
class ReplicaSetTest {
    @Test fun `failover acts on every retained position above the fold, at most one duplicate per unpublished act`() {
        holds("replica/design", ReplicaSetModel())
    }

    @Test fun `exact witness gives exactly once`() { holds("replica/exact-witness", ReplicaSetModel(ReplicaVariant(exactWitness = true))) }

    @Test fun `control - follower suppression advancing disposed omits positions at takeover`() {
        diverges("control/acted-vs-received", ReplicaSetModel(ReplicaVariant(followerAdvancesDisposed = true)), "omission")
    }

    @Test fun `control - no follower retention omits positions at takeover`() {
        diverges("control/no-retention", ReplicaSetModel(ReplicaVariant(noRetention = true)), "omission")
    }
}

/** Glitch-free region (COH §3.3, spec 34:163-174): no partial region park, no loss of partial-wave custody. */
class RegionTest {
    @Test fun `atomic region suspend, contagious veto and migrate with captured partial waves`() {
        holds("region/design", RegionModel())
        holds("region/veto", RegionModel(RegionVariant(m2NonSuspendable = true)))
    }

    /**
     * REGION-1 withdrawn: the configuration it flagged (whole region parked while J's `A` holds
     * a partial wave) is reachable and keeps both properties; it is custody, not a stall.
     */
    @Test fun `REGION-1 withdrawn - whole-region park with a partial wave in J's custody is explored and holds`() {
        val reached = Explorer.reach(RegionModel()) { s -> s.s1 && s.s2 && s.joinBuf.isNotEmpty() }
        withClue("the parked-with-partial-wave configuration must be reachable") { (reached != null) shouldBe true }
        Report.line("[region/REGION-1-config-reached] ${reached!!.joinToString(" | ")}")
    }

    @Test fun `control - member-by-member suspend leaves the region half parked`() {
        diverges("control/region-sequential", RegionModel(RegionVariant(sequentialSuspend = true, m2NonSuspendable = true)), "region atomicity")
    }

    @Test fun `control 5jhg3 - join drops partial waves on migrate`() {
        diverges("control/5jhg3", RegionModel(RegionVariant(joinDropsPartialOnMigrate = true)), "silent loss")
    }
}

/** Relocation H4 with a crash of either host at every step (COH §5.1). */
class RelocationTest {
    @Test fun `never two live holders and no lost frame`() { holds("relocation/design", RelocationModel()) }

    @Test fun `control - no source fence gives two live holders`() {
        diverges("control/no-source-fence", RelocationModel(RelocationVariant(noSourceFence = true)), "two live holders")
    }

    @Test fun `control - target activated at PREPARE`() {
        diverges("control/activate-on-prepare", RelocationModel(RelocationVariant(activateOnPrepare = true)), "I3")
    }
}
