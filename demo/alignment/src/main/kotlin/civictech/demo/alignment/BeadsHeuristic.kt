package civictech.demo.alignment

/**
 * The beads-triage heuristic: the triage board's automated rater (feature computenet-i00bh).
 *
 * Arithmetic over [Candidate] fields, not a model — it was named "Jev" until real Jev arrived
 * as an [AiRater] ([TypeSafeJevRater]). Its ratings go into the AI population under
 * [MODEL], so they fold into the board's AI score beside the human one, and a human who
 * disagrees with it shows up in the aggregate's `diverges`, not as a human-side `split`. Before
 * the AI score existed it wrote as participant `jev` into the human ratings; [seedBeadsTriage]
 * clears those legacy rows.
 *
 * ## Why a heuristic and not a model call
 *
 * The Eisenhower axes are the one place where skipping the model costs nothing (user decision,
 * 2026-09-29: build it self-contained), because **beads already carries the signal**:
 * importance and urgency are questions about the tracker's own graph and
 * timestamps, not about the world. A model asked "is computenet-8x9 important?"
 * would be guessing at `dependent_count` from prose; reading it is both cheaper
 * and more accurate. So the judge is arithmetic over [Candidate] fields, which
 * makes it deterministic, free, offline, and unit-testable against stated
 * numbers rather than against a recorded fixture.
 *
 * It is not an [AiRater] because of the next section: an [AiRater] answers per idea, blind to
 * the others, and this rates the whole round at once.
 *
 * ## It rates the ROUND, not the item
 *
 * [rate] takes the whole candidate set and answers a rating per candidate,
 * because the raw terms below are **rank-normalized within the round** rather
 * than mapped onto `[1, 9]` by absolute caps. That is not a refinement, it is
 * the difference between a working board and a useless one, and it was measured
 * rather than reasoned: against the real ready-epic queue on 2026-09-29, an
 * earlier absolute-cap version put **16 of 18 epics in [Quadrant.DROP] and none
 * in [Quadrant.DO]**. Epic-level `dependent_count` is almost always 0 in this
 * tracker — the dependency edges live on features — so the importance term
 * could not structurally cross [Eisenhower.MID], and the 2x2 collapsed onto one
 * corner.
 *
 * Rank-normalizing fixes that for any distribution: whatever the corpus looks
 * like, the round spreads across the full scale and the quadrants populate. It
 * also makes its claim an honest one — "these are the important ones *of
 * this set*" — and matches how a human uses the Compare view, placing every
 * idea on one axis relative to the others rather than against an absolute
 * anchor.
 *
 * The consequence to know about: **its ratings are relative, so a re-seed
 * whose candidate set changed re-rates it.** Human ratings are untouched, and
 * an unchanged set re-derives identical values (so the seed stays
 * journal-silent).
 *
 * ## The two axes
 *
 * Kept genuinely independent rather than both tracking `priority`, which would
 * collapse the 2x2 onto its diagonal and make the matrix decorative:
 *
 * | axis | raw term | dominated by |
 * |---|---|---|
 * | [Eisenhower.IMPORTANCE] | `0.35·p + 0.65·d` | `d`, structural consequence |
 * | [Eisenhower.URGENCY] | `0.65·p + 0.35·a` | `p`, declared priority |
 *
 * with `p = (3 − priority) / 3` (P0 → 1.0, P3 → 0.0), `d = dependentCount`
 * scaled by the round's own maximum, and `a = ageDays` likewise. Each raw term
 * is then rank-normalized to `[1, 9]` by [spread].
 *
 * Reading of each term, so a disagreeing human knows what they are arguing
 * with: `d` is *how much this unblocks* — an epic five others wait on is
 * important whatever its label says. `a` is *cost of delay accrued* — an item
 * untouched for two months is treated as more urgent, not less, because
 * neglect is the failure this board exists to surface.
 *
 * ## Abstention is a real answer
 *
 * A candidate with no [Candidate.priority] is absent from [rate]'s answer
 * entirely, and the seeding path writes no rating for it — leaving the key
 * *absent*, which is alignment's honest unrated state (computenet-sigl0-D5),
 * never a middling 5. This mirrors the real `JevJudge`'s `knowledge` gate
 * returning `OUTSIDE_KNOWLEDGE` whatever the score said: a judge that cannot
 * see the input should decline, not average. Abstainers are also excluded from
 * the rank normalization, so one unrateable row does not distort the rest.
 */
