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
| Scoring | `score_match.py`, `implication.py`, `score_sc.py` | stdout, `implication.json` |
| Self-containment check, and plausibility with and without the question | `selfcontained.py` | `sc.json`, `sc_rewrites_out.json` |

The scoring scripts need only the files in `data/`. The Jev scripts need
`TYPESAFE_API_KEY`. `candidates.py` runs under `uv run`.

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
  | mutual implication (A ⇒ B and B ⇒ A) | **0.94** | 0.40 |
  | implication in either direction | 0.78 | 0.83 |

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

## What this means for the design

1. **Recall**: embedding nearest neighbours at cos ≥ .85. This is cheap, and
   no twin in the sample was found below it.
2. **Reuse** a node only on mutual implication (precision 0.94–1.00). Its low
   recall is acceptable, because a missed twin only duplicates work.
3. **One-way implication is a coherence constraint** (c(Y) ≥ c(X), and
   c(X) ≤ c(Y)), not an argument edge. This revises the bead's
   "implication edge" proposal: the relation lives beside the credence layers,
   not inside them.
4. **Self-containment**:
   - enforce it at intake by rewriting unnamed referents using the parent, not
     by gating on Jev's check;
   - the rewriter should be a stronger model, or triage-time proposer feedback;
   - the check must run together with model D's knowledge Choice.
5. **Removing the question from plausibility** is justified, but moves about 9%
   of verdicts. Recalibrate after the change.
6. Where reuse pays off *in this corpus*: re-asked questions. At the intended
   scale, with deep trees over many questions, cross-question overlap should
   grow. Nothing here measures that growth.

## Caveats

- Gold labels come from one model family (Opus), with no human check.
- Recall is measured only within each claim's top-3 neighbours, and the
  .70–.75 band was not sampled.
- One corpus, one embedding model, and Jev only as the judge (no Claude as a
  precision stage).
- Experiment 2 has n = 12.

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
