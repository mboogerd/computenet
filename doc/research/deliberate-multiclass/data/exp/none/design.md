# None-of-these (Ω) for categorical deliberate roots — design + simulation (computenet-1ow0x)

Files in this directory: none.py (candidates), run.py (scenarios, sweeps; output out.md), volcano.py (volcano.out).
Layer semantics: faithful Kotlin ports in ../../verify/math/verify.py (wlo, woe, jnb, mlp). Listed-class
combination is the verified closed-world log-linear elimination (LL): score_k = log π_k − k‖w(e_i)(1−κ_ik)‖_p,
softmax, exact to each binary layer at K=2. Results are log-pooled over the four layers.

## Background (already established)
Putting Ω in the same softmax with a residual prior breaks K=2 continuity and leaks: 3 strong pros + 3 strong
cons on 50/50 → Ω .43 (here), leading. Closed-frame conflict m(∅) fires on every two-sided node.

## Key structural finding
(1) By IIA of the softmax, every candidate that adds Ω as one more option leaves the conditional
distribution over listed classes, s_k = P(k | listed), EXACTLY the closed-world LL (3e-16 at K=2 in every
layer). So the whole none-of-these problem reduces to one scalar: P(L) = P(the answer is listed).
(2) The flat softmax's Ω-vs-L log-odds is logit π_Ω − pen_Ω − log Σ_k π_k e^{−pen_k}. The last term is the
leak: every listed penalty raises Ω unless pen_Ω grows alongside, i.e. unless each claim's bearing on Ω
(κ_Ω) is judged. An unjudged claim defaults to κ_Ω=1 → full leak.
(3) "3 pros for A + 3 cons against A" and "each listed class eliminated once" are STRUCTURALLY the same
(pro-A = elimination of B). No rule over the κ matrix alone can satisfy both R2 and R3; the distinction
("is this objection absolute, or only relative to a rival?") has to come from a judgment. A nested-logit
λ ∈ [0,1] (λ=1 flat, λ=0 inert) is exactly the dial trading R2 against R3.

