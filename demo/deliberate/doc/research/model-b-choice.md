# Model B premise-vs-bearing routing: Choice versus graded Scores

Bead `computenet-n99jt` · measurement run 2026-10-04 · harness and raw data:
[`../model-b-choice/`](../model-b-choice/README.md)

## Recommendation

**Keep the shipped Choice; do not switch to the tested graded alternative.**
On 64 model-rated gold items, Choice was correct on 41 (0.641) and the graded
alternative on 32 (0.500). On their paired disagreements, Choice alone was
correct 15 times and graded alone 6 times. The paired bootstrap interval for
the graded-minus-Choice accuracy difference was [-0.281, 0.000] (point estimate
-0.141; exact McNemar p = 0.078).

This is a recommendation against **this wording and mapping**, not evidence
that relation Choices are generally sound. The current Choice never emitted
`NEITHER` and its macro-F1 was only 0.463. It should remain provisional and its
upstream triage gate remains important. Because the recommendation is not to
switch, no implementation task was filed and no production code changed.

## Question and methods

SPEC EXP-03 model B asks Jev whether a con candidate against a well-believed
claim:

1. disputes the claim;
2. grants the claim but denies that it bears on its parent; or
3. does neither.

The shipped implementation asks one three-way Choice. The prior claim-reuse
study found graded Scores much better than yes/no Choice for implication, so
this study compares the shipped question with one frozen graded formulation.

### Corpus

`make_corpus.py` extracts **all 64 con arguments** from the nine non-root chain
nodes in the committed calibration material. They came from live Claude/Codex
proposer calls over three deliberations (public transport, coffee and diabetes,
rent versus buy), with 6–8 cons at each node. Selection was complete and fixed
before gold labels or Jev outputs. Every item carries the production model-B
state: root question, path, parent claim, claim, link direction and candidate.

This sample measures the relation judgment, not production prevalence. The
calibration cache does not retain today's `BEARING_PLAUSIBILITY >= 0.8` result
or exact triage action for each candidate, and its generator retained actions
other than `DUPLICATE`/`REPLACE`. Some items therefore would not reach model B's
Choice after today's `ADD`-only gate. Filtering after seeing Jev would have
made the evaluation circular, so no such reconstruction was attempted.

### Gold

Opus 5.5 and Sonnet 5.5 independently labelled every item using the same
written routing rubric, blind to each other and to Jev. They agreed on 51/64
(0.797; Cohen's kappa 0.671). A fresh blind Opus pass adjudicated the 13
disagreements without seeing either primary answer. Two items received all
three possible labels; the adjudicator's answer is retained by the declared
policy. Gold support was:

| Outcome | Items |
|---|---:|
| `DISPUTES_CLAIM` | 23 |
| `DENIES_BEARING` | 31 |
| `NEITHER` | 10 |

The planned isolated Sol rater was not available: sandboxed `codex exec` failed
with `Operation not permitted`, and approval for an out-of-sandbox corpus call
was refused. Sonnet is the stated substitution. The two primary raters are
therefore different models but one provider/model family, and the adjudicator
is from that family too. These are model-rated labels with no human check.

### Judges

The current arm copies `JevJudge.bearing`'s state, question and criteria, batched
by claim context as in production.

The alternative asks two independent five-level Scores:

- how strongly the candidate makes the claim itself false or overstated;
- assuming the claim true, how strongly it defeats the specified connection
  to the parent.

The mapping was fixed before calls: both scores below level 2/4 means
`NEITHER`; otherwise the higher score wins; a tie remains `DISPUTES_CLAIM`
(the existing `ADD` path). No threshold was fitted to gold. Choice and graded
questions ran in separate requests so one answer could not cue the other.
There were 18 Jev requests (nine contexts × two arms), no errors, 54,929 input
tokens and 5,316 output tokens.

## Results

Precision and recall use the 64 resolved gold labels. For `NEITHER`, Choice
made no positive prediction; the table reports precision as 0 by the scorer's
zero-denominator convention (mathematically it is undefined).

| Method | Outcome | Precision | Recall | F1 | Support |
|---|---|---:|---:|---:|---:|
| current Choice | `DISPUTES_CLAIM` | 0.571 | 0.870 | 0.690 | 23 |
| current Choice | `DENIES_BEARING` | 0.724 | 0.677 | 0.700 | 31 |
| current Choice | `NEITHER` | 0.000 | 0.000 | 0.000 | 10 |
| graded two-Score | `DISPUTES_CLAIM` | 0.600 | 0.522 | 0.558 | 23 |
| graded two-Score | `DENIES_BEARING` | 0.552 | 0.516 | 0.533 | 31 |
| graded two-Score | `NEITHER` | 0.267 | 0.400 | 0.320 | 10 |

| Method | Accuracy | Macro-F1 |
|---|---:|---:|
| current Choice | 41/64 = **0.641** | 0.463 |
| graded two-Score | 32/64 = 0.500 | **0.470** |

Confusion matrices (rows are gold, columns predictions):

| Current Choice | dispute | bearing | neither |
|---|---:|---:|---:|
| dispute | 20 | 3 | 0 |
| bearing | 10 | 21 | 0 |
| neither | 5 | 5 | 0 |

| Graded two-Score | dispute | bearing | neither |
|---|---:|---:|---:|
| dispute | 12 | 8 | 3 |
| bearing | 7 | 16 | 8 |
| neither | 1 | 5 | 4 |

The graded arm's only clear gain was recognizing 4/10 `NEITHER` items. It paid
for that with 11 false `NEITHER` predictions and substantially lower recall on
both substantive routes. Its small macro-F1 edge (0.470 versus 0.463) comes
entirely from giving the third class non-zero recall; it is not a routing win.

## Limits

- Sixty-four items satisfy the requested sample size but come from only three
  topics and nine shared contexts. The item-level bootstrap ignores that
  clustering and is optimistic about corpus diversity.
- The sample is not filtered through today's plausibility and `ADD` gates, so
  class support and aggregate accuracy are not production-rate estimates.
- Gold is model-rated. Primary agreement is respectable rather than decisive,
  Sol could not be isolated, and two items produced a three-way split.
- Each Jev arm ran once. This study does not estimate response variance.
- Only one graded wording and one fixed mapping were tested. Its failure does
  not contradict the earlier implication result, which measured a different
  binary logical relation with a single graded quantity.

The committed `score.py` reproduces every table from the raw rater votes, gold
resolution and complete Jev responses in `data/`.
