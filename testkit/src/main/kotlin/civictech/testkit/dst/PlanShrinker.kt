package civictech.testkit.dst

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One candidate reduction of a fault plan: the [plan] to try, and a [description] that names
 * what was reduced so an accepted or discarded attempt can be read back afterwards.
 *
 * A `Reduction` is a *proposal*, never an edit. Nothing about producing one asserts that the
 * reduced plan still reproduces the failure — that is [PlanShrinker]'s re-verification
 * ([CHA1-36]) and nothing else.
 */
data class Reduction(val description: String, val plan: FaultPlan)

/**
 * Proposes candidate reductions of a plan ([CHA1-35]).
 *
 * ## Why the rig ships only one built-in strategy
 *
 * "Simpler" is a semantic judgement about a *field*, and the rig does not have it. Dropping a
 * fault is the one reduction that is unconditionally simpler — a plan with fewer adversaries
 * is a smaller claim about the system, whatever the adversaries were — and that is
 * [ReductionStrategies.dropFaults], the default.
 *
 * Every other reduction the epic names is direction-dependent, and a semantics-blind shrinker
 * gets the direction wrong half the time. Take a drop-partition configured with
 * `fromStep = 2`: moving it *later* makes the fault touch less traffic and is a genuine
 * reduction, while moving it *earlier* — the direction a blind "shrink numbers toward zero"
 * ladder would try first — destroys strictly more frames and yields a *larger* adversary
 * wearing the word "shrunk". Both still reproduce, so re-verification cannot tell them apart;
 * only the field's meaning can.
 *
 * So direction is supplied by whoever knows it: a fault class, or the suite that configured
 * it, builds a strategy with [ReductionStrategies.numericParamToward], naming the parameter
 * and the value it becomes less adversarial toward. That covers the epic's examples —
 * shortening a partition window (`until` toward `from`), lowering a duplication probability
 * (toward `0.0`), moving an activation later (`from` toward the step budget) — without the rig
 * guessing.
 */
fun interface ReductionStrategy {

    /**
     * Candidate reductions of [plan], most aggressive first.
     *
     * Called again after every accepted reduction, against the newly accepted plan, so a
     * strategy is a function of the current plan rather than a fixed schedule. [artifact] is
     * available for context a reduction needs but a plan does not carry — the step budget, the
     * graph id — and must not be used to change the seed: see [PlanShrinker]'s seed guard.
     */
    fun candidates(plan: FaultPlan, artifact: DstArtifact): List<Reduction>
}

/** The reduction strategies the rig ships, and the composition operator over them. */
object ReductionStrategies {

    /**
     * Drop one fault, for every fault in the plan. The only unconditionally sound reduction,
     * and the default.
     *
     * Emitted in plan order. The greedy loop in [PlanShrinker] re-asks after each acceptance,
     * so dropping fault *i* does not prevent fault *j* from being dropped on the next pass.
     */
    val dropFaults: ReductionStrategy = ReductionStrategy { plan, _ ->
        plan.faults.map { fault -> Reduction("drop fault \"${fault.id}\"", plan.without(fault.id)) }
    }

