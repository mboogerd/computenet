# Research synthesis: reusing claims across deliberations

Bead `computenet-6uimj` (parent epic `computenet-dq2fy`) · research run 2026-09-28 ·
draft PR #1159 · experiments, data and scripts: [`../claim-reuse/`](../claim-reuse/README.md)

This report is self-contained. It is written so that a reader in a new context
can weigh its suggestions against other research streams without the
conversation that produced it. Each claim below is tagged with its evidential
status:

- **[user decision]**: decided by the project owner during this research;
- **[evidence]**: measured here;
- **[proposal]**: a design suggestion inferred from the evidence, not yet
  decided;
- **[open]**: unresolved.

---

## 1. Context

### 1.1 The system

`demo/deliberate` grows a **deliberation graph** for a question typed by a
human. The normative spec is `demo/deliberate/SPEC.md`.

- **Proposers** (Claude CLI, Codex CLI) propose pro/con arguments for each
  claim, recursively.
- **Jev** (TypeSafe System One, a structured-judgment model) gives every
  judgment:
  - a claim's **plausibility**, its stance or first impression (CRED-01);
  - an edge's **strength**, how strongly the child bears on the parent
    (CRED-02);
  - triage of proposals: ADD / DUPLICATE / MERGE / REFINE / UNDERCUT … (EXP-03);
  - saturation, relevance and quality.
- **Credence** is propagated by kernel cells under seven gradual semantics.
  Claims and edges are both nodes; an edge is itself a claim, a "link", that
  can be argued about and undercut. The headline number is the **consensus**
  of three layers (`wlo`, `jnb`, `woe`): the geometric mean of their odds
  (CRED-04/05).
- **Exploration** is prioritised by value of information:
  |d root / d claim| × 4p(1−p), from a separate one-way **sensitivity** layer
  (model C).
- **Durability** journals inputs only (structure, stances, metadata); every
  credence is recomputed on restart (DUR-01).

Today every question is its own **tree**:

- each claim has exactly one parent and one root;
- plausibility is judged with the root question in its input;
- proposers see the path from the root;
- SPEC §8 lists "cross-tree links; merging equivalent claims across branches"
  as a non-goal.

### 1.2 The goal and the problem

The goal is for deliberate to become **a single source of truth for checking
any claim, and for understanding why it has the credence it has**
[user decision]. At that scale, with deep trees, the same propositions recur
across questions. Re-deliberating them wastes work and may reach different
verdicts for the same proposition. **Reusing claims is therefore
non-optional** [user decision]. The research question is *how* to make reuse
work in practice, not *whether* to.

---

## 2. Research questions

- **RQ1 — Identity.** When are two claims "the same", and can that be detected
  cheaply and accurately enough to share a claim's credence and subtree?
- **RQ2 — Near-misses.** What should happen to claims that are *related but
  not identical*, e.g. the same assertion plus a date or a detail?
- **RQ3 — Coherence.** When one claim logically implies another, how should
  the relation enter credence propagation?
- **RQ4 — Context-freedom.** Can claims be judged without the question they
  arose in? How many claims are not self-contained, and can that be detected
  or repaired at intake?
- **RQ5 — Consequences.** What do reuse and coherence enforcement do to
  claim-level and question-level verdicts?

---

## 3. Decisions taken during the research [user decision]

1. **Claims are first-class; questions are entry points.**
   - There is one global claim store, and a question is a root pointing into
     it.
   - A claim's own credence (plausibility plus its own subtree) is shared.
   - Its *bearing* on a given parent lives on the edge. This is already the
     model: edges are claims with their own strength.
2. **Claims must be self-contained and judged without the question.**
   - A claim must be understandable with no other context: explicit subject,
     scope and referents.
   - The root question is removed from the plausibility judgment. It used to
     be passed as topic context.
   - Proposals for a claim's own subtree are about the claim, not the path.
   - The question remains only where it is inherently per-question:
     relevance and value of information.
3. **Implications should be treated as constraints, and as believed edges.**
   - An implication "X ⇒ Y" is itself something people can believe to a
     degree w.
   - The degree decides how flexibly the constraint is interpreted: a fully
     believed implication is enforced more than a doubted one when they
     conflict.
   - Credences are adjusted *minimally* to honour all implications.
4. **Common cores.** Near-miss pairs should be explored for their
   intersection: a shared core proposition, with each original as a more
   detailed or more constrained version of it.
