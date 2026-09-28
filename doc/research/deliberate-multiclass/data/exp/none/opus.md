# Adversarial review: H-hurdle+D (none-of-these design)

## 1. J and S are decoupled, so a trivial objection triggers a full attack (math/design error)

S = min_k noisyOR over **all** eliminating claims, including pros and contrasts. J = min_k max_i abs_i, which ignores strength. The two can be set by different claims, so strength from contrastive claims gets multiplied by a flag from an unrelated absolute objection.

Counterexample: take S1 at β=0 (3 pros for A and 3 cons against A, each s=c=.9). Add one weak absolute objection to B with s=c=.2, so e=.04.
- J_A = 1, from the cons against A.
- J_B = max(β, 1) = 1, from the .04 objection.
- So J = 1.
- S_B = noisyOR(.81, .81, .81, .04) ≈ .993, and S_A ≈ .993.
- D therefore fires at J·S ≈ .99, stronger than S3's .72 edge that produced Ω .28.

P(Ω) should jump from .05 to roughly .3 or more because of a negligible objection. This is an R2 violation.

The random-population split hides it. "Routine" requires at least one class to have no absolute objection, which is exactly the condition under which J=0. The pathological case lands in "all-elim", where it counts as success.

Fix: weight each claim by its own absoluteness, S_abs = min_k noisyOR_i e_i·abs_i·(1−κ_ik), and drop the separate J multiplier.

## 2. The simulated judge makes the conclusion circular

Finding (3) says the R2/R3 distinction cannot be computed from κ and must come from a judgment. The simulation then supplies that judgment from the latent `kind` label, plus a constant upward bias β and sd .1 noise. Three consequences:
- The headline R2 numbers for H (and for B1 at β=0) mainly measure the oracle.
- The only error mode modelled is a uniform upgrade. Two realistic errors are absent:
  - reading absolute objections as merely comparative (β<0), which directly lowers R3;
  - errors correlated with the argument's phrasing (for example "X is ruled out because Y explains it better").
- The pilot evidence is itself that Jev fails at exactly this discrimination.

So "H beats B1 under bias" rests on H using one judged scalar instead of many. That is plausible, but it is not shown. The design should not ship until the absolute-vs-comparative calibration is measured on real Jev outputs.

## 3. The log-pool leaks Ω, so A0's reported residual is an artifact (math error)

Pooling P(L)·s_k gives Ω = g(1−P_L) / (g(1−P_L) + g(P_L)·Σ_k g(s_k)), where g is the geometric mean across layers. By AM-GM, Σ_k g(s_k) ≤ 1, with equality only when the layers agree.

Layer disagreement over listed shares therefore raises P(Ω). This breaks the "listed debate is Ω-inert" property that the whole hurdle design rests on. A0's routine +.001 mean / +.006 max should be exactly 0; that residual is this leak.

Fix: pool P(L) as a binary quantity and pool s_k separately.

## 4. R3 is capped by a single edge

However many decisive objections pile up, D is one attack edge with credence at most 1 into a binary layer. S3 with S=.72 gives only .28, the same as O-flat and B1, and Ω never leads. Even with 10 decisive objections per class, Ω stays near the value for one edge at credence 1, which I estimate at about .3–.35 for bL=.9 (not computed).

Calling this "high and visible" is a stretch. Beyond that cap, R3 depends entirely on proposers writing explicit none-claims, which is A0's weakness again.

## 5. The min over classes is gameable, and R4 contradicts itself

Any unobjected class, however implausible, zeroes D: S3d gives .100 against .273. A proposer can suppress none-of-these just by listing junk.

The same mechanism affects R4. When W is added in S3, D's min goes to 0 as soon as κ(·,W) is pending-unobjected. Ω then drops from about .28 to .10 before any judgment of W exists. That is not "mass flows as judgments arrive"; it is an immediate discontinuity, and a larger one than the acknowledged carve-switch jump.

Fix: take the min over classes weighted by plausibility, or treat classes below a plausibility threshold as objected by default.

## 6. R5 is only partly met

With Ω in play and no L-arguments, P(Ω) = 1−bL. That is Jev's unargued first impression acting as a floor, up to .2 in the populations (bL down to .8). On a K=2 question this changes the unconditional answer with no argument made. S2b also shows bL overriding Σp_k (.8 vs .3). Both are closer to a judged constant than an argued result.

## 7. D+ attaches its judge score to the wrong claim

Jp = max over all claims of positive(x), regardless of which class that claim supports. Sp comes from the best class. So a weak positive claim for class A can validate strong contrastive support for class B. This is the same decoupling as objection 1, and it explains why D+ lowers Ω in routine debate (S1 .05→.011).

## 8. Is the right design recommended?

The hurdle architecture (A0 plus a derived L-attack) is the right skeleton. The IIA reduction to a single P(L) and the R1 results are sound.

The specific D formula should be replaced by the per-claim S_abs form, which effectively applies B1's judged κ_Ω only to the L node. With that change H and B1 converge, and the pick should then be made on measured judge calibration, not on β-sweeps of an oracle.

**VERDICT: ACCEPT-WITH-CHANGES** (the architecture is fine; the D formula, the pooling, and the validation evidence are not)

## Objections ranked by severity
1. J/S decoupling: a .04 objection triggers a ≈.99 D-attack. It is an R2 violation that the routine/all-elim split hides.
2. Judge circularity: the absolute/comparative discrimination is an oracle; only β>0 errors are modelled, and there is no real-Jev calibration.
3. Log-pool leak: layer disagreement inflates Ω and breaks Ω-inertness (A0's +.006).
4. R3 cap: Ω saturates near a single-edge maximum (~.3), and cumulative elimination cannot make it lead.
5. The min over classes is gameable by junk classes, and R4 insertion collapses Ω before judgment.
6. The 1−bL floor makes Ω an unargued Jev impression (R5, and K=2 continuity with Ω in play).
7. D+ judge/strength mismatch lowers Ω in routine debate.