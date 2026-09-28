"""Q2: categorical-root VoI objectives over the closed-world log-linear elimination combination (LL per
log-odds layer wlo/jnb/woe, log-pooled). Top-level claims carry graded compatibility kappa over classes and a
binary subtree (tree.py) that sets their credence."""
import math, random, sys
from tree import *
sys.path.insert(0, "../../verify/math")
import verify as V

class Top:
    def __init__(self, node, s, kappa): self.node, self.s, self.kappa = node, s, kappa

class Cat:
    def __init__(self, prior, tops):
        self.prior = prior; self.tops = tops; self.K = list(prior)
        for t in tops: evaluate(t.node)
    def dist(self, cvals=None):
        """cvals: per top index -> {layer: credence}; default the tops' current credences."""
        logs = {k: 0.0 for k in self.K}
        for l in MEMBERS:
            f, en, w, pp, kk = V.LAYERS[l]
            claims = []
            for i, t in enumerate(self.tops):
                c = (cvals or {}).get(i, t.node.c)[l]
                claims.append((w(en(t.s, c)), t.kappa))
            d = V.LL(claims, self.prior, pp, kk)
            for k in self.K: logs[k] += math.log(max(d[k], 1e-300)) / len(MEMBERS)
        m = max(logs.values()); z = {k: math.exp(v - m) for k, v in logs.items()}; t = sum(z.values())
        return {k: v / t for k, v in z.items()}
    def top_index(self, n):
        r = root_of(n)
        return next(i for i, t in enumerate(self.tops) if t.node is r)
    def resolved(self, n, r):
        i = self.top_index(n)
        return self.dist({i: {l: resolved_root(n, r, l) for l in MEMBERS}})
    def linear_resolved(self, n, r):
        """tangent through the binary subtree, combination exact (the 'design hybrid')."""
        i = self.top_index(n); t = self.tops[i]
        vals = {}
        for l in MEMBERS:
            g = sens_layer(n, l); vals[l] = min(max(t.node.c[l] + g * (r - n.c[l]), 0.0), 1.0)
        return self.dist({i: vals})
    def moved(self, n, x):
        i = self.top_index(n)
        return self.dist({i: {l: resolved_root(n, min(max(x, 0), 1), l) for l in MEMBERS}})

def q_of(n): return Headline("consensus").of(n.c)

# ---- margins and objectives ----
def M(s, k): return s[k] - max(v for j, v in s.items() if j != k)
def leader(s): return max(s, key=s.get)
def weights(s, tau):
    if tau == 0: k1 = leader(s); return {k: (1.0 if k == k1 else 0.0) for k in s}
    z = {k: s[k] ** (1 / tau) for k in s}; t = sum(z.values()); return {k: v / t for k, v in z.items()}
def H(s): return -sum(v * math.log(v) for v in s.values() if v > 0)
def SM(s, tau):
    w = weights(s, tau); return sum(w[k] * M(s, k) for k in s)

def objectives(cat, n, lin=False):
    s0 = cat.dist(); q = q_of(n)
    res = cat.linear_resolved if lin else cat.resolved
    s1, s0r = res(n, 1.0), res(n, 0.0)
    E = lambda f: q * f(s1) + (1 - q) * f(s0r)
    out = {
        "eM_hard": E(lambda s: abs(M(s, leader(s0)) - M(s0, leader(s0)))),
        "eM_w(t=1)": sum(weights(s0, 1)[k] * E(lambda s: abs(M(s, k) - M(s0, k))) for k in s0),
        "eM_w(t=.5)": sum(weights(s0, .5)[k] * E(lambda s: abs(M(s, k) - M(s0, k))) for k in s0),
        "eSM(t=.1)": E(lambda s: abs(SM(s, .1) - SM(s0, .1))),
        "eTV": E(lambda s: sum(abs(s[k] - s0[k]) for k in s0)),
        "e|dH|": E(lambda s: abs(H(s) - H(s0))),
        "InfoGain": H(s0) - E(H),
        "P(flip)": E(lambda s: 1.0 if leader(s) != leader(s0) else 0.0),
    }
    return out

def tangent_eMw(cat, n, tau=1.0, h=1e-6):
    """fully linear: sum_k w_k |dM_k/dx| * 4q(1-q) (today's rule generalised)."""
    s0 = cat.dist(); x = q_of(n); w = weights(s0, tau)
    # perturb the node's credence in every layer by +-h (as CredenceGraph.scalar does)
    i = cat.top_index(n)
    def at(dx): return cat.dist({i: {l: resolved_root(n, min(max(n.c[l] + dx, 0), 1), l) for l in MEMBERS}})
    sp, sm = at(h), at(-h)
    return sum(w[k] * abs(M(sp, k) - M(sm, k)) / (2 * h) for k in s0) * unc(x) / 2  # 2q(1-q): M already carries the binary factor 2

def fmt(d, keys=None): return " ".join(f"{k}={d[k]:.4f}" for k in (keys or d))

def calibrated(K, tops, target):
    """prior such that the CURRENT pooled distribution equals target (exact ties by construction)."""
    c = Cat({k: 1 / len(K) for k in K}, tops); su = c.dist()
    raw = {k: target[k] / su[k] for k in K}; t = sum(raw.values())
    c.prior = {k: v / t for k, v in raw.items()}; return c
