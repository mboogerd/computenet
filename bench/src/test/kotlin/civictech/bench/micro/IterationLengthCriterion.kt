package civictech.bench.micro

/**
 * The pre-registered criterion for `[BEN1-28]`'s **TIME** channel — does within-iteration
 * `TagState` tag-map growth cost the set-shaped subjects wall clock? (computenet-bzwx.)
 *
 * ## Why this is a separate file from the render test that uses it
 *
 * `TagMapGrowthAllocRenderTest` (computenet-i61m) keeps its criterion private inside its
 * own `@Tag("bench")` class, which means the decision function only ever executes when
 * someone runs a sweep. That is enough to make the criterion *auditable* but not enough
 * to make it *tested*: nothing in the six required checks ever calls it, so a refactor
 * that inverted a comparison would land green. This criterion lives in its own object so
 * `IterationLengthCriterionTest` — an ordinary untagged test in `:bench:test` — can
 * exercise every branch against synthetic inputs on every build.
 *
 * ## The instrument, in one paragraph
 *
 * `OperatorThroughputBenchmark` rebuilds the graph at `@Setup(Level.Iteration)`, so the
 * operators' `TagState` maps grow monotonically *within* one measurement iteration and
 * reset at the iteration boundary. Lengthening the measurement iteration therefore grows
 * the map and changes nothing else about the measured work — same graph, same delta
 * stream, same seed, same JVM, same batch size. If growth costs wall clock, a 10x longer
 * iteration reports materially lower *average* throughput, because a larger share of the
 * iteration is spent at large map sizes. If it does not, the two arms agree.
 *
 * That is the whole design, and it is one-sided in a way a reader has to carry: a
 * ratio below 1 is consistent with tag-map growth costing time, but the arm length is
 * not a *label* on the tag map — anything else that degrades with time-in-iteration
 * (JIT deoptimisation, a filling young generation, a growing internal collection that is
 * not the tag map) moves it the same way. A [Verdict.FIRES] therefore establishes that
 * *something* accumulating within the iteration costs wall clock, with the tag map the
 * named candidate; it does not by itself attribute the cost to the map. The findings
 * entry states that limitation next to the number.
 *
 * ## The thresholds, and why each is the value it is
 *
 * - [MATERIAL_RATIO] = 0.90. A 10% throughput decline over a 10x longer iteration is the
 *   smallest effect this lane would call a cost rather than drift. `computenet-i61m`'s
 *   underpowered probe reported ratios from 0.98 down to 0.45, so 0.90 sits between the
 *   two clusters it saw and does not have to be moved to make either of them decide.
 * - [RESOLVABLE_RELATIVE_ERROR] = 0.10. A row whose 99.9% error bar is more than a tenth
 *   of its own score cannot discriminate a tenth-sized effect. Three of `i61m`'s eight
 *   10 s rows were above this — one at 1.93, an error bar wider than the score — and
 *   that, rather than any number it reported, is why that probe was not a result.
 *
 * Both are compared against **interval** bounds, never against the point ratio: a row
 * decides only when the whole error-propagated interval falls on one side of
 * [MATERIAL_RATIO]. A row whose interval straddles it is [RowVerdict.UNDECIDED] and votes
 * for nothing.
 *
 * ## The aggregation
 *
 * - [Verdict.FIRES] on a strict majority of rows measuring [RowVerdict.COSTS]. Not "any
 *   row", because a single subject falling could be that subject's own property; a
 *   majority of the family is a statement about the family.
 * - [Verdict.RETIRES] only when **every** row is resolved and **every** row measures
 *   [RowVerdict.DOES_NOT_COST]. Retiring a suspicion is the strong claim, so it takes the
 *   whole family and admits no unresolved row.
 * - [Verdict.INCONCLUSIVE] otherwise — including the case this lane most expects, a
 *   mixture.
 *
 * ## The split question, answered separately
 *
 * `computenet-i61m` observed `COUNT` and `FLAT_MAP` losing more than half their
 * throughput while `TAGGED_SET` and `FILTER` lost almost nothing at comparable
 * throughput — a pattern "the map got bigger" does not explain on its own. [splitOf]
 * states whether that pattern reproduces, and it is deliberately NOT part of [verdictOf]:
 * the split can reproduce under a FIRES and under an INCONCLUSIVE alike, and folding it
 * into the main verdict would make one question's answer depend on the other's.
 */
