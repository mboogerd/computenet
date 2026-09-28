1. **Agree with changes.** Use \(q\) only if it is the calibrated probability of resolution; consensus credence is a reasonable proxy, but not guaranteed correct. The martingale claim is overstated: even under \(q\), reported drift reaches −0.0268 because nonlinear recomputation does not ensure \(R=qR(1)+(1-q)R(0)\).

2. **Agree with changes.** An endpoint secant propagated exactly requires the same \(R(0),R(1)\) evaluations as exact \(E\), so exact dominates it. However, cell secants are not simply “no better”: `Sec1stHop_p` substantially reduces premature stops in several tables, albeit without consistently improving ranking or waste.

3. **Agree.** Replacing \(p\) with \(q\) materially improves ranking—especially for consensus—at negligible computation cost, while the 43 premature woe cases confirm that clamps remain unresolved.

4. **Agree with changes.** Exact path evaluation is the strongest implementation recommendation, and the reported aggregate cost—201 evaluations per tree versus 361—is encouraging. Benchmark real wall time and invalidation behavior first; the 80% result rejects only the near-threshold hybrid, while exact reranking of the tangent top 3–5 remains promising.

5. **Agree with changes.** \(eM_w(\tau=1)\) has attractive properties: continuity, no discontinuous leader privilege, and exact binary compatibility under the chosen factor-two convention. It is not yet established as the default: examples are hand-selected, random-objective “agreement” is measured against \(eM_w\) itself, and S4 shows a tail-only score of .0369—not generally near zero; sensitivity to class duplication/cardinality also needs testing.

6. **Agree with changes.** The experiment establishes that both linearizations can cross the stopping threshold incorrectly, so exact evaluation is preferable when false stopping matters. Calling the residual 13/10,258 failures “material” needs a relevant-denominator rate and runtime comparison.

**Overall verdict:** The case for exact endpoint evaluation is strong, and \(q\) plus \(eM_w\) are good provisional choices, but the martingale justification, categorical default, and performance claims should be softened pending calibration, invariance, and production benchmarks.