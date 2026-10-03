# Tuning the credence rule against real answers, without Opus as an input (2026-10-03)

**Question.** If Opus's direct answer is *not* an input, how close to the real answer can a tuned credence rule get
from the arguments and Jev's judgements alone? And which of our heuristics matter: sigmoid steepness, prior weight,
damping, clamps and the rest? The rule must stay **incremental**. Non-incremental techniques are reported only to show
what the data can support.

**Answer.** Not much closer. Every method lands in the same band:
- **Tuned incremental rules:** about 26 Metaculus points.
- **Shipped rule:** about 24.
- **Unrestricted learners:** 22–28.
- **Opus's direct answer:** 46 on the hard set and 78 on the knowledge set.

The rule is not the bottleneck. On this data, an argument's credence carries **no information at all**: replacing it
with a constant, or even inverting it, leaves the score unchanged. All the signal comes from Jev's judgement of how an
argument bears on each option, and that judgement is weak. The sigmoid has nothing to act on here, and neither does
recursion, which only refines argument credence. That is a property of this dataset as much as of the rule. Only 2% of
its arguments were judged false, so it cannot test how doubt propagates. §5 says what data would.

Code: `tune/extract.py`, `tune/tune.py`, `tune/diagnose.py`; full output in `tune/results.txt`. Offline, about 4 minutes,
no model calls (`uv run --with numpy --with scipy --with scikit-learn python tune.py`, after copying the research data
into `data/` per `data/DATA.md`).

## 1. Setup

- **Data:** 130 multiple-choice questions with known answers.
  - **Training (105):** the hard SuperGPQA set's tune split (55, 8–10 options, 32 arguments each: 16 proposed by Opus,
    16 by Sol) and the knowledge set (50, 5–6 options, 10 Opus arguments each).
  - **Held out (25):** the hard set's held-out split, fixed and hashed by the earlier study.
  - The 30 forecasting questions are excluded: they used an older judgement shape, and Jev was outside its knowledge
    on most of them.
- **Inputs:** for each argument, Jev's plausibility (with the question and context-free), its outside-knowledge flag,
  its bearing on every option in two paraphrases, and its probability of "rules out" and "settles"; for each option,
  Jev's plausibility of "the answer is this option" (model A).
  - **Excluded:** Opus's and Sol's direct answers. Arguments proposed by Opus stay in, because they are the
    deliberation's input, not its verdict.
- **Score:** Metaculus baseline (uniform = 0, higher is better), as in the earlier reports.
- **Comparisons:** every comparison uses out-of-sample scores from 4× repeated 5-fold cross-validation on the training
  set. The fit on all of training is applied once to the held-out set. Opus mean-of-5 is scored as a reference line.

### The incremental family

```
credence = softmax_c( a·logit(pos_c) + Σ_j φ(argument_j)_c / (1+N)^ρ )
φ_c   = gate · agree · source-weight · transfer_c
gate  = sigmoid(s·(p − θ)) · (ω if Jev was outside its knowledge)
         where p is a fitted mix of the plausibility with and without the question
agree = exp(−κ·|bearing_A − bearing_B|)              paraphrase disagreement discounts an argument
transfer_c = w_sup·d₊^γ − w_att·d₋^γ − w_r0·(rules-out_c) + w_r4·(settles_c)
         d = the bearing on c relative to the argument's mean bearing (all terms centred)
```

**Why it is incremental:** φ reads only one argument's own judgements, so a parent keeps a K-vector sum and a count.
Adding, retracting or revising an argument is one subtract and one add. A second incremental model, **LININC**, learns
φ as a linear function of 15 per-argument features with interactions. It keeps the same sums and is convex.

## 2. Results (cross-validated on training; 95% CI over questions)

