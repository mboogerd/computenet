#!/usr/bin/env python3
"""Where is the information lost? Run after tune.py: uv run --with numpy --with scipy python diagnose.py
(1) Does argument credence matter? Refit the incremental family with every claim's plausibility replaced by a constant.
    Recursion only refines that plausibility, so if it barely matters at one level, depth cannot buy accuracy.
(2) How good is Jev's per-option bearing? Precision of 'settles' / 'rules out' calls against the real answer."""
import numpy as np, tune as T
fixed_p = T.p.copy(); T.p[:] = np.where(T.jmask > 0, .75, 0); T.pcf[:] = T.p
s = T.cv(lambda ix: T.fit_family(ix)); T.line("family, plausibility replaced by constant .75", s)
T.p[:] = 1 - fixed_p * (T.jmask > 0) + (T.jmask == 0) * 0; T.pcf[:] = T.p   # inverted: high plausibility -> low
s = T.cv(lambda ix: T.fit_family(ix)); T.line("family, plausibility INVERTED (1 - p)", s)
T.p[:] = fixed_p; T.pcf[:] = fixed_p

print("\nJev bearing calls vs the real answer (all train+held-out claims; b = mean of paraphrases, score/4)")
truth = np.zeros_like(T.b, dtype=bool)
for i in range(T.Q): truth[i, :, T.y[i]] = True
live = (T.jmask[..., None] > 0) & T.km
for name, sel in (("settles (b >= .875)", T.b >= .875), ("counts for (.625 <= b < .875)", (T.b >= .625) & (T.b < .875)),
                  ("no bearing (.375 < b < .625)", (T.b > .375) & (T.b < .625)), ("counts against (.125 < b <= .375)", (T.b > .125) & (T.b <= .375)),
                  ("rules out (b <= .125)", T.b <= .125)):
    m = sel & live; base = (truth & live).sum() / live.sum()
    for pn, pm in (("any p", np.ones_like(T.p, bool)), ("p >= .75", T.p >= .75)):
        mm = m & pm[..., None]
        print(f"  {name:36} {pn:9} n={mm.sum():5d}  P(option is the answer)={(truth & mm).sum() / max(mm.sum(), 1):.3f}  (base rate {base:.3f})")
# per question: does ANY plausible claim single out the true answer as its top option?
top = T.b.argmax(-1); uniq = (T.b == T.b.max(-1, keepdims=True)).sum(-1) == 1
hit = [(((top[i] == T.y[i]) & uniq[i] & (T.jmask[i] > 0) & (T.p[i] >= .75)).any()) for i in range(T.Q)]
wrong = [(((top[i] != T.y[i]) & uniq[i] & (T.jmask[i] > 0) & (T.p[i] >= .75)).any()) for i in range(T.Q)]
print(f"\n  questions where some plausible claim uniquely points at the true answer: {np.mean(hit):.2f}; at a wrong one: {np.mean(wrong):.2f}")