object IterationLengthCriterion {

    /**
     * The ratio (long-arm throughput / short-arm throughput) at or below which a row is
     * said to have LOST throughput to the longer iteration.
     */
    const val MATERIAL_RATIO: Double = 0.90

    /**
     * The largest relative error (`scoreError / score`, JMH's 99.9% confidence bar) a row
     * may carry in EITHER arm and still enter the vote.
     */
    const val RESOLVABLE_RELATIVE_ERROR: Double = 0.10

    /** The subjects `computenet-i61m`'s probe saw fall by more than half. */
    val SPLIT_HIGH: List<String> = listOf("COUNT", "FLAT_MAP")

    /** The subjects `computenet-i61m`'s probe saw barely move, at comparable throughput. */
    val SPLIT_FLAT: List<String> = listOf("TAGGED_SET", "FILTER")

    /**
     * The criterion, in words, fixed before any number from this A/B existed.
     *
     * Lowercase `fires`/`retires` is load-bearing: `Findings.entry` counts whole-word
     * FIRES / RETIRES / INCONCLUSIVE case-sensitively and refuses a trigger statement
     * holding other than exactly one.
     */
    const val CRITERION: String =
        "the criterion applied is that each set-shaped subject's ratio of long-arm to " +
            "short-arm throughput is taken with its error bars propagated, a row counts " +
            "as losing throughput only if the whole ratio interval falls below " +
            "MATERIAL_RATIO=0.90 and as not losing it only if the whole interval falls " +
            "above it, a row whose relative error exceeds " +
            "RESOLVABLE_RELATIVE_ERROR=0.10 in either arm is unresolved and votes for " +
            "nothing, and the question fires only if a strict majority of the " +
            "set-shaped rows lose throughput, retires only if every row is resolved and " +
            "every row does not, and is otherwise undecided"

    /** One arm's measurement of one subject: JMH's `Score` and `Score Error (99.9%)`. */
    data class Arm(val score: Double, val error: Double) {

        /** `error / score`, or `NaN` for a non-positive score, which is not a rate at all. */
        val relativeError: Double
            get() = if (score <= 0.0 || !score.isFinite()) Double.NaN else error / score

        val resolvable: Boolean
            get() = relativeError.isFinite() && relativeError <= RESOLVABLE_RELATIVE_ERROR
    }

    /** What one subject's pair of arms says. */
    enum class RowVerdict {
        /** The whole ratio interval is below [MATERIAL_RATIO]: the longer iteration costs. */
        COSTS,

        /** The whole ratio interval is above [MATERIAL_RATIO]: the longer iteration does not. */
        DOES_NOT_COST,

        /** The interval straddles [MATERIAL_RATIO]: resolved, but not on either side. */
        UNDECIDED,

        /** An arm's error bar is too wide for the row to discriminate anything. */
        UNRESOLVED,
    }

    /** The three words a findings-entry trigger statement may carry. */
    enum class Verdict { FIRES, RETIRES, INCONCLUSIVE }

    /** Whether `computenet-i61m`'s subject split reproduces at full power. */
    enum class Split {
        /** The four named rows all decided, and exactly in the probe's pattern. */
        REPRODUCES,

        /** The four named rows all decided, and NOT in the probe's pattern. */
        DOES_NOT_REPRODUCE,

        /** At least one of the four named rows did not decide; the split is not settled. */
        UNRESOLVED,
    }

    /** One subject measured in both arms. */
    data class SubjectAb(val subject: String, val short: Arm, val long: Arm) {

        /** The point ratio, long over short. Reported; never the thing decided on. */
        val ratio: Double
            get() = if (short.score <= 0.0) Double.NaN else long.score / short.score

