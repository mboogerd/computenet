# First-pass survey findings (2026-09-28), condensed
- Idea 3 (binary claims + compatibility subsets) is an ELICITATION layer; it still needs a COMBINATION rule. Natural pairing: 3 (elicit which classes) + 5 (subjective logic / Dirichlet multinomial opinions with explicit uncertainty/vacuity mass, Jøsang; bijective with Dirichlet) — vacuity gives "none of these" and newly proposed classes somewhere to draw mass from.
- Dempster-Shafer (mass on 2^Θ) = formal home of idea 3; Dempster's normalised rule manufactures false certainty under high conflict (Zadeh 1979). Fixes: Smets' Transferable Belief Model (unnormalised, mass on ∅), PCR rules (Dezert-Smarandache), subjective-logic fusion operators.
- ACH (Heuer): evidence x hypothesis C/I/N matrix, same shape as idea 3; human empirical record weak/mixed (Cognitive Research PPI 2024: matrix layout gave no debiasing; transposed layout helped).
- Overlap semantics: "compatible with {A,C}" ambiguous (both true vs either). Must be fixed explicitly.
- Judge: verbalized K-way distributions poorly calibrated; pairwise judging has strong position bias (arXiv 2406.07791); pointwise noisy. No work validates LLM subset-compatibility judgments. Deliberate's own pilot: Jev Choice (pick one class) is degenerate.
- VoI: expected entropy reduction (BALD) generalises 4p(1-p); argmax-flip probability as cheap interpretable companion; expected regret needs utilities.
- Missed ideas: elimination ("rule out C") as a first-class operator; rank centrality for pairwise; open-set recognition for "none of these"; Amgoud & Prade two-phase (argue per option, then compare).
- Validation metric: Metaculus-style log score on MECE questions with known outcomes.

# Deep dive: literature (agent 4)
- No prior art for "binary claim -> edge to arbitrary class subset -> gradual semantics -> calibrated distribution": original work.
- Nearest: Gradual AA-CBR (Gould et al. 2025, arXiv 2505.15742; K-ary, one label per argument, learned weights); multi-rooted feature QBAFs for classifiers (Lertvittayakumjorn & Toni, arXiv 2205.10932; one class node per label, args attack/support class nodes, DF-QuAD once over whole graph, no normalisation, no subset edges). ArgLLM (Freedman et al., arXiv 2405.02079) is binary per claim.
- Amgoud & Prade AIJ 2009: two-phase (belief args, then option args compared pairwise by qualitative dominance); nobody grafted gradual numeric semantics on.
- Elimination: POE (Ma & Du EMNLP 2023) masks options below threshold, re-scores survivors; wins on logic-heavy sets (hard inconsistency), neutral/negative on graded plausibility; helps against locally-plausible distractors; value grows with K. Categorical defeat edge is discontinuous -> conflicts with gradual semantics' continuity axioms; no formalisation inside QBAF found. Models can game elimination (arXiv 2510.07761).
- Pairwise: PRP (Qin et al. arXiv 2306.17563) allpair O(K^2)/sort O(K log K)/sliding O(K); Rank Centrality (Negahban et al. OR 2017) gives a stationary distribution from a sparse connected comparison graph. Position bias strongest when items close (arXiv 2406.07791, DIAL 2609.31215) - exactly close calls. Khan et al. ICML 2024 debate is binary: QuALITY collapsed to top-2 first then debated.
- Best-supported compositional pattern: eliminate to shortlist, then pairwise/debate among survivors.