    /**
     * Move one numeric parameter of every fault of [kind] toward [target], by binary search.
     *
     * This is the semantics-aware half of the strategy seam: the caller states which parameter
     * and which direction is *less* adversarial, because the rig cannot know (see
     * [ReductionStrategy]). Candidates are emitted most-aggressive-first — [target] itself,
     * then the midpoint, then three-quarters of the way back to the current value — and
     * [PlanShrinker]'s greedy loop re-asks after each acceptance, so an accepted jump narrows
     * the interval and the sequence converges on the extreme value that still reproduces.
     *
     * The parameter is read and rewritten on the fault's *artifact record* — the JSON a
     * [FaultCodec] produced — and the modified record is decoded back into a live fault. A
     * candidate whose parameters the codec refuses (a window with `until <= from`, say) is
     * silently skipped: an unbuildable plan is not a reduction that failed to reproduce, it is
     * not a plan at all.
     *
     * Integer-valued parameters stay integers; the ladder is truncated, so a search between
     * adjacent values terminates rather than proposing the same value forever.
     */
    fun numericParamToward(
        kind: String,
        param: String,
        target: Double,
        steps: Int = 3,
    ): ReductionStrategy = ReductionStrategy { plan, _ ->
        buildList {
            plan.faults.forEachIndexed { index, fault ->
                val record = runCatching { FaultCodecs.encode(fault) }.getOrNull() ?: return@forEachIndexed
                if (record.kind != kind) return@forEachIndexed
                val primitive = record.params[param]?.jsonPrimitive ?: return@forEachIndexed
                val current = primitive.doubleOrNull ?: return@forEachIndexed
                val integral = '.' !in primitive.content && 'e' !in primitive.content.lowercase()

                candidateValues(current, target, steps, integral).forEach { value ->
                    val rebuilt = runCatching {
                        FaultCodecs.decode(record.copy(params = withParam(record.params, param, value, integral)))
                    }.getOrNull() ?: return@forEach
                    add(
                        Reduction(
                            "move \"${fault.id}\".$param from ${render(current, integral)} to " +
                                "${render(value, integral)} (toward ${render(target, integral)})",
                            plan.copy(faults = plan.faults.toMutableList().also { it[index] = rebuilt }),
                        ),
                    )
                }
            }
        }
    }

    /** Try [strategies] in order; the first that yields an accepted reduction wins the pass. */
    fun of(vararg strategies: ReductionStrategy): ReductionStrategy = ReductionStrategy { plan, artifact ->
        strategies.flatMap { it.candidates(plan, artifact) }
    }

    /** [dropFaults] — see [ReductionStrategy] for why nothing semantic is in the default. */
    val default: ReductionStrategy get() = dropFaults

    private fun candidateValues(current: Double, target: Double, steps: Int, integral: Boolean): List<Double> {
        if (current == target) return emptyList()
        val values = LinkedHashSet<Double>()
        for (i in 0 until steps.coerceAtLeast(1)) {
            val fraction = 1.0 / (1 shl i) // 1, 1/2, 1/4, ...
            val raw = current + (target - current) * fraction
            val value = if (integral) truncateToward(raw, current) else raw
            if (value != current) values += value
        }
        return values.toList()
    }

    /** Round an integral candidate away from [current], so a gap of one still produces a move. */
    private fun truncateToward(raw: Double, current: Double): Double =
        if (raw > current) Math.ceil(raw) else Math.floor(raw)

    private fun withParam(params: JsonObject, param: String, value: Double, integral: Boolean): JsonObject =
        JsonObject(params.toMutableMap().also { it[param] = numberOf(value, integral) })

    private fun numberOf(value: Double, integral: Boolean): JsonPrimitive =
        if (integral) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    private fun render(value: Double, integral: Boolean): String =
        if (integral) value.toLong().toString() else value.toString()
}

/**
 * "Does this run still fail the same way?" — [CHA1-36]'s predicate.
 *
 * ## Why this is not `DstReplay.grade`
 *
 * [DstReplay.grade] is the *replay* predicate, and replay re-runs the **same** plan: there,
 * comparing step counts, trace length and trace digest is exactly right, because a difference
 * in any of them means the recorded run was not reproduced.
 *
 * Shrinking runs a **different** plan on purpose. A legitimately reduced plan that still fails
 * the same check will normally quiesce at a different step, emit a different number of trace
 * events and hash to a different digest — so grading a reduction with the replay predicate
 * would report `DIVERGED` for precisely the reductions the shrinker exists to accept, and the
 * shrinker would accept nothing. The two predicates are deliberately different because they
 * answer different questions, and neither is a weakening of the other.
 *
 * What the default keeps is what "the same failing check" can honestly mean across two
 * different plans: the same [DstOutcome] and the same failing-check message. The failing
 * *step* is not compared, for the same reason the digest is not — a shorter plan reaching the
 * same contradiction sooner is the successful case, not a divergence.
 *
 * ## The one thing this asks of a check
 *
 * Comparing the message makes the *wording* of an assertion part of the property's identity,
 * so **a check whose message embeds a run-varying number defeats the shrinker**: every
 * reduction fails with a different message and is discarded as a different failure. Measured
 * while writing `ShrinkerTest`: a check reporting "only 12 of 30 deliveries arrived" rejected
 * a legitimate reduction that failed with "only 18 of 30". Keep the count out of the message
 * — the report and the trace already carry it — or pass [sameOutcome] and accept that it
 * cannot tell two different properties apart.
 */