5. **Reuse is non-optional** (§1.2). An early suggestion from this research to
   shelve cross-question reuse was rejected on those grounds.

---

## 4. Method

### 4.1 Corpus [evidence]

- **Source.** Two `GraphDto` snapshots from earlier live runs on 2026-09-27.
  Both predate models A–D: exploration was ordered by contribution, not VoI,
  and there was no framing or neutral prior. The claims and their Jev stances
  are what matter here, and those are unaffected.
  - `runA`: 9 questions, 788 non-root claims;
  - `runB`: 3 questions re-asked in a separate run, 278 non-root claims.
- **Size.** 12 question roots and **1,066 non-root claims**.
- **Questions:**
  - "Does God exist?" (in both runs);
  - "Is Donald Trump intelligent?" / "Is Trump intelligent?";
  - "Do animals use language?" / "Do animals employ language?" (the latter in
    both runs);
  - "Was the big beautiful bill good for America and Americans?";
  - "Can men give birth?";
  - "Will the universe end in a big rip / big crunch / big freeze?".
- **Why it's useful.** It contains natural overlaps: re-asked questions,
  paraphrased questions, and topically related but distinct questions (the
  three cosmology ones).
- **Stored per claim:** text, Jev plausibility, the per-layer credences and the
  consensus.
- **Harness check.** A Python port of the three consensus layers reproduces
  every stored layer value and root consensus **exactly** (max error 2e-16).
  That validates every "what if" recomputation below.

### 4.2 Pipeline

| Stage | How |
|---|---|
| Recall | `BAAI/bge-small-en-v1.5` embeddings (local). For each claim, its top-3 nearest neighbours in *other* questions → 2,691 candidate pairs. Plus 150 random pairs at cos .55–.70 as controls. |
| Gold labels | Opus annotators under strict written rubrics: SAME / CONTEXT_DEPENDENT / OVERLAP / DIFFERENT for 320 pairs, stratified by cosine band; SELF_CONTAINED / NEEDS_CONTEXT for 150 random claims; YES / NO for 208 claim→core implications. **No human labels.** |
| Precision judges | Jev Choices and Scores under several framings (§5). Every Jev request carries only the fields named. |
| Counterfactuals | Recompute credences with the Python layer port: merge, additive implication edge, constraint, convex soft-constraint solve. Shifts are propagated to the question roots. |
| Cost | Measured from Jev's reported token usage. |

Every stage is scripted and deterministic given the saved data. The scorers
rerun from `data/` without network access. The inventory is in
[`../claim-reuse/README.md`](../claim-reuse/README.md).

### 4.3 Experiments

| Id | Question | Scripts |
|---|---|---|
| E1 | RQ1: recall and precision of twin detection; how far separately deliberated twins diverge | `candidates.py`, `jev_match.py`, `jev_entail.py`, `score_match.py` |
| E2 | RQ3: implication as merge vs. additive argument edge vs. hard constraint | `implication.py` |
| E3 | RQ4: self-containment rate; plausibility with and without the question; repair by rewrite | `selfcontained.py`, `score_sc.py` |
| E4 | RQ2: common-core extraction, validity, reuse | `cores.py`, `cores_graded.py` |
| E5 | RQ3/RQ5: believed soft constraints, minimal adjustment, effect on question roots | `implication_belief.py`, `soft_constraints.py`, `layers.py` |
| E6 | RQ4: rewordings of the self-containment check | `sc_wording.py` |

---

## 5. Findings [evidence]

### 5.1 Finding candidates is cheap (E1)

- **Every** gold SAME pair has cosine ≥ .85 in the sample:
  - 33 of 100 pairs at ≥ .90 and 3 of 60 at .85–.90 are SAME;
  - 0 of 60 at .80–.85, 0 of 40 at .75–.80, and 0 of 60 controls are.
- About 42 twin pairs exist in the corpus by estimate, so about **4% of claims
  have a twin** in another question.
- At cos ≥ .85 there are **0.27 candidates per new claim**.
- Exact-text duplicates across questions: 2. **String matching is useless.**

### 5.2 Telling twins from near-misses: the graded implication belief works (E1, E4)

Candidates at cos ≥ .85, scored against gold SAME:

