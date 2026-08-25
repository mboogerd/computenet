package civictech.testkit

import civictech.testkit.dst.SweepFailure

/**
 * T12 finding 3: run every seed in [seeds], instead of the modal
 * `for (seed in 0L until 100L) { ... assertion ... }` idiom that aborts at
 * the first failing seed — losing both the seed and the failure *density*
 * (can't distinguish "seed 7 only" from "93 of 100"). [block] runs for
 * every seed regardless of earlier failures (AGENTS.md: never alter which
 * seeds run); failures are collected and, if any occurred, rethrown as one
 * [SweepFailure].
 *
 * ## Identity vs. density (computenet-e0to)
 *
 * The thrown message used to be `"failed on N of M seeds; first: seed=K —
 * <first failure message>"` — the failure count and first-failing seed sat
 * directly in [Throwable.message]. That is exactly the run-varying shape
 * computenet-umx.4 removed from `DstSweepReport.assertAllPassed`, and for
 * the same reason: `DstRun` records any throwable out of a `DstCheck.verify`
 * as `FailingCheck(it.message, ...)`, and `PlanShrinker`'s
 * `FailurePredicate.sameFailingCheck` compares exactly that string. A
 * `DstCheck` body that calls `forEachSeed` — nothing prevents it — would
 * defeat the shrinker: two runs of the same underlying assertion at
 * different densities would read as *different* failures and neither
 * reduction would be recognized as still reproducing.
 *
 * So the thrown message is the failure **mode** alone — here, the first
 * failure's own message — and the density (`failed on N of M seeds; first:
 * seed=K — <message>`, [forEachSeed]'s historical shape, unchanged) moves to
 * [SweepFailure.detail], which is also attached as a suppressed throwable so
 * a human running a plain `@Test` still sees it in the failure output. The
 * first failure remains [Throwable.cause] so an IDE's jump-to-failure still
 * lands on the real assertion. This reuses computenet-umx.4's
 * `civictech.testkit.dst.SweepFailure` rather than duplicating the
 * identity/detail split.
 */
fun forEachSeed(seeds: LongRange, block: (Long) -> Unit) {
    var total = 0
    val failures = mutableListOf<Pair<Long, Throwable>>()
    for (seed in seeds) {
        total++
        try {
            block(seed)
        } catch (t: Throwable) {
            failures += seed to t
        }
    }
    if (failures.isNotEmpty()) {
        val (firstSeed, firstFailure) = failures.first()
        throw SweepFailure(
            identity = firstFailure.message ?: firstFailure.toString(),
            detail = "failed on ${failures.size} of $total seeds; first: seed=$firstSeed — ${firstFailure.message}",
            cause = firstFailure,
        )
    }
}
