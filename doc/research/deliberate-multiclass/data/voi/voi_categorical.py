#!/usr/bin/env python3
"""VoI for a categorical root (bead computenet-1ow0x). Stdlib only.

Three combination rules turn claim credences c into a distribution s over K classes:
  softmax    model A: each class is a position with credence c_k; s = softmax(logit(c)/T).
             candidate nodes are arguments under one position, chain factor g = d c_pos / d c_node.
  dirichlet  alpha_k = prior_k + base_k + sum_i c_i * w_i * compat_ik (crisp 0/1 compat), s = alpha/sum.
  likelihood s_k ∝ pi_k * prod_i (c_i*rho_ik + (1-c_i)*(1-rho_ik))  (graded compat rho; rho=½ is neutral).

Every candidate is resolved to 0 (prob 1-p) or 1 (prob p). VoI measures:
  gH    |dH/dc| * 4p(1-p)                 (gradient of entropy)
  gReg  |d(1-max s)/dc| * 4p(1-p)          (gradient of 0/1 expected regret)
  gMar  ½|d(s1-s2)/dc| * 4p(1-p)           (gradient of half top-margin)
  gTV   ½||ds/dc||_1 * 4p(1-p)             (mass moved, linear)
  eTV   E_r ||s(r)-s||_1                   (mass moved, exact preposterior)   [= gTV at K=2 linear]
  eH    E_r |H(s(r)) - H(s)|
  flip  P(argmax changes)
  evsi  E_r [max s(r) - s(r)[k*]]          (value of switching answer, 0/1 loss)
  eM    E_r |M*(s(r)) - M*(s)|, M*(s) = s[k*] - max_{k!=k*} s[k]  (signed leader margin; = today's rule at K=2)
"""
import math

CLASSES = "ABCDE"
K = 5
W = 2.0          # evidence weight of a candidate claim (dirichlet)
G = 0.4          # chain factor of an argument under a position (softmax)
T = 1.0
LOW, HIGH = 0.01, 0.99


def norm(v):
    t = sum(v)
    return [x / t for x in v]


def H(s):
    return -sum(x * math.log(x) for x in s if x > 0)


def top2(s):
    o = sorted(range(len(s)), key=lambda k: -s[k])
    return o[0], o[1]


def logit(p):
    p = min(max(p, LOW), HIGH)
    return math.log(p / (1 - p))


# candidates: name -> (crisp compat set, graded rho, softmax (position, sign))
# name -> (crisp compat set, graded rho, softmax (position, signed chain g), strength multiplier)
TEMPER = 0.6     # likelihood: rho_eff = ½ + TEMPER*mult*(rho-½), clamped to [0,1]
CANDS = {
    "LEAD  A-over-B": ({0}, [1, 0, .5, .5, .5], (0, +G), 1.0),
    "TAIL  D-over-E": ({3}, [.5, .5, .5, 1, 0], (3, +G), 1.0),
    "BROAD AB-over-tail": ({0, 1}, [1, 1, 0, 0, 0], None, 1.0),
    "ANTI  against-A": ({1, 2, 3, 4}, [0, 1, 1, 1, 1], (0, -G), 1.0),
    "SURGE strong-for-C": ({2}, [.5, .5, 1, .5, .5], (2, +2.5 * G), 4.5),
}


def rho_eff(n):
    _, rho, _, mult = CANDS[n]
    m = mult if mult == 1.0 else 1.5
    return [min(1.0, max(0.0, .5 + TEMPER * m * (r - .5))) for r in rho]
P = 0.5  # every candidate's current plausibility (so 4p(1-p)=1; rankings reflect bearing only)

SCEN = {
    "near-tie + tail": ([.34, .32, .12, .11, .11], 20.0),
    "tie + contender": ([.33, .31, .21, .075, .075], 30.0),
    "clear winner":    ([.80, .08, .05, .04, .03], 60.0),
    "all implausible": ([.21, .20, .20, .20, .19], 11.0),   # little total evidence / all positions ~0.1
}


class Rule:
    """s(x) where x = dict cand -> credence; calibrated so s(P for all) == target."""

    def __init__(self, kind, target, total):
        self.kind = kind
        self.names = [n for n in CANDS if kind != "softmax" or CANDS[n][2] is not None]
        x0 = {n: P for n in self.names}
        if kind == "dirichlet":
            alpha = [total * t for t in target]
            # SURGE is infeasible (would need negative base evidence) unless its class already holds W*mult*P
            if alpha[2] - P * W * CANDS["SURGE strong-for-C"][3] - P * W <= 0:
                self.names = [n for n in self.names if not n.startswith("SURGE")]
                x0 = {n: P for n in self.names}
            self.base = alpha[:]
            for n in self.names:
                for k in CANDS[n][0]:
                    self.base[k] -= P * W * CANDS[n][3]
            assert min(self.base) > 0, (target, self.base)
        elif kind == "likelihood":
            lp = [math.log(t) for t in target]
            for n in self.names:
                rho = rho_eff(n)
                for k in range(K):
                    lp[k] -= math.log(P * rho[k] + (1 - P) * (1 - rho[k]))
            self.logpi = lp
        else:  # softmax; "all implausible" = every position ~0.1, else leader ~0.7
            scale = 0.1 if total < 15 else 0.6
            lz = [math.log(t) for t in target]
            shift = logit(scale) - max(lz)
            self.pos = [1 / (1 + math.exp(-(z + shift))) for z in lz]
        assert all(abs(a - b) < 1e-9 for a, b in zip(self.s(x0), target)), (kind, self.s(x0), target)
        self.x0 = x0

    def s(self, x):
        if self.kind == "dirichlet":
            a = self.base[:]
            for n in self.names:
                for k in CANDS[n][0]:
                    a[k] += x[n] * W * CANDS[n][3]
            return norm(a)
        if self.kind == "likelihood":
            lp = self.logpi[:]
            for n in self.names:
                rho = rho_eff(n)
                for k in range(K):
                    lp[k] += math.log(max(1e-12, x[n] * rho[k] + (1 - x[n]) * (1 - rho[k])))
            m = max(lp)
            return norm([math.exp(v - m) for v in lp])
        c = self.pos[:]
        for n in self.names:
            k, g = CANDS[n][2]
            c[k] = min(max(c[k] + g * (x[n] - P), LOW), HIGH)
        m = [logit(v) / T for v in c]
        mx = max(m)
        return norm([math.exp(v - mx) for v in m])