## Candidates
- O-flat(-resid): Ω in softmax, prior 1−bL (or residual max(.05,1−Σp)), unjudged κ_Ω=1. (refuted baseline)
- N-λ: nested logit, listed nest inclusive value scaled by λ (λ=.5 shown).
- B1-κΩ: Ω in softmax; every listed claim gets a Jev-judged κ_Ω ("would this still be expected if the answer
  were not listed?"); contrastive claims' true κ_Ω = prior-mixture. = the Bayesian answer if judged perfectly.
- T-TBM-open: m(∅) → Ω (mass-function bug of combine.py fixed: all-κ=0 claim → m(∅)).
- A0-hurdle: L = ordinary binary deliberate root "the answer is one of X, Y, Z" with its own Jev first
  impression bL and its own argued/judged children (proposers asked for none-of-these/other-answer
  arguments). P(k) = P(L)·s_k, P(Ω)=1−P(L). Listed debate is Ω-inert.
- H-hurdle+D (RECOMMENDED): A0 plus ONE derived node D− attached as an attack on L: "every listed answer
  faces a standing objection that does not depend on a rival being right". Structural credence
  S = min_k noisyOR_i e_i(1−κ_ik) (rebuttals lower c_i, so S falls); Jev-judged plausibility J (Jev sees the
  per-class objection list and is asked whether they are absolute or merely comparative); edge energy J·S,
  propagated through L by the ordinary binary layer. Simulated J = min_k max_i abs_i(1−κ_ik), abs_i the
  latent truth upgraded by judge bias β (pilot: Jev upgrades "counts against" to "rules out").
- H-hurdle+D±: also a derived D+ ("some listed answer has positive support that would be unexpected if
  the answer were unlisted") supporting L.

## Results (log-pool of 4 layers; β = judge upgrade bias; see out.md for full tables)
Scenario P(Ω) [first impression Ω in brackets]:

| scenario | O-flat | N-.5 | B1 β0/β.3 | TBM | A0 | H+D β0/β.3 | H+D± β0 |
|---|---|---|---|---|---|---|---|
| S1 3 pros+3 cons, A vs B [.05] | .428 | .166 | .050/.104 | .987 | .050 | .050/.074 | .011 |
| S1b same, K=3 [.05] | .428 | .166 | .050/.104 | .987 | .050 | .050/.074 | .011 |
| S1c cyclic contrasts X>Y>Z>X [.05] | .226 | .110 | .087/.117 | .676 | .050 | .050/.070 | .050 |
| S2 all implausible p=.1 each, bL=.3 [.70] | .700 | .700 | .700 | .700 | .700 | .700 | .700 |
| S2b same, Jev incoherent bL=.8 [.20] | .200 (resid .700) | .200 | .200 | .200 | .200 | .200 | .200 |
| S3 each class absolutely objected e=.72 [.10] | .284 | .174 | .284 | .436 | .100 | .280 | .280 |
| S3b same, objections rebutted c=.2 [.10] | .131 | .115 | .131 | .105 | .100 | .130 | .130 |
| S3c S3 + explicit none-claim [.10] | .555 | .398 | .555 | .797 | .259 | .402 | .402 |
| S3d X,Y,Z objected, implausible V p=.02 unobjected [.10] | .273 | .170 | .273 | .100 | .100 | .100 | .100 |
| S4 one strong answer X [.10] | .250 | .162 | .040/.072 | .360 | .101 | .101/.111 | .023 |
| S5 Bronze Age, listed debate only [.40] | .768 | .598 | .768 | .787 | .400 | .718 | .718 |
| S5b + 'systems collapse' L-claim [.40] | .906 | .812 | .906 | .915 | .659 | .804 | .804 |

(S5 classes Sea Peoples/Drought/Quakes/Revolt; every class has an absolute objection; drought and raid
evidence is positive but compatible with a multi-causal answer.)

Random populations (1500 trees each, K∈{2,3,4}, bL∈[.8,.97], judge noise sd .1). "Routine" = pros, cons and
contrasts where at least one class has no absolute objection; "all-elim" = every class absolutely objected.

| candidate | β | routine mean ΔΩ | routine max ΔΩ | routine Ω leads | all-elim mean ΔΩ |
|---|---|---|---|---|---|
| O-flat | – | +.223 | +.621 | 42.4% | +.178 |
| N-.5 | – | +.091 | +.317 | 10.1% | +.074 |
| B1-κΩ | 0/.3/.5 | −.010/+.033/+.074 | +.142/+.231/+.309 | 0/1.7/7.7% | +.121/+.132/+.139 |
| TBM-open | – | +.417 | +.926 | 66.5% | +.323 |
| A0-hurdle | – | +.001 | +.006 | 0% | +.000 |
| H+D | 0/.3/.5 | +.004/+.028/+.053 | +.034/+.126/+.216 | 0/0.3/2.5% | +.128 |
| H+D± | 0/.3/.5 | −.075/−.057/−.024 | +.008/+.064/+.139 | 0% | +.107 |

R1 (4000 random K=2 trees per layer): conditional s_A = binary layer to 3.3e-16 for every candidate;
with Ω not in play (no L node) H is the layer to 2.2e-16. With Ω in play (bL=.95, no L-args) the unconditional
P(A) differs by exactly the factor P(L) under A0/H (max .050 = 1−bL); O-flat .27–.37, B1 .21–.23.

R4 class W (plaus .3) added mid-way, H+D; pending κ(existing claim, W) policies vs the fully-judged value:

| start | optimistic κ=1 | pessimistic κ=0 | neutral κ=Σ s_k κ_ik | carve from Ω (q=.5) | fully judged |
|---|---|---|---|---|---|
| S3 all-eliminated: P(W) | .505 | .075 | .315 | .140 | .505 |
| S4 X strongly supported: P(W) | .441 (> X .379) | .085 | .303 | .050 | .094 |

No κ-default is right in both (it depends on whether the claims are positive or objections). Recommended:
"carve from Ω" — W starts with q·P(Ω) where q is Jev's judged "given none of the old list, how plausible is
W", listed shares untouched; bL is re-judged for the enlarged list; claims are then re-judged against W, and
D is re-derived (an un-objected W drives D's min to 0, so mass flows from Ω to W as judgments arrive).

R6 exact VoI on P(Ω) (E|ΔP(Ω)| when a node resolves true/false w.p. its credence), Bronze Age S5b:
L-claim 'systems collapse' .047 (H) / .117 (A0) / .053 (O); the listed objection to the least-objected
class (con Drought, c=.6) .017 under H — D's min makes the weakest standing objection the crux for Ω.
S3d: prospective objection to the un-objected implausible V: VoI .068 under H vs .004 under B1/O-flat.

## Scorecard (R1–R6)
- R1: H/A0 exact (not in play; conditional always). B1/O/N/TBM: conditional exact, unconditional breaks .2–.4.
- R2: H β0 +.004 mean/+.034 max; bias β.3 +.028/+.126 (bounded by J). A0 perfect. B1 more β-sensitive.
- R3: S2 all equal (driven by bL). All-eliminated: H .28/.40 with none-claim/.72 Bronze Age; A0 only via
  explicit L-claims (.10 → .26). Layers are weak eliminators (one e=.72 objection moves a K=3 class .30→.24),
  so "high" needs several objections or an explicit none-claim.
- R4: carve-from-Ω, no jump over well-supported classes; D re-derived.
- R5: Ω is its own argued/judged root; D is a judged node, no constant beyond the layers' own.
- R6: L-tree nodes are ordinary VoI targets; D's min surfaces the weakest objection as the Ω crux.

## Known weaknesses of H+D
- min over classes: an un-objected implausible class blocks D (S3d: H .100 vs B1 .273); mitigated only via
  VoI pricing an objection to it (.068) — the system asks for it rather than inferring it.
- Positive evidence for one answer does not lower Ω unless D+ is on (S4 .101 vs .023); D+ also lowers Ω in
  routine debate (S1 .05 → .011).
- Model D "arguments alone" for L uses neutral base .5 → an unargued L shows Ω 50% in the arguments-alone
  view; needs its own presentation.
- The judge simulation is a stub: J's real calibration (absolute vs comparative objections) is unmeasured;
  the pilot found Jev over-eliminates, which is β>0.
- S2b: Jev's direct bL and Σp_k can disagree (.8 vs .3); H trusts bL; a coherence flag / second layer needed.
- Carve-from-Ω has a discontinuity when the first W-judgment arrives (switch to full LL).

## Revision after review (sol.md REJECT, opus.md ACCEPT-WITH-CHANGES) — revise.py/revise.out, revise2.py/revise2.out
- Pooling fixed: P(L) pooled as a binary (mean logit = Consensus), s_k log-pooled separately. A0 leak is now exactly 0.
- D rebuilt as Hmin-pen: logit P(L) −= min_k pen_k^abs, where pen_k^abs is the layer's own LL penalty on class k
  from claims whose strength is scaled by THAT claim's judged absoluteness (no decoupled J; accumulates like the
  layers). Counterexamples fixed at unbiased judge (Sol-cx .050, Opus-cx .052); 10 decisive objections/class → .98.
- Still open: junk-class veto (S3d .100); judge-upgrade leak (up.3: routine max +.15, Sol-cx .21); the judge is an
  oracle on latent labels (circular until Jev's absoluteness calibration is measured); carve→full jump (S4 W .060→.019);
  Sol-extreme .047 is the layers' own single-attack strength at bL=.99; B1/HB per-claim κ_Ω explodes on contrastive
  claims (Sol-cx → 1.000) and is rejected.