| Jev judge | Precision | Est. recall |
|---|---|---|
| 4-way Choice "same proposition?", bare texts | 0.72 | 0.83 |
| same, plus each claim's question and parent | 0.60 | 0.93 |
| yes/no implication both ways (A ⇒ B and B ⇒ A) | 0.94 | 0.40 |
| **graded implication belief both ways, min(w_ab, w_ba) ≥ .5** | **0.94** | **0.83** |
| graded, min(w) ≥ .75 | 0.95 | 0.60 |

- **Context hurts.** Giving Jev the question and parent *lowers* precision.
- **Graded beats yes/no.** Jev's yes/no implication Choice is systematically
  too strict. On 208 claim→core implications adjudicated by Opus (200 true):

  | Judge | Precision | Recall |
  |---|---|---|
  | yes/no Choice | 0.98 | 0.54 |
  | graded w ≥ .5 | 0.97 | 0.94 |

- **The false positives** of the weaker judges are near-paraphrases with an
  added qualifier ("sometimes", a date, a named detail).
- **"Same words, different meaning in context"** (CONTEXT_DEPENDENT) occurred
  **0 times**, in gold and in Jev's output.
- **Cost** is about 650 Jev input tokens per pair-judgment, well under
  $0.0001 per new claim at the assumed Jev price.

### 5.3 Precision matters more than recall (E1)

Twins that were deliberated separately end up close; near-misses do not:

| Pair type | Mean |Δ plausibility| | Mean |Δ consensus| | Opposite verdicts |
|---|---|---|---|
| gold SAME (36) | 0.040 | 0.047 | 1 |
| gold OVERLAP at ≥ .85 (121) | 0.114 | 0.148 | 35 |

- **Merging a near-miss corrupts a verdict** roughly a third of the time.
  **Missing a twin** only duplicates work.
- **Where twins come from:** 34 of 36 are between *re-asked or paraphrased*
  questions, and only 2 connect genuinely different questions. So in this
  corpus, exact-twin reuse mostly serves re-asked questions (see §5.6 for
  where the cross-question reuse actually is).

### 5.4 Implication: a constraint, not an argument edge (E2, E5)

**E2** covers 12 one-way implication pairs X ⇒ Y. Coherence requires
c(Y) ≥ c(X). As deliberated, 3 of the 12 violate it (mean gap 0.039).

