# Multi-class questions in deliberate: research report

Bead: `computenet-1ow0x` (exploration, parent epic `computenet-dq2fy`).
Research carried out 2026-09-28, at `origin/main` ≈ `73d14b2d`…`c6dcef98`.
Author: a Claude Opus 5.5 orchestrating session, which dispatched about 20
sub-agents. At the user's request, Opus 5.5 and Sol (`gpt-5.6-sol` via
`codex exec`) stood in for human review.

This report is written so that an agent with no other context can consolidate
it with parallel experiments and make design decisions. Every number here is
from a run whose artefacts are listed in §11. Claims that were made and later
refuted are collected in §10, because several reversals matter for the design.

---

## 0. Summary

**Question.** Can deliberate settle a question with several candidate answers
("which of A, B, C, D?") as a probability distribution, with the same rigour
as a yes/no claim? The method would be the same: LLM-proposed arguments,
Jev-judged, credence propagated through dataflow cells, exploration priced by
value of information.

**Short answer.**

- **The mechanism works and was checked independently.**
  - A closed-world log-linear elimination rule turns claim credences into a
    distribution. At K=2 it equals every existing log-odds layer exactly.
  - "None of these" is a separate binary hurdle root.
  - Exploration is priced by the exact expected mass moved (eTV), weighted by
    propagated credence.
  - Jev supplies a graded per-class bearing judgment.
- **The accuracy value is not shown.** Tested on 80 questions with known answers
  (30 future-event forecasts resolved after the models' cutoff, 50 knowledge
  questions):
  - One level of judged arguments on its own is far worse than asking Opus
    for a distribution directly: −37 Metaculus points pooled.
  - As an adjustment to Opus's own distribution it is accuracy-neutral:
    −0.5 [−2.5, +1.2].
  - Full recursive deliberation with the real engine, 8 questions, added
    nothing over one level.
- **Shipped defects found along the way.** Model A (the shipped POSITIONS shares)
  and model C (the shipped VoI) both have defects, filed as `computenet-x91yk`
  and `computenet-dw2wh`.

**Design implication.** The evidence supports:
- building multi-class as an **explanation, audit and disagreement layer**
  around an LLM first impression, in the spirit of model D;
- **not** building it as an accuracy engine that replaces asking the model.

Whether an accuracy claim is achievable is still open. It would need a harder
question set and contrastive arguments at depth (§8.3).

---

## 1. Research question and scope

### 1.1 Question as refined with the user

The user's refinement: explore how deliberate could settle classification
questions with the same rigour and philosophy as its yes/no claims, **without
presupposing model A or any new mechanism**. The deliverable is a map of the
solution space, a recommendation, and implementation beads.

### 1.2 Scope decisions (user, 2026-09-28)

| Axis | Decision |
|---|---|
| A. Answer object | A **probability distribution** over the classes. Rankings, a winner with a margin, and defensible sets are out of scope. |
| B. Class sets | MECE was the initial lean, but **overlapping and growing** class sets are in the analysis. Look for one approach that works for all, allowing specialisations. |
| C. Argument semantics | The core open problem; the user asked for ideas. |
| D–G | Research questions: what Jev is asked, aggregation, exploration/stopping, UI. |

### 1.3 The yardstick: what "the same rigour" means

Every option was scored against the seven principles deliberate already embodies:

1. Generation is separate from judgment: LLMs propose, Jev judges, and neither
   grades its own work.
2. The verdict is derived, never asserted. It comes from judged arguments
   through a transparent semantics.
3. Pluralism is surfaced: several semantics run as layers, the consensus is
   reported, and disagreement is shown.
4. The first impression is kept apart from what the arguments alone establish
   (model D).
5. Exploration is priced: dig where it could change the answer, and stop when
   nothing could (model C).
6. Cruxes are explicit: the system can say what would change its mind.
7. The human can steer: override, expand, stop.

### 1.4 The core difficulty

In the binary case, "against X" fully determines where credence goes: to not-X.
With K classes, "against A" does not say which of B, C, D gains. Every
modelling option is an answer to **"where does the moved credence go?"**

---

## 2. Baseline: model A as shipped

