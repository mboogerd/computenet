The recommendation is not supported: H+D fails hard requirements, and its favorable simulation is largely constructed into the judge and scenario generator.

1. **R3 is decisively violated.** With three classes, `bL=.99`, and a perfect `s=c=1` absolute objection to every class, H+D returns only **P(Ω)=.047** (per-layer .100/.041/.045/.027), nowhere near “high.” In the supplied S3d, three classes are eliminated and the remaining class has prior `.02`, yet Ω remains **.100**. The `min` treats an implausible unobjected class as a complete veto and ignores prior plausibility.

2. **D mixes comparative evidence into absolute elimination.** In `none.py:91–93`, `S` aggregates *all* incompatible claims, while `J` merely gates that aggregate. Thus any nominal absolute objection can unlock energy supplied by unrelated contrasts. Concrete counterexample: balanced A/B, `bL=.95`, ten strong contrasts each way (`s=c=.95`), plus one virtually weightless absolute objection to each class (`s=c=.01`). A0 gives Ω=.050; H+D gives **.206**. As the absolute objections approach zero energy, `J` remains 1 because `Judge.absolute` ignores strength and credence.

3. **R2 is not satisfied, even by A0.** Full-distribution log pooling (`none.py:172–179`) reintroduces leakage. If every layer has the same hurdle \(P(L)=p\), pooling yields  
   \[
   P(\Omega)=\frac{1-p}{1-p+p\sum_k(\prod_\ell s_{\ell k})^{1/m}},
   \]
   and the sum is generally below 1 when layers disagree. Hence Ω rises without any Ω evidence. The published A0 routine maximum, **+.006**, already contradicts “does not raise”; I found a valid case of **+.0118**. Pool \(P(L)\) and conditional listed shares separately.

4. **The simulation is circular.** The generator labels every `con` as absolute, deliberately leaves one class “safe” in routine cases, and gives every class a `con` in elimination cases (`run.py:103–119`). The judge then reads that latent `kind` directly (`none.py:69–79`). At β=0 it is an oracle for exactly the distinction H was designed around; β adds mostly one-sided false positives. This tests recovery of labels embedded by the simulator, not LLM-judge calibration.

5. **R4’s recommended policy is not simulated.** `run.py:65–76` tests four κ defaults, but contains no carve-from-Ω implementation. It also arbitrarily changes `bL` by `.03`. A coherent q=.5 carve from `bL=.9` implies new `bL=.95`, not `.93`. The advertised transition can jump W from `.140` to `.505`, so “sensible start” is unvalidated.

6. **R6 is sensitivity, not VoI.** `run.py:137–141` computes expected absolute probability movement after forcing credence to 0/1. It has no decision utility and need not obey the posterior martingale property. H’s `.068` for V mainly prices its discontinuous `min` gate—a fragility, not demonstrated informational value.

7. **Implementation and theory diverge.** D claims edge energy \(J S\), but JNB receives `(J,S)` through its nonlinear `jnb_energy`; only the product-energy layers implement \(JS\). Likewise B1’s “Bayesian” description is unsupported: prior-averaged κ followed by nonlinear p-norm penalties is not Bayesian marginalization, and cyclic contrasts raise Ω from `.050` to `.087`.

8. **Recommend corrected A0, not H+D.** Preserve the hurdle, pool hurdle and conditional distributions separately, and elicit independently proposed/judged L/Ω claims—including a holistic coverage-failure claim. Do not derive that claim with the current min/noisy-OR gate. H+D± has the same defects plus unjustified downward Ω leakage.

VERDICT: REJECT