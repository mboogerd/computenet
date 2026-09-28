# Claim reuse across questions — offline experiments

Bead `computenet-6uimj`, run 2026-09-28. The goal behind them: deliberate as a
single source of truth for *any* claim. At that scale claims must be reused, so
the question is how to make reuse work, not whether to do it. The
direction decided so far is recorded on the bead:

- claims are first-class and questions are entry points;
- every claim is self-contained and judged without the question.

These experiments test the mechanisms that direction needs. Everything here is
offline: two snapshots of earlier live runs, re-judged by Jev. No engine code is
involved.

## Corpus

`data/runA.json` and `data/runB.json` are slimmed `GraphDto` snapshots of two
earlier live runs (`GET /graph`): 12 questions and 1066 non-root claims.

- runA holds 9 questions; runB re-asks 3 of them.
- The corpus contains natural overlaps: "Is Trump intelligent?" vs "Is Donald
  Trump intelligent?", "Do animals use / employ language?", "Does God exist?" in
  both runs, and big rip / crunch / freeze.
- `common.load_claims()` rebuilds the claim list from the snapshots. A claim's
  `id` is its index in that list.

## Method

| Step | Script | Output |
|---|---|---|
| Recall: embed every claim (`BAAI/bge-small-en-v1.5`, local) and keep each claim's top-3 neighbours in *other* questions, plus 150 random low-similarity controls (cos .55–.70) | `candidates.py` | `pairs.json` |
| Draw the labelling samples (fixed seeds) | `make_samples.py` | `sample.json`, `all80.json`, `sc_sample.json` |
| Gold labels, by Opus annotators (prompts below) | — | `gold.json`, `sc_gold.json` |
| Jev 4-way Choice, with bare texts or with question and parent added | `jev_match.py bare ctx` | `jev_bare.json`, `jev_ctx.json` |
| Jev implication in each direction | `jev_entail.py` | `jev_entail.json`, `jev_entail_all80.json` |
| Jev graded implication belief, both directions, all pairs at cos ≥ .80 | `implication_belief.py` | `implication_belief.json` |
| Scoring | `score_match.py`, `implication.py`, `score_sc.py` | stdout, `implication.json` |
| Self-containment check, and plausibility with and without the question | `selfcontained.py` | `sc.json`, `sc_rewrites_out.json` |
| Common cores: extraction (Opus), validity, reuse | `cores.py`, `cores_graded.py` | `cores.json`, `cores_eval*.json`, `cores_graded.json`, `impl_gold.json` |
| Soft-constraint minimal adjustment, propagated to question roots | `soft_constraints.py`, `layers.py` | stdout |
| Self-containment check rewordings | `sc_wording.py` | `sc_wording.json` |

The scoring scripts need only the files in `data/` (`--score-only` where a
script also calls Jev). The Jev scripts need `TYPESAFE_API_KEY`.
`candidates.py`, `cores.py` and `soft_constraints.py` run under `uv run`.

The labelling sample is stratified by cosine: all 100 pairs at ≥ .90, then
60 / 60 / 40 at .85 / .80 / .75, plus 60 controls. **SAME is strict**: one
credence could serve both claims. An extra conjunct, a date, a number,
"some" vs "most" or a named example makes a pair OVERLAP instead.

## Results

### 1. Matching: finding candidates is easy; telling them apart is the hard part

- **Candidate finding.** Every gold SAME pair sits at cos ≥ .85:
  - 33 of 100 pairs at ≥ .90 are SAME, and 3 of 60 at .85–.90;
  - none of the 100 pairs at .75–.85 or the 60 controls is SAME.

  About 42 SAME pairs are estimated in the whole corpus, so about 4% of claims
  have a twin somewhere. At ≥ .85 there are 0.27 candidates per new claim.
  Only 2 cross-question pairs share exactly the same text.
