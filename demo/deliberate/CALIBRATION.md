# deliberate — Jev threshold calibration (SPEC §10)

Measured 2026-09-27 with `jev-latest`, by
`DELIBERATE_CALIBRATE=1 ./gradlew :demo:deliberate:test --tests '*CalibrationTest' --rerun`.

**Material.** There are three questions: a policy question (free public transport), an empirical one (coffee and type 2 diabetes) and a practical one (rent or buy if moving within five years). For each question the harness built a chain root → d1 → d2 → d3. Each link follows the first pro, then the first con, then the first pro. Every chain node got 6–8 pro and 6–8 con arguments. The harness generated them the way the engine does: rounds that ask both `claude` and `codex`, show them the existing arguments, and drop the duplicates Jev finds. The material is cached in `src/test/resources/calibration/material.json`, so a rerun only calls Jev. Raw results go to `build/calibration/results.json`.

**Measured.** EXP-04 saturation (1 − p(an important consideration is still missing)) was taken on the first n arguments of each of the 24 node-sides, with 2 samples each. The two samples differ by 0.006 on average and at most 0.03. For every argument of nodes d0–d2 (108 claims at depths 1–3) the harness took the CRED-02 strength, the EXP-05 relevance, reach, and influence = relevance × reach.

## Saturation vs. number of arguments on the side

Mean saturation by the depth of the claim whose side is judged:

| claim depth | n=0 | n=1 | n=2 | n=3 | n=4 | n=6 |
|---|---|---|---|---|---|---|
| 0 | 0.04 | 0.11 | 0.13 | 0.16 | 0.18 | 0.22 |
| 1 | 0.07 | 0.14 | 0.19 | 0.22 | 0.23 | 0.27 |
| 2 | 0.09 | 0.17 | 0.21 | 0.23 | 0.25 | 0.27 |
| 3 | 0.10 | 0.18 | 0.22 | 0.24 | 0.26 | 0.28 |

Share of sides judged saturated at a given threshold:

| threshold | n=0 | n=1 | n=2 | n=3 | n=4 | n=6 |
|---|---|---|---|---|---|---|
| 0.18 | 2% | 27% | 62% | 75% | 85% | 88% |
| 0.20 | 0% | 8% | 40% | 58% | 73% | 85% |
| **0.22** | 0% | 2% | 25% | **50%** | **65%** | 81% |
| 0.25 | 0% | 0% | 8% | 25% | 44% | 71% |
| 0.30 | 0% | 0% | 0% | 4% | 8% | 27% |

## Strength, relevance, reach and influence by claim depth

Each row has 36 claims. Each cell gives the median, with p25–p75 in brackets.

| depth | strength | relevance | reach | influence = relevance × reach |
|---|---|---|---|---|
| 1 | 0.64 (0.55–0.70) | 0.70 (0.62–0.77) | 0.64 (0.55–0.70) | 0.43 (0.31–0.54) |
| 2 | 0.66 (0.59–0.71) | 0.62 (0.49–0.69) | 0.51 (0.43–0.53) | 0.31 (0.20–0.35) |
| 3 | 0.64 (0.55–0.70) | 0.60 (0.56–0.74) | 0.32 (0.28–0.36) | 0.20 (0.17–0.26) |

Share of claims the EXP-05 gate would expand:

| minInfluence | depth 1 | depth 2 | depth 2, cross estimate | depth 3 |
|---|---|---|---|---|
| 0.25 | 89% | 69% | 45% | 28% |
| 0.30 | 78% | 56% | 25% | 6% |
| **0.35** | **72%** | **25%** | **12%** | **0%** |

In the "cross estimate" column, each depth-1 strength (36 claims) is paired with each depth-2 relevance×strength (36 claims) as if they were independent. It is there because the chain always follows a parent's *first* argument, which was usually a strong one: the chain's depth-2 parents have reach 0.74–0.80, above the depth-1 median of 0.64.

## Chosen defaults and why