        /**
         * The pessimistic end of the ratio interval — long at its lowest over short at its
         * highest. `NaN` when the short arm's lower bound is not a positive number, which
         * is a row that cannot bound a ratio at all rather than one bounding it at zero.
         */
        val ratioLow: Double
            get() {
                val denominator = short.score + short.error
                return if (denominator <= 0.0) Double.NaN else (long.score - long.error) / denominator
            }

        /** The optimistic end — long at its highest over short at its lowest. */
        val ratioHigh: Double
            get() {
                val denominator = short.score - short.error
                return if (denominator <= 0.0) Double.NaN else (long.score + long.error) / denominator
            }

        val verdict: RowVerdict
            get() {
                if (!short.resolvable || !long.resolvable) return RowVerdict.UNRESOLVED
                val low = ratioLow
                val high = ratioHigh
                if (!low.isFinite() || !high.isFinite()) return RowVerdict.UNRESOLVED
                return when {
                    high < MATERIAL_RATIO -> RowVerdict.COSTS
                    low > MATERIAL_RATIO -> RowVerdict.DOES_NOT_COST
                    else -> RowVerdict.UNDECIDED
                }
            }
    }

    /**
     * The whole of the decision, in code.
     *
     * Total and dull on purpose: three mutually exclusive branches over one derived
     * per-row verdict. An empty input is [Verdict.INCONCLUSIVE] — zero rows are not a
     * majority and are not "every row" either, and answering otherwise would let a sweep
     * that measured nothing retire a suspicion.
     */
    fun verdictOf(rows: List<SubjectAb>): Verdict {
        if (rows.isEmpty()) return Verdict.INCONCLUSIVE
        val verdicts = rows.map { it.verdict }
        val costs = verdicts.count { it == RowVerdict.COSTS }
        return when {
            costs * 2 > rows.size -> Verdict.FIRES
            verdicts.all { it == RowVerdict.DOES_NOT_COST } -> Verdict.RETIRES
            else -> Verdict.INCONCLUSIVE
        }
    }

    /**
     * Whether `computenet-i61m`'s `COUNT`/`FLAT_MAP` versus `TAGGED_SET`/`FILTER` split
     * reproduces — the second question `computenet-bzwx`'s acceptance names.
     *
     * [Split.REPRODUCES] requires all four named rows to have decided AND the two that
     * fell in the probe to be [RowVerdict.COSTS] while the two that did not are
     * [RowVerdict.DOES_NOT_COST]. Any other fully-decided arrangement is
     * [Split.DOES_NOT_REPRODUCE]; a missing or undecided row among the four leaves it
     * [Split.UNRESOLVED], which is the honest answer when the powered arms cannot settle
     * it.
     */
    fun splitOf(rows: List<SubjectAb>): Split {
        val bySubject = rows.associateBy { it.subject }
        val high = SPLIT_HIGH.map { bySubject[it]?.verdict }
        val flat = SPLIT_FLAT.map { bySubject[it]?.verdict }
        val named = high + flat
        if (named.any { it == null || it == RowVerdict.UNDECIDED || it == RowVerdict.UNRESOLVED }) {
            return Split.UNRESOLVED
        }
        val reproduces = high.all { it == RowVerdict.COSTS } &&
            flat.all { it == RowVerdict.DOES_NOT_COST }
        return if (reproduces) Split.REPRODUCES else Split.DOES_NOT_REPRODUCE
    }

    /**
     * The measured half of the trigger statement — what [CRITERION] was applied TO.
     *
     * Generated from the same rows the verdict is, so the sentence in the rendered entry
     * cannot drift from the table beside it. Carries no whole-word FIRES/RETIRES/
     * INCONCLUSIVE of its own; the caller prefixes exactly one.
     */
    fun measuredClause(rows: List<SubjectAb>): String {
        if (rows.isEmpty()) return "the A/B held no set-shaped rows at all"
        val verdicts = rows.map { it.verdict }
        val ordered = rows.sortedBy { it.ratio }
        val lowest = ordered.first()
        val highest = ordered.last()
        return "across ${rows.size} set-shaped rows the long-arm/short-arm throughput " +
            "ratio ranges ${lowest.ratio} (${lowest.subject}) to ${highest.ratio} " +
            "(${highest.subject}), with " +
            "${verdicts.count { it == RowVerdict.COSTS }} rows losing throughput, " +
            "${verdicts.count { it == RowVerdict.DOES_NOT_COST }} not losing it, " +
            "${verdicts.count { it == RowVerdict.UNDECIDED }} straddling the 0.90 " +
            "boundary and ${verdicts.count { it == RowVerdict.UNRESOLVED }} unresolved " +
            "for width of error bar; the computenet-i61m subject split " +
            "${splitOf(rows).name.replace('_', '-')}"
    }
}