internal object BeadsHeuristic {

    /**
     * The heuristic's model name and version; it rates as participant `ai:beads-heuristic-1`
     * ([RaterClass]). Bump the version whenever the weights in [rate] change, so the new arithmetic
     * is a new rater beside the old rather than silently overwriting what the old one said.
     */
    const val MODEL = "beads-heuristic-1"

    /**
     * The heuristic's ratings of [candidates] on [dim], as `[1, 9]` values keyed by
     * [Candidate.id]. Candidates it abstains on, and an unknown [dim], are
     * absent from the answer rather than defaulted.
     */
    fun rate(candidates: List<Candidate>, dim: String): Map<String, Double> {
        val raw = when (dim) {
            Eisenhower.IMPORTANCE -> rawTerms(candidates, dependentWeight = 0.65)
            Eisenhower.URGENCY -> rawTerms(candidates, dependentWeight = 0.0, ageWeight = 0.35)
            else -> return emptyMap()
        }
        return spread(raw)
    }

    /**
     * The raw term per rateable candidate: `(1 − w_d − w_a)·p + w_d·d + w_a·a`,
     * where `d` and `a` are scaled by the round's own maxima (0 when every
     * candidate shares the same value, so a flat signal contributes nothing
     * rather than dividing by zero).
     */
    private fun rawTerms(
        candidates: List<Candidate>,
        dependentWeight: Double,
        ageWeight: Double = 0.0,
    ): Map<String, Double> {
        val rateable = candidates.filter { it.priority != null }
        val maxDependents = rateable.maxOfOrNull { it.dependentCount }?.takeIf { it > 0 } ?: 0
        val maxAge = rateable.maxOfOrNull { it.ageDays }?.takeIf { it > 0 } ?: 0L
        val priorityWeight = 1.0 - dependentWeight - ageWeight
        return rateable.associate { c ->
            val p = (3 - c.priority!!) / 3.0
            val d = if (maxDependents == 0) 0.0 else c.dependentCount / maxDependents.toDouble()
            val a = if (maxAge == 0L) 0.0 else c.ageDays / maxAge.toDouble()
            c.id to (priorityWeight * p + dependentWeight * d + ageWeight * a)
        }
    }

    /**
     * Rank-normalizes [raw] onto the `[1, 9]` rating scale: the lowest raw term
     * becomes 1.0, the highest 9.0, and ties share the midpoint of the positions
     * they span (the same mid-rank rule [PairwiseFit]'s callers see from
     * fractional Borda ranks).
     *
     * Degenerate rounds resolve towards attention, consistent with
     * [Eisenhower.quadrantOf]'s midpoint rule: a single candidate, or a round
     * where every raw term ties, rates 5.0 — "no relative information, look at
     * it" rather than a silent [Quadrant.DROP].
     */
    private fun spread(raw: Map<String, Double>): Map<String, Double> {
        if (raw.isEmpty()) return emptyMap()
        if (raw.size == 1) return raw.mapValues { 5.0 }
        val sorted = raw.values.sorted()
        val last = sorted.size - 1
        return raw.mapValues { (_, v) ->
            val below = sorted.indexOfFirst { it >= v }
            val atOrBelow = sorted.indexOfLast { it <= v }
            val midRank = (below + atOrBelow) / 2.0
            1.0 + 8.0 * (midRank / last)
        }
    }
}