- **Telling twins from near-misses.** Measured on the candidates at cos ≥ .85:

  | Jev question | Precision | Est. recall |
  |---|---|---|
  | 4-way Choice, bare texts | 0.72 | 0.83 |
  | 4-way Choice, + question and parent | 0.60 | 0.93 |
  | mutual implication (A ⇒ B and B ⇒ A), yes/no Choice | 0.94 | 0.40 |
  | implication in either direction, yes/no Choice | 0.78 | 0.83 |
  | **graded mutual implication, min(w_ab, w_ba) ≥ .5** (experiment f's finding) | **0.94** | **0.83** |

  - Graded mutual implication (`implication_belief.py`) is the matcher to use: the
    precision of the yes/no test, without its lost recall. The yes/no Choice is
    too strict (see experiment f).
  - Adding the question and parent makes precision *worse*.
  - The false positives are near-paraphrases that add something: "sometimes",
    a date, a named detail.
  - CONTEXT_DEPENDENT (same words, different meaning in context) never
    occurred, in gold or from Jev.
- **Cost.** About 645 Jev input tokens per request, so well under $0.0001 per
  new claim.
- **Why precision matters more than recall.** Twins that were deliberated
  separately ended close together, while near-misses did not:

  | Pairs | Mean |Δ consensus| | Opposite verdicts |
  |---|---|---|
  | gold SAME | 0.047 | 1 / 36 |
  | gold OVERLAP at ≥ .85 | 0.148 | 35 / 121 |

  Merging a near-miss corrupts a verdict. Missing a twin only duplicates work.
- **Where the twins came from.** 34 of the 36 twins come from re-asking the
  *same* question: Trump / Donald Trump 20, use / employ 7, the same question in
  two runs 7. Only 2 connect genuinely different questions (rip vs freeze and
  rip vs crunch). Within one question, triage already removes duplicates among
  siblings.

### 2. One-way implication: a constraint, not an extra argument

Setup:

- **Population.** All 708 candidate pairs at cos ≥ .80. Jev calls 24 of them
  one-way implications, but 12 of those are gold SAME: the directional test
  often says "one way" when both directions hold.
- **Pairs analysed.** The remaining 12, where X ⇒ Y, so coherence requires
  c(Y) ≥ c(X).
- **Harness check.** The deliberate layers wlo / jnb / woe are re-derived from
  the snapshot (stance plus incoming edges) and reproduce the stored layer
  values to 2e-16.

As deliberated, 3 of the 12 pairs are incoherent (c(Y) < c(X), mean gap 0.039).

| Strategy | Mean move Y | Mean move X | Still incoherent | Move on already-coherent pairs | Verdict flips |
|---|---|---|---|---|---|
| merge (Y := c(X)) | 0.057 | 0 | 0 | 0.064 | 0 |
| additive edge (SUPPORT X→Y and ATTACK ¬Y→X, strength 1) | 0.069 | 0.035 | 0 | **0.088** | 1 |
| constraint (Y := max(c(Y), c(X))) | 0.010 | 0 | 0 | **0** | 0 |

- **The additive edge double-counts.** An ordinary support edge treats the
  implication as new evidence. So it moves claims that were already coherent,
  by more than merging does, and flipped one verdict: "Six of Trump's
  businesses filed for Chapter 11" went 0.75 → 0.62.
- **A constraint does the minimum.** Implication is a logical constraint, and
  enforcing it as one fixes every incoherence and touches nothing else.
- **Misrouting is safe here.** A true twin routed to "implication" by mistake
  keeps its credence under a constraint and costs only duplicated work.

Caveats: n = 12, one propagation step, no fixpoint iteration.

### 3. Self-containment, and judging without the question

- **Few claims need context.** Gold: 8 of 150 claims (5%) NEEDS_CONTEXT.
  7 of them are under "Was the big beautiful bill good…?": "the 2025 law", "the
  credit" and "Medicaid work requirements" leave the bill unnamed. The eighth
  says "Alex" without saying Alex is the parrot.
  - Canonical form (EXP-02) already works for almost everything.
  - The failure mode is an unnamed proper noun that the question supplied.
- **Jev cannot be the gate.** As a detector it has recall 7/8 but precision
  7/46: it flags 360 of 1066 claims, mostly dense cosmology claims that are
  perfectly self-contained.
- **A minimal rewrite fixes most.** One that names the referent passes Jev's
  re-check for 6 of 8 claims.
  - Naming "the One Big Beautiful Bill Act" moves plausibility a lot (e.g.
    0.75 → 0.24).
  - This is most likely Jev's knowledge cut-off: the script does not ask
    model D's `knowledge` Choice, which would map such a claim to 0.5.
- **Dropping `root_question` from the plausibility judgment is a real change.**

  | Claims | Mean |Δp|, without vs with the question | Opposite sides of .5 |
  |---|---|---|
  | all 1066 | 0.066 | 92 (8.6%) |
  | gold SELF_CONTAINED | 0.066 | same shift |
  | noise floor (same prompt, fresh request) | 0.013 | — |

  The question moves the judgment of claims that don't need it as much as of
  those that do. So it acts as bias, not context, which supports removing it.
  Removing it still visibly moves about 9% of verdicts, so the change needs its
  own calibration check (SPEC §10).

### 4. Common cores: most near-misses share a reusable proposition (experiment f)

Setup (`cores.py`, `cores_graded.py`):

- **Extraction.** For each of the 121 gold OVERLAP pairs at cos ≥ .85, an Opus
  extractor wrote the strongest proposition both claims imply (the core), each
  claim's residual detail, and whether the core is useful rather than trivial.
  Its output is `cores.json`; the prompt is below.
- **Validity.** Does each original claim imply its core? Judged by Jev and
  adjudicated by a second Opus pass (`impl_gold.json`).
- **Reuse.** Compare every valid core with its corpus neighbours (cos ≥ .80)
  and with the other cores (cos ≥ .85).

Results:

- **Most near-misses have a useful core.** The extractor found one for 104 of
  121 pairs (86%). By adjudication, both originals imply the core for 96 of
  those 104.
- **Jev's yes/no implication check is too strict; its graded score is not.**
  On the 208 claim → core implications (adjudication: 200 YES):

  | Jev | Precision | Recall |
  |---|---|---|
  | yes/no Choice (`jev_entail.py`) | 0.98 | 0.54 |
  | graded belief, w ≥ .5 (`implication_belief.py`) | 0.97 | **0.94** |
  | graded belief, w ≥ .75 | 0.98 | 0.61 |

  This is what revised the matcher in §1. The remaining figures use graded
  w ≥ .5.
- **Cores gather more claims than their own pair.**
  - 88 of the 104 useful cores are valid by Jev.
  - 66 of those 88 are also implied by at least one further corpus claim,
    1.95 further claims on average. For 13 cores, such a claim comes from a
    question outside the pair's two.
  - 9 cores already exist as a claim in the corpus.
  - 24 cores are implied by claims from 3 different question runs.
- **Cores form a hierarchy.** Of 141 core–core pairs at cos ≥ .85, 20 are the
  same proposition and 59 have one implying the other.
- **Coverage.** Claims implying at least one valid core: 155 of 1066 (15%).
  That comes from cores of only the 121 *sampled* near-miss pairs; exact twins
  alone cover about 4%.

Caveat: the extractor and the adjudicator are both Opus, so the validity
figure may be optimistic. Jev's independent graded check agrees on 88 of 104.

### 5. Believed soft constraints: minimal adjustment (experiment g)

Setup (`implication_belief.py`, `soft_constraints.py`):

- **Belief.** Jev's graded belief w for both directions of every candidate
  pair at cos ≥ .80: 708 pairs, 1416 directions.
- **Constraints.** Every direction with w ≥ .5 becomes one, with stiffness
  s(w) = w / (1 − w), w capped at .95. That gives 180 constraints over 183
  claims.
- **Solver.** Minimise Σ a_n (z_n − z0_n)² + Σ s(w)·max(0, z_X − z_Y)² over
  logit credences, with a convex L-BFGS solve.
- **Propagation.** The solved shifts are pushed through the recomputed
  credence graph to every question root. `layers.Graph` reproduces both
  snapshots exactly.

| Setting | Violations > .01 before → after (believed w ≥ .75) | Claim moves: mean / > .05 / max | Claim verdict flips | Question roots: max |Δ| / flips |
|---|---|---|---|---|
| hard: Jev yes/no, stiff | 18 → 0 (18 → 0) | 0.017 / 5 / 0.147 | 0 | 0.005 / 0 |
| soft, a_n = 1 | 51 → 21 (22 → 4) | 0.017 / 21 / 0.178 | 3 | 0.006 / 0 |
| soft, a_n = 1 + claims explored below | 51 → 24 (22 → 6) | 0.016 / 23 / 0.194 | 3 | 0.008 / 0 |

- **Strongly believed implications are almost fully enforced; weak ones only
  partly.** This is the intended behaviour: weakly believed implications
  bend, strongly believed ones hold.
- **The largest moves are twins, not one-way pairs.** Two strongly believed
  implications in opposite directions act as a soft equality and pull a twin
  pair together. One pair, both "DESI 2024 BAO-only did not significantly
  favour evolving dark energy", went from 0.66 / 0.90 to 0.85 / 0.88. So
  reuse and implication are one mechanism on one scale: a twin is a mutual
  implication believed at w ≈ 1.
- **Question verdicts barely move.** The largest root change is 0.008, with no
  flips. Adjustments land on deep claims, which have little sway over the root.
  So enforcing coherence is cheap for answers.
- **Firmness weights barely matter here.** 114 of 183 constrained claims have
  nothing explored below them (a_n = 1), so both settings are nearly
  identical. The weighting will matter once shared claims carry deep subtrees.

### 6. Rewording the self-containment check (experiment h)

Scored against the 150 gold claims, of which 8 need context (`sc_wording.py`):

| Wording | Precision | Recall | Flagged over all 1066 |
|---|---|---|---|
| original (experiment c) | 7/46 | 7/8 | 34% |
| "would its truth depend on which debate it appears in?" | 0/0 | 0/8 | 0% |
| "does it refer to something it never names?" | 3/3 | 3/8 | 1% |
| combined, telling Jev that jargon and named studies are fine | 6/25 | 6/8 | 18% |

- No single wording is a reliable gate.
- **"Unnamed referent" is precise but misses most cases.** It catches only
  claims that visibly point at something ("the 2025 law").
- **The combined wording halves the flag rate at nearly the original recall**,
  so it works as a pre-filter.
- The misses are implicit scope ("Medicaid work requirements", i.e. the bill's),
  which no wording reliably catches from the sentence alone.
- With only 8 positives, these numbers are indicative only.

## What this means for the design

1. **Recall**: embedding nearest neighbours at cos ≥ .85. This is cheap, and
   no twin in the sample was found below it.
2. **Precision**: Jev's **graded** implication belief, never the yes/no
   Choice. The yes/no test misses about half of true implications (recall
   0.54 vs 0.94).
3. **One relation, not three.** Reuse and implication are the same mechanism:
   a believed implication X ⇒ Y with weight w, enforced as a soft constraint.
   - A twin is two strongly believed opposite implications. It becomes a
     shared node when w ≈ 1 in both directions (graded min(w) ≥ .5: precision
     0.94, recall 0.83), or stays two constrained nodes otherwise.
   - A near-miss is one-way.
   - Nothing is ever forced to merge.
4. **Implications are claim-like nodes with credence w** (arguable,
   undercuttable), and their effect is a hinge penalty whose stiffness grows
   with w: credences are adjusted minimally to satisfy all believed
   implications. This is convex, solvable locally, and moved question verdicts
   by at most 0.008 on this corpus. It is **not** an argument edge, which
   double-counts (§2).
5. **Common cores are the main source of reuse.**
   - Most near-misses share a useful core (86%), and both originals really
     imply it (96 of 104 by adjudication).
   - A core gathers about 2 further claims on average.
   - Cores form a hierarchy, and claims from about 15% of the corpus attach to
     them even from this small sample.
   - Design: when a candidate is a near-miss, extract the core, reuse it or
     create it once, and link both claims to it by implication. Only the
     residual details are deliberated separately.
6. **Self-containment**:
   - enforce it at intake by rewriting unnamed referents using the parent;
   - use Jev's combined wording only as a pre-filter (18% flagged, recall
     6/8), with a stronger model or triage-time feedback to decide;
   - the check must run together with model D's knowledge Choice.
7. **Removing the question from plausibility** is justified, but moves about 9%
   of verdicts. Recalibrate after the change.
8. **Scale.** In this corpus, exact twins come mostly from re-asked questions,
   while cores already connect different questions (13 cores). At the
   intended scale, overlap should grow; nothing here measures that growth.

## Caveats

- Gold labels come from one model family (Opus), with no human check.
- Recall is measured only within each claim's top-3 neighbours, and the
  .70–.75 band was not sampled.
- One corpus, one embedding model, and Jev only as the judge (no Claude as a
  precision stage).
- Experiment 2 has n = 12.
- Experiment 4's cores come only from the 121 sampled near-miss pairs.
- Experiment 5 solves one static corpus once; nothing tests incremental
  relaxation as claims arrive.
- Experiment 6 has only 8 positive gold labels.

## Annotator prompts (gold labels)

**Pairs** (`gold.json`, 4 annotators × 80 pairs). Each pair was shown with each
claim's text, question, parent and polarity, and the cosine was hidden. The
instructions:

- **SAME**: the same proposition — same subject, assertion and scope; a
  paraphrase counts. One credence and one analysis of its own pros and cons
  could serve both. Its bearing on the two parents may differ.
- **CONTEXT_DEPENDENT**: the same or near-same text that asserts different
  things in its own context.
- **OVERLAP**: related but not the same — broader / narrower, instance of or
  evidence for the other, a material qualifier (date, number, hedge, extra
  conjunct), or a shared topic.
- **DIFFERENT**: shared vocabulary at most.
- Be strict about SAME: an extra conjunct, a different quantity, "most" vs
  "some", "causes" vs "correlates with", or a different named example all make
  a pair OVERLAP.
- `ab_entails` is set when either claim clearly entails the other.

**Self-containment** (`sc_gold.json`, 150 claims). Question and parent were
shown only so the annotator could spot silent dependence. The instructions:

- **SELF_CONTAINED**: someone who sees only the sentence knows exactly what it
  asserts and could judge it. It may be vague or contested.
- **NEEDS_CONTEXT**: it relies on something unstated — a pronoun, a
  "this" / "such" / "the bill", an implicit subject, or a scope only the
  question or parent supplies.
- For every NEEDS_CONTEXT claim, write a minimal rewrite that keeps the
  assertion unchanged.

**Common cores** (`cores.json`, 2 extractors × ~60 pairs; only the two claim
texts were shown). For each pair, the extractor wrote:

- `core`: one self-contained sentence of at most 25 words — the strongest
  proposition both claims imply, keeping as much shared specific content as
  possible and naming everything explicitly;
- `detail_a` / `detail_b`: what each claim adds beyond the core;
- `useful`: false when the only shared implication is trivial or generic
  (e.g. "Vervet monkeys exist").

**Implication adjudication** (`impl_gold.json`, 208 claim → core items). Does
`claim` imply `core` on a natural reading?

- **YES** when the core is weaker or equal: it drops details, examples, dates,
  names or qualifiers, or paraphrases.
- **NO** when the core asserts anything the claim does not establish: broader
  scope, stronger modality, an absent detail, a different subject or time, or
  a hedge dropped so that it strengthens.
