1. Only partly. The MC references are non-circular with respect to the candidate formula, but they are not EVSI or a gold-standard Bayesian oracle.

   - The required coherence identity \(s=q\,s^1+(1-q)s^0\) is not enforced. Auditing 4,015 generated nodes gave L1 residual median \(1.3\times10^{-4}\), 95th percentile .024, maximum .118.
   - The sampled node marginal is not propagated \(q\), and current \(s\) is not generally the marginal of the completed-world class.
   - “Observing” a node merely plugs its value into the current graph; it does not compute \(P(T\mid n)\). Thus MC measures the score gain of this approximate update policy, not information value.
   - The 30% negative MC-log and 32% negative MC-Brier gains confirm that distinction. Proper Bayesian information cannot have negative expected proper-score value.
   - Log and Brier are proper scores; 0-1 is an argmax decision loss, not a proper probability score.

   Call the preposterior quantities proper-score displacement and the MC quantities model-internal plug-in policy gain. They remain useful comparisons, but “non-circular EVSI” overstates them.

2. The invariance results establish that \(eM_w\) is representation-sensitive. If splitting A into indistinguishable aliases must not change the answer object, the 52% median movement and 153 threshold crossings disqualify it. eTV and KL have the desired proportional-refinement invariance algebraically.

   They do not prove duplication invariance is universally mandatory: if A1 and A2 are distinct available actions, splitting changes the decision problem and margin movement is legitimate. The K sweep likewise changes the hypothesis space and prior; it is sensitivity evidence, not a theorem about cardinality. Tiny-Z testing is encouraging but local, and the “neutral” compatibility is heuristic rather than proven LL-neutrality.

3. Switch from \(eM_w\) to eTV. The paired MC-log improvement is credible, the binary reduction and clone invariance are stronger reasons, and the matched-rate premature-stop result remains favorable: 172 versus 275 nodes, 9 versus 16 questions.

   Do not sell the raw-.01 result: eTV stops only 8 questions versus 32, so its zero premature stops mostly reflect a different scale. “Material” is also a matched quantile, not a utility-calibrated threshold.

   EVSI-log ranks better, but rescaling nats cannot make KL equal the binary L1 rule across states; no constant scale fixes that functional mismatch. Ship it only if expected log score is explicitly adopted as the product utility. Under scope decision A and binary compatibility, eTV is the better default. Retune or explicitly preserve the categorical stopping threshold.

4. Yes, with a qualification. Joint eTV is the correct objective under the assumed factorization and disjoint node types:

   - L-node: \(2E|dP(L)|\).
   - Listed node: \(P(L)\,E\|ds\|_1\).

   This is not a general additive decomposition for a node affecting both L and \(s\). KL’s analogous L-side term is Bernoulli KL, not the binary eTV rule. Also, `none.py` prints exact reduction checks for eTV/eMw, not KL; add the claimed KL check.

5. Still unproven:

   - Calibration under a coherent joint model using true node marginals and conditional posteriors.
   - External validity beyond a generator built from the same LL/tree mechanics.
   - Realistic compatibility coverage: `kappa()`’s “contrast” case is distributionally just another rules-out-one case.
   - Replication across generators/seeds and uncertainty for the small Brier advantages; per-question Spearman excludes 98/400 questions.
   - Sequential/adaptive querying, production stopping costs, and whether tail redistribution deserves linear value.
   - Correlated hurdle/listed evidence and nodes affecting both components.
   - Tie cases, broader refinement/coarsening semantics, and operational threshold calibration across K.

**VERDICT: ACCEPT-WITH-CHANGES — ship eTV, using joint eTV for the hurdle.**