| Treatment | Movement on already-coherent pairs | Remaining violations | Verdict flips |
|---|---|---|---|
| merge (Y takes X's credence) | 0.064 | 0 | 0 |
| additive edge: "X supports Y" + "¬Y attacks X", strength 1 | **0.088** | 0 | 1 |
| constraint: Y := max(c(Y), c(X)) | **0** | 0 | 0 |

The additive edge **double-counts**: it treats the implication as new evidence
and moves claims that were already coherent, by more than merging does.

**E5** tests believed soft constraints:

- **Belief.** Jev's graded belief w per direction over all 708 candidate pairs
  at cos ≥ .80 (1,416 directions). The 180 directions with w ≥ .5 became
  constraints over 183 claims.
- **Objective:**
  minimise Σₙ aₙ (logit c′ₙ − logit cₙ)² + Σₖ s(wₖ) · max(0, logit c′_X − logit c′_Y)²,
  with s(w) = w / (1 − w), w capped at .95. Convex, solved with L-BFGS.
- **Propagation.** The shifts are pushed through the full recomputed credence
  graphs to every question root.

| Setting | Violations > .01, before → after (w ≥ .75 only) | Claim moves: mean / > .05 / max | Claim flips | Root: max |Δ| / flips |
|---|---|---|---|---|
| hard: yes/no Jev, stiff | 18 → 0 (18 → 0) | .017 / 5 / .147 | 0 | .005 / 0 |
| soft, aₙ = 1 | 51 → 21 (22 → 4) | .017 / 21 / .178 | 3 | .006 / 0 |
| soft, aₙ = 1 + claims explored below | 51 → 24 (22 → 6) | .016 / 23 / .194 | 3 | .008 / 0 |

What this shows:

- **Belief works as stiffness, as intended:** strongly believed implications
  are almost fully satisfied, and weakly believed ones bend.
- **Twins are mutual implications.** The largest adjustments were twin pairs,
  where two strongly believed opposite implications act as a soft equality.
  One pair, both "DESI 2024 BAO-only did not significantly favour evolving
  dark energy", went 0.66 / 0.90 → 0.85 / 0.88. **Reuse and implication are
  one mechanism on one scale.**
- **Question answers barely move** (≤ 0.008, no flips): the adjustments land
  on deep claims with little sway over the root.
- **Firmness weights aₙ made no difference here,** because 114 of 183
  constrained claims have nothing explored below them.

### 5.5 Self-containment and judging without the question (E3, E6)

- **Only 5% of claims need context** (8 of 150 by gold):
  - 7 are under the bill question and leave the bill unnamed ("the 2025 law",
    "the credit", "Medicaid work requirements");
  - 1 says "Alex" without saying it's the parrot.
  - The existing canonical-form request to proposers (EXP-02) already works
    for about 95% of claims.
- **Jev can't gate it.** Four check wordings, scored against gold:

  | Wording | Precision | Recall | Flagged over all 1,066 claims |
  |---|---|---|---|
  | original ("self-contained?") | 7/46 | 7/8 | 34% |
  | "would its truth depend on the debate it appears in?" | 0/0 | 0/8 | 0% |
  | "does it refer to something it never names?" | 3/3 | 3/8 | 1% |
  | combined, jargon and named studies declared fine | 6/25 | 6/8 | 18% |

  None is reliable; the last is usable as a pre-filter. The misses are
  implicit scope. There are only 8 positive examples, so this is indicative.
- **Minimal rewrites** that name the missing referent pass Jev's re-check for
  6 of 8.
  - Rewrites that name "the One Big Beautiful Bill Act" drop sharply in
    plausibility (e.g. 0.75 → 0.24).
  - This is most likely Jev's knowledge cut-off: the experiments did not ask
    model D's `knowledge` Choice, which maps such claims to 0.5.
- **Removing the root question from the plausibility judgment:**

  | Claims | Mean |Δp| | Opposite sides of .5 |
  |---|---|---|
  | all 1,066 | 0.066 | 92 (8.6%) |
  | gold self-contained claims | 0.066 | 9 of 142 |
  | noise floor (same prompt, fresh request) | 0.013 | — |

  The question shifts self-contained claims just as much as context-dependent
  ones. So it acts as **bias, not needed context**, which supports decision 2.
  But removing it visibly changes about 9% of first impressions.

### 5.6 Common cores are the main reuse opportunity (E4)

- **Extraction.** Over the 121 gold near-miss pairs at cos ≥ .85, an Opus
  extractor found a useful (non-trivial) core for **104 (86%)**.
  - Both originals imply the core: **96 of 104** by Opus adjudication, 88 of
    104 by Jev's graded belief.
  - Example: "Six of Trump's businesses, including Trump Taj Mahal …, filed
    for Chapter 11" and "Trump's businesses filed for Chapter 11 six times
    between 1991 and 2009" share the core "Six Trump businesses filed for
    Chapter 11 bankruptcy."
- **Reuse** (Jev graded, w ≥ .5, corpus neighbours at cos ≥ .80):
  - **66 of 88** valid cores are implied by further corpus claims beyond
    their own pair, **1.95 on average**;
  - for 13 cores, one of those claims comes from a question outside the
    pair's two;
  - 24 cores are implied by claims from 3 distinct question runs;
  - 9 cores already exist verbatim-equivalent as a claim.
- **Hierarchy.** Of 141 core–core pairs at cos ≥ .85, 20 are the same
  proposition and 59 have one implying the other: cores form a lattice.
- **Coverage.** Claims implying at least one valid core: **155 of 1,066
  (15%)**, from cores of only the 121 *sampled* near-miss pairs. Exact twins
  cover about 4%.

---

## 6. Conclusions

1. **Reuse is technically feasible at low cost.** Embedding recall at
   cos ≥ .85 plus Jev's graded implication belief identifies twins at
   precision .94 and recall .83, for under $0.0001 per new claim.
2. **The unit of reuse should be the proposition, related by implication, not
   by identity.** Exact twins are rare (4%). Shared *cores* reach about 15%
   of claims even from a partial sample, and cores organise into a hierarchy.
3. **Implication belongs in the graph as a believed soft constraint, not as
   evidence.** Adding it as an argument double-counts. Minimal convex
   adjustment weighted by belief satisfies strong implications, tolerates
   weak ones, and barely moves question verdicts.
4. **Identity is the limit of implication.** A twin is a mutual implication
   believed near 1. One mechanism covers twins, near-misses and cores; merging
   becomes an optimisation (sharing the node and subtree when both directions
   are near-certain), not a separate semantic.
5. **Question context should leave the claim level.** Claims are already about
   95% self-contained. Question context acts as bias in plausibility and hurts
   match precision. Removing it is right, but visibly shifts about 9% of first
   impressions.
6. **Use graded Jev judgments for logical relations, never its yes/no Choice.**
   This probably generalises beyond this study (see §9).

---

## 7. Solution design implications [proposal unless marked]

### 7.1 Architecture

```
            question roots (entry points)          per-question: relevance, VoI, sensitivity
                   │
   ┌───────────────▼────────────────┐
   │  global claim store            │  claims: self-contained text, plausibility (no question)
   │   ├─ argument edges (links)    │  bearing on a parent; credence; undercuttable   (as today)
   │   └─ implication relations     │  X ⇒ Y with belief w; claim-like (arguable)     (new)
   └───────────────┬────────────────┘
                   │ raw credences c (cells, as today: stance + arguments, 7 layers)
   ┌───────────────▼────────────────┐
   │  coherence layer               │  minimal adjustment under believed implications → c′
   └────────────────────────────────┘
```

### 7.2 Intake of a new claim

Extends EXP-03 triage.

1. **Self-containment.**
   - Keep the canonical-form request to proposers.
   - Run Jev's combined wording as a cheap pre-filter (about 18% flagged).
   - On a flag, a stronger model rewrites unnamed referents using the parent.
   - Not a Jev gate (§5.5).
2. **Recall.** Embedding index over the global store: neighbours at
   cos ≥ .85 for twins, ≥ .80 for implications and cores.
3. **Relation.** Jev graded belief in both directions for each candidate.
   - **Both w ≥ ~.5 (twin):** reuse the existing node. The new text becomes a
     journaled, reversible alias.
   - **One direction believed:** add an implication relation with belief w.
   - **Related but neither:** extract a common core with a generative model,
     then verify both implications with graded Jev (validity is about 92%).
     Reuse an equivalent existing core, else create it. Link both claims to it
     by implication; only the residual details are deliberated separately.
   - **Otherwise:** a new claim.
4. **Plausibility** is judged on the claim alone [user decision], together
   with model D's knowledge Choice.

### 7.3 Credence

- **Raw credence** stays exactly as today: cells and layers, derived and never
  journaled.
- **Implication relation.** A node with credence w, via links-as-claims
  (LINK-01..06). It can have arguments and undercutters, but it never feeds a
  claim's layers as an argument.
- **Coherence layer.** Solves
  min Σ aₙ (logit c′ − logit c)² + Σ s(w)·hinge(logit c′_X − logit c′_Y)².
  - It is convex, with a unique solution.
  - It suits **local relaxation**: each implication repeatedly nudges its two
    ends until changes fall below a quiescence threshold. That mirrors the
    credence graph's own cycle handling, so it fits the cell model.
  - Being derived, it is recomputed on restart, not journaled (DUR-01).
  - Prior art: probabilistic soft logic / hinge-loss Markov random fields.
- **Firmness aₙ** is how strongly a claim holds its own credence. It decides
  who moves. Candidates: size of the explored subtree, number of questions
  using the claim, 1/(4p(1−p)) [open].

### 7.4 Exploration and sensitivity

These were raised during the research and not tested.

- **Sensitivity per active question root.** A shared claim otherwise gets
  d rootA + d rootB, which pollutes both questions' value of information.
  Propagate it sparsely: only above the stop threshold ε.
- **A claim shared by several questions** accumulates their VoI, so widely
  used claims are explored first. This is a natural prioritisation.
- **Cycles become normal** in a global graph. Credence keeps agora's
  cycle-head rule; the sensitivity layer needs a cycle gate (damped iteration
  or ε truncation). Implication relations add small loops (X bounds Y, Y
  bounds X).
- **Exploration of a shared subtree** is paid by whichever question's VoI
  schedules it; accounting per question needs rethinking (COST-01, EXP-06
  budgets).

### 7.5 Spec and code consequences

These follow from the decisions and the proposals.

- **SPEC §8:** drop the non-goal on cross-tree links and merging.
- **CRED-01:** drop `root_question` from plausibility. Recalibrate, since
  about 9% of first impressions change side; SPEC §10's calibration criteria
  apply.
- **EXP-02:** proposers get the claim, not the path, for a claim's own
  subtree. The path is kept for relevance/VoI only.
- **EXP-03:** triage targets include index neighbours, with new actions for
  REUSE, IMPLIES / IMPLIED_BY and CORE.
- **Durability:** a global structure log. Aliases and implication relations
  become new structure ops; merges must be splittable.
- **`Claim`:** today it has a single `parent` and `root`. It must become a node
  with many parents (edges) and many question memberships.

---

## 8. Open questions [open]

1. **Parents read c′ or c?** If c′, coherence adjustments reach question
   verdicts (measured as ≤ .008 here) and sensitivity must pass through the
   coherence layer. If c, coherence is display-only and the graph can stay
   incoherent.
2. **Who sets aₙ,** and whether it should grow with the number of questions a
   claim serves.
3. **Where implication belief w comes from.** A Jev graded judgment gives the
   stance (as here). Should w also be deliberated like a link (proposers argue
   whether the implication holds)?
4. **The core-extraction model and prompt.** Opus was used here; there is no
   cost or quality comparison. Which model runs it in production (Claude CLI,
   like MERGE)?
5. **Core proliferation.** When to stop abstracting (a core of cores), and how
   to keep trivial cores out: the extractor's `useful` flag rejected 14%.
6. **Twin threshold.** min(w) ≥ .5 vs .75 trades recall .83 against .60.
   Also: should the soft equality simply do the work, sharing the node only
   as a cost optimisation?
7. **Incremental solving.** Relaxation as claims arrive; how far a new
   implication's adjustment propagates; interaction with live updates (SSE)
   and the ≤ 10/s broadcast.
8. **Aliases and UX.** How a reused or aliased claim, a core and an
   implication are shown and navigated (the original ticket's UX question).
9. **Relation to** `computenet-drz8.3` (deliberation graph extraction from
   agora) and `computenet-259rq` (agora adopting deliberate's semantics): the
   global claim store is a candidate shared substrate.

---

## 9. Threats to validity

- **Gold labels are from one model family (Opus), with no human review.**
  - In E4, extraction and adjudication are both Opus. The validity figure
    (96 of 104) may be optimistic; Jev's independent graded check gave 88.
  - SAME was labelled strictly, which favours precision-oriented judges.
- **One corpus** of 12 questions, one embedding model (bge-small), one judge
  model (Jev). The cosmology questions dominate some samples.
- **Recall is measured only within each claim's top-3 cross-question
  neighbours.** The .70–.75 band was not sampled, and core reuse used
  neighbours at ≥ .80 only. So cores are likely *under*-counted.
- **Small n:**
  - E2 has 12 pairs;
  - E6 has 8 positive gold labels;
  - E5 solves one static snapshot, not an incremental stream.
- **Jev knowledge limits** confound plausibility for recent topics (the 2025
  bill). Model D's knowledge Choice was not used in these experiments.
- **Scale.** Nothing here measures how overlap grows with deep trees and many
  questions. That growth is the premise of the goal; it is plausible but not
  measured.

---

## 10. Suggested next steps

1. **Design note / SPEC draft section** consolidating §7 with the parallel
   research streams. This is the bead's remaining item (d).
2. **Core extraction at scale.** Run it over *all* near-miss pairs (not the
   sample), and over cores themselves, to measure the lattice's depth and
   coverage.
3. **Incremental coherence.** Replay the corpus claim by claim with local
   relaxation; measure convergence, and moves per new claim.
4. **Human spot-check** of about 40 gold SAME labels and about 40 core
   validities, to bound the single-annotator-family bias.
5. **Recalibration check** of plausibility without the question (SPEC §10),
   with model D's knowledge Choice included.

## Appendix: artifact map

In `../claim-reuse/`:

| Artifact | Contents |
|---|---|
| `README.md` | Per-experiment method, results tables, the annotator and extractor prompts |
| `data/runA.json`, `data/runB.json` | Slimmed corpus snapshots |
| `data/gold.json`, `data/sc_gold.json`, `data/impl_gold.json` | Gold labels |
| `data/cores.json` | Extracted cores with residual details |
| `data/jev_*.json`, `data/implication_belief.json`, `data/cores_eval*.json`, `data/sc*.json` | Raw Jev outputs |
| `layers.py` | Port of the consensus layers (`wlo`, `jnb`, `woe`) and graph recomputation, verified exact against the snapshots |
