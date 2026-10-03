# Deliberate credence benchmark

**Purpose:** a tiered dataset for measuring and tuning how deliberate propagates credence through support and attack
relations. Each tier has its own source of truth (design discussion, 2026-10-03):

| Tier | Truth | Role |
|---|---|---|
| **1. Synthetic trees** (`tier1.py`) | Exact, by construction | Tune the rule; separate rule error from judge error |
| **2. Real-knowledge trees, Jev-judged** (`tier2.py`) | Wikidata label at every claim | Rule plus real judgements, with doubted arguments |
| 3. Graded or resolved empirical claims (fact-check verdicts, post-cutoff forecasts) | Graded / eventual | Calibration where truth has degrees |
| 4. Wicked problems | None: property tests | Must-pass constraints (responsiveness, invariance, manipulation resistance), agreement with human argument-impact ratings (Kialo) |

Why tiers: the earlier multiple-choice study (`../deliberate-multiclass/TUNING.md`) could not tell judge error from
rule error. Its arguments were also almost never doubted, so it could not test doubt propagation.

## Headline (Tiers 1 and 2 together)

1. **The decisive modelling choice is what a *doubted* argument does.**
   - Real arguments behave *argument-style*: a true argument is evidence, a false one says almost nothing.
     "Avril was born in 1820" being false tells you little about whether Avril was born before Brahms.
   - Under that shape, any rule that lets a doubted argument count the other way is badly wrong: −13 and −8 in the
     table below.
   - Tier 1's first run drew relations at random, many of them symmetric. It therefore favoured exactly such a rule, and
     its first headline ("the parameter-free 2·atanh rule captures 46%, 100% near-logic") **does not hold for real
     arguments**. It is kept below for the record.
2. **A steep sigmoid on the argument's credence is right, as suspected.** Every argument-style fit, synthetic and real,
   chooses the same per-argument contribution:

   ```
   ± w · 2·atanh( s^γ · max(0, 2·sigmoid(k·logit c) − 1) )
   ```

   - k between 2 and 9: a doubted argument is inert, and a believed one counts nearly in full.
   - The contribution saturates at a cap set by the relation strength s.
   - It is incremental: the parent's log-odds are its base plus a sum of these terms.
3. **This rule beats the shipped layers on both tiers, mostly at the root.**
   - On real Jev-judged trees it matches dfquad overall (log loss .227 vs .226) and beats the consensus layers (.252).
   - At the root, where two partial arguments and an attack must combine, it gains .055 log loss over Jev's prior.
     dfquad gains nothing there (+.001) and the consensus layers .023.
   - On argument-style synthetic trees it uses 40% of the argument information, against 28% for consensus and −20% for
     dfquad.
4. **The judge is not the bottleneck on factual claims.**
   - Jev's isolated plausibility is right 81% of the time.
   - Jev rates decisive links strong (mean .92) and logically irrelevant ones irrelevant (69%, mean .10).
   - With the true leaf values substituted ("oracle leaves"), the best rule reaches 93% accuracy against 92% with Jev's
     leaves.
5. **Still unsettled:**
   - The support-versus-attack weight ratio. Tier 2 fits attacks at 2–3× supports, Tier 1 argument-style about 0.75×;
     likely template-specific. Fitting a single shared weight was unstable on one half of Tier 2.
   - The exact steepness k (fits range 2–9).

## Tier 1: synthetic argument trees

`uv run --with numpy --with scipy python tier1.py` (offline, seeded, ~20 s). `--selftest` checks the exact target
against brute-force enumeration. Output: `tier1_results.txt`, `tier1_results.json`.

### The model

- **Trees:** each tree is a Bayesian network (depth 1–4, 1–4 arguments per claim, up to 31 claims).
- **Relations:** an argument C of claim P has P(C | P) = a and P(C | not P) = b; a > b is support, a < b attack.
- **Inputs:** a claim's *base* is P(claim | its own evidence), playing the role of Jev's isolated stance.
- **Target:** the exact P(claim | all evidence in its subtree). The target is itself incremental: base log-odds plus one
  message per argument.
- **Relation shapes:**
  - *free*: (a, b) drawn at random, with judged strength |a − b|; relations from symmetric to argument-style.
  - *argument-style*: a true argument has likelihood ratio e^(4s) and a false one is nearly uninformative, with judged
    strength s.
- **Conditions:** exact inputs; noisy inputs (base logit + N(0, .7), strength + N(0, .15)); near-logic (|a − b| ≥ .8,
  near-certain leaves).
- **Metric:** *captured* = (log loss of base only − log loss of the rule) / (log loss of base only − log loss of exact),
  against the sampled truths.
  - 1 = all the argument information used.
  - 0 = no better than ignoring the arguments.
  - Below 0 = the arguments made it worse.

### Results (held-out trees, all argued claims, captured)

