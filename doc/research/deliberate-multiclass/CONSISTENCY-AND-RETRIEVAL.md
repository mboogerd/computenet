# Consistency and retrieval: where deliberation can beat the immediate answer

Follow-up to [ACCURACY-PUSH.md](ACCURACY-PUSH.md) (`computenet-6di6y`), run
2026-09-29 on `MacBoo`.

**Why these two tests.** The accuracy push showed that arguments made from one
model's knowledge cannot out-answer that model. Human deliberation gains
through things that push lacked:
- **new information** from others (tested here as retrieval);
- **coherence across many related beliefs** (tested here as consistency).

**Cost.** Almost no Claude spend, at the user's request:
- Sol (`codex exec`, gpt-5.6-sol) generated, answered and proposed;
- Jev judged;
- the engine ran with `--proposers codex`, with a shim refusing every Claude
  CLI call (one merge rewrite was refused and fell back as designed);
- Opus results came from cache.

All data is local and not in git: `data/deliberate-multiclass/exp/push2/`
(consistency) and `exp/push/` (retrieval: `retrieval.py`, `audit_leak.py`,
`learn.py --push-only`).

## 1. Consistency (label-free)

**Material.** Sol wrote 20 sets of contested claims, and an independent Sol
pass verified every stated relation; 19 were kept. Topics include minimum-wage
effects on teen employment, mammography at 40–49, Polynesian contact with the
Americas, the bilingual advantage, and wolves and willows in Yellowstone.

- **13 families** of 5 claims each:
  - base B;
  - paraphrase P ≡ B;
  - negation N ≡ ¬B;
  - weaker W, with B ⇒ W;
  - stronger S, with S ⇒ B.
- **6 partitions** of 3–4 claims, of which exactly one is true.

**Arms.** Each is scored as-is and after the claim-store **coherence step**
(design note §5.5).
- **Sol direct:** each claim asked alone, with the family members interleaved
  among other families.
- **Jev plausibility.**
- **The real engine**, one server per family, 20 claims per root: its first
  impression, its consensus, and its arguments-alone view.

**The coherence step.**
- Jev gives a graded belief, on bare texts, in four relations for every pair in
  a family: A ⇒ B, B ⇒ A, "not both" and "at least one".
- Relations believed at 0.5 or more become soft constraints, with stiffness
  w/(1−w).
- A minimal logit adjustment is solved over them.

**Measure.** The mean violation of each probability law. The table also
reports mean |p − .5| (informativeness), so any gain from flattening toward
0.5 is visible.

| Arm | Paraphrase \|B−P\| | Negation \|B+N−1\| | Partition \|Σ−1\| | mean\|p−.5\| |
|---|---|---|---|---|
| Sol direct (mean of 2) | .125 | .118 | .475 | .217 |
| **+ coherence** | **.009** | **.007** | .435 | .150 (moved .085) |
| Jev plausibility | .066 | .065 | .506 | .107 |
| + coherence | .006 | .003 | .443 | .091 (moved .045) |
| Engine consensus (deliberated) | .132 | .119 | .382 | .201 |
| **+ coherence** | **.012** | **.007** | .350 | .171 (moved .075) |
| Engine arguments-alone | .121 | .106 | .432 | .157 |

Implication violations (W below B, S above B) were already small in every arm
(≤ .05) and are 0 after coherence.

**Findings.**

1. **The immediate answer is measurably incoherent.**
   - Sol puts a claim and its paraphrase 0.125 apart on average.
   - A claim and its negation sum to 1 ± 0.12.
   - Its partition probabilities sum to between 0.96 and 1.86 (mean error
     0.475).
2. **Deliberating each claim separately does not fix that.** The engine's
   consensus is as incoherent as Sol's direct answer, because today every
   question is its own tree and nothing connects the trees.
3. **The claim-store coherence step fixes it.** Pairwise violations fall more
   than tenfold, to about 0.01, with moves of 0.05–0.09. Jev detects the true
   relations reliably: 11/13 to 13/13 per relation type at belief ≥ 0.5.
   (Precision on unrelated pairs was not measured.)
4. **Partitions need an n-ary constraint.** Pairwise "not both" constraints
   only lower pairs that sum above 1. They cannot say "these three sum to 1", so
   partition error stays at .35–.44. An exhaustive set needs its own
   Σ = 1 constraint; the multi-class read-out's normalisation already is one.
5. **Coherence costs informativeness.** Mean |p − .5| drops by about a third,
   because resolving "B + N = 1.24" lowers both.

**Why this matters for accuracy even without labels.** When forecasts violate
the probability laws, projecting them onto the coherent set strictly lowers
their Brier score under **every** possible outcome. That is de Finetti's result,
made operational by Osherson and Vardi as "coherentization". The step here is a
soft logit-space version of that projection, not the exact Euclidean one, so
the guarantee holds approximately and only when the detected relations are
real. With that caveat, this is a mechanism by which the deliberation graph
**beats the immediate answer by construction**. Checking it against outcomes
needs resolved questions, which is untested here.

## 2. Retrieval (the 80 SuperGPQA questions of the accuracy push)

**Setup.** Sol with live web search proposed 16 claims per question, each
citing a source it found, and also answered directly with search (the
control). Jev judged the claims exactly as before.

**Leak audit.**
- 0 banned domains (question banks, benchmark datasets) and 0 queries pasting
  the question.
- 11 queries named an option's concept on reference sites (MSD Manuals,
  GeneReviews, EyeWiki). That is legitimate evidence search.

| Arm (Δ Metaculus points vs Opus mean-of-5) | Tune | Held-out |
|---|---|---|
| Sol direct, no search | −54 | −34 |
| Sol direct, with search | −42 | −23 |
| Retrieved arguments alone | −27 | −29 |
| Opus + retrieved arguments (CV k) | −0.7 … −3.6 | −0.2 … +1.1 |
| Learned combiner: Opus + retrieved arguments | −1.3 | +0.1 |
| Learned combiner: Opus + Sol+search direct | −0.7 | +1.5 |

**Findings.**
- Search improves Sol by about 11 points, so retrieval does add knowledge.
- On these graduate-level questions Sol is far weaker than Opus, and the
  evidence it retrieves adds nothing once Opus is in the combiner.
- **Not tested:** Opus as the retrieving proposer, left out to save Claude
  credit. It is the fair version of this test.

## 3. What this means for the design

- **Coherence is the deliberation graph's demonstrable advantage** over the
  immediate answer. It is also the design note's claim store with implication
  constraints (S2, `computenet-i9ujc`). This test extends the relations beyond
  implication:
  - "not both" (p_A + p_B ≤ 1);
  - "at least one" (p_A + p_B ≥ 1);
  - an n-ary "exactly one" constraint for partitions.
- **Coherence has to live in the shared claim store.** It cannot come from
  better per-question deliberation: separate trees stayed as incoherent as the
  raw model.
- **To show accuracy gains from coherence directly**, the next test is
  consistency families built from **resolved** questions, for example
  historical forecasting questions. Brier before and after coherence would then
  be measured, not argued.
