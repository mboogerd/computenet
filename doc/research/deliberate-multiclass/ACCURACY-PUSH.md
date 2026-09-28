# Multi-class accuracy push: report

Bead `computenet-6di6y` (parent epic `computenet-dq2fy`), run 2026-09-28 on
`MacBoo`. It follows [REPORT.md](REPORT.md) (`computenet-1ow0x`) and is the
experiment the user chose before any multi-class build (design note
`demo/deliberate/doc/design/claims-and-questions.md` §10, PR #1162).

## 0. Summary

**Question.** The first study found no accuracy gain over asking Opus
directly. It named two untested changes as the most promising: a harder question set
(Opus ≈ 50% top-1, Jev knowledgeable) and **contrastive arguments at depth**
(REPORT §8.3–8.4). Do they change that?

**Answer: no. Verdict: no-go for a multi-class build as an accuracy engine.**

- **Harder set.** 80 SuperGPQA-hard questions with their original close
  distractors (mostly 10 options). Opus mean-of-5 gets 60–63% top-1, and Jev
  says OUTSIDE_MY_KNOWLEDGE for only 4.5% of claims.
  - Arguments alone: −20 (tune) and −24 (held-out) Metaculus points against
    Opus, significant.
  - Opus's distribution adjusted by the arguments: +0.6 / +1.0, CI about ±4,
    not significant. This is the first study's result again.
- **Contrastive arguments at depth.** Depth-1 claims were proposed seeing every
  option, and each was then deliberated as its own binary question by the real
  engine.
  - With value-of-information selection, deliberation moves the claims
    materially: mean |Δcredence| 0.17, and 4 of 20 cross 0.5.
  - It barely moves the answers. The adjusted verdict moves at most
    TV 0.009 and the arguments-alone read-out at most 0.064. The argmax
    never changes, and the effect on the score is −0.3 (combined) and −1.3
    (arguments alone) points.
- **Free offline checks on the first study's data** changed nothing (±1 point):
  dropping OUTSIDE_MY_KNOWLEDGE claims, log-pooling the prior with
  arguments-alone, and adjusting only when the arguments disagree.
- **Judging claims without the question** (the design's CRED-01 change,
  `computenet-pyyi0`) makes no accuracy difference either way.

**Why.** The bottleneck is not depth. It is that the arguments carry nothing the
proposer's own direct distribution lacks: one level of arguments already reads
out worse than Opus, and correcting individual claims at depth shifts a 10-way
read-out by hundredths. This agrees with REPORT §8.2's likeliest reason.

**What still stands.** Multi-class as an explanation, audit and disagreement
layer (REPORT §9.2; design note §0.6) is unaffected. This result only rules out
selling its verdict as more accurate than the model's own.

## 1. Method

All code and data are in the ignored `data/deliberate-multiclass/exp/push/` on
`MacBoo` (not in git; see §5).

| Stage | What | Cost |
|---|---|---|
| Pool | SuperGPQA (ODC-BY) difficulty=hard, non-calculation, **original options** (1ow0x used far distractors, K=6); no figure/table stems, no all/none-of-the-above, the first study's items excluded → 484 unique | — |
| Screen | 1 Opus direct sample on 291 items, discipline round-robin | $3.2 |
| Select | Opus's screen **confidence** max-p < 0.7, never its correctness → 119 eligible → 80 random (seed 20260930), **55 tune / 25 held-out**, fixed and hashed (`questions.json.sha256`) before any further call. Screen top-1 on the selected set 0.64 (reported only) | — |
| One level | Opus and Sol each propose 16 claims (the 1ow0x prompt: discriminating claims, all options visible); Opus direct ×5, Sol direct ×1 | Opus $7.0; Sol unmetered |
| Jev | Per claim: plausibility with the question (as 1ow0x); **context-free** plausibility; `instr` per-class bearing in 2 paraphrases. Per class: model-A position plausibility. 10,961 requests | $0.85 |
| Depth pilot 1 | 5 random tune questions × top-4 Opus claims by **energy**, each its own root in the real engine (dist at `c6dcef98`, default proposers, framing shimmed to NONE, `--max-claims 25`) | $5.4 |
| Depth pilot 2 | Same 5 questions × top-4 by **one-level eTV** (exact expected TV move of the arguments-alone read-out, weighted by plausibility; REPORT §5.5) | $9.1 |

Total: about **$25.5** of the $70 cap.

**Scoring** follows REPORT §3.3: Metaculus baseline (uniform = 0), log score,
top-1, paired bootstrap CIs against Opus mean-of-5. Every fitted scale was
chosen by leave-one-out **on the tune split only**, then fitted on all of it and
applied **once** to the held-out split.

**Pre-registered stop rule for depth** (stated before the pilot): if
deliberation barely moves the answer distributions, stop the depth arm rather
than run it on about 25 more questions.

## 2. Results: one level (80 questions)

Metaculus baseline; Δ is paired against Opus mean-of-5, with a 95% CI (* = significant).

| Arm | Tune (LOO, n=55) | Held-out (n=25) |
|---|---|---|
| Opus mean of 5 | 46.1 (top-1 .63) | 52.2 (top-1 .60) |
| Opus, 1 sample | Δ −1.0 | Δ −2.6* |
| Sol direct | Δ −54.0* | Δ −34.3* |
| model A (softmax T=1) | Δ −21.8* | Δ −29.9* |
| Arguments alone, Opus claims, CV k (1.5) | Δ −20.0* | Δ −24.3* |
| Arguments alone, Opus+Sol claims | Δ −23.5* | Δ −24.0* |
| Opus prior + arguments, Opus claims, CV k (0.25) | Δ −0.4 [−3.1, +1.8] | Δ +0.8 [−2.3, +3.4] |
| Opus prior + arguments, Opus+Sol claims, context-free plausibility | Δ +0.7 [−2.1, +3.2] | Δ +1.1 [−3.5, +4.9] |

- Context-free plausibility (PLCF) and plausibility with the question (PL)
  differ by less than 1 point in every arm.
- Sol's claims are worth about as much as Opus's; pooling both adds nothing.
- The CV scale for the adjustment is small (k = 0.25–0.5): the arguments'
  weight is tuned close to zero.

## 3. Results: depth (5 tune questions, 34 distinct deliberated claims)

| | Pilot 1 (energy selection) | Pilot 2 (eTV selection) |
|---|---|---|
| Plausibility of selected claims | .83–1.00 (settled facts) | .34–.94 |
| Mean \|consensus − plausibility\| | 0.058, 0 cross .5 | **0.17, 4 of 20 cross .5** |
| Mean \|neutral (arguments alone) − plausibility\| | 0.175 | 0.125 |
| Max TV move, adjusted verdict (consensus / neutral) | 0.008 / 0.009 | 0.006 / 0.009 |
| Max TV move, arguments-alone read-out (consensus / neutral) | 0.046 / 0.063 | 0.045 / 0.064 |
| Argmax changed | never | never |

- **Replicates.** 6 claims were deliberated in both pilots; their consensus
  agrees within 0.05. Engine run-to-run noise is small next to the moves above.
- **Score effect.** Using the tune-fitted scale and swapping only each depth
  claim's credence for its deliberated one:
  - arguments alone: −1.3 points (consensus), −2.6 (neutral);
  - adjusted verdict: −0.3 (both).

  Per question it goes both ways; it is not toward the truth.
- The LOO numbers on the 5-question subset (`results.txt`) are too unstable to
  read and are not used.

The stop rule applied. The depth arm was not extended; about $45 was left
unspent.

## 4. Conclusions

1. On a set where Opus is right only about 60% of the time, judged arguments still add
   no accuracy: alone they are much worse, and as an adjustment they are
   neutral. This replicates REPORT §7.2 on a harder set, now with a held-out
   split.
2. Contrastive arguments at depth were the last untested change the first study
   named. Deliberating the most decisive claims moves them substantially but
   moves the answer by hundredths, and not toward the truth.
3. Therefore **no multi-class build is filed as an accuracy engine**
   (`computenet-6di6y` acceptance: no-go). Model A positions stay (user
   decision 2026-09-28), so `computenet-x91yk` remains a fix-now bug. If a
   multi-class explanation layer is ever wanted, REPORT §9 and design note
   §5.3–5.4 hold the mechanism.
4. **Design note implications.**
   - Judging claims without the question (`pyyi0`) is accuracy-neutral, so it
     can go ahead on its reuse and bias merits.
   - Changing how strongly arguments move a claim at depth (`nxege`) is
     unlikely to pay off in multi-class accuracy on its own. Here, even large
     claim-level moves did not reach the answer.

**Untested, and what it would take:**
- **Arguments grounded in retrieved evidence**, i.e. knowledge the proposer
  lacks (REPORT §8.4). Out of scope here, and the one remaining route with a
  mechanism for beating the proposer.
- **Other read-outs, κ mappings and pooling rules** are free to test on the
  saved data (§5).

## 5. Dataset for tuning

Kept for offline tuning of the algorithm, **not in git** (54 MB): on `MacBoo`
in `data/deliberate-multiclass/exp/push/`, next to the first study's
`data/deliberate-multiclass/`, whose modules it imports (`e2e/run.py`,
`e2e2/run2.py`, `e2e2/analyze2.py`, `leak/part_b.py`).

| File | Contents |
|---|---|
| `questions.json` (+`.sha256`) | 80 questions with answer and `split` (tune/heldout). Tune on `tune` only. |
| `pool.json`, `cache/llm.jsonl` keys `opus\|screen\|…` | The pool and the screening samples used for selection |
| `cache/llm.jsonl` | Every Opus/Sol output, raw and parsed: 16+16 claims per question, Opus direct ×5, Sol direct ×1 |
| `cache/jev.jsonl` | Every Jev request state and response: `PL`, `PLCF`, `BEARI` (A/B, every class), `POS` |
| `runs/<qid>/`, `runs_etv/<qid>/` | Depth runs: `graph.json` (per-node plausibility, edge strengths, per-layer credences), `result.json` (roots → claim index, QuestionDto incl. `neutralCredence`), `data/` (structure log + journal), `server.log` |
| `analyze_push.py` | Arms, tune/held-out protocol; `python3 analyze_push.py` reproduces `results.txt` with no model calls |
| `offline_r2.py` | The offline checks on the first study's data (`offline_r2.txt`) |
| `screen.py`, `select_qs.py`, `onelevel.py`, `depth.py`, `bin/claude` | The pipeline; each stage caches and resumes |

Tuning rules: fit on `split == "tune"`, report `heldout` once. The held-out
split has been scored once already, for the arms in §2, so any new arm's
held-out score is a second look at the same 25 questions: report that.
