package civictech.testkit.dst

import civictech.cell.host.SimulationController
import java.io.File

/**
 * One seed's result inside a sweep.
 *
 * [error] is for a run that could not be *executed* at all — an unknown fault target
 * ([UnknownFaultTargetException]), a broken graph builder, a fault whose `install` threw. That
 * is a different thing from a run that executed and failed its check, and the sweep keeps them
 * apart while counting both as failures: a sweep that swallowed a broken experiment and
 * reported the seed green would be worse than one that reported a property failure.
 */
/**
 * A sweep's failure, with the *identity* of the failure in [message] and everything that varies
 * run to run in [detail].
 *
 * ## Why the split exists
 *
 * A sweep's `assertAllPassed` can be the failure path of a [DstCheck], and [DstRun] records a
 * failing check as `FailingCheck(throwable.message, ...)`. [FailurePredicate.sameFailingCheck] —
 * [CHA1-36]'s predicate — then compares exactly that string to decide whether a shrink candidate
 * still reproduces. So anything in the *message* becomes part of the property's identity.
 *
 * A density (`failed on 3 of 17`) and a first-failing seed are the two things that legitimately
 * move under a shrink: a smaller plan destroys less traffic and fails fewer sub-cases. With them
 * in the message every honest reduction reads as a *different* failure and is discarded, the
 * shrinker reports a plan far larger than the real minimum, and it raises no error while doing
 * it (computenet-umx.4; measured on computenet-umx.3.7 as "only 18 of 30 arrived" against a
 * recorded "only 12 of 30").
 *
 * So the message names the failure **mode** and nothing else, and the density goes in [detail],
 * which is attached as a **suppressed** throwable. Suppressed throwables are printed by JUnit
 * and by `Throwable.printStackTrace`, so a human running a plain sweep still reads
 * [CHA1-39]'s density in the failure output — but `Throwable.message`, the only thing
 * [FailurePredicate] sees, does not carry it.
 *
 * [FailurePredicate.sameOutcome] remains the labelled escape hatch for a check whose message
 * genuinely must vary; it is not what a sweep needs, because a sweep can say which mode failed.
 */
class SweepFailure(
    identity: String,
    val detail: String,
    cause: Throwable?,
) : AssertionError(identity, cause) {
    init {
        addSuppressed(SweepDetail(detail))
    }
}

/**
 * Carrier for [SweepFailure.detail]: a throwable with no stack trace of its own, whose only job
 * is to be printed under `Suppressed:` so the varying half of a sweep failure stays visible to a
 * human without entering the check's identity.
 */
class SweepDetail(message: String) : Throwable(message) {
    override fun fillInStackTrace(): Throwable = this

    override fun toString(): String = message ?: ""
}

data class SweepEntry(
    val seed: Long,
    val report: DstReport?,
    val error: Throwable?,
    val artifact: File?,
) {
    /** Executed and failed its check, or never executed at all. */
    val failed: Boolean get() = error != null || report?.outcome == DstOutcome.FAILED

    /** Never quiesced within budget: no verdict was claimed about the property ([CHA1-03]). */
    val exhausted: Boolean get() = report?.outcome == DstOutcome.BUDGET_EXHAUSTED

    val message: String
        get() = error?.message
            ?: report?.failingCheck?.message
            ?: report?.outcome?.name
            ?: "no report"

    val cause: Throwable?
        get() = error ?: report?.failingCheck?.error
}

/**
 * What a sweep of a seed range found ([CHA1-38], [CHA1-39], BS-15).
 *
 * Two properties are structural rather than promised:
 *
 *  - **Every seed in the range has an entry.** The `init` block below refuses a report whose
 *    entries are not exactly [seedRange], in order — so a sweep that narrowed, skipped or
 *    re-mapped its range cannot produce a report at all, and [CHA1-39] cannot be quietly
 *    violated by a consumer that catches its own failures.
 *  - **The executed range is recorded** ([CHA1-38]). Note the epic's own honesty clause (§9
 *    risk 8): "never narrow a failing seed range" is a **review** rule. The rig makes a
 *    narrowing *detectable* by recording the range in every sweep report; it does not and
 *    cannot enforce it, and this type claims no more than that.
 *
 * [nonDeterministic] is [CHA1-40]: a sweep driven across JVMs is marked, and makes no
 * replay-reproducibility claim for any of its artifacts.
 */
