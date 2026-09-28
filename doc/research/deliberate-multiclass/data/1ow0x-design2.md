## Research + verification, 2026-09-28 (sub-agents; Opus 5.5 and Sol stood in for human review at the user's request)

Scratch artefacts (combine.py, voi_categorical.py, pilot.py + testset*.json + responses.jsonl, verify/*) live in the 2026-09-28 session scratchpad and are NOT durable yet.

### Prior art (citations checked against primary sources: 12/15 verified, 3 corrected below)
- No published work does "binary claim -> link to a subset of classes -> gradual semantics -> calibrated distribution"; this is original design work. Nearest halves: Gradual AA-CBR (Gould & Toni, arXiv 2505.15742: multi-class, one label per argument, learned strengths); an illustrative class-node QBAF under DF-QuAD with argmax, no normalisation (Yin, Potyka, Rago & Toni, arXiv 2609.02399). ArgLLM (Freedman et al., arXiv 2405.02079) is binary. Amgoud & Prade (AIJ 173, 2009): two-phase — argue beliefs/options, then compare option pairs by decision principles.
- ACH (Heuer) has idea 3's shape; Dhami et al. 2024 (CRPI, s41235-024-00560-y): the ACH matrix layout did not reduce confirmation bias, hypotheses-in-rows did. Dempster-Shafer is idea 3's formal home; Zadeh's conflict case is the known hazard.
- Judges: option-order bias (Zheng et al., ICLR 2024, arXiv 2309.03882); pairwise position bias, more likely for similar-quality pairs (Shi et al., arXiv 2406.07791). LEAP (arXiv 2609.01337): per-evidence likelihood elicitation + deterministic aggregation improves most prediction/calibration metrics and supports multi-choice (Brier gains mixed). Consistency checks correlate with forecasting accuracy (Paleka et al., arXiv 2412.18544).
- Elimination: POE (Ma & Du, EMNLP 2023) masks options judged wrong, re-predicts; especially strong on logical reasoning. A hard defeat edge breaks gradual semantics' continuity.
- Pairwise: PRP (Qin et al., arXiv 2306.17563: allpair O(N²), sorting O(N log N), sliding O(N)); Rank Centrality (Negahban, Oh, Shah, OR 2017) = stationary distribution over a sparse comparison graph. Khan et al. (arXiv 2402.06782) debate uses two answers (correct + best distractor).
- Debate evidence is mixed; do not cite Qian (arXiv 2609.08016) for "debate is not better" (that sentence summarises other work; its own result is a non-detection on open-ended questions, not budget-matched).

### Judge pilot — RUN (jev-1.13.0, 534 requests, ~$0.05 Jev + $0.27 Opus rating)
Gold: Opus and Sol each rated all 137 cells blind, twice (class order reversed). Sign agreement with the original reference .85-.90; each rater changed ~12% of cells on order reversal; biggest split on the Bronze Age question (Opus reads evidence for one cause as against rivals, Sol rates independently).
- Original gold: compat and elim (0/1 Noul) PASS all gates (rule-out κ .74-.81); bear (5-level Score) fails only the hard-coded overlap gate G5 (dolphin MARINE bearing .59-.70 vs .375-.625; the LLM raters themselves rate one of those cells "+"); lik orders classes correctly 95% but doesn't beat bear by .05; pair orders 96-98% correctly but only 85% order-consistent (<.9).
- Strict LLM consensus gold (rule-out only if all 4 ratings say "--"): every shape fails rule-out κ (.52-.60). Cause: Jev never misses a true rule-out but upgrades "counts against" to "rules out". With a 3-of-4 rule, compat passes again.
- PRIOR LEAKAGE (pre-registered manual block at r > .4 on no-bearing cells): compat .60/.66, elim .66/.70, bear .48/.34, lik .53/.49 (original/consensus; CTX arm bear .49/.32, lik .40/.22). Blocks compat/elim; bear and lik borderline. Coherence good (|compat+elim−1| ≈ .04).
- Choice (pick one class): degenerate, confirmed with data (67% of answers put ≥.8 on one option; NO_PREFERENCE on no-bearing claims only .21-.27).
- Reading: Jev's per-class judgments carry signal, but must be used GRADED (never as hard elimination — it over-eliminates) and need a correction for Jev's own class prior.

### Combination rule — verified against faithful ports of the Kotlin layers
- CONFIRMED: closed-world log-linear elimination per log-odds layer (wlo, woe, jnb, mlp): score_k = α log π_k − k·‖w(e_i)(1−κ_ik)‖_p, softmax, is EXACTLY each layer at K=2 (≤5e-16), provided the base is within the layer's [.01,.99] clamp and jnb uses its own Jeffrey energy of (strength, credence), not strength·credence. Doubted claims withhold; an all-compatible claim is inert.
- REFUTED: "Ω none-of-these class with a residual prior" + "exact K=2 continuity" cannot both hold. With Ω at its .05 floor K=2 continuity breaks (0.12-0.23 typical), and ordinary two-sided debate LEAKS into Ω (3 strong pros + 3 strong cons on 50/50 -> Ω .38, leading). None-of-these needs another design (open).
- CONFIRMED narrowly: Dempster + base-rate pignistic = DF-QuAD on one-sided trees only; diverges up to .93 on two-sided.
- REFUTED: closed-frame conflict m(∅) as a none-of-these / exclusivity detector — it is nonzero on every two-sided node (it measures contestedness).
- CONFIRMED: model A defects (filed computenet-x91yk): T=1 gives sigmoid(2·logit p) for complementary positions; prior (.15,.2,.7) -> (.064,.091,.845); no none-of-these.
- Overlapping class sets: a single distribution is the wrong object; K signed binary roots (today's machinery) with a marginal vector (not re-verified, but uncontested).

### VoI for a categorical root
- eM (expected |change in signed leader margin| over a node resolving 0/1) is a reasonable objective: tail-only discriminators score ≈0, leader discriminators high. Entropy's gradient is ≈0 at ties (confirmed).
- REFUTED: "reduces exactly to today's |g|·4p(1−p) at K=2". Holds only with a linearised combination, current credence = plausibility, and a closed frame; today p is Jev plausibility, not propagated credence.
- Linearisation through the tree is materially wrong: linear/exact ratio 0.62-1.31 (5-95%), extremes 0.19-2.05; near the woe energy clamp the gradient is 0 while the exact value is .059, so nodes get marked DIMINISHING prematurely. (This also applies to TODAY's binary VoI — a separate finding worth its own bead.)
- eM is discontinuous at exact ties (leader chosen by tie-break); "a surging third class scores highest" was a scenario artefact; broad claims moving several leaders together are undervalued; Ω as leader is almost never judged against.

### Current recommendation (revised; not decided)
1. Elicitation: idea 3, GRADED per-class judgments (bear or lik), with prior-leakage correction; never Jev 0/1 as hard elimination. Choice: no.
2. Combination: closed-world log-linear elimination per log-odds layer, pooled by log-pool (= today's Consensus at K=2), JSD for layer disagreement. DF-QuAD's K-class form: DS only if one-sided divergence is acceptable (undecided).
3. None-of-these: OPEN — not an Ω class in the same softmax. Candidates: a separate binary question "does any listed answer hold?", or Ω fed only by claims explicitly judged against it.
4. VoI: eM-shaped objective, but needs exact (or better-than-linear) recomputation near clamps and a smooth leader definition at ties.
5. Overlapping sets: K binary roots, marginal vector.
6. Pairwise/rank centrality: optional extra layer only with both-order averaging (85% order consistency).

### Next experiments (to raise confidence further)
- Prior-leakage correction: re-run the pilot's ISO arm with the class prior factored out (e.g. judge relative to Jev's own no-claim prior, or ask bearing without naming the class's plausibility) and re-measure leakage + κ.
- None-of-these design + simulation.
- VoI: exact vs linear cost/benefit; smooth-leader variant.
- End-to-end accuracy: log score on questions with known answers vs model A / sampling vote / direct Jev.