# VoI for a categorical root (agent 3; script scratchpad/voi/voi_categorical.py, out.txt)
- Recommended: eM = expected |change in signed leader margin M* = s_leader - max_other s| over the node resolving to 0/1 (prob p); tree part linearised via existing sensitivity g, combination evaluated exactly twice (K-dim). Reduces EXACTLY to |g|*4p(1-p) at K=2 (today's rule = linearised preposterior E|dM|).
- Entropy (gradient or expected) FAILS: rewards tail discriminators and "leaders vs tail", ~0 near uniform / at ties. Mass-moved fails similarly. Linear margin gradient blind to a third class surging past leader. Flip probability too coarse (0 under clear winner -> premature stop).
- eM: tail discriminators ~0, leader discriminators high, overtaking surge highest, graded under clear winner.
- Dirichlet additive rule defects: tail evidence dilutes leader; a compatible-with-everything claim pulls toward uniform. Likelihood rule s_k ∝ π_k Π(c ρ_ik + (1-c)(1-ρ_ik)), ρ=½ neutral, has neither.
- "All implausible" invisible to any objective on normalised s -> combination rule must carry vacuity/"none" mass.
- Stop: max eM·decay < voi-eps (0.01 keeps its meaning). Cruxes: margin cruxes (top eM) + flip cruxes ("if X false, B overtakes A").
- Architecture: read-side only — positions stay sensitivity roots emitting 1; ExplorationPolicy.valueOf evaluates eM via a pure combine(credences)->s. CRED-03 untouched. Rejected: IssueNode seeding sensitivity (linear only, re-propagation). SPEC: FRA-02/03, §3 VoI, QuestionDto.cruxes flip annotations.

# Combination rule (agent 1; scratchpad/combine/combine.py, out.md)
- Claim energy e_i = s_i·c_i (link strength x claim credence; = existing Arg). Profile κ_ik; unjudged κ => compatible (withhold). Ω ("none/other") class, prior π_Ω = max(.05, 1-Σp_k). MECE "{A,C}" = EITHER (claim eliminates complement).
- PRIMARY: log-linear elimination LL-<layer>: score_k = α log π_k − k·‖w(e_i)(1−κ_ik)‖_p, softmax. bayesVE member = exact naive Bayes with Pearl virtual evidence. EXACT K=2 continuity with every log-odds layer (wlo, woe, jnb, mlp) over 2000 random trees (max diff 0.0000). Doubted claims withhold; all-compatible claim inert; inherits p-norm damping + energy clamps (which defuse Zadeh/open-world hair trigger).
- COMPANION: Dempster-Shafer conjunctive + base-rate pignistic = DF-QuAD's K-class form (exact on one-sided trees); always report closed-frame m(∅) — the only signal for "none of these" on closed frame, Zadeh conflict, and overlap/exclusivity violation. Drop PCR6 (identical in open world, 2^n). Subjective logic not as a layer (no continuity, arbitrary N, complement variant badly wrong); vacuity u worth showing.
- Disbelief-to-complement is wrong: double counts attackers.
- Consensus = normalised log-pool (= today's Consensus at K=2); disagreement = JSD (small routine .002-.03, large .311 when frame in question).
- Class added mid-way: unjudged=>compatible gives mild free-ride (.083->.111), fixed by judging links (queue by VoI); unjudged=>incompatible kills it (~.01).
- Overlapping classes: MECE combination is wrong object; use K signed binary roots (today's machinery), answer = marginal vector. TBM m(∅)=.62 flags exclusivity violation.
- Model A: T=1 double-counts at K=2 (T=2 continuous); no Ω (1/3 in none-of-these cases); softmax over plausibility logits distorts prior (.15,.2,.7 -> .064,.091,.845). Keep only as reference layer.

# VERIFY 1: LLM gold + Jev pilot (jev-1.13.0, 534 req, ~$0.05 Jev + $0.27 Opus)
- Raters: Opus/Sol sign agreement w/ original .85-.90; Opus-vs-Sol exact .71-.83; ~12% self-change on class-order reversal. Q3 (Bronze Age) main disagreement: Opus reads evidence for one cause as against rivals, Sol independent.
- Consensus gold (sign agree all 4; rule-out needs all 4 "--"): 33 contested; rule-out cells 12 -> 9.
- Original gold: compat & elim PASS (κ .74-.81); bear fails only G5 (overlap: MARINE bearing on d1/d2/d8 .59-.70 > .625 bound — hard-coded gate, raters themselves rate d1 MARINE "+"); lik passes G1/G4 (poa .95) but doesn't beat bear by .05; pair poa .985 but order consistency .847 < .9.
- Consensus gold (strict): all per-class shapes fail G2 (κ .52-.60) -> "keep model A". Cause: Jev over-rules-out "-" cells (false positives), never misses a true "--". Majority or mixed-contested rule-out -> ADOPT compat again.
- Choice degenerate confirmed: spike .67, NO_PREFERENCE on neutral .21-.27.
- PRIOR LEAKAGE (pre-registered manual block at >.4): compat .60/.66, elim .66/.70, bear .48/.34, lik .53/.49 (CTX bear .49/.32, lik .40/.22). Agent did not apply this block rule. Coherence fine (.04).
- Files: scratchpad/verify/gold/*, pilot/responses.jsonl, analyze_{llm,orig}.txt.

# VERIFY 3: citations (12/15 verified, 3 misattributed, none fake)
- DIAL 2609.31215: does NOT show "bias strongest for close pairs" — cite Shi et al. 2406.07791 only.
- Lertvittayakumjorn & Toni 2205.10932 is binary PLR, single root: replace with Yin, Potyka, Rago & Toni arXiv 2609.02399 (class nodes, DF-QuAD, argmax; illustrative example).
- Qian 2609.08016: "debate not reliably better" is its related-work summary; own finding narrower (no detectable gain vs same committee without debate, open-ended, not budget-matched).
- LEAP 2609.01337: soften to "improves most metrics; supports multi-choice" (Brier gains mixed).
- Gould & Toni (2 authors). POE extra claims (graded plausibility, value grows with K) unverified -> drop.
- Sol: codex rejects --search.

# EXP leak (prior-leakage correction; $0.02; files exp/leak/)
- Prior subtraction (logit, fitted β, class-z) BACKFIRES: overshoots to r −.4..−.86, kills κ/POA (evidence genuinely correlates with prior in test set).
- Placebo claims (irrelevant): every 5-level shape gives exactly 0.50 → graded leak is not a per-class offset; appears only for on-topic neutral claims. compat has a pure prior offset → placebo-diff fixes compat (leak .16-.19 all golds, POA .95).
- Recommended: ISO "instr" shape = bear Score + "don't judge overall plausibility": leak .32 (consensus golds) / .44 (original), POA .97, AUC .96, graded rule-out κ .71-.81. Use graded; no correction. lik ÷ class-mean passes everywhere but κ ~.5.
- Rule-out upgrading insensitive to wording; most upgrades are cells raters themselves split on; graded bear threshold gives rule-out κ .79-.81.
- Caveat: 40 neutral cells, 11 distinct class priors, CI ±.17 → leakage verdicts near .4 are not decisive.

# EXP none-of-these (files exp/none/)
- Structural: Ω as extra option leaves conditional shares s_k = closed-world LL exactly -> only P(L) ("answer is in list") to design. Pool P(L) as binary and s separately (full K+1 log-pool leaks via AM-GM).
- R2 (debate doesn't raise none) vs R3 (all eliminated -> none high) indistinguishable from κ alone: pro-A = elimination of B. Needs a judgment "absolute vs relative objection".
- A0 hurdle: L = ordinary binary deliberate root ("the answer is one of the listed"), P(k)=P(L)·s_k. Exactly inert to listed debate (R2 = 0), exact K=2 continuity, R3 only via explicit judged none-claims. Sol recommends (REJECTED the fancier one).
- Hmin-pen: A0 + logit P(L) lowered by min_k penalty from judged-ABSOLUTE objections. R3 improves (10 decisive objections per class -> .98); leaks +.15 if Jev over-calls absoluteness (bias .3); junk-class veto. Opus ACCEPT-WITH-CHANGES on architecture; no second review of final formula.
- Reject flat Ω, per-claim κ_Ω (contrastive claims drive Ω to 1.0), TBM-open.
- New class: carve out of Ω (q·P(Ω)); carve->full jump unresolved.
- Gate: measure Jev on absolute vs comparative objections before Hmin-pen; else A0.

# EXP VoI (exp/voi/; Sol agreed-with-changes)
- Binary: today = tangent x 4p(1-p), p = Jev plausibility. Two errors: tangent AND p instead of propagated credence q. Over 300 random trees/3873 candidates vs exact E_q: today 54 premature DIMINISHING, Spearman .883; T_q .980/33 premature; exact E_p 20 premature. q semantically right (p=1 claim under decisive attack scores 0 today, E_q .050); Sol: q not guaranteed calibrated.
- Secants cost as much as exact; exact on demand = pure fn over hub snapshot, ~15 evaluate() per candidate; engine rescoring all ready tasks per dequeue worst ~4300 cheap calls; memoise per hub version; fallback: exact re-rank of top 3-5 (recovers top-1 in 94-100%).
- Categorical: eM_w(τ=1) = Σ_k s_k·E|ΔM_k| (M_k = s_k − max_{j≠k} s_j), exact, q-weighted. = binary E_q at K=2 (7e-16), continuous at ties, values broad claims, small (not 0) for tail-only. Entropy/TV/info gain/flip rejected. Linear through tree: 71 premature of 10258 vs 13 hybrid -> exact. Sol: provisional (hand-picked scenarios; invariance under duplicated classes / K change untested).

# EXP absolute vs comparative objections (exp/absolute; $0.30)
- Gold: 71 items, Opus/Sol blind κ .94 (both stable) -> 61 gold (31 abs, 28 comp); "mixed" collapsed. Caveat: Opus wrote items.
- Jev AUC .96-.99; Score 5-level: κ .83, bias up .17 (floor effect: comparative never <.14), Noul B bias .42 (bad).
- Sim leak on routine debate: Noul raw ≈ feared (+.155 max); Score raw +.09 max; Score thresholded at .5 → 0; LOQO rescale → +.04 max. Cost: all-eliminated rise +.07 vs oracle +.11.
- Verdict: adopt Hmin-pen with Score + threshold .5 (or rescale); else A0. Limits: clean author-written objections; 0/28 comparative >.5 still allows up to ~11%.

# EXP VoI invariance (exp/voi2; Sol ACCEPT-WITH-CHANGES)
- SWITCH eM_w -> eTV = E_q Σ_k |s_k^r − s_k| (2x expected total-variation move), exact, q-weighted.
- eTV exactly invariant to duplicated classes (eM_w changes median 52% when leader split) and relabelling; ~invariant to tiny irrelevant class; decays with K like the EVSI reference (eM_w decays 6x).
- vs non-circular references (preposterior EVSI log/Brier; MC-to-completion): eTV Spearman .966/.974/.693 vs eM_w .943/.964/.673; top-pick capture eTV .948 vs eM_w .900 (EVSI_log .993 but no K=2 reduction, units nats). Matched-rate premature stops eTV 172 vs eM_w 275.
- Hurdle: joint eTV on K+1 splits exactly: node under L -> 2E|ΔP(L)|; listed node -> P(L)·eTV_closed(s). K=2 reductions hold.
- Costs: tail reshuffles count; voi-eps needs re-tuning (eTV ~2x eM_w for K>2). Sol: MC refs are model-internal plug-in gains (model not coherent); duplication invariance only when aliases; unproven: calibration, external validity, seeds, sequential querying, nodes affecting both L and s, eps across K.
- NOTE: this reverses the round-1 VoI finding that rejected mass-moved/TV (that was against hand scenarios and the argmax intuition).

# EXP recursive deliberation (exp/recursive; $16.64; real engine at c6dcef98, positions pinned via claude shim; max-claims 90)
- 8 future-event questions: Opus direct +38.7, one-level P +21.3, recursive LL +4.4, recursive model A +1.8, uniform 0. Recursion added nothing: ignoring subtrees gives +6.2 (helped 4, hurt 4).
- Why: depth-2+ claims about 2026 events -> Jev OUTSIDE_MY_KNOWLEDGE (29-66%); budget spent on link-arguments/undercutters (70-79/90 claims ended BUDGET, only 9-16 explored); propagated credence differs from plausibility by .004-.03; 180-claim run ≈ 90.
- Per-position trees argue only about their own position -> no discriminating arguments -> worse than one level of contrastive arguments judged per class (P). All position first impressions 0.5.
- Opus direct best 6/8; lost World Cup (spread) and Eurovision (confidently wrong; damping helped).

## Round 4 experiments, 2026-09-28 (files in the local, gitignored data/deliberate-multiclass/ on MacBoo; supersedes conflicting points above)

### VoI: switch to eTV (exp/voi2; Sol ACCEPT-WITH-CHANGES) — reverses the eM choice
eTV = E_q Σ_k |s_k^r − s_k| (exact, q-weighted). Exactly invariant to duplicated classes (eM_w changed a median 52% when the leader was split) and relabelling; ~invariant to a tiny irrelevant class; decays with K like the reference. Against non-circular references (preposterior score gain, MC to completion): Spearman .966/.974/.693 vs eM_w .943/.964/.673; top-pick capture .948 vs .900; fewer premature stops at matched rate (172 vs 275). Reduces to binary E_q at K=2. Hurdle: joint eTV splits exactly — node under L gets 2E|ΔP(L)|, listed node gets P(L)·eTV(s). Costs: tail reshuffles count; --voi-eps needs re-tuning for K>2. Unproven: calibration under a coherent model, external validity, seeds, nodes moving both L and s.

### None-of-these gate: Jev absolute vs comparative objections (exp/absolute, ~$0.30)
Opus/Sol blind gold (κ .94) on 61 of 71 objection items. Jev AUC .96-.99; 5-level Score κ .83, bias toward "absolute" .17 on comparative items (floor effect); one Noul paraphrase .42. Simulated routine-debate leak into Ω: raw Score max +.09; Score thresholded at .5 -> 0 (rescale -> +.04); all-eliminated rise +.07 vs oracle +.11. Verdict: Hmin-pen is adoptable with Score + threshold .5; A0 otherwise.

### Full recursive deliberation (exp/recursive, $16.64; real engine c6dcef98, positions pinned via a claude-CLI framing shim, --max-claims 90)
8 future-event questions: Opus direct +38.7, one-level P +21.3, recursive LL +4.4, recursive model A +1.8. Recursion added nothing (ignoring subtrees: +6.2; helped 4, hurt 4): deep claims about 2026 are OUTSIDE_MY_KNOWLEDGE, budget goes to link-arguments/undercutters (only 9-16 of 90 claims explored), 180 claims ≈ 90. Per-position trees contain no discriminating arguments, which is worse than one level of contrastive arguments judged per class.

### Knowledge domain + calibrated scale + LLM prior (exp/e2e2, ~$2.45)
50 knowledge questions (30 SuperGPQA hard incl. 12 with a "none of the listed" option, 20 Wikidata born-first / furthest-north) + the 30 forecasting ones; leave-one-question-out CV. Jev engaged on knowledge (2% OUTSIDE_MY_KNOWLEDGE). Opus direct too strong on knowledge (90% top-1).
Pooled Metaculus baseline: Opus direct 56.1; arguments alone with CV scale 19.4 (−36.7 vs Opus, significant); P-prior (Opus distribution + argument adjustment, CV scale) 55.6-56.0 (−0.5 [−2.5, +1.2] vs Opus; vs Opus mean-of-5 −0.0 [−1.4, +1.0]); adding Sol to the prior hurts; CV shrinks the argument weight to ≈0.5 (0 with the Opus+Sol prior); in-sample best gain .005 nats/question. The adjustment never changed a top-1 answer on the knowledge set. Arguments alone handle "which is the extreme" questions badly (Wikidata 30-40% top-1) and none-of-these-true badly (1/6). Where Opus was wrong, arguments put more on the truth, but too rarely and weakly to pay on average.

### Conclusion of the exploration so far
The MECHANISM is sound and verified: closed-world log-linear combination (exact K=2 continuity), none-of-these as a hurdle root (optionally Hmin-pen with thresholded Score), exact q-weighted VoI with eTV, graded bearing with the "don't judge plausibility" instruction. The VALUE is not shown: one level of judged arguments carries almost nothing beyond what the proposer already knows; recursion adds nothing on future events; as an adjustment to an LLM prior it is accuracy-neutral. Credible remaining roles: (a) explanation/audit layer — model-D style, the LLM's distribution as first impression with the arguments-alone distribution and cruxes shown separately; (b) disagreement signal where arguments-alone and the LLM prior diverge (the Opus-wrong cases). An accuracy claim would need a harder question set where Opus is ~50%, and discriminating (contrastive) arguments at depth, not per-position trees.
