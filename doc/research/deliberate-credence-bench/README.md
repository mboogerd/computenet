# Deliberate credence benchmark

**Purpose:** a tiered dataset for measuring and tuning how deliberate propagates credence through support and attack
relations. Each tier has its own source of truth (design discussion, 2026-10-03):

| Tier | Truth | Role |
|---|---|---|
| **1. Synthetic trees** (`tier1.py`, done) | Exact, by construction | Tune the propagation rule; separate rule error from judge error |
| 2. Natural-language reasoning with per-step labels (ProofWriter, EntailmentBank, FOLIO, StrategyQA) | Labelled | Validate rule + real Jev judgements, with depth |
| 3. Graded or resolved empirical claims (fact-check verdicts, post-cutoff forecasts) | Graded / eventual | Calibration where truth has degrees |
| 4. Wicked problems | None: property tests | Must-pass constraints (responsiveness, invariance, manipulation resistance), agreement with human argument-impact ratings (Kialo) |

Why tiers: the earlier multiple-choice study (`../deliberate-multiclass/TUNING.md`) could not tell judge error from
rule error. Its arguments were also almost never doubted, so it could not test doubt propagation at all.

## Tier 1: synthetic argument trees

`uv run --with numpy --with scipy python tier1.py` (offline, seeded, ~10 s). `--selftest` checks the exact target
against brute-force enumeration. Output: `tier1_results.txt`, `tier1_results.json`.

### The model

- **Trees:** each tree is a Bayesian network (depth 1–4, 1–4 arguments per claim, up to 31 claims).
- **Relations:** an argument C of claim P has P(C | P) = a and P(C | not P) = b.
  - a > b makes C a support; a < b an attack.
  - The judged strength is |a − b|.
  - (a, b) are drawn freely. Relations therefore range from **symmetric** (a refuted support counts against) to
    **argument-style** (a refuted argument says little), and the rule is never told which.
- **Evidence:** each claim may carry its own noisy direct evidence.
- **Inputs:** a claim's *base* is P(claim | its own evidence), which plays the role of Jev's isolated stance.
- **Target:** the exact P(claim | all evidence in its subtree), the quantity a node's credence is meant to be.
  - This target is itself **incremental**: it equals the base's log-odds plus one message per argument.
  - So an incremental rule can in principle be exactly right.
- **Conditions:**
  - **Exact inputs:** base and strength are given exactly, so any error is the rule's.
  - **Noisy inputs:** base logit + N(0, .7), strength + N(0, .15), standing in for judge error.
  - **Near-logic:** relations with |a − b| ≥ .8 and near-certain leaves.
- **Metric:** *captured* = (log loss of base only − log loss of the rule) / (log loss of base only − log loss of exact),
  scored against the sampled truths.
  - 1 = all the information in the arguments is used.
  - 0 = no better than ignoring the arguments.
  - Below 0 = the arguments made it worse.

### Results (held-out trees, all argued claims)

| Rule | Exact inputs | Noisy inputs | Near-logic |
|---|---|---|---|
| dfquad (the shipped headline layer) | **−0.95** | −0.37 | 0.31 |
| wlo / jnb / woe (the consensus members) | 0.06 / −0.15 / 0.13 | −0.03 / −0.10 / 0.01 | 0.39 / 0.27 / 0.35 |
| consensus(wlo, jnb, woe) | 0.02 | −0.03 | 0.34 |
| euler / qe / mlp | 0.15 / 0.10 / 0.11 | 0.06 / 0.09 / 0.04 | 0.21 / 0.29 / 0.27 |
| **bp-sym**, parameter-free: `logit(base) + Σ ±2·atanh(s·(2c−1))` | 0.46 | 0.10 | **1.00** |
| Tuned family (7 parameters, incremental) | **0.66** | 0.31 | 0.98 |
| … only the sigmoid steepness k tuned | 0.61 | 0.22 | 0.95 |
| … tuned on noisy inputs | 0.36 | **0.40** | 0.92 |
| Two-sided (told the relation's shape (a, b) too) | 0.65 | 0.26 | 1.00 |

**Fitted family** (exact inputs): α = .88 (prior weight), w_sup ≈ w_att ≈ .87, strength power γ = 1.31, sigmoid
steepness k = 1.37, child-base discount β = .23, below-neutral weight η = .99 (a doubted argument counts the other
way, almost fully).

### Findings

1. **The shipped layers use almost none of the information in the arguments.** The consensus layers capture about 0%.
   DF-QuAD, the headline layer, is *worse than ignoring the arguments* (−0.95). They stay poor or negative even on
   argument-style relations, the case their "a doubted argument is inert" design is built for (consensus −0.16,
   dfquad −1.45).
2. **The cause is the energy `strength × credence`.** Two defects:
   - An argument nobody has evidence about (credence .5) still pushes its parent with half its strength.
   - A refuted support (credence near 0) has no effect, when it should count against.

   The correct per-argument message is centred on "no information" and saturates at a bound set by the strength.
   It is exactly the sigmoid shape you suspected: 2·atanh(s·(2c − 1)).
3. **A parameter-free rule of that shape is a large improvement:**
   - 0.46 captured overall, against about 0 for the shipped layers.
   - 0.77 at the root.
   - 1.00 in near-logic, where the shipped layers reach 0.3 and fall to about 0 at depth 3–4.

   It is incremental, bounded, and keeps a claim with no arguments at its base.
4. **Tuning adds a further 0.2:** 0.66 captured, with the sigmoid steepness alone giving most of it (0.61). The fitted
   values say:
   - Count support and attack equally.
   - Weaken the prior slightly.
   - Let a doubted argument count the other way.
5. **The remaining loss is the argument's own prior, not the relation's shape.** Telling the rule (a, b) adds nothing
   over the tuned family (0.65). The missing input is how far an argument's credence sits from what it would be *with
   no evidence at all*. For an argument that is rarely true, a low credence is just its prior and carries no evidence.
   The exact rule needs that "evidence-free prior" per claim, which no judge currently supplies.
6. **Judge noise halves what any rule can use:** 0.40 at best with noisy inputs.
   - Rules must then be tuned for the noise: a weaker prior (α .66) and smaller weights (about .58).
   - How much noise Jev actually has is unknown. Tier 2 measures it.

### Caveats

- **The truth model is Bayesian.** The rules derived from it (bp-sym, the family) are favoured by construction.
  Argumentation semantics encode other intuitions, such as "a defeated attacker is inert".
  - Finding 1 holds even on the argument-style relations that match those intuitions.
  - Tier 4's property tests are where non-probabilistic desiderata get their say.
- **Trees only:** no shared sub-arguments (DAGs) and no cycles, both of which deliberate allows.
- **The noise model is arbitrary** until Tier 2 measures Jev.

### Next

- **Tier 2:** run Jev on ProofWriter-style chains (per-step truth labels, depth up to 5) to measure the real judge noise
  and whether the bp-sym / family ranking survives real judgements.
- **Engine candidate, if Tier 2 confirms:** bp-sym or the tuned family as a new layer beside the existing ones. It is
  an additional `Semantics` with a different energy, not a change to the existing layers.