/**
 * The pre-registered criterion for the **residual** of `computenet-bzwx`'s iteration-length
 * A/B — the two set-shaped rows, `TAGGED_SET` and `UNION`, whose ratio intervals straddled
 * the materiality boundary and therefore decided nothing (computenet-ciz9).
 *
 * ## Why this is a second criterion and not a re-use of [IterationLengthCriterion]
 *
 * [IterationLengthCriterion] is stated over the whole set-shaped family: its
 * [IterationLengthCriterion.verdictOf] aggregates a majority over eight rows, and its
 * `RETIRES` branch demands that *every* one of the eight be resolved. Applying it to a
 * two-row sweep would silently redefine "the family" as those two rows and would let a
 * measurement that touched two subjects retire — or fire — a question stated over eight.
 * This object therefore states its own aggregation over its own named pair, and holds its
 * own copies of the two thresholds rather than importing them, so that a later change to
 * the family criterion's numbers cannot retroactively move the boundary this run was
 * decided against. That the two constants are currently *equal* to the family criterion's
 * is itself the pre-registered decision: `computenet-bzwx` fixed
 * `MATERIAL_RATIO = 0.90` before its numbers existed and both residual rows landed near
 * it, so moving the boundary now — in either direction — would be choosing a threshold to
 * suit two known point estimates, which is exactly the defect pre-registration exists to
 * prevent.
 *
 * ## The arm design this criterion was fixed against, stated before any number existed
 *
 * Identical to `computenet-bzwx`'s in every respect except the fork count and the subject
 * list: `OperatorThroughputBenchmark.real`, `direction=INSERT`, `drive=REAL`, warmup
 * `5 x 1 s`, measurement `10 x <arm>`, the single knob between arms being JMH's `-r`
 * (`1s` SHORT / `10s` LONG). The fork count is raised from the class's declared 2 to
 * [FORKS] = 16 per arm, and the sweep is narrowed to [SUBJECTS].
 *
 * **Why 16, derived from `computenet-bzwx`'s published error bars rather than from the
 * time available.** A row decides only when its whole propagated ratio interval clears
 * [MATERIAL_RATIO]. At 2 forks `TAGGED_SET` reported 0.9156 with a half-width of about
 * 0.0332 and `UNION` 0.9414 with about 0.0453 — so the distances left to cover are 0.0156
 * and 0.0414 respectively, and `TAGGED_SET` is the binding row. Shrinking its half-width
 * by the required factor of 2.13 needs at least `2 x 2.13^2` = 9.1 forks on the
 * `1/sqrt(n)` term alone; 16 forks buys a factor of about 3.3 once the `t(0.999, df)`
 * multiplier's own shrink from `df = 19` to `df = 159` is included, which is margin over
 * the minimum rather than exactly the minimum. Sixteen forks over two subjects costs
 * about the same wall clock as `computenet-bzwx`'s two forks over eight.
 *
 * **What this design cannot do, stated in advance.** A row whose *true* ratio sits within
 * roughly 0.01 of [MATERIAL_RATIO] cannot be resolved by any affordable fork count, and
 * this design does not claim to resolve one. If either row comes back
 * [IterationLengthCriterion.RowVerdict.UNDECIDED] at 16 forks, that is this instrument's
 * honest answer — the effect, if any, is smaller than the boundary the lane pre-registered
 * as the smallest it would call a cost — and not a failure of the run.
 *
 * ## What a verdict here does and does not cover
 *
 * [Verdict.FIRES] and [Verdict.RETIRES] from [verdictOf] are statements about
 * [SUBJECTS] and nothing else. `computenet-bzwx` measured the other six set-shaped rows
 * as [IterationLengthCriterion.RowVerdict.DOES_NOT_COST]; this criterion neither
 * re-measures nor re-states them, and [CRITERION] says so in the sentence it renders.
 */
