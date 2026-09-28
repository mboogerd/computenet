
## Round 3 experiments, 2026-09-28 (supersedes conflicting points above)

### Architecture that emerged
P(answer k) = P(L) · s_k, where s = closed-world log-linear elimination over the listed answers and L = "the answer is one of the listed" is its own ordinary binary deliberate root (argued, Jev-judged, first impression). Pool P(L) as a binary and s separately (pooling the full K+1 vector leaks via AM-GM).

### None-of-these (exp/none)
- Adding Ω as a softmax option leaves s_k exactly the closed-world result: only P(L) needs design.
- From compatibility links alone, "3 pros for A + 3 cons against A" is indistinguishable from "every class eliminated once" (a pro-A claim eliminates B) — separating them needs a Jev judgment "absolute vs comparative objection".
- RECOMMENDED A0 hurdle: L as its own root; exactly inert to listed debate; exact K=2 continuity; none-of-these rises only via explicit judged claims on L. Sol recommends this.
- Optional Hmin-pen: logit P(L) lowered by the least-objected class's penalty from judged-ABSOLUTE objections (10 decisive objections per class -> .98). Leaks up to +.15 if Jev over-calls absoluteness; a junk class can veto. Opus accept-with-changes; final formula unreviewed. Gate: measure Jev on absolute vs comparative objections.
- Rejected: flat Ω, per-claim κ_Ω (contrastive claims -> Ω 1.0), TBM-open. Open: new class start (carve from Ω; carve->full jump), Jev's direct L impression vs per-class plausibilities disagreeing.

### Prior leakage (exp/leak, $0.02)
- Prior subtraction (logit β=1, fitted β, class-z) BACKFIRES (overshoots, kills κ). Irrelevant placebo claims get exactly "no bearing" from every graded shape: the leak appears only for on-topic neutral claims, not as a per-class offset.
- Best: ISO bear + instruction "don't judge the answer's overall plausibility", graded: leak .32 (consensus golds) / .44 (original), POA .97, graded rule-out κ .71-.81. n=40 neutral cells, CI ±.17: not decisive. Rule-out upgrading insensitive to wording; mostly on cells raters split on.

### VoI (exp/voi; Sol agreed-with-changes) — see also computenet-dw2wh
- Binary: exact E_q = 2(q|R(1)−R| + (1−q)|R(0)−R|), q = propagated credence (not Jev p), computed on demand (~15 evaluate() per candidate; memoise per hub version). Today: 54/3873 premature DIMINISHING.
- Categorical: eM_w(τ=1) = Σ_k s_k·E|ΔM_k|, M_k = s_k − max_{j≠k} s_j, exact, q-weighted. = binary E_q at K=2; continuous at ties; values broad claims; small for tail-only. Provisional (hand-picked scenarios; duplicate-class / K-change invariance untested).

### End-to-end accuracy proxy (exp/e2e, $7.56)
30 Manifold multiple-choice markets resolved 2026-07-01..09-27 (after model cutoff; K 3-8; OTHER true in 4); one level of arguments, no recursion, no VoI, no human. Metaculus baseline score (uniform = 0), 95% bootstrap CI:
- P (Opus proposes, Jev bear per class, woe+mlp log-pool): +5.6 [1.2, 10.8]; beats uniform, model A (exactly uniform: Jev said OUTSIDE_MY_KNOWLEDGE for all positions) and every Jev-only arm (Jev Choice −74). P-sol (Sol proposes) same (−0.9 [−3.2, +1.6] vs P).
- Opus asked directly: +17.7 [1.7, 33.5]; paired +12.2 [−2.0, +25.7] over P (Brier gap of Opus mean-of-5 excludes 0). P is NOT shown better than a direct LLM forecast.
- P is badly UNDER-CONFIDENT: mean claim energy .12; 41% of claims judged OUTSIDE_MY_KNOWLEDGE and withheld. Post-hoc k×2 -> +10.6 (in-sample, not a result).
- β=1 leak correction hurts (−10.4). OTHER-true questions: P stays near 1/K (claims rarely bear on the catch-all) — the none-of-these problem in the wild. One case (Eurovision) where P's damping beat confidently wrong Opus/Sol.
- Caveat: future-event forecasting is Jev's weakest domain (knowledge cutoff); n=30, only large effects detectable.

### Status of the recommendation
Mechanism is sound and verified (combination, none-of-these hurdle, exact VoI). The open question is VALUE: at one level, argue/judge/combine carries real but weak signal and loses to asking Opus directly on future events. Next: (1) a knowledge-domain question set (not forecasting), where Jev is not OUTSIDE_MY_KNOWLEDGE; (2) calibrate energy scale (k) out-of-sample; (3) full recursive deliberation on a handful of questions vs direct Opus; (4) consider an LLM-direct distribution as the first impression / prior layer (model D analogue) with arguments as the adjustment.