**`saturation` = 0.22.** Jev's saturation signal responds to argument count, but weakly. It rises monotonically with n, and the samples are very stable, yet it never leaves the bottom third of the scale: even with 6 arguments Jev still gives, on average, a 0.73 probability that something important is missing, and the highest saturation observed was 0.37. So the first run's 0.7 threshold, like any threshold on a "probably saturated" reading, can never fire. At 0.22, no side saturates empty and 2% saturate with one argument. Half of all sides saturate by n=3 and two thirds by n=4, which meets §10(a) on the median. The discrimination between 3 and 6 arguments is shallow (0.22 → 0.27 mean), so the reliable stop is still **`maxArgsPerSide` = 6**. Jev's saturation mainly ends a side early at 2–4 arguments, where its reading is higher than typical.

**`minInfluence` = 0.35.** Relevance alone barely decays with depth (median 0.70 → 0.62 → 0.60), which is why a relevance-only gate never pruned. Reach supplies the decay: 0.64 → 0.51 → 0.32. At 0.35 the gate expands about 72% of depth-1 claims, pruning the weakly connected ones (strength ≤ ~0.5). It expands only 12–25% of depth-2 claims and no depth-3 claim. Most depth-2 claims therefore end `PRUNED`, and `DEPTH_LIMIT` (depth > 3) is practically unreachable, which meets §10(b).

With the defaults of two proposers × `argsPerCall` 1, each round offers up to two new arguments per side. Jev's saturation judgment is therefore consulted at 2 and 4 arguments per side, before the dependable `maxArgsPerSide` cap of 6 ends a side that Jev has not already saturated.

## Questionless CRED-01 recalibration (2026-10-03)

