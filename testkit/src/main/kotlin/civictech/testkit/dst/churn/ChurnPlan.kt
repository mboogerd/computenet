package civictech.testkit.dst.churn

import civictech.testkit.dst.ChurnEvent
import civictech.testkit.dst.DepartEvent
import civictech.testkit.dst.Fault
import civictech.testkit.dst.FaultPlan
import civictech.testkit.dst.JoinEvent
import civictech.testkit.dst.ReassignEvent
import civictech.testkit.dst.ReductionStrategies
import civictech.testkit.dst.ReductionStrategy
import civictech.testkit.dst.RejoinEvent

/**
 * One scheduled write of the workload script ([CHA3-05]'s op-script length and
 * write-concurrency fraction).
 *
 * A write is **not** a [Fault] and deliberately is not modelled as one: it is the workload the
 * churn is adversarial *against*, and `doc/dst-rig.md` §1 seam 4 is explicit that a graph
 * builder injects its own workload from a step hook, because `DstRun.execute()` owns the drive
 * loop. So the plan carries the schedule as data and the graph builder plays it.
 *
 * [ordinal] is the write's position in the script, stable across the whole plan, so a check
 * can name "the 7th write" without depending on which step it landed on.
 */
data class ChurnWrite(val atStep: Int, val peer: String, val ordinal: Int)

/**
 * A generated churn plan: a **thin, ordered view** over churn events, any CHA1 faults folded
 * in alongside them, and the workload script they run against.
 *
 * ## What this type is not
 *
 * It is not a second plan format. [CHA3-07] forbids CHA3 shipping its own artifact,
 * replay or shrink machinery, so everything downstream of generation goes through
 * [toFaultPlan] and is the rig's: `FaultPlan(seed, faults)` is what a `DstRun` takes, what a
 * `DstArtifact` records, and what a `PlanShrinker` reduces. `ChurnPlan` exists only above that
 * line — it is what a *generator* produces and a *graph builder* reads, and it holds the two
 * things a bare `FaultPlan` has nowhere to put: the peer roster and the write schedule.
 *
 * ## Ordering
 *
 * [events] is ordered by activation step, and within a step by generation order (a membership
 * event before the reassignment derived from it). That order is part of the plan's value: two
 * plans with the same events in a different order are different plans, and `equals` says so.
 *
 * @property seed the one run seed ([CHA3-06]) — the same field [FaultPlan] holds, carried here
 *   so [toFaultPlan] cannot mint a different one.
 * @property config the configuration this plan was generated from. Kept so a report can say
 *   what was asked for, and so `(seed, config)` — the whole input of [CHA3-01] — is
 *   recoverable from the plan.
 * @property peers the mesh roster, in generation order. Every [ChurnEvent.peer] is one of
 *   these; the graph builder declares a [civictech.testkit.dst.PeerHandle] per name.
 * @property faults CHA1 faults folded into the same plan — a partition, a crash, a journal
 *   surgery. Empty by default: the generator produces churn, and a suite composes the rest.
 */
data class ChurnPlan(
    val seed: Long,
    val config: ChurnConfig,
    val peers: List<String>,
    val events: List<ChurnEvent>,
    val writeSchedule: List<ChurnWrite> = emptyList(),
    val faults: List<Fault> = emptyList(),
) {
    init {
        val unknown = events.map { it.peer }.toSet() - peers.toSet()
        require(unknown.isEmpty()) {
            "churn events name peers that are not on the roster: ${unknown.sorted()}; roster: $peers"
        }
        require(events.all { it.atStep < config.stepBudget }) {
            "every activation step must be inside the plan's horizon (stepBudget=${config.stepBudget}); " +
                "offending: ${events.filter { it.atStep >= config.stepBudget }.map { "${it.id}@${it.atStep}" }}"
        }
    }

    /** The activation horizon this plan was generated against ([CHA3-02]). */
    val stepBudget: Int get() = config.stepBudget

    /** Fold CHA1 faults in alongside the churn. Returns a new plan; nothing here mutates. */
    fun withFaults(vararg extra: Fault): ChurnPlan = copy(faults = faults + extra)

    /**
     * Compile to the rig's own plan — the single [FaultPlan] a `DstRun`, a `DstArtifact` and a
     * `PlanShrinker` all take unchanged ([CHA3-07]).
     *
     * Churn events come first, then the folded CHA1 faults. `FaultPlan`'s own `init` rejects a
     * duplicate id across both lists, so a suite that names a CHA1 fault after a churn event
     * finds out here rather than by watching two faults merge their firing counts.
     */
    fun toFaultPlan(): FaultPlan = FaultPlan(seed, events + faults)

    /** One line for a report: what the adversary is, without the whole event list. */
    fun summary(): String =
        "churn(seed=$seed, peers=${peers.size}, events=${events.size}, writes=${writeSchedule.size}" +
            (if (faults.isEmpty()) "" else ", +${faults.size} CHA1 fault(s)") +
            ", horizon=$stepBudget)"
}

