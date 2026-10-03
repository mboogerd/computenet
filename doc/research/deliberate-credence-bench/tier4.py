#!/usr/bin/env python3
"""Tier 4 of the deliberate credence benchmark: property tests for wicked problems, where there is no answer to score.
Run: uv run --with numpy --with scipy python tier4.py      (offline, deterministic, seconds)

A rule that a group uses to make a collective bet must be trustworthy as a PROCESS. Each property below is a check on
small hand-built trees, run on every shipped layer and on the candidate rules from Tiers 1-2. MUST properties are hard
requirements; SHOULD properties are measured and reported (some trade off against each other). Every candidate is
also scored on Tier 2 (log loss vs truth; needs tier2's Jev cache), so each property's accuracy cost is visible.

MUST
  M1 neutral       no arguments -> the claim stays at its base
  M2 bounded       credence stays in [0, 1], and one decisive (s 1), certainly-true (c 1) argument does not create
                   certainty (< .9999) from a .5 prior. (Many maximal arguments round to 1.0 in floating point under any
                   logistic rule, so "never certain" is tested on one argument.)
  M3 monotone      more support (strength, credence, count) never lowers credence; more attack never raises it
  M4 order-free    the result does not depend on the order arguments arrive in (incrementality)
  M5 responsive    (computenet-nxege) one strong (s .9), believed (c .9), unrebutted argument moves its parent across .5
                   from a prior of .25 (support) or .75 (attack): the argument outweighs the system-one prior
  M6 side-neutral  a support and an attack of equal strength and credence move a .5 prior by equal amounts in opposite
                   directions - no side is privileged by the rule itself
SHOULD
  S1 doubt inert   a refuted argument (c .05) moves its parent by < .02
  S2 unknown inert an argument nobody has evidence about (c .5) moves its parent by < .05
  S3 duplicate     effect of the same argument twice / effect once (1.0 = immune; 2.0 = fully double-counted).
                   A rule sees only numbers, so true duplicate immunity belongs at intake (triage DUPLICATE); this
                   measures how much damage a duplicate that slips through does
  S4 flood         how many weak arguments (s .2, c .7) on one side it takes to overturn one strong believed argument
                   (s .9, c .9) on the other side, from a .5 prior (higher = more resistant; '>50' = never)
  S5 depth         root move from a chain of strong believed supports (s .9, leaf c .95, intermediate priors .5),
                   at depth 1..4: does the evidence reach the root, or is it swallowed by the priors on the way?"""
import json, math
from pathlib import Path
import numpy as np
import tier1 as T1

HERE = Path(__file__).resolve().parent

def tree(base_root, kids):
    """kids: list of (sign, s, child) where child is a credence (leaf) or a nested (base, kids) tuple. Returns forest F."""
    P, D, SG, S, B = [], [], [], [], []
    def add(parent, depth, sign, s, node):
        i = len(P); P.append(parent); D.append(depth); SG.append(sign); S.append(s)
        if isinstance(node, tuple): B.append(node[0]); [add(i, depth + 1, *k) for k in node[1]]
        else: B.append(node)
        return i
    add(-1, 0, 1.0, 0.0, (base_root, kids))
    P = np.array(P); return dict(parent=P, depth=np.array(D), sign=np.array(SG, float), s=np.array(S, float), base=np.array(B, float),
                                 nk=np.bincount(P[P >= 0], minlength=len(P)))

def mean_params(ths): return {k: float(np.mean([t[k] for t in ths])) for k in ths[0]}
t2 = json.load(open(HERE / "tier2_fitted.json"))["  ... doubted argument inert (eta=0)"]
t1 = json.load(open(HERE / "tier1_results.json"))["fitted"]["  ... eta=0 (doubted argument inert)"]
NEUTRAL = dict(alpha=1.0, w_sup=1.0, w_att=1.0, log_gamma=0.0, log_k=math.log(5), beta=0.0, eta=0.0)
CANDIDATES = {"new, Tier 2 fit": mean_params(t2), "new, Tier 1 argument fit": t1, "new, side-neutral (w=1, k=5)": NEUTRAL}

# The side-neutral candidate with each side aggregated by a p-norm, as wlo/woe do: still incremental (a parent keeps the
# per-side sums of |term|^p), but a duplicate counts 2^(1/p) rather than 2 times, and a flood of weak arguments grows
# like sqrt(n) rather than n.
def fam_p(th, p):
    term1, _ = T1.fam(th)
    def term(c, s, sg_, cb):
        t = term1(c, s, sg_, cb)[:, 0]; return np.stack([np.where(t > 0, t, 0) ** p, np.where(t < 0, -t, 0) ** p], 1)
    return term, (lambda b, S: T1.sg(th["alpha"] * T1.lg(b) + S[:, 0] ** (1 / p) - S[:, 1] ** (1 / p)))
PNORM = {"new, side-neutral, p=2": (NEUTRAL, 2.0)}   # w=1 chosen on Tier 2 from {1, 1.5, 2, 3}