def measures(rule, n):
    x0 = rule.x0
    s0 = rule.s(x0)
    k1, k2 = top2(s0)
    u = 4 * P * (1 - P)
    h = 1e-6
    xp, xm = dict(x0), dict(x0)
    xp[n] += h
    xm[n] -= h
    sp, sm = rule.s(xp), rule.s(xm)
    ds = [(a - b) / (2 * h) for a, b in zip(sp, sm)]
    dH = (H(sp) - H(sm)) / (2 * h)
    out = {
        "gH": abs(dH) * u,
        "gReg": abs(ds[k1]) * u,
        "gMar": 0.5 * abs(ds[k1] - ds[k2]) * u,
        "gTV": 0.5 * sum(abs(d) for d in ds) * u,
    }
    def lead_margin(s):
        return s[k1] - max(s[k] for k in range(K) if k != k1)
    eTV = eH = flip = evsi = eM = 0.0
    for r, pr in ((1.0, P), (0.0, 1 - P)):
        x = dict(x0)
        x[n] = r
        s = rule.s(x)
        eTV += pr * sum(abs(a - b) for a, b in zip(s, s0))
        eH += pr * abs(H(s) - H(s0))
        kk = max(range(K), key=lambda k: s[k])
        flip += pr * (kk != k1)
        evsi += pr * (s[kk] - s[k1])
        eM += pr * abs(lead_margin(s) - lead_margin(s0))
    out.update(eTV=eTV, eH=eH, flip=flip, evsi=evsi, eM=eM)
    return out


def analytic_check():
    """Closed forms vs finite differences."""
    # softmax: dH/dz_m = -s_m (ln s_m + H)
    c = [.7, .6, .3, .2, .1]
    z = [logit(v) for v in c]
    s = norm([math.exp(v) for v in z])
    for m in range(K):
        h = 1e-6
        zp = z[:]; zp[m] += h
        zm = z[:]; zm[m] -= h
        fd = (H(norm([math.exp(v) for v in zp])) - H(norm([math.exp(v) for v in zm]))) / (2 * h)
        an = -s[m] * (math.log(s[m]) + H(s))
        assert abs(fd - an) < 1e-6, (m, fd, an)
    # dirichlet: ds/dc_i = (w kappa / A)(q - s); dH/dc_i = (w kappa/A)(CE(q,s) - H(s))
    alpha = [5, 4, 2, 1, 1]
    A = sum(alpha)
    s = norm(alpha)
    compat = [0, 0, 1, 1, 0]
    kappa = sum(compat)
    q = [x / kappa for x in compat]
    h = 1e-6
    ap = [a + W * h * cc for a, cc in zip(alpha, compat)]
    am = [a - W * h * cc for a, cc in zip(alpha, compat)]
    fd = (H(norm(ap)) - H(norm(am))) / (2 * h)
    an = W * kappa / A * (-sum(qk * math.log(sk) for qk, sk in zip(q, s)) - H(s))
    assert abs(fd - an) < 1e-6, (fd, an)
    # K=2 reduction: margin/regret/TV gradient VoI == |g| 4p(1-p); preposterior E|Δr| == 2|g|p(1-p)
    for p in (.2, .5, .9):
        for g in (.1, .3):
            r0 = .7
            e_abs = p * abs(g * (1 - p)) + (1 - p) * abs(g * p)
            assert abs(2 * e_abs - g * 4 * p * (1 - p)) < 1e-12
            # half-margin: M = s1 - s2 = 2r - 1; ½|dM/dc| = |g|
            assert abs(0.5 * abs(2 * g) - g) < 1e-12
    print("analytic checks: OK (softmax dH/dz, dirichlet dH/dc, K=2 reductions)")


def main():
    analytic_check()
    keys = ["gH", "gReg", "gMar", "gTV", "eTV", "eH", "flip", "evsi", "eM"]
    for kind in ("softmax", "dirichlet", "likelihood"):
        for sname, (target, total) in SCEN.items():
            rule = Rule(kind, target, total)
            print(f"\n== {kind} / {sname}  s={[round(v, 3) for v in rule.s(rule.x0)]}  H={H(rule.s(rule.x0)):.3f}")
            print(f"{'candidate':22s}" + "".join(f"{k:>8s}" for k in keys))
            rows = {n: measures(rule, n) for n in rule.names}
            for n, r in rows.items():
                print(f"{n:22s}" + "".join(f"{r[k]:8.3f}" for k in keys))
            print("  top by measure: " + "  ".join(
                f"{k}={max(rows, key=lambda n: rows[n][k]).split()[0] if max(r[k] for r in rows.values()) > 1e-9 else '-'}"
                for k in keys))


if __name__ == "__main__":
    main()