The CRED-01 request now holds the claim only; it no longer sends the root
question. A bounded live run with `jev-latest` paired the old question-context
request and the new claim-only request on 24 deterministic claims from the
cached three-question material above (48 calls total, concurrency 4; 33,504
input and 3,216 output tokens, about $0.0014 at the demo's assumed Jev rate).
Per question, the sample included the chain claims at depths 1–3, two other
root pros, two root cons, and one depth-1 pro. Both sides of every pair asked
the current model D `knowledge` Choice. None of the 48 answers selected
`OUTSIDE_MY_KNOWLEDGE`.

| measure | result |
|---|---:|
| mean absolute plausibility change | 0.055 |
| mean signed change (claim-only minus question-context) | +0.002 |
| first impressions crossing 0.5 | **1/24 (4.2%)** |
| at or above `BEARING_PLAUSIBILITY` 0.8, question-context → claim-only | 9/24 → 7/24 |
| crossing 0.8 in either direction | 4/24 (16.7%): three down, one up |

This small run is a shipping check, not a new population estimate: one claim
is 4.2 percentage points, and the corpus has only three root questions. The
claim-reuse research run is a comparison point, not its baseline: that larger
configuration omitted the `knowledge` Choice and found 92/1,066 (8.6%) 0.5-side
flips with mean absolute change 0.066.

**Decision: keep `BEARING_PLAUSIBILITY` = 0.8.** The new context-free scores
changed individual boundary decisions, as intended, but did not shift the
sample as a whole (mean signed change +0.002). Lowering the boundary merely to
preserve the old request's eligibility rate would reintroduce that request as
the target; 0.8 still expresses the semantic condition that the attacked
claim is highly plausible. The sample is too small to optimise a replacement.

**Decision: keep `saturation` = 0.22.** Saturation has its own request over the
question, claim, side, and existing arguments; CRED-01 changes none of its
state, prompt, or threshold semantics. The live saturation evidence above
therefore remains the applicable calibration. A future corpus-wide rerun may
still test selection effects from changed exploration, but this bounded result
provides no reason to move the threshold.

## Iteration 4: judgments changed after this calibration

Plausibility no longer sees the path from the question (its state is only the
question and the claim), quality is now the construction Noul **times** a
canonical-form Noul, and proposers write canonical arguments. The thresholds
above were calibrated under the old judgments and prompts.

The first live run under the new ones (2026-09-27, "Should cities ban private
cars from their centres?", defaults with `minInfluence` 0.35) showed the gate
misbehaving: all 9 depth-1 arguments ended `PRUNED` (contributions 0.09–0.32),
so nothing below the root was ever explored. Re-asking Jev for the two factors
of those 9 arguments separately:

| factor | values (9 root arguments) | median |
|---|---|---|
| construction Noul | 0.77–0.94 | 0.91 |
| canonical-form Noul | 0.25–0.77 | 0.43 |

The construction Noul is where it was; the canonical factor alone roughly
halves every contribution, even for arguments written to the canonical rules
(Jev mostly faults missing dates and vague scope). **`minInfluence` = 0.15**
keeps the old gate's meaning under the new quality: 0.35 × the median
canonical factor 0.43 ≈ 0.15. On that run it would expand 8 of the 9 depth-1
arguments (the old calibration expanded ~72%). This rescaling rests on 9
samples; a full recalibration of `minInfluence` and `saturation` under the new
judgments is residual.

## Iteration 5: balancing exploration depth across questions

**Symptom.** A live run on 2026-09-27 (defaults of iteration 4, three
questions asked at once) grew very uneven trees: "Does God exist?" 57 claims,
"Is Trump intelligent?" 21, "Do animals employ language?" 110. Tree size was
decided at depth 1: only 36% of root arguments passed the gate
contribution = reach × relevance × quality ≥ 0.15, where the calibration above
intended about 72%.

**Causes** (found by re-asking Jev for every factor of the recorded trees):

1. The canonical-form factor inside quality **anti-correlated with relevance**
   (r −0.30 to −0.54 per question): the most on-point arguments — concrete,
   specific evidence — were the ones Jev faulted on scope and dates, so the
   factor pruned exactly them (most visibly for "Does God exist?").
2. Two of the four `CliProposer.CANONICAL_EXAMPLES` were about Donald Trump
   and were copied near-verbatim into that question's root arguments, which
   Jev then rated low on relevance. Swapping in topic-neutral examples raised
   the mean relevance of that question's root arguments from 0.29 to 0.45 in
   a regeneration experiment.
3. Incident-style arguments (one quote, one event) bearing weakly on a
   generalisation about a person.
4. A discount Jev applies to relevance for claims about a known person —
   not addressable in this demo.

**Offline replay.** The recorded trees were replayed through alternative rules
(a claim the rule expands produces its recorded children, or ones resampled
from its question's recorded claims when the recording never expanded it;
200 runs per rule; budget 180). Mean claims ± sd per question, and
min/max of the three means:

| rule | God | Trump | animals | min/max |
|---|---|---|---|---|
| iteration 4 (quality with canonical factor, ≥ 0.15) | 50 ± 0 | 21 ± 0 | 110 ± 0 | 0.19 |
| no canonical factor, ≥ 0.20 | 174 ± 10 | 21 ± 0 | 179 ± 3 | 0.12 |
| no canonical factor, ≥ 0.15 | 180 ± 0 | 66 ± 13 | 180 ± 0 | 0.37 |
| no canonical factor, ≥ 0.10, budget 100 | 100 ± 0 | 100 ± 1 | 100 ± 0 | 1.00 |
| no canonical factor, ≥ 0.10 + yield stop (0.6, window 8, ≥ 40 claims), budget 180 | 140 ± 53 | 124 ± 44 | 142 ± 43 | 0.87 |

Without the canonical factor a 0.10 floor lets every question grow; the budget
alone then decides every tree's size, which balances them only by filling each
one to the cap. A per-question stop **relative to the question's own history**
balances them without a flat cap: absolute round yields differ several-fold
between questions (Trump's rounds yielded roughly half of the others'), so any
absolute yield threshold would starve one question again.

**Decided** (SPEC EXP-02, EXP-05, EXP-10): quality is the construction Noul
alone — the canonical Noul is no longer asked at all, canonical form is only
urged in the proposer and merger prompts; the prompt examples use invented,
mundane subjects (a test keeps demo-run topics out of the prompts);
`minInfluence` = 0.10; the yield stop (window 8, ratio 0.6, at least 40
claims and 16 **non-root** rounds) ends a question only when it actually halts
at least one `QUEUED` claim; `maxClaims` stays 180 as a ceiling. Root rounds
are excluded because their naturally high yield distorted the earlier mean.

### Historical live result before the final yield-history correction

This 2026-09-27 run used the first iteration-5 implementation: it included
root rounds and reported `diminishing` even when no queued claim was halted.
The raw measurements remain useful for the quality and influence decisions,
but the stop labels and yield summaries below are not measurements of the
final EXP-10 rule.

| question | claims before → after | stopped by | depth 0/1/2/3/4/5 | explored (rounds > 0) by depth | depth-1 explored | yield rounds, recent vs earlier | done after |
|---|---|---|---|---|---|---|---|
| Does God exist? | 57 → **162** | diminishing | 1/12/28/54/55/12 | 1/6/16/12 | 6 of 12 | 41, 0.113 vs 0.188 | ~5.0 min |
| Is Trump intelligent? | 21 → **55** | diminishing (nothing left to halt) | 1/10/25/13/5/1 | 1/5/1/2 | 5 of 10 | 22, 0.060 vs 0.114 | ~5.4 min |
| Do animals employ language? | 110 → **64** | diminishing | 1/8/27/20/8/0 | 1/8/5/2 | 8 of 8 | 16, 0.113 vs 0.198 | ~3.4 min |

Status counts: God — 65 `DEPTH_LIMIT`, 50 `PRUNED`, 42 `DIMINISHING`,
3 `SATURATED`, 2 `ROUND_LIMIT`; Trump — 40 `PRUNED`, 6 `DEPTH_LIMIT`,
6 `ROUND_LIMIT`, 3 `SATURATED`; animals — 31 `PRUNED`, 22 `DIMINISHING`,
8 `DEPTH_LIMIT`, 2 `SATURATED`, 1 `ROUND_LIMIT`. Triage: God 172 proposals
(107 added, 43 nested as evidence, 10 undercuts), Trump 59 (29, 14, 11),
animals 69 (47, 14, 3). The whole run made 332 CLI calls (169 `claude`,
163 `codex`) over 5.1 minutes; no question reached the 180-claim budget.

**Reading.** min/max of the three tree sizes went from 0.19 to 0.34, and the
smallest tree grew 2.6×. Trump's tree now explores half its root arguments
(before, only one root argument was explored), for example "Donald Trump scored 30/30 on the
Montreal Cognitive Assessment in 2018, according to White House physician
Ronny Jackson.", "During an April 2020 White House briefing, Donald Trump
proposed investigating disinfectant injections into the body as a treatment
for COVID-19." and "Donald Trump built a real estate and branding business
that Forbes estimated to be worth billions of dollars as of the 2020s." — each
a checkable, relevant fact rather than a copy of a prompt example. Its root
relevance stays low (0.16–0.57, cause 4), so it still ends mostly by `PRUNED`;
its yield stop fired on its last rounds with no claim left waiting.

**Final correction.** The animals stop exposed a biased comparison: the root's
naturally high yields occupied the earlier window, so the first 16 recorded
rounds compared child work against root work. EXP-10 now excludes root rounds
and counts its 2 × window minimum over non-root rounds. The Trump label exposed
a separate reporting error: its threshold crossed only after no claim was
left to halt. The final rule records `stoppedBy = "diminishing"` only when at
least one `QUEUED` claim is actually halted; an exhausted tree reports no
stop. Engine tests with scripted fake proposers and judge cover both cases. Saturation is still
calibrated under iteration-2 judgments; a fresh live run of the corrected
stop remains useful calibration work, not a condition of this correction.

## Calibration history in brief (formerly SPEC §10)

SPEC §10 states only the calibration criteria; this is the narrative it used
to carry, kept here with the measurements it summarises.

- **Iteration 2.** The first live runs showed both Jev gates inert
  (saturation 0.04–0.43 against a 0.7 threshold; relevance never below 0.5),
  so exploration was stopped only by hard limits. That prompted the
  measured calibration at the top of this file. Criterion (a) is met on the
  median; the per-side cap remains the dependable stop because Jev's
  saturation signal is shallow.
- **Iteration 4.** Plausibility's state (CRED-01) changed and a canonical
  factor was added to quality (EXP-05) after that calibration, so
  `saturation` and `minInfluence` had been calibrated under the old
  judgments. The first live run under the new ones pruned every depth-1
  argument, so `minInfluence` was rescaled to 0.15 (section "Iteration 4").
- **Iteration 5.** Tree size was decided at depth 1 and very uneven across
  questions (21 vs 57 and 110 claims in one run): the canonical factor pruned
  the most on-point arguments (it anti-correlated with relevance, r −0.30 to
  −0.54), two prompt examples about a real politician were copied into one
  question's root arguments, and a flat threshold cannot suit questions whose
  argument quality differs. So quality lost its canonical factor (EXP-05),
  the prompt examples became topic-neutral (EXP-02), `minInfluence` became
  0.10 (an offline replay of the recorded trees balanced best there), and each
  question now stops by its own diminishing returns (EXP-10) rather than by
  the budget. Root rounds are excluded from the yield history, and a
  diminishing stop is recorded only when it actually halts a `QUEUED` claim;
  these corrections prevent a high-yield root from depressing the apparent
  return of its children and prevent exhausted trees from claiming they were
  stopped (section "Iteration 5").
- **Residual.** A full recalibration of `saturation` under the current
  judgments is still outstanding.

## Findings behind individual requirements

- **CRED-01, plausibility sees no path.** A live investigation measured the
  path pulling the plausibility judgment towards the claim's role in the
  argument (e.g. 0.31 with the path vs 0.53 without); dropping the path
  shifts fresh arguments by −0.011 on average.
- **EXP-04, balance rule.** Jev's saturation reads systematically higher for
  con sides, so con sides stopped early — one root ended 5 pro / 2 con —
  which is why a side holding fewer arguments than the other is never
  saturated by Jev's judgment.

## Prior dominance after model C (2026-10-01)

`PriorDominanceTest` reuses the three live questions in
`src/test/resources/calibration/material.json`. It reconstructs their full
root → d1 → d2 → d3 branches (172 claims and 169 links), obtains current Jev
plausibility and strength judgments once, and caches those judgments and all
reassessments in `prior-dominance.json`. A cached run succeeds with
`TYPESAFE_API_KEY` absent. Run it with:

```
DELIBERATE_CALIBRATE=1 ./gradlew :demo:deliberate:test \
  --tests 'civictech.deliberate.PriorDominanceTest' --rerun --no-build-cache
```

The measurements use these explicit counterfactuals:

- **freeze dK** replaces every depth-K claim's deliberated standing by its Jev
  prior and recomputes its ancestors;
- a claim is **immovable** when freezing that one argued claim changes the
  root headline by less than 0.01;
- **prior share** is `|actual − local-neutral-prior|` divided by that distance
  plus `|actual − arguments-removed|`; the complementary share is the
  argument share. These are diagnostic counterfactual distances, not an
  additive causal decomposition.

### Current semantics

| question | root | freeze d1 | freeze d2 | freeze d3 |
|---|---:|---:|---:|---:|
| free public transport | 0.463 | 0.011 | 0.003 | 0.000 |
| coffee and diabetes | 0.853 | 0.001 | 0.001 | 0.000 |
| rent versus buy | 0.939 | 0.000 | 0.001 | 0.000 |

The largest depth-1 freeze delta is **0.011**, below the out-of-repository
scratch comparison point of 0.046. Across all 12 argued claims, 8/12 (67%)
cannot move the root by 0.01; excluding the three question roots, 8/9 (89%)
cannot. The quoted 53% used an unavailable scratch procedure, so neither
comparison is treated as a reproduced baseline; the in-repository definitions
and cache above are the reproducible result.

The mechanism is the full-prior term at every node. In the headline's three
member layers, `wlo` keeps `alpha * logit(base)` with alpha 1, `woe` likewise
keeps the unshrunk log-odds base, and `jnb` keeps the unshrunk base odds while
argument evidence is conditioned on the child's credence and edge strength.
At depth 1 the mean prior shares are **0.938 / 0.893 / 0.931** for
`wlo / jnb / woe`; their argument shares are only 0.062 / 0.107 / 0.069.
The mean fraction of a claim's local argument-driven move transmitted to the
root compounds by level:

| layer | depth 1 | depth 2 | depth 3 |
|---|---:|---:|---:|
| headline consensus | 0.135 | 0.010 | 0.001 |
| wlo | 0.151 | 0.012 | 0.001 |
| jnb | 0.060 | 0.002 | 0.000 |
| woe | 0.203 | 0.017 | 0.002 |

The requested exact strong-argument subgroup is empty on this material. There
are 65 claims at credence at least 0.8, but no link strength reaches 0.8; the
maximum is 0.7975. The harness reports `n/a` and does not relax the threshold.
The implementation recommendation below therefore requires the property as a
synthetic semantics test rather than claiming it was observed live. To keep
the candidates comparable against that property, the harness also isolates
the nearest actual support and attack whose child already clears 0.8 credence,
and raises only their measured strengths to the exact 0.8 boundary: support
0.835 credence and 0.7975 → 0.8 strength; attack 0.945 credence and 0.785 → 0.8
strength. These are labelled counterfactual probes, never live observations.

### Candidate semantics on the same material

For the two formal candidates, a node with no arguments keeps its Jev prior;
only an argued node's base is shrunk. All outputs stayed in [0,1]. Prior-share
columns average the headline member layers (`wlo`, `jnb`, `woe`) at d0–d3.

| candidate | max freeze d1 / d2 / d3 | prior share d0 / d1 / d2 / d3 | exact-threshold target probes |
|---|---|---|---:|
| current full prior | 0.011 / 0.003 / 0.000 | 0.424 / 0.921 / 0.545 / 0.422 | 12/16 layers |
| weak prior, weight 0.25 | 0.035 / 0.002 / 0.000 | 0.200 / 0.293 / 0.322 / 0.328 | 15/16 layers |
| arguments-first neutral base | **0.048** / 0.002 / 0.000 | 0 / 0 / 0 / 0 | **16/16 layers** |
| bottom-up Jev reassessment | **0.057** / 0.010 / 0.013 | 0.262 / 0.424 / 0.331 / 0.094 | 2/2 judgments |

The weak and neutral formal views make depth-1 work more visible, but the
remaining d2/d3 attenuation shows that removing the local prior is not the
same as making arbitrary deep branches influential. The neutral view is a
diagnostic arguments-first view, not evidence that arguments improve answer
accuracy. The multi-class study summarized in
`doc/design/claims-and-questions.md` found arguments alone worse than the
direct judgment and neutral as an adjustment, so changing the headline is not
justified by this measurement.

### Bottom-up Jev reassessment

The harness presented each claim with its direct pro and con arguments, their
bottom-up deliberated credences, their relation strengths, and the claim's
first impression. It asked for both a considered standing and an
arguments-only standing. The design questions resolve as follows:

1. **Double counting:** the considered standing replaces formal aggregation
   in this candidate. It may be shown beside the formal view, but it must not
   become a new base with the same arguments aggregated on top.
2. **Re-trigger:** reassess after subtree quiescence or a VoI stop, only when a
   shown direct child's standing changed by at least 0.05 since the last
   fingerprint, with at most two reassessments per node per question.
3. **Cost:** one stable pass made four calls per question and cost
   $0.000261–$0.000288 per question at the assumed Jev rate. The two-pass cap
   projects $0.000522–$0.000577 per question; this belongs in the normal cost
   estimate if the candidate is ever implemented.
4. **Determinism and restore:** a live model answer is not reproducible derived
   state. Journal the considered judgment and the exact shown-child
   fingerprint as an input; restore it until that fingerprint crosses the
   re-trigger rule.
5. **Calibration for the job:** Jev's mean argument shares exceed its prior
   shares at every measured depth (0.738, 0.576, 0.669, 0.906 at d0–d3), so it
   does not merely copy the first impression. Its effective propagation is
   nevertheless uneven: freezing d1 moved the policy root 0.057 but the other
   roots only 0.003 and 0.000. It followed both exact-threshold counterfactual
   probes (2/2), but with no live ≥0.8-strength example this is not enough
   evidence that reassessment reliably satisfies the target property.

### Recommendation

Generalise model D's deterministic **arguments-first neutral-prior view to
every argued node**, keep a no-argument node at its Jev prior, and expose the
view beside the existing headline. Do not change the headline yet. This is the
smallest candidate that removes local prior dominance, increases the maximum
depth-1 freeze delta from 0.011 to 0.048, adds no LLM cost or durability state,
and preserves the accuracy caution above. Bottom-up reassessment costs little,
but its restore/re-trigger machinery and uneven effect are not justified by
these three questions.