fun interface FailurePredicate {

    fun reproduces(recorded: ObservedRun, report: DstReport): Boolean

    companion object {

        /** Same outcome, same failing-check message. The default; see the type KDoc. */
        val sameFailingCheck: FailurePredicate = FailurePredicate { recorded, report ->
            report.outcome == recorded.outcome && report.failingCheck?.message == recorded.failingCheck
        }

        /**
         * Outcome only — the escape hatch for a suite whose check message genuinely must carry
         * run-varying values.
         *
         * **Weaker than it looks**: a graph with two properties, or one check that throws for
         * two reasons, will report "the same failure" for a reduction that broke something
         * else. Prefer a stable message.
         */
        val sameOutcome: FailurePredicate = FailurePredicate { recorded, report ->
            report.outcome == recorded.outcome
        }

        /** Additionally pins the failing step — for a suite whose check is step-indexed. */
        val sameFailingCheckAndStep: FailurePredicate = FailurePredicate { recorded, report ->
            sameFailingCheck.reproduces(recorded, report) && report.failingCheck?.step == recorded.failingStep
        }
    }
}

/**
 * What one shrink produced: the plan it settled on, the artifact carrying it, the bookkeeping
 * and the trail.
 *
 * [artifact] is the *original* artifact with [DstArtifact.shrunkPlan] and [DstArtifact.shrink]
 * filled — [DstArtifact.plan] is untouched ([CHA1-37]) and [DstArtifact.seed] is the same
 * `Long` it was ([CHA1-35]).
 *
 * [trail] is one line per attempt, accepted or discarded, in order. It is *not* stored in the
 * artifact: it is diagnosis for the session that ran the shrink, and an artifact is a
 * reproducer rather than a log.
 */
data class ShrinkResult(
    val artifact: DstArtifact,
    val plan: FaultPlan,
    val record: ShrinkRecord,
    val trail: List<String>,
) {
    /** True when the shrinker ran out of budget rather than out of candidates (epic §9 risk 7). */
    val stoppedEarly: Boolean get() = record.stoppedEarly

    /**
     * One line for a report. Says "locally minimal under the strategy" rather than "minimal":
     * the shrinker explores the candidates one strategy proposes, greedily, and a different
     * strategy may well reduce further.
     */
    fun summary(): String =
        "shrink seed=${artifact.seed}: ${artifact.plan.faults.size} -> ${plan.faults.size} faults in " +
            "${record.attempts} attempts (${record.reductionsAccepted} accepted); " +
            if (record.stoppedEarly) {
                "STOPPED EARLY — ${record.stopReason}; this is not a minimum, only where the budget ran out"
            } else {
                "${record.stopReason ?: "no further reduction reproduced the failure"} " +
                    "(locally minimal under the strategy, not proven minimal)"
            }
}