object IterationLengthResidualCriterion {

    /**
     * The two rows `computenet-bzwx` left straddling the boundary, and the only rows this
     * criterion is stated over. Fixed here, in committed source, so a sweep that quietly
     * covered a different pair could not be rendered through it.
     */
    val SUBJECTS: List<String> = listOf("TAGGED_SET", "UNION")

    /** Forks per arm, per subject — raised from `computenet-bzwx`'s 2. See this object's KDoc. */
    const val FORKS: Int = 16

    /**
     * The ratio (long-arm throughput / short-arm throughput) at or below which a row is
     * said to have LOST throughput to the longer iteration.
     *
     * Deliberately the same 0.90 `computenet-bzwx` pre-registered, held as this object's
     * own constant rather than imported: see the KDoc for why keeping it, rather than
     * moving it, is the pre-registered choice.
     */
    const val MATERIAL_RATIO: Double = 0.90

    /**
     * The largest relative error (`scoreError / score`) a row may carry in EITHER arm and
     * still enter this criterion. Again `computenet-bzwx`'s value, held locally.
     */
    const val RESOLVABLE_RELATIVE_ERROR: Double = 0.10

    /** Whether the two rows say the same thing as each other. */
    enum class Agreement {
        /** Both rows resolved, and both LOST throughput to the longer iteration. */
        AGREE_COSTS,

        /** Both rows resolved, and neither lost throughput. */
        AGREE_DOES_NOT_COST,

        /** Both rows resolved, and to OPPOSITE sides of [MATERIAL_RATIO]. */
        DISAGREE,

        /** At least one row did not resolve; the pair cannot be said to agree or not. */
        NOT_BOTH_RESOLVED,
    }

    /** The three words a findings-entry trigger statement may carry. */
    enum class Verdict { FIRES, RETIRES, INCONCLUSIVE }

    /**
     * The criterion, in words, fixed before any number from this sweep existed.
     *
     * Lowercase `fires`/`retires` is load-bearing: `Findings.entry` counts whole-word
     * FIRES / RETIRES / INCONCLUSIVE case-sensitively and refuses a trigger statement
     * holding other than exactly one.
     */
    const val CRITERION: String =
        "the criterion applied is stated over the two residual subjects TAGGED_SET and " +
            "UNION only — the rows computenet-bzwx left straddling its boundary, the " +
            "other six set-shaped rows being neither re-measured nor re-stated here — " +
            "and is that each of the two subjects' ratio of long-arm to short-arm " +
            "throughput is taken with its error bars propagated, a row counts as losing " +
            "throughput only if the whole ratio interval falls below MATERIAL_RATIO=0.90 " +
            "and as not losing it only if the whole interval falls above it, a row whose " +
            "relative error exceeds RESOLVABLE_RELATIVE_ERROR=0.10 in either arm or " +
            "whose interval straddles the boundary resolves nothing, and the residual " +
            "question fires only if BOTH rows lose throughput, retires only if BOTH rows " +
            "are resolved and neither loses it, and is otherwise undecided — which " +
            "includes the case where the two rows resolve to opposite sides"