data class DstSweepReport(
    val suite: String,
    val seedRange: LongRange,
    val graphId: String,
    val driver: DstDriver,
    val entries: List<SweepEntry>,
) {
    init {
        val executed = entries.map { it.seed }
        require(executed == seedRange.toList()) {
            "[CHA1-39]: a sweep report must cover every seed in its recorded range $seedRange, in order; " +
                "got ${executed.size} entries " +
                (if (executed.isEmpty()) "(none)" else "spanning ${executed.first()}..${executed.last()}")
        }
    }

    val total: Int get() = entries.size

    val failures: List<SweepEntry> get() = entries.filter { it.failed }

    val exhausted: List<SweepEntry> get() = entries.filter { it.exhausted }

    /** Every artifact this sweep wrote, in seed order ([CHA1-31], BS-15's "lists all paths"). */
    val artifactPaths: List<File> get() = entries.mapNotNull { it.artifact }

    /** [CHA1-40]: true when the driver makes no replay-reproducibility claim. */
    val nonDeterministic: Boolean get() = !driver.deterministic

    /**
     * [CHA1-39]'s density, in `forEachSeed`'s words: `failed on N of M`.
     *
     * **Reporting only.** This string moves with the run by construction, so it belongs in a
     * report, an artifact or [SweepFailure.detail] — never in the message of a failing check,
     * where it would become part of the property's identity and defeat [PlanShrinker]. See
     * [SweepFailure].
     */
    val density: String get() = "failed on ${failures.size} of $total"

    /**
     * The whole sweep in one line: density, the executed range, and the exhausted count.
     *
     * Reporting only, for [density]'s reason: it embeds the density.
     */
    fun summary(): String = buildString {
        append("DST sweep suite=$suite graph=$graphId driver=$driver seeds=${seedRange.first}..${seedRange.last} ")
        append("(executed $total); $density")
        if (exhausted.isNotEmpty()) append("; budget exhausted on ${exhausted.size} (no verdict claimed)")
        if (nonDeterministic) append("; NON-DETERMINISTIC — no replay reproducibility claimed ([CHA1-40])")
    }

    /**
     * `forEachSeed`'s contract, extended ([CHA1-39]) and split in two ([SweepFailure]).
     *
     * `civictech.testkit.forEachSeed` runs every seed, collects failures and rethrows one
     * summary — `failed on N of M seeds; first: seed=K — <message>` — with the **first**
     * failure as [Throwable.cause] so an IDE's jump-to-failure still lands on the real
     * assertion. That whole line is preserved, plus what a sweep knows and a bare loop does not
     * (the executed seed range, the artifact path for every failing seed) — but it is carried in
     * [SweepFailure.detail] and printed as a suppressed throwable rather than in the thrown
     * message.
     *
     * The **message** is the failure mode alone: the suite, the graph, and the first bad entry's
     * own message. Two runs of the same failure mode under different fault plans therefore
     * produce byte-identical messages, which is what [CHA1-36]'s shrink predicate needs and what
     * the old shape denied it (computenet-umx.4). The count, the first failing seed and the
     * density stayed exactly where a human reads them; they simply stopped being the property's
     * identity.
     *
     * A `BUDGET_EXHAUSTED` seed fails the sweep as well. It disproved nothing, but a run that
     * never quiesced must not read as a pass.
     */
    fun assertAllPassed() {
        val bad = entries.filter { it.failed || it.exhausted }
        if (bad.isEmpty()) return
        val first = bad.first()
        val artifacts = artifactPaths.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "; artifacts: ", separator = ", ") { it.path }
            ?: ""
        throw SweepFailure(
            identity = "DST sweep suite=$suite graph=$graphId failed: ${first.message}",
            detail = "failed on ${bad.size} of $total seeds; first: seed=${first.seed} — ${first.message} " +
                "[${summary()}$artifacts]",
            cause = first.cause,
        )
    }
}

/**
 * Run every seed in [seeds] against [graph], collect the lot, and write an artifact for each
 * failure ([CHA1-31], [CHA1-38], [CHA1-39], BS-15).
 *
 * **Every seed runs, regardless of earlier failures.** There is no early exit and no fail-fast
 * option, because the number this exists to produce is the *density*: "seed 7 only" and "93 of
 * 100" are different findings, and a loop that aborts at the first failure cannot tell them
 * apart. See `civictech.testkit.forEachSeed`, whose contract [DstSweepReport.assertAllPassed]
 * preserves.
 *
 * @param checkId the id of the property under test, registered in [CheckRegistry]. Passing the
 *   *id* rather than the [DstCheck] is deliberate: it is what a failing seed's artifact records
 *   so the failure can be replayed ([CHA1-32]), and a sweep that could not name its own check
 *   would write artifacts that replay as false passes.
 * @param planFor the adversary for a given seed. The default is the empty plan, which makes a
 *   sweep a plain determinism/soak run over the graph.
 * @param artifactRoot must be under a module build directory ([CHA1-54]); validated up front,
 *   before any seed runs, so a misconfigured root fails the sweep rather than the 84th seed.
 */
fun dstSweep(
    suite: String,
    seeds: LongRange,
    graph: GraphSpec,
    checkId: String? = null,
    budget: Int = SimulationController.DEFAULT_BUDGET,
    driver: DstDriver = DstDriver.IN_PROCESS,
    artifactRoot: File = DstArtifacts.defaultRoot(),
    writeArtifacts: Boolean = true,
    planFor: (Long) -> FaultPlan = { FaultPlan.empty(it) },
): DstSweepReport {
    DstArtifacts.requireArtifactName(suite, "suite")
    val root = DstArtifacts.requireUnderBuildDirectory(artifactRoot)
    val check = checkId?.let(CheckRegistry::require) ?: DstCheck.none

    val entries = seeds.map { seed ->
        val plan = planFor(seed)
        require(plan.seed == seed) {
            "[CHA1-30]: planFor($seed) returned a plan on seed ${plan.seed}; a sweep's seed is the run's seed"
        }
        val run = DstRun(graph, plan, budget, check)
        val report = runCatching { run.execute() }
        val failed = report.isFailure || report.getOrNull()?.outcome == DstOutcome.FAILED
        val artifact = if (writeArtifacts && failed && report.isSuccess) {
            DstArtifacts.write(
                DstArtifact.of(run, report.getOrThrow(), suite = suite, checkId = checkId, driver = driver),
                root,
            )
        } else {
            null
        }
        SweepEntry(seed, report.getOrNull(), report.exceptionOrNull(), artifact)
    }

    return DstSweepReport(suite, seeds, graph.id, driver, entries)
}
