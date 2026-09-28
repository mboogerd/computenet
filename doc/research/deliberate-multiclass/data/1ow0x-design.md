## Research pass 2026-09-28 (five sub-agents; scratch artefacts are session-local, see "Artefacts")

### Prior art (condensed)
- No published work does "binary claim -> link to an arbitrary subset of classes -> gradual semantics -> calibrated distribution". Nearest halves: Gradual AA-CBR (Gould et al. 2025, arXiv 2505.15742: K-ary, one label per argument, learned weights) and multi-rooted argumentative classifiers (Lertvittayakumjorn & Toni, arXiv 2205.10932: one node per class, DF-QuAD over the whole graph, no subset edges, no normalisation). Amgoud & Prade AIJ 2009: two-phase (argue beliefs, then compare options by qualitative dominance).
- ACH (Heuer) is idea 3's shape; human empirical record weak/mixed. Dempster-Shafer is its formal home; Zadeh's conflict pathology is the known hazard.
- Judges: verbalised distributions poorly calibrated; pairwise has position bias, worst when options are close; option-order bias 13-85% (Zheng et al. ICLR 2024). LEAP (arXiv 2609.01337): per-evidence likelihood elicitation + deterministic aggregation beat monolithic forecasting incl. multi-choice. No work validates LLM subset-compatibility judgments.
- Elimination (POE, Ma & Du EMNLP 2023) wins where wrong answers are hard-inconsistent, esp. plausible distractors; neutral on graded plausibility; value grows with K. A hard defeat edge breaks gradual semantics' continuity -> separate step, not a big attack weight.
- Pairwise route: PRP (Qin et al.) + Rank Centrality (Negahban et al.) gives a distribution from a sparse connected comparison graph (K-1 pairs suffice). Khan et al. ICML 2024 debate is binary (cut to top-2 first). Best-supported compositional pattern: eliminate to a shortlist, then compare survivors.

### Combination rule (claim credences + compatibility -> distribution) — simulated, stdlib script
- Claim energy e_i = strength x credence (today's Arg). Unjudged link => compatible (withhold). Explicit Ω "none of these" class, prior = plausibility residual (floor .05). MECE "{A,C}" = EITHER.
- PRIMARY: log-linear elimination per log-odds layer (wlo/woe/jnb/mlp): score_k = α log π_k − k·‖w(e_i)(1−κ_ik)‖_p, softmax; bayesVE member = naive Bayes with Pearl virtual evidence. EXACT K=2 continuity with each layer (2000 random trees, max diff 0.0000). Doubted claims withhold (disbelief-to-complement double-counts attackers); all-compatible claim is inert; energy clamps defuse the Zadeh / open-Ω hair trigger.
- COMPANION: Dempster-Shafer conjunctive + base-rate pignistic = DF-QuAD's K-class form (exact on one-sided trees). Always report closed-frame conflict m(∅): the only signal for none-of-these on a closed frame, strong conflict, and exclusivity violations. Dropped: PCR6 (identical in open world, 2^n), subjective logic as a layer (no K=2 continuity, arbitrary evidence scale).
- Consensus: normalised log-pool (= today's Consensus at K=2); disagreement: Jensen-Shannon (≈0 routinely, large when the frame is in question).
- Growing classes: unjudged=>compatible gives a mild free-ride until links are judged (queue by VoI); the opposite default kills new classes.
- OVERLAPPING classes: a distribution is the wrong object (split 86%/85% causes into 42%/38%). Use K signed binary roots (today's machinery); answer = marginal vector.

### VoI for a categorical root — derived + simulated
- eM = expected |change of the signed leader margin| (leader minus best other, negative when overtaken) over the node resolving 0/1; tree part linearised via today's sensitivity, combination evaluated exactly. Reduces EXACTLY to |g|·4p(1−p) at K=2, so --voi-eps keeps its meaning.
- Entropy (gradient or expected) and mass-moved FAIL (reward tail reshuffles, ≈0 at ties); linear margin misses a third class overtaking; flip probability too coarse.
- Cruxes: margin cruxes (top eM) + flip cruxes ("if X is false, B overtakes A").
- Architecture: read-side only — positions stay sensitivity roots emitting 1; ExplorationPolicy.valueOf evaluates a pure combine(credences)->s twice per node; CRED-03 intact. SPEC touch: FRA-02, FRA-03, §3 VoI, QuestionDto.cruxes.

### Judge pilot (designed, NOT run)
- Shapes, each per claim: compat (Noul, 0/1), elim (Noul), lik (Score, P(claim|class)), bear (Score 5-level: rules out..settles; level 0 gives the subset), pair (Choice, both orders), choice (control), prior (leakage baseline). ISO arm (no alternatives visible) vs CTX arm (rotated). K per-class questions pack into ONE request.
- Test set: 4 questions, 39 claims, 137 cells with a 5-level hand reference (fate of universe MECE; city traffic policy; Late Bronze Age collapse with NONE; dolphin overlapping), contested cells flagged.
- Pre-registered gates: pairwise-order accuracy ≥.85, rule-out κ ≥.6, neutral false-move ≤20%, flip ≤10%, overlap holds. Adopt bear > compat; lik/pair only if they beat bear by .05. No per-class shape passes => stop, keep model A.
- Note: the "Jev Choice is degenerate" claim in dq2fy.29 has no recorded data; pilot re-measures it. JevJudge reads only Choice argmax, never its probabilities.
- Cost ≈ $0.05 total (534 requests). Blocker is human review of the reference matrices (Q2, Q3 especially), ideally a blind second rater.

### Emerging recommendation (not yet decided)
Idea 3 as elicitation (bear/compat per class, pending pilot) + log-linear elimination per layer + DS/m(∅) companion + Ω class + eM VoI with flip cruxes; overlapping sets as K binary roots; elimination as a separate masking step; pairwise/rank-centrality as a candidate extra layer.

### Artefacts
Scripts (combine.py, voi_categorical.py, pilot.py + testset.json) live in the session scratchpad of 2026-09-28 and are NOT durable; persisting them is an open step.