def rule_fns():
    R = {k: (lambda F, k=k: T1.propagate(F, *T1.LAYERS[k])) for k in T1.LAYERS}
    R["consensus(wlo,jnb,woe)"] = T1.consensus
    for name, th in CANDIDATES.items(): R[name] = lambda F, th=th: T1.propagate(F, *T1.fam(th))
    for name, (th, p) in PNORM.items(): R[name] = lambda F, th=th, p=p: T1.propagate(F, *fam_p(th, p))
    return R

def root(rule, base, kids): return float(rule(tree(base, kids))[0])

def checks(rule):
    r = {}
    r["M1"] = all(abs(root(rule, b, []) - b) < 1e-9 for b in (.02, .25, .5, .75, .98))
    vals = [root(rule, b, [(sg, s, c)] * n) for b in (.01, .5, .99) for sg in (1, -1) for s in (.1, .9, 1) for c in (0, .5, 1) for n in (1, 5, 40)]
    r["M2"] = all(0 <= v <= 1 for v in vals) and root(rule, .5, [(1, 1, 1)]) < .9999 and root(rule, .5, [(-1, 1, 1)]) > 1e-4
    mono = True
    for b in (.2, .5, .8):
        for sg in (1, -1):
            grid_s = [root(rule, b, [(sg, s, .9)]) for s in np.linspace(0, 1, 21)]
            grid_c = [root(rule, b, [(sg, .8, c)]) for c in np.linspace(0, 1, 21)]
            grid_n = [root(rule, b, [(sg, .6, .9)] * n) for n in range(0, 8)]
            for g in (grid_s, grid_c, grid_n):
                d = np.diff(g) * sg
                mono &= bool((d >= -1e-9).all())
    r["M3"] = mono
    kids = [(1, .9, .8), (-1, .6, .7), (1, .3, .95), (-1, .8, .2), (1, .5, .5)]
    outs = [root(rule, .4, [kids[i] for i in p]) for p in ([0, 1, 2, 3, 4], [4, 3, 2, 1, 0], [2, 0, 4, 1, 3])]
    r["M4"] = max(outs) - min(outs) < 1e-9
    up, dn = root(rule, .25, [(1, .9, .9)]), root(rule, .75, [(-1, .9, .9)])
    r["M5"] = up > .5 and dn < .5; r["M5_detail"] = f".25->{up:.2f}, .75->{dn:.2f}"
    a, b_ = root(rule, .5, [(1, .7, .8)]) - .5, .5 - root(rule, .5, [(-1, .7, .8)])
    r["M6"] = abs(a - b_) < .01; r["M6_detail"] = f"+{a:.2f} / -{b_:.2f}"
    s1 = max(abs(root(rule, b, [(sg, .9, .05)]) - b) for b in (.3, .5, .7) for sg in (1, -1)); r["S1"] = s1
    s2 = max(abs(root(rule, b, [(sg, .9, .5)]) - b) for b in (.3, .5, .7) for sg in (1, -1)); r["S2"] = s2
    lg = lambda p: math.log(p / (1 - p))
    one, two = lg(root(rule, .5, [(1, .7, .8)])), lg(root(rule, .5, [(1, .7, .8)] * 2))
    r["S3"] = two / one if one > 1e-9 else float("nan")
    n = next((n for n in range(1, 51) if root(rule, .5, [(-1, .9, .9)] + [(1, .2, .7)] * n) > .5), None)
    r["S4"] = n if n else ">50"
    depth = []
    for k in range(1, 5):
        node = .95
        for _ in range(k - 1): node = (.5, [(1, .9, node)])
        depth.append(root(rule, .5, [(1, .9, node)]) - .5)
    r["S5"] = depth
    return r

def tier2_score(rule):
    try:
        import tier2
        F = tier2.forest()
    except Exception as e:
        return None
    m = F["nk"] > 0; y = F["truth"].astype(float)
    return float(T1.ce(rule(F)[m], y[m]).mean())

if __name__ == "__main__":
    R = rule_fns()
    print("Candidate parameters:")
    for k, th in CANDIDATES.items():
        print(f"  {k:30} " + ", ".join(f"{p}={(math.exp(v) if p.startswith('log_') else v):.2f}".replace('log_', '') for p, v in th.items()))
    hdr = f"\n{'rule':30} {'M1':>3} {'M2':>3} {'M3':>3} {'M4':>3} {'M5':>3} {'M6':>3}  {'S1 doubt':>8} {'S2 unkn':>8} {'S3 dup':>7} {'S4 flood':>8}   {'S5 depth 1..4 root move':26} {'M5 detail':18} {'M6 detail':14} T2 logloss"
    print(hdr); out = {}
    for name, rule in R.items():
        c = checks(rule); out[name] = c; t2s = tier2_score(rule)
        mk = lambda k: " ok" if c[k] else " NO"
        print(f"{name:30}" + "".join(f" {mk(k)}" for k in ("M1", "M2", "M3", "M4", "M5", "M6")) +
              f"  {c['S1']:8.3f} {c['S2']:8.3f} {c['S3']:7.2f} {str(c['S4']):>8}   " + " ".join(f"{d:+.2f}" for d in c["S5"]) +
              f"    {c['M5_detail']:18} {c['M6_detail']:14} {t2s:.3f}" if t2s else "")
        c["tier2_logloss"] = t2s
    json.dump(out, open(HERE / "tier4_results.json", "w"), indent=1, default=str)
