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
