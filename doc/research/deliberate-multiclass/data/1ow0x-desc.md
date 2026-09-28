EXPLORATION (design, no production code).

## Goal

Explore how deliberate could settle a question with several candidate answers ("which of A, B, C, D?") with the same rigor it brings to a yes/no claim, producing a DISTRIBUTION over the answers. The deliverable is a map of the solution space and a recommendation, not a prescribed mechanism. Model A (computenet-dq2fy.29: framing into positions, each an independent binary root, shares via a softmax IssueNode) is one point in that space, not the starting assumption, and nothing here presumes changing it.

## What "the same rigor" means (the yardstick for every option)

1. Generation is separate from judgment: LLMs propose, Jev judges, neither grades its own work.
2. The verdict is derived, never asserted: it comes from judged arguments through a transparent semantics.
3. Pluralism is surfaced: several semantics run as layers, consensus reported, disagreement shown.
4. First impression is kept apart from what the arguments alone establish (model D).
5. Exploration is priced: dig where it could change the answer, stop when nothing left could (model C VoI).
6. Cruxes are explicit: the system can say what would change its mind.
7. The human can steer (override / expand / stop).

## Scope decisions (2026-09-28, with the user)

- A. Answer object: a probability distribution over the classes (rankings, winner+margin, defensible sets are out of scope for now).
- B. Class sets: MECE is the initial lean, but overlapping and growing class sets are in the analysis. Look for one approach that works across all three, possibly with specialisations per type.
- C. Argument semantics is the core open problem (below).
- D-G are research questions within that frame.

## C. The core problem: what does an argument bear on?

Binary: "against X" fully determines where credence goes (to not-X). K classes: "against A" doesn't say which of B, C, D gains. Each modelling idea is an answer to "where does the moved credence go":

1. Likelihood profile: an argument carries P(E | class k) per class; Bayesian update. Contrastive/eliminative arguments are special profiles. Needs one entry per class per argument; overlapping classes break normalisation.
2. Pairwise contrasts: binary propositions "A is a better answer than B", each an ordinary deliberate tree (all existing machinery reused); Bradley-Terry fits the distribution. K^2 pairs, intransitivity must be reconciled.
3. Binary claims + compatibility links (USER'S CURRENT LEAN, scrutinise hardest): arguments stay ordinary binary claims; a new edge maps a claim to the (graded or 0/1) subset of classes it is consistent with; credence in the claim moves mass toward that set. Handles overlap and growth naturally (a new class only needs compatibility with existing top-level claims). Idea 1 with 0/1 entries.
4. Criteria (MCDA): arguments establish per-facet scores and facet weights. Fits "which should we do", not "which is true".
5. Evidence counts (Dirichlet / subjective logic): arguments add weight to classes; exposes evidence mass as well as shape. Additive, where 1/3 are multiplicative.

Class-set fit (first pass): MECE all natural; overlapping — 3 and 2 fine, 1 and 5 break; growing — 3, 4, 5 cheap, 1 re-annotates every argument, 2 adds K pairs. Options can also run side by side as layers (principle 3).

## Research questions

- D. What is Jev asked: per-class absolute plausibility, conditional likelihood, pairwise comparison, compatibility? Calibration. Note: dq2fy.29's pilot found Jev picking one class directly (Choice) degenerate.
- E. Aggregation semantics per option; how DF-QuAD and the other gradual semantics generalise (categorical / multi-valued argumentation); consensus across layers.
- F. Exploration/stopping for a categorical root: entropy reduction, probability the argmax flips, expected regret — the replacement for |sensitivity| x 4p(1-p).
- G. UI: distribution view, cruxes that would move mass / flip the argmax, human steering.
- Proposer prompts for arguments that discriminate between classes.
- Open-set: "other / none of these", and the "all positions implausible but shares still 1/n" failure model A shows today.

## Deliverable

A design note: prior art (what academia/industry tried, what did and didn't work, when and why), the options above scored against the seven principles and the three class-set types, a recommendation, worked examples on 2-3 real multi-class questions (at least one MECE, one overlapping or growing, one where "none of these" is a live answer), and implementation beads filed under computenet-dq2fy.