/**
 * The churn-specific shrink moves ([CHA3-48] mechanics).
 *
 * ## What this is, and what it deliberately is not
 *
 * It is a set of `(kind, param, target)` bindings for the rig's own
 * [ReductionStrategies.numericParamToward] — nothing more. **The seed is not mentioned
 * anywhere here**, and that is not an omission: `PlanShrinker` holds it constant with its own
 * `require`, loudly, and re-implementing that guard in CHA3 would give a reader two places to
 * check and one of them could rot. [ChurnFaultCodecTest] asserts the rig's guard fires rather
 * than asserting a guard of ours.
 *
 * ## Which parameters shrink, and toward what
 *
 * `numericParamToward`'s whole contract is that *direction is supplied by whoever knows the
 * semantics* — the rig cannot infer that a smaller number is a smaller adversary, because
 * usually it is not. So here are the four decisions, with the argument for each:
 *
 *  - **`churn-reassign`.`epoch` → 0.** The kernel's assignment register merges by epoch-max
 *    (`InstanceSet`'s `epochMaxUnion`), so a reassignment at a *lower* epoch is admitted less
 *    often and, at a low enough epoch, not at all. Lower is strictly weaker. Unconditionally
 *    sound.
 *  - **`churn-join`.`atStep` → the horizon.** A peer that joins later is a member for less of
 *    the run, so it participates in less of the workload. Later is weaker.
 *  - **`churn-depart`.`atStep` → the horizon.** Same shape: a departure closer to quiescence
 *    overlaps less in-flight work. Pushed far enough it stops firing entirely, which is the
 *    same reduction `dropFaults` would have made — a legitimate outcome, not a cheat.
 *  - **`churn-reassign`.`atStep` → the horizon.** Same argument as the departure it usually
 *    accompanies.
 *
 * And one deliberate **exclusion**, because [CHA3-48] asks for the direction to be decided and
 * documented rather than guessed:
 *
 *  - **`churn-rejoin`.`atStep` has no binding.** Its direction is the opposite of the others'
 *    and is not even stable: a peer that rejoins *later* has been absent *longer*, which is
 *    more adversarial, not less — but pushed past the horizon it never rejoins at all, which
 *    is less. The function is not monotone, so no single target is honest, and a binding
 *    invented for symmetry would let the shrinker report a "reduction" that made the run
 *    harsher. A suite that knows its own graph's answer supplies one with [atStepToward].
 *
 *    The *other* direction is unsound too, for a different reason, and it is worth writing
 *    down because it is the one a later reader will reach for first: toward `0` looks
 *    monotone — earlier rejoin, shorter absence, milder run — but a rejoin shrunk below the
 *    departure it answers is not a smaller adversary, it is an incoherent plan (a peer
 *    returning before it left), and **nothing rejects it**. `RejoinEvent`'s only `require` is
 *    `atStep >= 0`; the codec has no view of the departure, so `numericParamToward` would
 *    build the candidate happily and the shrinker would grade whatever the graph does with
 *    it. So neither `0` nor the horizon is a target this object can state honestly, and the
 *    absence is the finding rather than a gap in it. What is *not* lost by leaving it
 *    unbound: [ReductionStrategies.dropFaults] still removes rejoin events whole, so the
 *    shrinker is never blind to them — it simply cannot move one.
 *
 * Note what `atStep` bindings cost: `numericParamToward` never proposes a value that leaves
 * the plan unbuildable (the codec's own `require`s reject those and the candidate is skipped),
 * but it *can* propose one past the run's quiesce point. That is fine — the reduction is
 * re-verified by a full re-run like every other, and a candidate that no longer reproduces is
 * discarded.
 */
object ChurnReductions {

    /**
     * One declared numeric shrink knob: which fault [kind]'s [param] moves toward [target],
     * and [why] that direction is the less adversarial one.
     *
     * [why] is a field rather than a comment on purpose. The direction *is* the semantic
     * content of a `numericParamToward` binding, and a binding whose argument lives only in a
     * commit message is one nobody can re-check.
     */
    data class Knob(val kind: String, val param: String, val target: Double, val why: String)

    /**
     * The declared knobs for a plan whose horizon is [stepBudget]. See the type KDoc for the
     * argument behind each, and for why `churn-rejoin`.`atStep` is absent.
     */
    fun declaredFor(stepBudget: Int): List<Knob> = listOf(
        Knob(
            ReassignEvent.CODEC.kind,
            "epoch",
            0.0,
            "the assignment register merges by epoch-max, so a lower epoch is admitted less often and " +
                "eventually not at all — strictly weaker",
        ),
        Knob(
            JoinEvent.CODEC.kind,
            "atStep",
            stepBudget.toDouble(),
            "a peer that joins later is a member for less of the run, so it participates in less of the workload",
        ),
        Knob(
            DepartEvent.CODEC.kind,
            "atStep",
            stepBudget.toDouble(),
            "a departure closer to quiescence overlaps less in-flight work",
        ),
        Knob(
            ReassignEvent.CODEC.kind,
            "atStep",
            stepBudget.toDouble(),
            "same as the departure it accompanies: later reassignment, less in-flight work to disturb",
        ),
    )

    /**
     * The default churn strategy: [ReductionStrategies.dropFaults] first — the one
     * unconditionally sound reduction — then every knob of [declaredFor].
     */
    fun strategyFor(stepBudget: Int): ReductionStrategy = ReductionStrategies.of(
        ReductionStrategies.dropFaults,
        *declaredFor(stepBudget)
            .map { ReductionStrategies.numericParamToward(it.kind, it.param, it.target) }
            .toTypedArray(),
    )

    /** [strategyFor] against a plan's own horizon. */
    fun strategyFor(plan: ChurnPlan): ReductionStrategy = strategyFor(plan.stepBudget)

    /**
     * An explicit `atStep` binding for one churn [kind], for a suite that knows a direction
     * this object refuses to guess — `churn-rejoin` above all. The caller states [target]
     * because the caller is the one who knows.
     */
    fun atStepToward(kind: String, target: Double): ReductionStrategy =
        ReductionStrategies.numericParamToward(kind, "atStep", target)

    /** The four churn kinds, for a suite that wants to assert coverage over all of them. */
    val kinds: List<String> = listOf(
        JoinEvent.CODEC.kind,
        RejoinEvent.CODEC.kind,
        DepartEvent.CODEC.kind,
        ReassignEvent.CODEC.kind,
    )
}
