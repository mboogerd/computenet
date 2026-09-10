package civictech.cell.replication

/**
 * The election **posture** of a [SingleWriterReplication] engine (spec 42
 * §Single-writer replication; epic MEM1, decision f7h.4-D1).
 *
 * [MEM1-05]: automatic election is **opt-in**. [Manual] is what every engine
 * built before this type did and still does — leadership changes only when
 * somebody calls [SingleWriterReplication.designateLeader] (or a peer's mark
 * is mirrored in), and the engine never mints a [LeaderMark] of its own. An
 * engine constructed without an `election` argument is [Manual], so no
 * existing construction changes behaviour.
 *
 * [MEM1-30]: sealed, so a third arm — a *park* posture that refuses to elect
 * and holds writes instead — can be added later without any call site
 * growing a boolean.
 */
sealed interface LeaderElection {

    /**
     * No automatic election ([MEM1-05], the default). The engine arms
     * nothing, counts nothing, and [SingleWriterReplication.observe] is a
     * no-op; `designateLeader` remains the only path to
     * `LocationRegistry.markLeader`.
     */
    object Manual : LeaderElection

    /**
     * Elect by claiming the next epoch once the folded leader has been
     * missing for [window] membership observations (f7h.4-D3): the claimant
     * folds `LeaderMark(logicalId, foldedEpoch + 1, self)` through the same
     * single fold a manual designation uses — there is no second write path
     * (f7h.4-D4).
     */
    data class EpochClaim(val window: DetectionWindow) : LeaderElection
}

/**
 * How long a leader must be missing before [LeaderElection.EpochClaim]
 * claims — measured in **membership observations**, never in time
 * ([MEM1-25]).
 *
 * An observation is a membership event this engine witnesses for the logical
 * id (a publish or an unpublish) or an explicit
 * [SingleWriterReplication.observe] call. That is the same cadence discipline
 * [Replication.heartbeat] already established: the kernel ships the *step*,
 * the caller — a test, a DST hook, an application's supervisor — decides when
 * steps happen. Nothing in this package reads a clock, starts a thread, or
 * schedules a timer, and `LeaderElectionTest`'s identifier fence makes that a
 * build-breaking fact rather than a promise.
 *
 * [observations] must be at least 1: a window of 0 would mean "claim before
 * anything was observed", which is not a detection rule at all.
 */
data class DetectionWindow(val observations: Int) {
    init {
        require(observations >= 1) {
            "DetectionWindow requires at least one observation (got $observations) — " +
                "a zero-observation window claims before anything has been witnessed ([MEM1-25])"
        }
    }
}