/**
 * Reduces the **fault plan** of a failing run, holding the seed constant and re-verifying
 * every reduction ([CHA1-35], [CHA1-36], [CHA1-37], BS-3, epic §9 risk 7).
 *
 * ## The three things it will not do
 *
 *  - **Vary the seed** ([CHA1-35]). Structurally it cannot: a plan's seed is one field
 *    ([FaultPlan.seed]), the artifact stores it once at the top level and [PlanRecord] has no
 *    seed at all, so a shrunk plan has nothing to disagree with. On top of that, a candidate
 *    whose seed differs from the artifact's is **rejected loudly** rather than skipped — a
 *    [ReductionStrategy] that varies the seed is a broken instrument, not an unlucky
 *    candidate, and a shrinker that quietly ignored it would report a clean shrink over a
 *    strategy that was doing something else entirely.
 *  - **Accept a reduction it did not re-verify** ([CHA1-36]). Every candidate is re-run in
 *    full — same graph, same budget, same check, same seed — and kept only if
 *    [FailurePredicate] says it still fails the same way. There is no cached, predicted or
 *    inferred acceptance path.
 *  - **Claim a minimum it did not reach** (epic §9 risk 7). The search is bounded by
 *    [maxAttempts] and optionally by wall clock, and when a bound ends it,
 *    [ShrinkRecord.stoppedEarly] is `true` with the reason recorded in the artifact. Even when
 *    it runs to exhaustion the claim is *locally* minimal under the strategy given: greedy
 *    search over one strategy's candidates is not a proof of minimality, and the wording in
 *    [ShrinkResult.summary] says so.
 *
 * ## Cost
 *
 * One reduction costs one whole simulation, so a shrink costs `attempts` runs of the failing
 * suite. That is why [maxAttempts] is small by default and why the trail records discarded
 * attempts: a shrink that spent its budget on candidates that never reproduced is a finding
 * about the strategy, not about the system under test.
 */
object PlanShrinker {

    /** Attempts, not reductions: a discarded candidate costs a full run and is counted. */
    const val DEFAULT_MAX_ATTEMPTS: Int = 32

    /**
     * Shrink [artifact]'s plan and return the artifact with the result recorded.
     *
     * @param maxAttempts the hard bound on candidate runs (epic §9 risk 7). Reaching it sets
     *   [ShrinkRecord.stoppedEarly].
     * @param wallClockMillis an optional second bound, checked before each candidate run. A
     *   candidate already running is never interrupted — a half-run simulation has no verdict.
     * @param strategy which reductions to propose; see [ReductionStrategies].
     * @param sameFailure [CHA1-36]'s predicate; see [FailurePredicate] for why it is not
     *   `DstReplay.grade`.
     */
    fun shrink(
        artifact: DstArtifact,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        wallClockMillis: Long? = null,
        strategy: ReductionStrategy = ReductionStrategies.default,
        sameFailure: FailurePredicate = FailurePredicate.sameFailingCheck,
    ): ShrinkResult {
        require(maxAttempts >= 0) { "maxAttempts is a bound on candidate runs, got $maxAttempts" }
        require(artifact.observed.outcome == DstOutcome.FAILED) {
            "only a FAILED run can be shrunk: there is no failure to hold constant in a " +
                "${artifact.observed.outcome} run, so every reduction would trivially \"reproduce\" it"
        }
        require(artifact.driver.claimsReplayReproducibility) {
            "a ${artifact.driver} run cannot be shrunk: shrinking re-verifies each reduction by re-running it, " +
                "and the rig makes no reproducibility claim for that driver ([CHA1-40])"
        }

        val deadline = wallClockMillis?.let { System.nanoTime() + it * 1_000_000 }
        val trail = mutableListOf<String>()
        var best = artifact.plan()
        var attempts = 0
        var accepted = 0
        val visited = mutableSetOf(key(best))

        // Control: the recorded failure must reproduce from the artifact's own plan before any
        // reduction is graded against it. Without this, an artifact that no longer reproduces
        // would shrink to an empty plan and report it as the minimal reproducer.
        val control = execute(artifact, best)
        val controlReport = control.report
        if (controlReport == null || !sameFailure.reproduces(artifact.observed, controlReport)) {
            val why = control.describe()
            trail += "control: the artifact's own plan did NOT reproduce the recorded failure ($why)"
            return finish(
                artifact,
                best,
                ShrinkRecord(
                    attempts = 0,
                    reductionsAccepted = 0,
                    stoppedEarly = true,
                    stopReason = "the artifact's own plan did not reproduce the recorded failure " +
                        "(recorded ${artifact.observed.outcome}" +
                        (artifact.observed.failingCheck?.let { " \"$it\"" } ?: "") + "; re-run $why) — " +
                        "nothing was shrunk, because there was no failure to hold constant",
                ),
                trail,
            )
        }
        trail += "control: the artifact's own plan reproduces the recorded failure"

        var stoppedEarly = false
        var stopReason: String? = null

        search@ while (true) {
            val candidates = strategy.candidates(best, artifact).filterNot { key(it.plan) in visited }
            if (candidates.isEmpty()) {
                stopReason = "no further reduction was proposed"
                break@search
            }

            var acceptedThisPass = false
            for (candidate in candidates) {
                requireSeedHeld(artifact, candidate)
                if (attempts >= maxAttempts) {
                    stoppedEarly = true
                    stopReason = "attempt cap reached ($attempts of $maxAttempts); the plan below is where the " +
                        "budget ran out, not a proven minimum"
                    break@search
                }
                if (deadline != null && System.nanoTime() >= deadline) {
                    stoppedEarly = true
                    stopReason = "wall-clock budget of ${wallClockMillis}ms exhausted after $attempts attempts; " +
                        "the plan below is where the budget ran out, not a proven minimum"
                    break@search
                }

                visited += key(candidate.plan)
                attempts++
                val attempt = execute(artifact, candidate.plan)
                val reproduced = attempt.report != null && sameFailure.reproduces(artifact.observed, attempt.report)
                if (reproduced) {
                    accepted++
                    best = candidate.plan
                    trail += "accepted: ${candidate.description}"
                    acceptedThisPass = true
                    break
                }
                trail += "discarded: ${candidate.description} — ${attempt.describe()}"
            }

            if (!acceptedThisPass) {
                stopReason = "no further reduction reproduced the failure"
                break@search
            }
        }

        return finish(artifact, best, ShrinkRecord(attempts, accepted, stoppedEarly, stopReason), trail)
    }