    /**
     * One row's verdict under THIS criterion's constants.
     *
     * Deliberately not [IterationLengthCriterion.SubjectAb.verdict]: that property closes
     * over the family criterion's [IterationLengthCriterion.MATERIAL_RATIO], so reusing it
     * would make this object's own [MATERIAL_RATIO] decorative and a change to the family
     * threshold would silently move this run's boundary. The row's *arithmetic* — the
     * ratio and its propagated interval — is shared, because that is measurement rather
     * than criterion.
     */
    fun rowVerdictOf(row: IterationLengthCriterion.SubjectAb): IterationLengthCriterion.RowVerdict {
        val shortResolvable = row.short.relativeError.isFinite() &&
            row.short.relativeError <= RESOLVABLE_RELATIVE_ERROR
        val longResolvable = row.long.relativeError.isFinite() &&
            row.long.relativeError <= RESOLVABLE_RELATIVE_ERROR
        if (!shortResolvable || !longResolvable) return IterationLengthCriterion.RowVerdict.UNRESOLVED
        val low = row.ratioLow
        val high = row.ratioHigh
        if (!low.isFinite() || !high.isFinite()) return IterationLengthCriterion.RowVerdict.UNRESOLVED
        return when {
            high < MATERIAL_RATIO -> IterationLengthCriterion.RowVerdict.COSTS
            low > MATERIAL_RATIO -> IterationLengthCriterion.RowVerdict.DOES_NOT_COST
            else -> IterationLengthCriterion.RowVerdict.UNDECIDED
        }
    }

    /**
     * The whole of the decision, in code.
     *
     * Both of [SUBJECTS] must be present exactly once. Anything else — a missing row, a
     * duplicate, an extra subject — is [Verdict.INCONCLUSIVE] rather than a partial
     * answer: a two-row criterion aggregated over one row would be a criterion nobody
     * pre-registered.
     */
    fun verdictOf(rows: List<IterationLengthCriterion.SubjectAb>): Verdict {
        val byName = rows.groupBy { it.subject }
        if (byName.keys != SUBJECTS.toSet()) return Verdict.INCONCLUSIVE
        if (byName.values.any { it.size != 1 }) return Verdict.INCONCLUSIVE
        val verdicts = SUBJECTS.map { rowVerdictOf(byName.getValue(it).single()) }
        return when {
            verdicts.all { it == IterationLengthCriterion.RowVerdict.COSTS } -> Verdict.FIRES
            verdicts.all { it == IterationLengthCriterion.RowVerdict.DOES_NOT_COST } -> Verdict.RETIRES
            else -> Verdict.INCONCLUSIVE
        }
    }

    /**
     * Whether the two rows agree with each other — the second question this item's
     * acceptance names, kept out of [verdictOf] so that neither answer depends on the
     * other's shape.
     */
    fun agreementOf(rows: List<IterationLengthCriterion.SubjectAb>): Agreement {
        val byName = rows.groupBy { it.subject }
        if (byName.keys != SUBJECTS.toSet() || byName.values.any { it.size != 1 }) {
            return Agreement.NOT_BOTH_RESOLVED
        }
        val verdicts = SUBJECTS.map { rowVerdictOf(byName.getValue(it).single()) }
        if (verdicts.any {
                it == IterationLengthCriterion.RowVerdict.UNDECIDED ||
                    it == IterationLengthCriterion.RowVerdict.UNRESOLVED
            }
        ) {
            return Agreement.NOT_BOTH_RESOLVED
        }
        return when {
            verdicts.all { it == IterationLengthCriterion.RowVerdict.COSTS } -> Agreement.AGREE_COSTS
            verdicts.all { it == IterationLengthCriterion.RowVerdict.DOES_NOT_COST } ->
                Agreement.AGREE_DOES_NOT_COST
            else -> Agreement.DISAGREE
        }
    }

    /**
     * The measured half of the trigger statement — what [CRITERION] was applied TO.
     *
     * Generated from the same rows the verdict is, so the rendered sentence cannot drift
     * from the table beside it. Carries no whole-word FIRES/RETIRES/INCONCLUSIVE of its
     * own; the caller prefixes exactly one.
     */
    fun measuredClause(rows: List<IterationLengthCriterion.SubjectAb>): String {
        if (rows.isEmpty()) return "the sweep held no residual row at all"
        val stated = rows.sortedBy { it.subject }.joinToString("; ") { row ->
            "${row.subject} ratio ${row.ratio} (interval ${row.ratioLow} to " +
                "${row.ratioHigh}, ${rowVerdictOf(row)})"
        }
        return "at $FORKS forks per arm, $stated; the two rows " +
            "${agreementOf(rows).name.replace('_', '-')}"
    }
}
