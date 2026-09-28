import math, voi_check as Q, verify as V
cls = "ABCDE"
forX = lambda X: (0.9, {c: (1 if c == X else 0) for c in cls})
claims = [forX("A"), forX("B"), forX("E")]  # A leader-ish, B, E tail
f, en, w, pp, k = V.LAYERS["woe"]
def s_of(pi, x): return V.LL([(w(en(st, x[i])), kp) for i, (st, kp) in enumerate(claims)], pi, pp, k)
# solve priors so s is exactly uniform at x=.5 (log pi_k = + penalty_k)
x0 = [0.5, 0.5, 0.5]
pen = {c: 0 for c in cls}
for c in cls:
    ws = [w(en(st, x0[i])) * (1 - kp.get(c, 1)) for i, (st, kp) in enumerate(claims)]
    pen[c] = k * V.pnorm([v for v in ws if v > 0], pp)
def run(eps):
    pi = {c: math.exp(pen[c]) * (1 + (eps if c == "E" else 0)) for c in cls}; t = sum(pi.values()); pi = {c: v / t for c, v in pi.items()}
    s0 = s_of(pi, x0); k1 = max(s0, key=s0.get)
    M = lambda d: d[k1] - max(v for kk, v in d.items() if kk != k1)
    res = []
    for i in range(3):
        e = 0
        for r, pr in ((1.0, .5), (0.0, .5)):
            x = list(x0); x[i] = r; e += pr * abs(M(s_of(pi, x)) - M(s0))
        res.append(e)
    return s0, k1, res
for eps in (0.0, 1e-4, -1e-4):
    s0, k1, res = run(eps)
    print(f"prior tweak on E {eps:+g}: s={[round(s0[c],4) for c in cls]} leader={k1} eM(for A)={res[0]:.4f} eM(for B)={res[1]:.4f} eM(for E)={res[2]:.4f}")