    /**
     * [CHA1-35]'s loud guard. A strategy that returns a plan on another seed is not proposing a
     * reduction of this run — it is proposing a different run, and the whole point of shrinking
     * is that the seed is the one thing held fixed.
     */
    private fun requireSeedHeld(artifact: DstArtifact, candidate: Reduction) {
        require(candidate.plan.seed == artifact.seed) {
            "[CHA1-35] a shrink reduction must hold the run seed constant, but reduction " +
                "\"${candidate.description}\" proposes seed=${candidate.plan.seed} against artifact seed=" +
                "${artifact.seed}. Varying the seed is a different experiment, not a smaller one: the failure it " +
                "reproduces would not be the recorded failure. Rejected before it was run."
        }
    }

    /** The result of one candidate run: a report, or the throwable that stopped it. */
    private class Attempt(val report: DstReport?, val failure: String?) {

        /** Why this attempt is not the recorded failure, in one clause for the trail. */
        fun describe(): String {
            val run = report ?: return failure ?: "did not run"
            return "ran to ${run.outcome}" + (run.failingCheck?.let { " with check \"${it.message}\"" } ?: "")
        }
    }

    private fun execute(artifact: DstArtifact, plan: FaultPlan): Attempt =
        runCatching { DstRun(artifact.graph(), plan, artifact.budget, artifact.check()).execute() }
            .fold(
                onSuccess = { Attempt(it, null) },
                // A plan the rig refuses to run (an unknown target, a fault that cannot install)
                // is a candidate that did not reproduce the failure, not a shrink failure. It is
                // named in the trail so an unbuildable strategy is visible rather than silent.
                onFailure = { Attempt(null, "the run could not be executed: ${it::class.simpleName}: ${it.message}") },
            )

    private fun finish(
        artifact: DstArtifact,
        plan: FaultPlan,
        record: ShrinkRecord,
        trail: List<String>,
    ): ShrinkResult = ShrinkResult(artifact.withShrunkPlan(plan, record), plan, record, trail)

    /** Identity of a plan for the visited set: its faults as they would be written to disk. */
    private fun key(plan: FaultPlan): String =
        plan.faults.map { fault ->
            runCatching { FaultCodecs.encode(fault) }
                .fold({ "${it.id}/${it.kind}/${it.params}" }, { "${fault.id}/${fault.describe()}" })
        }.joinToString("|")
}