| Rule | Free, exact | Free, noisy | Near-logic | **Argument-style** |
|---|---|---|---|---|
| dfquad (shipped headline) | −0.95 | −0.37 | 0.31 | −0.20 |
| wlo / jnb / woe | 0.06 / −0.15 / 0.13 | −0.03 / −0.10 / 0.01 | 0.39 / 0.27 / 0.35 | 0.34 / 0.10 / 0.32 |
| consensus(wlo, jnb, woe) | 0.02 | −0.03 | 0.34 | 0.28 |
| euler / qe / mlp | 0.15 / 0.10 / 0.11 | 0.06 / 0.09 / 0.04 | 0.21 / 0.29 / 0.27 | 0.25 / 0.32 / 0.26 |
| bp-sym `logit(base) + Σ ±2·atanh(s·(2c−1))` | 0.46 | 0.10 | 1.00 | **−13.3** |
| Family tuned on free relations | 0.66 | 0.31 | 0.98 | **−7.8** |
| **Family tuned on argument-style** | | | | **0.42** |
| … with η = 0 (a doubted argument is inert) | | | | 0.40 (root .53) |

The η = 0 argument-style fit: α = .99, w_sup = .54, w_att = .40, γ = .39, **k = 5.7**, β = −.17. The unconstrained
argument-style fit reaches 0.42 through a degenerate parameterisation (k → 0, β → −18), so η = 0 is the one to read.

Tier-1-only observations that survive:
- dfquad's energy `strength × credence` lets an argument nobody has evidence about (c = .5) push with half its strength.
- With exact inputs the shipped layers fall off with depth.
- The missing input for an exact rule is an argument's *evidence-free* prior: the two-sided rule, told (a, b) but
  assuming a .5 prior, still scores −0.10 on argument-style trees.

## Tier 2: real-knowledge trees, judged by Jev

`tier2.py build` creates `trees.json` from the local Wikidata dumps (CC0, seeded). `tier2.py jev` makes the Jev calls
(cached in `cache/`, which is git-ignored; about $0.09 to regenerate). `tier2.py analyze` runs offline and writes
`tier2_results.txt`.

### The trees

200 trees, 1,798 claims, with the truth of every claim from Wikidata. 57% of claims are true, and 56% of roots.

- **Templates:** "A was born before B" (painters, composers) and "City A lies further north than city B".
- **Under each root:**
  - two threshold claims that support it ("A was born before 1843", "B was born in 1854 or later");
  - one that attacks it;
  - a logically irrelevant distractor ("A was a painter").
- **Under each threshold claim:** 1–2 stated values ("A was born in 1849"). 45% of them are deliberately wrong, so
  refuted supports and true attacks are common.
- **Judgements:** Jev rates every claim's plausibility and every link's strength, with the engine's prompts verbatim
  (`JevJudge.kt` CRED-01 / CRED-02).
- **Scoring:** log loss against the truth at all 800 argued claims. Fitted rules use 2-fold cross-validation by tree.

### Results

| Rule | Log loss, all argued | Roots | Threshold claims | Accuracy |
|---|---|---|---|---|
| Base only (Jev's isolated judgement) | .366 | .265 | .400 | .81 |
| dfquad | .226 | .266 | .212 | .91 |
| wlo / woe | .246 / .257 | .241 / .243 | .248 / .262 | .90 / .89 |
| consensus(wlo, jnb, woe) | .252 | .242 | .255 | .89 |
| bp-sym (parameter-free) | .401 | .268 | .445 | .82 |
| Family tuned on Tier 1 free relations | .376 | .259 | .415 | .83 |
| **Family tuned on Tier 2** (2-fold CV) | **.222** | **.215** | .224 | **.92** |
| … η = 0 (a doubted argument is inert) | .227 | .209 | .233 | .91 |

- **Fitted on the two halves (η = 0):**
  - α ≈ .94
  - w_sup ≈ .77, w_att ≈ 1.6–2.3
  - γ ≈ 1.1
  - k = 2.7 / 7.2
  - β ≈ −.15 to −.37

  The free fit gives η ≈ −.2, again effectively inert.
- **Oracle leaves** (leaf truths substituted, Jev strengths kept): best rule .158, accuracy .93. dfquad .188,
  consensus .228.
- **Jev as judge:**
  - Plausibility: 81% accurate on leaves and on argued claims. Mean .77 on true leaves and .34 on false ones.
  - Strength: decisive links rated .92 on average (80% "decisive"); partial links .46; distractors .10 (69%
    "irrelevant").

## Caveats

- **Tier 2 is narrow:** two templates of factual comparison. Its arguments are conjunctive (a threshold claim needs its
  partner), which no additive rule represents exactly. Contested, value-laden or vague claims are Tier 3/4 territory.
- **Tier 1 is still Bayesian** and trees only (no shared sub-arguments, no cycles).
- **Fits were tuned on the same templates they are scored on** (cross-validated, but not across templates).

## Next

- **Engine candidate:** the η = 0 family as an *additional* layer (`Semantics` with its own energy), beside the
  existing ones, with k, w and γ at the Tier 2 / Tier 1 consensus values. It changes no existing layer.
- **Tier 3:** graded fact-check verdicts and resolved forecasts, for claims whose truth has degrees, and to test across
  templates.
- **Tier 4:** the property tests (responsiveness, invariance, duplicate and flood resistance) as must-pass checks for
  any adopted rule.
