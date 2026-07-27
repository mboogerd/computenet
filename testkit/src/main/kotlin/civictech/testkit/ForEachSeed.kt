package civictech.testkit

/**
 * T12 finding 3: run every seed in [seeds], instead of the modal
 * `for (seed in 0L until 100L) { ... assertion ... }` idiom that aborts at
 * the first failing seed — losing both the seed and the failure *density*
 * (can't distinguish "seed 7 only" from "93 of 100"). [block] runs for
 * every seed regardless of earlier failures (AGENTS.md: never alter which
 * seeds run); failures are collected and, if any occurred, rethrown as one
 * summary — `"failed on N of M seeds; first: seed=K — <first failure
 * message>"` — with the first failure as [Throwable.cause] so IDE
 * "jump to failure" still lands on the real assertion.
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
        throw AssertionError(
            "failed on ${failures.size} of $total seeds; first: seed=$firstSeed — ${firstFailure.message}",
            firstFailure,
        )
    }
}