| Rule | Hard set | Knowledge | Pooled | Δ vs shipped |
|---|---|---|---|---|
| *Opus mean of 5 (reference)* | *46.1* | *78.5* | *61.5* | |
| Model A (Jev's position plausibility, fitted temperature) | 23.3 | 22.0 | 22.7 | |
| **Shipped semantics**, arguments alone, CV-chosen scale | 25.5 | 22.0 | 23.8 | 0 |
| Incremental family, all components free | 25.5 | 26.7 | 26.1 | +2.2 [−1.2, +5.9] |
| … support weight tied to attack weight | 25.4 | 28.0 | 26.7 | +2.8 [−0.6, +6.4] |
| … linear gate (no sigmoid) | 25.0 | 25.1 | 25.1 | +1.3 [−2.3, +5.2] |
| LININC (learned per-argument contribution), best setting | 26.3 | 26.5 | 26.4 | +2.6 [−2.6, +7.9] |
| *Non-incremental:* FLEX (conditional logit incl. strongest-for / strongest-against) | 25.7 | 31.1 | 28.3 | +4.5 [0.0, +9.0] |
| *Non-incremental:* gradient-boosted trees | 26.4 | 17.6 | 22.2 | −1.6 [−7.9, +4.4] |

**Held out (n=25, applied once):**

| Rule | Score | Top-1 |
|---|---|---|
| Opus | 52.2 | .60 |
| FLEX | 30.1 | .40 |
| LININC | 28.3 | .48 |
| Shipped | 27.4 | .44 |
| Model A | 23.0 | .36 |
| Incremental family, full-train fit | 22.6 | .44 |
| Gradient-boosted trees | 21.6 | .32 |

The 13-parameter family's full-train fit is unstable on held-out. It is non-convex, and the fit drives some parameters
to extremes: κ = 38 effectively drops every argument whose paraphrases disagree. LININC is the steadier of the two
incremental candidates.

**Ablations and the sigmoid.** Pinning any single component to neutral moves the pooled score by at most 1.7 points, and
no difference is significant. The steepness profile is flat: the score stays between 25.8 and 26.6 for every s from 0.5
to 64. The components that help most are paraphrase agreement, count damping (on the hard set) and Sol-argument
down-weighting, at about 1–1.5 points each.

**What the incremental restriction costs:** about 2 points against FLEX, which can use the strongest argument for and
against each option. That is at the edge of significance.

## 3. Where the information is lost (`tune/diagnose.py`)

1. **Argument credence carries no signal here.** Refitting the family with every argument's plausibility set to a
   constant scores 26.0 pooled. Setting it to its *inverse* (1 − p) scores 26.7. Both match the real plausibility
   (26.1). The cause is that the arguments are nearly all believed: mean plausibility .81, 72% at or above .75, and only
   2% at or below .25. Opus proposes true background facts, not contested claims.
2. **Jev's bearing judgement is where the signal is, and it is weak.** Against a base rate of .11:

| Bearing call on an option | How often that option is the answer |
|---|---|
| "settles it" | .38 |
| "counts for it" | .23 |
| "no bearing" | .11 |
| "counts against it" | .05 |
| "rules it out" | .03 (.02 when the argument is plausible) |

   - In 85% of questions, some plausible argument points uniquely at the right answer. In 96%, some plausible argument
     points uniquely at a wrong one.
   - The arguments contain the answer. Reading which option they favour is a reasoning step, and Jev's bearing
     judgement gets it right about a third of the time even when it is most confident.

## 4. Conclusions

- **Propagation is not the lever on this data.** Sigmoid steepness, prior weight, gates, clamps and damping can all be
  tuned. Together they buy +2–3 points (not significant) over the shipped rule. Unrestricted learners do no better.
- **Recursion cannot help accuracy here** either. Depth only refines argument credence, and argument credence has zero
  leverage on these questions.
- **The bottleneck is the edge judgement:** whether a true argument favours an option. Improving the propagation will
  not close the gap with Opus. Improving that judgement might.
- **If an incremental rule is adopted anyway,** the evidence favours a simpler rule than the shipped one:
  - one weight for support and attack;
  - count damping of roughly 1/√N;
  - discounting arguments whose paraphrase judgements disagree.

  The gain is small and uncertain. It does not justify a production change on this evidence alone.

## 5. What would test the doubt-propagation question

This dataset cannot test the question that motivated the work: how certainty and doubt should flow through supports and
attacks. Almost no argument is doubted, and there are no deep trees with known answers. A dataset that can needs:

1. **Contested arguments with known truth.** Ground truth is then needed at claim level, not only at question level.
   Two options:
   - Mix seeded false or misleading arguments into the proposals (a known-false argument should be discounted).
   - Use binary claims from a labelled true/false source.
2. **Binary questions** (K=2, the case the sigmoid acts on directly). Resolved binary forecasts or true/false benchmark
   items, so each claim's propagated credence can be scored against reality.
3. **Recursive trees on those questions.** Real engine runs, with depth-2/3 arguments judged by Jev, so propagation
   across levels can be fitted. The existing 10 engine runs are too few, and their arguments are not contested.

**Cost:** Jev calls are cheap (about $1 per 10k). Generating trees costs about $1–2 per question in Opus proposals,
going by the earlier pilots.

**Separate experiment worth running first:** improve the edge judgement. Options include having the proposer state which
option an argument favours and why, with Jev rating only that one link, or a stronger model judging bearing. A stronger
judge in that role judges edges, not the answer, so it stays within the "no Opus answer as input" rule.