Model A (`computenet-dq2fy.29`, PR #1143) handles open questions like this:

- The Framer splits an open question into up to 5 POSITIONS.
- Each position is its own binary root with its own argument tree, first
  impression, model D view and model C sensitivity root, where VoI = 1 at the
  root.
- An `IssueNode` folds the positions' credences into shares with
  `Softmax.shares`: normalised odds, `T = 1`.
- It is one-way: nothing flows back into credence or sensitivity (FRA-03).

Defects found (verified, filed as `computenet-x91yk`):

- **Over-sharpening.** For complementary positions, T=1 gives
  `σ(2·logit p)`, so a 0.7 verdict shows as 0.84. T=2 is continuous only in
  that special case.
- **No "none of these".** If every position is implausible, the shares are
  still 1/n.
- **Prior sharpening.** First impressions (.15, .2, .7) become shares
  (.064, .091, .845).
- **VoI ignores the distribution.** Each position is a root with VoI 1, so a
  3% position is explored as eagerly as the frontrunner.
- **No argument can discriminate between positions.** Each tree argues only
  about its own position. §7.3 shows empirically that this matters.

The dq2fy.29 pilot said "Jev Choice is degenerate", but no data was recorded.
It has now been measured: 67% of Choice answers put ≥ 0.8 on one option even
for claims with no bearing, and NO_PREFERENCE on neutral claims is only
.21–.27. On forecasting questions Choice scored −74 Metaculus points.

---

## 3. Methods

### 3.1 Process

The work ran in rounds. In each round, sub-agents did design, simulation or
paid experiments, and each round was then adversarially verified by Opus 5.5
and Sol, independently. Human review of gold standards was replaced by blind
double rating by Opus and Sol, with class order reversed to catch order
effects. The user accepted this as a substitute ("I'm not going to be more
factual than those two combined").

| Round | Work |
|---|---|
| 1 | Literature survey (2 passes); combination-rule simulation; categorical VoI derivation; Jev pilot design |
| 2 | Verification: blind gold by Opus+Sol and a Jev pilot run; adversarial maths review against faithful Kotlin ports (Opus and Sol); citation check against primary sources |
| 3 | Prior-leakage correction; none-of-these design and simulation (reviewed by Opus and Sol); exact vs linear VoI (Sol-reviewed); end-to-end accuracy on 30 forecasting questions |
| 4 | Knowledge-domain accuracy with cross-validated scale and an LLM prior; full recursive deliberation with the real engine; the Jev absolute-vs-comparative gate; VoI invariance against non-circular references (Sol-reviewed) |

### 3.2 Models and tools

| Model or tool | Role |
|---|---|
| Opus 5.5 | Newest bundled `claude` CLI: `-p --bare --model claude-opus-5-5 --tools ""`. Used for rater, proposer, direct forecaster and reviewer. |
| Sol | `codex exec -s read-only -m gpt-5.6-sol`, with no web search (`--search` is rejected). Used for rater, reviewer and direct forecaster. |
| Jev | `jev-1.13.0` via the TypeSafe API. Used for plausibility, per-class judgments and Choice. |
| Simulations | Python (stdlib). Every layer was **re-ported from the Kotlin source** (`Semantics.kt`, agora `DfQuad`, `Sensitivity.kt`, `ExplorationPolicy.kt`) and checked to ≤ 5e-16. |
| Real engine | `:demo:deliberate:installDist` at `c6dcef98`. Positions were pinned with a `claude` shim that answers only the framing prompt. |

### 3.3 Accuracy scoring

- Metaculus baseline score, `100·ln(pK)/ln K`, where uniform = 0.
- Also log score, Brier, top-1 accuracy and ECE.
- 95% bootstrap CIs, **paired** differences against Opus direct, and
  leave-one-question-out CV for any fitted parameter.

### 3.4 Cost

About $28 in total:

| Experiment | Cost |
|---|---|
| Recursive engine runs | $16.6 |
| Forecasting end-to-end | $7.6 |
| Knowledge end-to-end | $2.5 |
| Pilots, raters and reviews | ≈ $1 |
| Sol | Not metered in dollars |

---

## 4. Findings: prior art

Citations were checked against primary sources: 12 of 15 verified, 3 corrected.
None were fabricated.

- **No published work** does "a binary claim, linked to an arbitrary subset of
  classes, propagated by gradual semantics, read off as a calibrated
  distribution". This is original design work. The nearest halves:
  - **Gradual AA-CBR** (Gould & Toni, arXiv 2505.15742). Multi-class, but
    each argument carries one label and strengths are learned.
  - **A class-node QBAF under DF-QuAD with argmax** (Yin, Potyka, Rago &
    Toni, arXiv 2609.02399). This is an illustrative example in that paper,
    with no normalisation and no subset edges. *Correction:* this was first
    misattributed to Lertvittayakumjorn & Toni 2205.10932, which is binary
    logistic regression.
  - ArgLLM (Freedman et al., arXiv 2405.02079) is binary.
  - Amgoud & Prade (AIJ 173, 2009) is two-phase: argue beliefs and options,
    then compare pairs of options by decision principles.
- **ACH (Heuer)** has the same shape as a compatibility matrix. Its empirical
  record with humans is weak: Dhami et al. 2024 (CRPI) found that the matrix
  layout did not reduce confirmation bias. **Dempster–Shafer** is its formal
  home, and Zadeh's conflict case is the known hazard.
- **Judges.**
  - Option-order bias is large (Zheng et al., ICLR 2024).
  - Pairwise position bias is worse for close pairs (Shi et al., arXiv
    2406.07791). *Correction:* DIAL 2609.31215 does not support this.
  - LEAP (arXiv 2609.01337) found that per-evidence likelihood elicitation
    plus deterministic aggregation improves most forecasting metrics, including
    multi-choice; Brier gains were mixed.
  - Consistency checks correlate with forecasting accuracy (Paleka et al.,
    arXiv 2412.18544).
- **Elimination.** POE (Ma & Du, EMNLP 2023) masks options judged wrong and
  then re-predicts; it is strongest on logical reasoning. A hard defeat edge
  would break gradual semantics' continuity axioms.
- **Pairwise.**
  - PRP (Qin et al., arXiv 2306.17563) has allpair O(N²), sorting O(N log N)
    and sliding O(N) variants.
  - Rank Centrality (Negahban et al., OR 2017) gives a stationary distribution
    from a sparse comparison graph.
  - Khan et al.'s debate (arXiv 2402.06782) uses two answers, the correct one
    and the best distractor.
- **Debate evidence is mixed.** *Correction:* do not cite Qian 2609.08016 for
  "debate is not better"; that sentence summarises other papers.

---

## 5. Findings: the mechanism

### 5.1 Five candidate answers to "where does credence go"

| # | Idea | First-pass fate |
|---|---|---|
| 1 | Each argument carries a likelihood profile P(E\|class) | Became the **bayesVE** member of the log-linear family (5.2) |
| 2 | Pairwise contrasts plus Bradley–Terry / rank centrality | Kept only as an optional extra layer: Jev pairwise was 96–98% order-accurate but only 85% order-consistent |
| 3 | Binary claims plus a compatibility link to a subset of classes (the user's lean) | **Survived as the elicitation layer**, graded rather than 0/1 |
| 4 | MCDA criteria | Fits "which should we do", not "which is true". Not pursued |
| 5 | Dirichlet / subjective-logic counts | **Rejected** as a layer: no K=2 continuity, and the evidence scale is arbitrary |

### 5.2 Combination rule: closed-world log-linear elimination (CONFIRMED)

For each log-odds layer L ∈ {wlo, woe, jnb, mlp}, with claims i, class k, and
per-class compatibility κ_ik ∈ [0, 1]:

```
score_k = α·log π_k − k_L · ‖ { w_L(e_i) · (1 − κ_ik) }_i ‖_{p_L}
s = softmax(score)
```

Here `e_i` is the claim's energy: strength × credence, except that jnb must use
its own Jeffrey energy of (strength, credence). Consensus across layers is a
normalised log-pool, which is exactly today's `Consensus` at K=2. Disagreement
between layers is Jensen–Shannon divergence.

- **Exact at K=2** (≤ 5e-16) for all four log-odds layers, over random trees,
  3 seeds, up to 20 arguments and extreme values. Two conditions:
  - the base must lie inside the layer's `[.01, .99]` clamp;
  - jnb must use its own energy.

  This was verified independently by an Opus agent and Sol, against fresh
  Kotlin ports.
- **Behaviour.**
  - A doubted claim withholds. Sending disbelief to the complement
    double-counts the claim's attackers.
  - A claim compatible with every class is inert.
  - The layers' energy clamps are what keep Zadeh-style false certainty down.
- **DF-QuAD's K-class analogue:** Dempster conjunctive + base-rate pignistic
  equals DF-QuAD only on **one-sided** trees, and diverges by up to .93 on
  two-sided trees. Undecided.
- **Closed-frame conflict mass m(∅) is not a none-of-these detector.** It is
  non-zero on every two-sided node, so it measures contestedness (REFUTED).
- **Overlapping classes: a single distribution is the wrong object.** It split
  two causes with 86% and 85% marginals into 42% / 38%. Use K signed binary
  roots (today's machinery) and report a marginal vector. This is not disputed
  but was not separately tested.

### 5.3 None of these: a hurdle root

- **Structural result.** Adding Ω as one more softmax option leaves the
  conditional shares `s_k = P(k | listed)` exactly equal to the closed-world
  result (3e-16). Only one number, P(L), where L = "the answer is one of the
  listed", needs design.
- **Ω inside the softmax fails.**
  - It breaks K=2 continuity: 0.12–0.23 off.
  - Ordinary two-sided debate leaks into it: 3 strong pros and 3 strong cons
    on a 50/50 question leave Ω at .38, leading.
- **Impossibility.** From compatibility links alone, "3 pros for A + 3 cons
  against A" is indistinguishable from "every class eliminated once", because
  a pro-A claim eliminates B. Separating the two needs a judgment of whether
  an objection is **absolute or comparative**.
- **A0 hurdle (recommended baseline; Sol's pick).**
  - L is its own ordinary binary deliberate root: argued, Jev-judged, with a
    first impression.
  - `P(k) = P(L)·s_k`, `P(Ω) = 1 − P(L)`.
  - Pool P(L) as a binary and s separately. Pooling the full K+1 vector leaks
    through the AM-GM inequality.
  - A0 is exactly inert to debate among the listed classes and exact at K=2.
    "None" rises only through explicit, judged claims on L.
- **Hmin-pen extension.**
  - logit P(L) is lowered by the least-objected class's penalty, counting only
    objections Jev judges **absolute**.
  - With an oracle judge, 10 decisive objections per class drive Ω to .98.
  - **Gate measured:** Opus and Sol blind-rated gold (κ .94, 61 items).
  - Jev AUC was .96–.99. The 5-level Score over-calls absoluteness by .17
    (a floor effect); one Noul paraphrase over-calls by .42.
  - Simulated routine-debate leak into Ω:
    - raw Score: max +.09;
    - Score thresholded at .5: 0;
    - rescaled: +.04.

    The price is detection: the all-eliminated rise is +.07 against the
    oracle's +.11.
  - **Adoptable with Score plus a .5 threshold.** Opus accepted the
    architecture with changes; the final formula was never reviewed.
- **Rejected.** Flat Ω; per-claim κ_Ω, where contrastive claims drive Ω to
  1.0; TBM-open.
- **Open.**
  - A new class currently starts by being carved out of Ω, `q·P(Ω)`, and its
    value jumps once its links are judged.
  - When Jev's direct impression of L disagrees with its per-class
    plausibilities, there is no rule yet.
  - A junk class can veto the Hmin-pen.

### 5.4 Judge elicitation

**Pilot:** 4 questions, 39 claims, 137 cells.
- The questions: fate of the universe (MECE), a city traffic policy, the Late
  Bronze Age collapse (with NONE), and the dolphin (overlapping).
- 534 Jev requests (≈ $0.05).

**Gold:** Opus and Sol each rated every cell blind, twice, with class order
reversed.
- Sign agreement with the original reference: .85–.90.
- Each rater changed about 12% of cells when the class order was reversed.
- They disagreed most on the Bronze Age question. Opus reads evidence for one
  cause as evidence against its rivals; Sol rates the causes independently.

| Shape (Jev) | Result |
|---|---|
| `compat` (0/1 Noul "could X still be the answer?") | Passes against the original gold (rule-out κ .74–.81); fails the strict consensus gold (κ .52–.60) because Jev **upgrades "counts against" to "rules out"** (it never misses a true rule-out). **Prior leakage .60–.66.** |
| `elim` (Noul "rules out X?") | Like compat; leakage .66–.70 |
| `lik` (Score, P(claim \| X)) | Orders classes 95% correctly; leakage .49–.53 |
| `bear` (5-level Score, from rules out to settles) | Orders 96–97% correctly. Fails only a hard-coded overlap gate, which the raters themselves dispute. Leakage .34–.48 |
| **`instr`** (bear plus "don't judge the answer's overall plausibility") | **Recommended.** Leakage .32 against the consensus golds and .44 against the original; orders 97% correctly; rule-out κ as a graded signal .71–.81 |
| pair (Choice, both orders) | 96–98% order-accurate, 85% order-consistent |
| Choice | Degenerate (see §2) |

**Prior leakage** is the correlation, on no-bearing cells, between Jev's
judgment and Jev's own no-claim plausibility of the class.
- **Subtracting the prior backfires**, whether at logit β=1, with a fitted β,
  or with class z-scoring. It overshoots to r −.4…−.86 and destroys κ. On
  end-to-end forecasting, β=1 cost 10 points.
- Placebo claims (irrelevant topics) get exactly "no bearing" from every graded
  shape. So the leak is not a per-class offset: it appears only for on-topic
  neutral claims.
- With n = 40 neutral cells and CI ±.17, leakage verdicts near .4 are **not
  decisive**.

**Rule.** Use Jev's per-class judgments **graded**, never as hard elimination.

### 5.5 Value of information

**Binary, today's model C (bug `computenet-dw2wh`).** VoI is
`|d root/d node| · 4p(1−p)`: a tangent, weighted by Jev plausibility p. It has
two errors:

1. **The tangent.** Near energy clamps the gradient is 0 while the true value
   is not. With a woe headline: 0 against an exact .059. With the consensus
   headline: .065 against .082.
2. **p instead of propagated credence q.** A p=1 claim under a decisive attack
   (q = .949) scores 0 today, while its exact value is .050.

Over 300 random trees (3873 candidates), measured against the exact
`E_q = 2(q|R(1)−R| + (1−q)|R(0)−R|)`:

| Variant | Premature DIMINISHING | Spearman |
|---|---|---|
| Today | 54 | .883 |
| Tangent × 4q(1−q) | 33 | .980 |
| **Exact, on demand** | 0 | 1 |

- Secants cost the same as exact and do no better.
- Exact on demand costs about 15 `evaluate()` calls per candidate. It is a
  pure function of the hub snapshot, memoised per hub version.
- Fallback: re-rank only the top 3–5 exactly, which recovers the exact top-1
  in 94–100% of trees.
- Sol agreed with changes: benchmark wall time and cache invalidation first,
  and treat q as a proxy that is not guaranteed to be calibrated.

**Categorical.** The recommendation changed twice (see §10). Final:
**eTV = E_q Σ_k |s_k^r − s_k|**, twice the expected total-variation move,
computed exactly and q-weighted.

- It equals binary E_q at K=2 (6.7e-16).
- It is exactly invariant to duplicating a class and to relabelling, and
  nearly invariant to a tiny irrelevant class. The margin objective eM_w
  changed by a median of 52% when the leader was split.
- Against **non-circular** references (preposterior expected score gain, and
  Monte Carlo resolution to completion), eTV beats the alternatives:

  | Objective | Spearman (EVSI log / EVSI Brier / MC log) | Top-pick capture | Premature stops at matched rate |
  |---|---|---|---|
  | **eTV** | .966 / .974 / .693 | .948 | 172 |
  | eM_w | .943 / .964 / .673 | .900 | 275 |
  | Entropy / info gain / flip probability | inferior | | |

- **Over the hurdle**, joint eTV on the K+1 vector splits exactly:
  - a node under L gets `2·E|ΔP(L)|`;
  - a listed node gets `P(L)·eTV(s)`.
- **Costs.** Tail-only reshuffles now count. `--voi-eps` needs re-tuning for
  K > 2, because eTV runs at about 2× eM_w.
- **Still unproven** (per Sol):
  - calibration under a coherent joint model;
  - external validity beyond the simulator;
  - replication across seeds;
  - sequential querying;
  - nodes that move both L and s.

**Linearisation through the tree must go**, for both binary and categorical
VoI: it produced 71 premature stops out of 10,258 categorical nodes, against 13
with exact evaluation.

---

## 6. Findings: sub-agent reviews

Every mechanism claim above that says "confirmed" was reproduced by an Opus
agent writing its own Kotlin ports and by Sol running or reading the scripts.
The reviews found real bugs in first-pass scripts; §10 lists the consequential
ones. Reviewers did not always agree. Example: on the Hmin-pen, Sol said REJECT
and Opus said ACCEPT-WITH-CHANGES. The report states both where they differ.

---

## 7. Findings: accuracy

### 7.1 One level of arguments on forecasting (30 questions)

Setup:
- Manifold multiple-choice markets resolved between 2026-07-01 and 2026-09-27,
  all after the models' cutoff. K 3–8; OTHER was the true answer in 4.
- Pipeline P: Opus proposes about 10 atomic claims; Jev judges plausibility and
  per-class `bear`; closed-world LL with woe+mlp log-pooled.

Metaculus baseline scores:

| Arm | Score |
|---|---|
| P | +5.6 [1.2, 10.8] |
| P with Sol proposing | +4.7 (−0.9 vs P) |
| Model A | 0.0: Jev said OUTSIDE_MY_KNOWLEDGE for all 209 positions |
| Jev Choice | −74 |
| **Opus direct** | **+17.7** [1.7, 33.5]; paired +12.2 [−2.0, +25.7] over P |

- P was badly under-confident: mean claim energy .12, and 41% of claims were
  withheld as outside Jev's knowledge.

### 7.2 Knowledge domain, CV-calibrated scale, LLM prior (80 questions pooled)

Setup:
- 50 knowledge questions:
  - 30 SuperGPQA-hard items, including 12 with a "none of the listed" option
    (6 where it is correct);
  - 20 built from Wikidata (born-first, furthest-north).
- Plus the 30 forecasting questions. The Jev shape was `instr`, and the scale
  was chosen by leave-one-question-out CV.
- Jev engaged on knowledge: only 2% of claims were outside its knowledge.
- **The knowledge set turned out too easy:** Opus direct got 90% top-1.

| Arm (pooled) | Score | vs Opus direct |
|---|---|---|
| Opus direct | 56.1 [44.1, 66.9] | — |
| Opus mean of 5 | 55.6 | |
| Sol direct | 43.0 | −13.0* |
| Arguments alone, CV scale | 19.4 | **−36.7 [−51.9, −22.9]*** |
| **Opus prior + argument adjustment (CV scale)** | 55.6–56.0 | **−0.5 [−2.5, +1.2]**; vs mean of 5: −0.0 [−1.4, +1.0] |
| Opus+Sol prior + adjustment | 52.6 | −3.5 (Brier significantly worse) |
| Model A | 13.8 | |

- **CV scale.**
  - For arguments alone: ×3 pooled (×2 on knowledge, ×6 on forecasting). The
    original scale was 2–6× too small, and the right scale depends on domain.
  - As an adjustment: ×0.5; ×0 with the Opus+Sol prior.
  - In-sample best gain: .005 nats per question.
- **The adjustment never changed a top-1 answer** on the knowledge set.
- **Arguments alone are structurally weak on:**
  - "which is the extreme" questions: 30–40% top-1 on Wikidata, because
    "X was born in 1840" only bears on X relative to the others;
  - none-of-these-true: 1 of 6.
- On the 5 knowledge items Opus got wrong, arguments put more weight on the
  truth (.14–.36 against .04–.11), but too rarely and too weakly to pay on
  average.

### 7.3 Full recursive deliberation with the real engine (8 questions)

Setup:
- Real engine at `c6dcef98`, positions pinned to the options, defaults except
  `--max-claims 90`. Proposers were Sonnet 5 and Sol. Cost $16.6.

| Arm | Score |
|---|---|
| Opus direct | +38.7 |
| One-level P | +21.3 |
| Recursive LL | +4.4 |
| Recursive model A | +1.8 |
| Recursive, subtrees ignored | +6.2 |

- **Recursion added nothing.** It helped on 4 questions and hurt on 4.
  - Deep claims about 2026 are outside Jev's knowledge.
  - The budget goes on link-arguments and undercutters: 70–79 of 90 claims
    ended BUDGET, and only 9–16 were explored.
  - Propagated credence differs from plausibility by only .004–.03.
  - 180 claims gave about the same result as 90.
- **Per-position trees contain no discriminating arguments.** That is why they
  lose to one level of contrastive arguments judged per class. This is the
  most design-relevant accuracy finding.
- n = 8, all future events: read as patterns, not significance.

---

## 8. Conclusions

### 8.1 What is established

1. Multi-class can be done in deliberate's philosophy **without a new claim
   type**:
   - arguments stay binary claims;
   - a graded per-class bearing judgment is added;
   - a closed-world log-linear rule, one per existing layer, is exact at K=2;
   - "none of these" is its own binary root.
2. Model A's position-per-tree design is the wrong shape. It has no
   discriminating arguments, sharpens the prior and has no "none". Its shares
   formula is also defective.
3. Model C's VoI should be exact and q-weighted, for binary questions too.
4. Jev can supply per-class judgments that order classes well (≈ .97), but
   they leak its class prior and over-call "rules out". Use them graded. Do not
   use Jev Choice.

### 8.2 What is not established

- **Any accuracy gain over asking Opus directly.** On knowledge questions and
  forecasts, one level or recursive, alone or as an adjustment.

The likeliest reason: **arguments proposed by an LLM carry little that the same
LLM's direct distribution lacks**, and Jev (a smaller judgment model) cannot
add knowledge beyond the proposer's. The only positive signals are:

- damping a confidently wrong LLM (Eurovision in both forecasting runs);
- putting more weight on the truth where Opus was wrong (5 knowledge items).

Both are too rare to move averages at these n.

### 8.3 What would change the conclusion

- A harder question set, where Opus direct gets about 50% top-1. Both sets
  here were either too easy (knowledge, 90%) or out of Jev's knowledge
  (future events).
- **Contrastive arguments at depth.** Recursion over arguments that bear on
  several classes, rather than per-position trees. This was never tested,
  because the engine has no such mode yet.
- Arguments grounded in retrieved evidence, i.e. knowledge the proposer lacks.
  Out of scope here.

---

## 9. Solution design implications

### 9.1 Recommended architecture, if multi-class is built

```
question ──framing──► listed classes C1..CK  (+ hurdle root L: "the answer is one of the listed")
                           │
  proposer (sees ALL classes) ─► binary argument claims ─► Jev: plausibility, strength,
                                                            per-class bearing (instr shape, graded)
                           │
  per layer L ∈ {wlo, woe, jnb, mlp}:  s^L = softmax(α log π − k_L ‖w_L(e)(1−κ)‖_p)
  consensus s = log-pool(s^L); layer disagreement = JSD
  P(k) = P(L)·s_k ;  P(none) = 1 − P(L)      (pool P(L) and s separately)
                           │
  VoI(node) = exact joint eTV, q-weighted (node under L: 2E|ΔP(L)|; listed: P(L)·eTV(s))
  cruxes: nodes with the largest eTV; annotate those whose resolution flips the argmax
```

| Component | Decision strength |
|---|---|
| Closed-world log-linear per log-odds layer; log-pool consensus; JSD disagreement | **Strong** (verified twice) |
| Hurdle root L, with P(L) and s pooled separately (A0) | **Strong** (Sol and Opus agree on the architecture) |
| Hmin-pen (absolute objections lower P(L)), Score thresholded at .5 | Medium: gate passed on clean items; formula unreviewed; junk-class veto open |
| Jev `instr` bearing, graded; never hard 0/1; no prior subtraction | Medium: leakage near the threshold, n small |
| VoI = exact q-weighted eTV | Medium-strong: beats the alternatives on non-circular references; eps needs re-tuning; calibration unproven |
| Overlapping class sets as K signed binary roots with a marginal vector | Medium: argued, not tested |
| DS/pignistic as DF-QuAD's K-class layer | Weak: one-sided only |
| Pairwise / rank-centrality layer | Optional; needs both-order averaging |
| Proposer prompt: must see all classes and produce discriminating and none-of-these arguments | **Strong**: per-position trees lose (§7.3); "none" claims are the only route to P(none) under A0 |

### 9.2 What the evidence says to build it *for*

Accuracy did not improve, so the defensible product role is model D's pattern,
lifted to K classes:

- **First impression:** the LLM's direct distribution, or Jev's where it has
  knowledge.
- **Arguments alone:** the LL distribution from judged arguments with a
  uniform prior, shown **separately**, with cruxes.
- **Disagreement flag:** when the two diverge, which was where Opus was wrong.
- **The combined verdict** (prior plus a CV-scaled adjustment) is at best
  accuracy-neutral. Show it, but do not claim it is better.

This keeps principles 1–7 (transparency, cruxes, steerability), and stays
honest that the numeric verdict is not more accurate than the model's own.

### 9.3 Options for the next step (from the user decision point)

- **(a)** Build the explanation/audit version above.
- **(b)** One more accuracy push first: a harder set (Opus ≈ 50%) plus
  contrastive arguments at depth.
- **(c)** Close the exploration with the negative accuracy result recorded, and
  ship only the two standalone bug fixes.

These are not exclusive. The two bugs stand on their own merits under any
option.

### 9.4 Do-not-build list

- Ω inside the softmax.
- Per-claim κ_Ω.
- Closed-frame m(∅) as a "none" signal.
- Subjective logic as a layer.
- Jev Choice.
- Hard 0/1 elimination from Jev.
- Prior subtraction.
- Tangent (linearised) VoI.
- Entropy, information-gain or flip-probability VoI.
- Per-position recursive trees as the multi-class engine.

---

## 10. Reversals and corrections (read before reusing any earlier claim)

| Earlier claim | Status |
|---|---|
| "Log-linear rule is exact at K=2 for every layer, with an Ω class" | **Refuted with Ω.** Exact only closed-world, base in clamp, and jnb energy separate |
| "Ω with a residual prior handles none-of-these" | **Refuted.** Leaks under debate; replaced by the hurdle root |
| "Closed-frame m(∅) detects none-of-these / exclusivity violation" | **Refuted.** Fires on every two-sided node |
| "eM (leader margin) VoI reduces exactly to today's rule" | **Refuted.** The first check was tautological; true only with a linear combination, q = p and a closed frame |
| "Entropy and mass-moved VoI fail" (round 1, hand scenarios) | **Reversed for mass-moved.** eTV wins against non-circular references (round 4). Entropy still fails |
| "eM_w (probability-weighted margin) is the categorical VoI" (round 3) | **Superseded by eTV.** Not invariant to duplicated classes; weaker against references |
| "Surging third class scores highest under eM" | **Artefact** of scenario construction |
| "Jev Choice is degenerate" (dq2fy.29, no data) | **Confirmed with data** |
| "Subtract Jev's class prior to fix leakage" | **Refuted.** Overshoots; hurts accuracy |
| "Recursive deliberation is the remaining route to value" | **Tested (§7.3): no gain on future events.** Contrastive recursion remains untested |
| Citations: DIAL, Lertvittayakumjorn & Toni, Qian | Corrected (§4) |

---

## 11. Artefacts and reproduction

The experiment data is **not in git**. It is in `data/deliberate-multiclass/`
at the repository root on the machine that ran it (MacBoo), excluded through
`.git/info/exclude`. About 20 MB. Layout:

| Path | Content |
|---|---|
| `1ow0x-survey.md` | Running condensed notes of every round |
| `combine/` | First combination-rule simulation (known bugs, see §10) |
| `voi/`, `exp/voi/`, `exp/voi2/` | VoI derivations, exact-vs-linear, invariance and non-circular references, Sol reviews |
| `pilot/` | Jev pilot: `pilot.py`, `testset*.json` (gold standards), `responses.jsonl` |
| `verify/gold/`, `verify/math/`, `verify/cites/` | Opus/Sol gold rating; faithful Kotlin ports (`verify.py`, the reference implementation of the layers); citation checks |
| `exp/leak/` | Prior-leakage corrections and new shapes |
| `exp/none/` | None-of-these candidates and simulations; `design.md`; `sol.md` and `opus.md` reviews |
| `exp/absolute/` | Absolute-vs-comparative gold and Jev results; leak simulation |
| `exp/e2e/` | Forecasting accuracy: questions (Manifold URLs), caches, analysis |
| `exp/e2e2/` | Knowledge accuracy: `questions_knowledge.json` (+ sha256), CV analysis, `results2.txt` |
| `exp/recursive/` | Real-engine runs: `drive.py`, the framing shim `bin/claude`, per-run graphs and journals, `summary.json` |

Model invocations follow `.claude/skills/deliberate/scripts/deliberate.py`.
Every external model call needs an explicit timeout: two `codex exec` runs hung
for over 80 minutes during this work.

---

## 12. Beads

- `computenet-1ow0x` holds this exploration, with its full design notes.
- `computenet-x91yk` (bug): model A shares over-sharpen, have no "none", and
  sharpen the prior. It may be superseded by a multi-class build.
- `computenet-dw2wh` (bug): model C linearised VoI and the p-vs-q weighting.
  Its fix and 4 regression tests are specified in its notes; it is independent
  of any multi-class decision.
- No multi-class implementation beads have been filed. That waits on the §9.3
  decision.
