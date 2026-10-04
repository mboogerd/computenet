package civictech.compmodel

import civictech.compmodel.composite.FlipModel
import civictech.compmodel.composite.FlipVariant
import civictech.compmodel.composite.PromLeaf
import civictech.compmodel.composite.PromotionModel
import civictech.compmodel.composite.PromotionVariant
import civictech.compmodel.composite.RegionModel
import civictech.compmodel.composite.RegionVariant
import civictech.compmodel.composite.RelocationModel
import civictech.compmodel.composite.RelocationVariant
import civictech.compmodel.composite.ReplicaSetModel
import civictech.compmodel.composite.ReplicaVariant
import org.junit.jupiter.api.Test

/** Repartition flip (COH §3.2, PLP §5.6) with a crash of the router or a shard at every step. */
class FlipTest {
    private val fixed = FlipVariant(abortFencesFirst = true)

    @Test fun `FINDING FLIP-1 abort before the fence reaches A drops a parked R-slice at A`() {
        diverges("finding/FLIP-1", FlipModel(FlipVariant()), "silent loss")
    }

    @Test fun `flip with fence-first abort keeps every invariant under router and shard crashes at every step`() {
        holds("flip/design+fix", FlipModel(fixed))
    }

    @Test fun `B2 confirmed - releasing before B acknowledges Committed (5820e5c1 text) loses a parked slice`() {
        diverges("flip/B2-release-before-committed", FlipModel(fixed.copy(releaseAfterCommittedAck = false)), "silent loss")
    }

    @Test fun `with control on the management band, release right after COMMIT is safe`() {
        holds("flip/preempting-control", FlipModel(fixed.copy(releaseAfterCommittedAck = false, asyncControl = false)))
    }

    @Test fun `control d2lue - volatile flip state loses parked frames`() {
        diverges("control/d2lue", FlipModel(fixed.copy(volatileRouter = true)), "silent loss")
    }

    @Test fun `control - gainer R scope from the loser's high-water suppresses a parked frame`() {
        diverges("control/flip-loser-hw", FlipModel(fixed.copy(gainerCursorFromLoserHighWater = true)), "silent loss")
    }

    @Test fun `control - one unscoped cursor drops the parked slice of a split boundary frame`() {
        diverges("control/flip-unscoped", FlipModel(fixed.copy(unscoped = true)), "silent loss")
    }

    @Test fun `control 8g7kg - moved-in state as an acting catch-up re-fires the moved range`() {
        diverges("control/8g7kg", FlipModel(fixed.copy(handoffActs = true)), "I2 effect")
    }
}

/** Promotion swap (COH §3.4, PLP §7 T0/T1/T2 rows), crash and rollback at every phase. */
class PromotionTest {
    private val recheck = PromotionVariant(recheckOnRelease = true)

    @Test fun `FINDING SWAP-1 Swap's held buffer below X lets a duplicate pass X twice`() {
        diverges("finding/SWAP-1", PromotionModel(PromotionVariant(leaf = PromLeaf.EFFECT)), "I2 effect")
    }

    @Test fun `with X re-check on release, T1 and T2 promotions of an Effectful leaf keep every invariant`() {
        holds("promotion/effect-T1", PromotionModel(recheck))
        holds("promotion/effect-T2-same-identity", PromotionModel(recheck.copy(tier = 2)))
        holds("promotion/effect-T2-other-identity-effectFrom-COMMIT", PromotionModel(recheck.copy(tier = 2, candidateIdentity = 2)))
    }

    @Test fun `T1 and T2 promotions of a relay keep positions (T2 without rollback after COMMIT)`() {
        holds("promotion/relay-T1", PromotionModel(recheck.copy(leaf = PromLeaf.RELAY)))
        holds("promotion/relay-T2", PromotionModel(recheck.copy(leaf = PromLeaf.RELAY, tier = 2, rollbackAfterCommit = false)))
    }

    @Test fun `FINDING SWAP-2 rollback between COMMIT and RETIRE of a T2 swap resumes the incumbent on a superseded lane`() {
        diverges("finding/SWAP-2", PromotionModel(recheck.copy(leaf = PromLeaf.RELAY, tier = 2)), "lost downstream")
    }

    @Test fun `control lzfr0 - T2 ReBaseline supersedes the candidate's lane`() {
        diverges("control/lzfr0", PromotionModel(recheck.copy(leaf = PromLeaf.RELAY, tier = 2, rollbackAfterCommit = false, supersedeCandidateLane = true)), "lost downstream")
    }

    @Test fun `control - unlogged swap window`() {
        diverges("control/unlogged-swap", PromotionModel(recheck.copy(leaf = PromLeaf.RELAY, tier = 2, rollbackAfterCommit = false, unloggedWindow = true)), "lost downstream")
    }

    @Test fun `control - candidate dedup starting empty`() {
        diverges("control/dedup-empty", PromotionModel(recheck.copy(tier = 2, candidateIdentity = 2, rollbackAfterCommit = false, candidateDedupStartsEmpty = true)), "I-P3 gap")
    }

    @Test fun `FINDING F9-X an X-suppressed replay does not re-derive an Effectful leaf's emission`() {
        diverges("finding/F9-X", PromotionModel(PromotionVariant(leaf = PromLeaf.EFFECT_EMIT, recheckOnRelease = true)), "lost downstream")
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

/** Glitch-free region (COH §3.3, spec 34:163-174). */
class RegionTest {
    @Test fun `atomic region suspend, contagious veto and migrate with captured partial waves`() {
        holds("region/design", RegionModel())
        holds("region/veto", RegionModel(RegionVariant(m2NonSuspendable = true)))
    }

    @Test fun `control - member-by-member suspend leaves the region half parked`() {
        diverges("control/region-sequential", RegionModel(RegionVariant(sequentialSuspend = true, m2NonSuspendable = true)), "region atomicity")
    }

    @Test fun `control 5jhg3 - join drops partial waves on migrate`() {
        diverges("control/5jhg3", RegionModel(RegionVariant(joinDropsPartialOnMigrate = true)), "silent loss")
    }

    @Test fun `FINDING REGION-1 atomic suspension does not exclude a partial-diamond stall`() {
        diverges("finding/REGION-1", RegionModel(RegionVariant(checkNoPartialDiamondStall = true)), "partial-diamond stall")
        holds("finding/REGION-1-wave-boundary", RegionModel(RegionVariant(checkNoPartialDiamondStall = true, suspendAtWaveBoundary = true)))
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
