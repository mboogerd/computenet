You are reviewing an experiment (read-only). cwd holds Python scripts and outputs; the Kotlin originals are in
/Users/merlijn/Documents/local-projects/computenet/demo/deliberate/src/main/kotlin/civictech/deliberate/
(Sensitivity.kt, ExplorationPolicy.kt, CredenceGraph.kt, Semantics.kt, DeliberationEngine.kt take()/currentPriority) and SPEC.md in
demo/deliberate. Layer ports: ../../verify/math/verify.py (previously checked vs Kotlin to 5e-16); tree.py adds euler/qe.
Files: tree.py (tree model, tangent sensitivity = Kotlin localPartials chain x headlineGradient, exact path
re-evaluation, secant variants, response-curve cells), q1.py (+q1_main.out, q1_layers.out), q1b.py (+q1b.out),
cat.py + q2.py (+q2.out). You may run them (python3, stdlib only, a few seconds each).

Context: deliberate model C orders its exploration queue and stops nodes (DIMINISHING at --voi-eps .01) by
VoI = |d headline(root)/d node| * 4p(1-p), p = Jev plausibility (not the propagated credence q). Bug computenet-dw2wh:
the tangent mis-prices nodes near energy clamps (woe emax .7) and can stop them prematurely.

My conclusions -- please check each, try to break them, and give a verdict (agree / disagree / agree-with-changes):

Q1 (binary)
1. The right reference is E_q = 2(q|R(1)-R| + (1-q)|R(0)-R|), q = the node's propagated headline credence, not p:
   under p the expected root after resolution drifts (q1b.out: -0.12, -0.15) while under q it is ~0 (martingale /
   conservation of expected evidence); a Jev-certain claim (p=1) under a decisive attack gets VoI 0 today and is
   stopped at once although E_q=.05.
2. A "secant" evaluated at node=0 and node=1 and propagated exactly along the path needs exactly the evaluations the
   exact E needs (R is already known), so it is dominated by exact. Cell-compatible secants (secant partials per hop
   in the sensitivity cells) are no better than today's tangent (q1_main.out SecChain_p/Sec1stHop_p).
3. T_q (today's tangent with q instead of p) is a free improvement for ranking (Spearman .88 -> .98 vs E_q,
   consensus) but still fails near clamps (woe headline: 43 premature), so it does not fix dw2wh.
4. Recommendation: VoI := exact E_q computed on demand by a pure function over the credence hub snapshot + structure
   + stances (walk node->root, re-evaluate only the headline layers), memoised per hub version; cost ~15 evaluate()
   calls per candidate vs ~361 per full sensitivity-layer recompute for a 14-node tree; take() rescoring all ready
   tasks per dequeue is then <= ~180*2*4*3 evaluate calls. Hybrid "tangent + exact confirm near eps" does not pay:
   80% of candidates fall in the confirm band. Links: same with their credence.
   Regression tests: woe clamp case (today 0, exact .059), over-rate case (.433 vs .309), p=1 claim under decisive
   attack (today 0, E_q .050 consensus), plus a seeded random-tree property test vs an independent full-tree evaluator.
Q2 (categorical, closed-world log-linear elimination per layer, log-pooled)
5. Recommend eM_w(tau=1) = sum_k s_k * E|dM_k|, M_k(s) = s_k - max_{j!=k} s_j, weights at the CURRENT s, expectation
   over resolution to 1/0 with prob q, exact through the tree and the combination. It is continuous at ties (S1/S6:
   eM_hard jumps .1446<->.1006 across a 3-way tie, eM_w constant .1106), keeps tail-only discriminators ~0
   (S3 .0019, eTV .040, e|dH| .034), values broad claims more than eM_hard (S3 'rules out C' .063 vs .041, while
   'for A' drops .153->.127), removes the leader privilege of a 1% lead (S2), and reduces EXACTLY to the binary
   E_q at K=2 (S0: 6.7e-16), as do eM_hard and eTV; eSM (smooth margin with recomputed weights), entropy, info gain
   and flip probability do not.
6. Linearisation through the tree is again materially worse (random categorical: tangent 71 premature /10258,
   tree-linear-combination-exact 13), so exact it is.
Look especially for: bugs in my ports or variant definitions (e.g. the factor 2 conventions, curve_R, calibrated()),
whether q vs p is really the right call, whether the martingale argument is sound given q is a consensus of layers,
unfair comparisons, and anything that would make eM_w(tau=1) a bad default. Reply in <= 600 words.
