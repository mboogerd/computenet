package civictech.deliberate

import civictech.cell.CellRef

/**
 * SPEC §12: per question, per backend, the usage of every call made for it,
 * priced by [pricing], and the question's spend projection. Not thread-safe:
 * [DeliberationEngine] calls every method except [price] under its lock, so
 * the counters are consistent with the rest of a snapshot or persisted record.
 */
internal class CostLedger(private val pricing: Pricing) {

    private val costs = HashMap<CellRef, MutableMap<String, BackendTally>>()

    companion object {
        val BACKEND_ORDER = listOf(Pricing.CLAUDE, Pricing.CODEX, Pricing.JEV)
        /** SPEC §12: the projection needs at least this many completed rounds. */
        const val PROJECTION_MIN_ROUNDS = 3

        /** [this] question with its spend, its projection and the per-backend details of [c]. */
        fun QuestionDto.withCost(c: CostDto): QuestionDto {
            val spent = c.backends.sumOf { it.usd ?: 0.0 }
            return copy(costUsd = spent, projectedUsd = c.perRoundUsd?.let { spent + c.queued * it }, cost = c)
        }
    }

    /** What [u] cost, or null when it cannot be priced. Needs no lock. */
    fun price(u: CallUsage): Double? = try {
        pricing.price(u)
    } catch (e: Exception) {
        null
    }

    /** Adds [u], priced at [usd], to [root]'s counters. */
    fun record(root: CellRef, u: CallUsage, usd: Double?) {
        val tallies = costs.getOrPut(root) { LinkedHashMap() }
        tallies[u.backend] = (tallies[u.backend] ?: BackendTally()).plus(u, usd)
    }

    /** [root]'s counters by backend (durable, SPEC §12). */
    fun tallies(root: CellRef): Map<String, BackendTally> = costs[root].orEmpty()

    /** Restores [root]'s counters from its durable record. */
    fun restore(root: CellRef, tallies: Map<String, BackendTally>) {
        if (tallies.isNotEmpty()) costs[root] = LinkedHashMap(tallies)
    }

    /**
     * [root]'s spend, its projection — spent + [queued] claims still to explore
     * × mean cost per completed round, once [PROJECTION_MIN_ROUNDS] of its
     * [rounds] completed — and the per-backend details.
     */
    fun costOf(root: CellRef, rounds: Int, queued: Int): CostDto {
        val tallies = costs[root].orEmpty()
        val backends = (BACKEND_ORDER + tallies.keys.sorted()).distinct().mapNotNull { b ->
            val t = tallies[b] ?: return@mapNotNull null
            val info = pricing.info(b)
            BackendCostDto(
                backend = b, models = t.models, calls = t.calls,
                inputTokens = t.inputTokens, cachedInputTokens = t.cachedInputTokens, cacheWriteTokens = t.cacheWriteTokens,
                outputTokens = t.outputTokens, reasoningTokens = t.reasoningTokens,
                usd = t.usd.takeIf { t.unpricedCalls < t.calls }, unpricedCalls = t.unpricedCalls,
                rate = info.rate, rateSource = info.source, rateDate = info.date, assumed = info.assumed, note = info.note,
            )
        }
        val spent = tallies.values.sumOf { it.usd }
        return CostDto(
            backends = backends,
            rounds = rounds,
            queued = queued,
            perRoundUsd = if (rounds >= PROJECTION_MIN_ROUNDS) spent / rounds else null,
        )
    }
}
