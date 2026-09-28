"""Invariance tests: class duplication, irrelevant tiny class, relabelling, K sweep."""
import copy, random, math
from lib import *

KEYS = CANDS + PREPOST

def rebuild(cat, prior, kap_fn):
    """same Node objects (credences untouched), new classes/kappas."""
    tops = [Top(t.node, t.s, kap_fn(t.kappa)) for t in cat.tops]
    return Cat(prior, tops)

def split(cat, A):
    pri = {}
    for k, v in cat.prior.items():
        if k == A: pri[A + "1"] = v / 2; pri[A + "2"] = v / 2
        else: pri[k] = v
    def kf(kap):
        d = {}
        for k, v in kap.items():
            if k == A: d[A + "1"] = v; d[A + "2"] = v
            else: d[k] = v
        return d
    return rebuild(cat, pri, kf)

def add_tiny(cat, mode, eps=1e-3):
    pri = {k: v * (1 - eps) for k, v in cat.prior.items()}; pri["Z"] = eps
    s = cat.prior
    def kf(kap):
        d = dict(kap)
        d["Z"] = 1.0 if mode == "k1" else (0.0 if mode == "k0" else sum(s[k] * kap[k] for k in s))
        return d
    return rebuild(cat, pri, kf)

def relabel(cat, rnd):
    ks = list(cat.prior); perm = ks[:]; rnd.shuffle(perm); mp = dict(zip(ks, perm))
    pri = {mp[k]: v for k, v in cat.prior.items()}
    return rebuild(cat, {k: pri[k] for k in ks}, lambda kap: {mp[k]: v for k, v in kap.items()})

def vec(cat):
    s0 = cat.dist(); return [all_scores(cat, n, s0=s0) for n in nodes_of(cat)]

def compare(tag, pairs):
    print(f"\n#### {tag}  ({len(pairs)} questions)")
    print("| objective | median |Δ|/value | 95% | max | per-question Spearman(before,after) | top-1 same | ≥.01 decision flips |\n|---|---|---|---|---|---|---|---|")
    for k in KEYS:
        rel, sp, t1, fl = [], [], [], 0
        for a, b in pairs:
            xa = [r[k] for r in a]; xb = [r[k] for r in b]
            for u, v in zip(xa, xb):
                if abs(u) > 1e-6: rel.append(abs(v - u) / abs(u))
                if (u >= .01) != (v >= .01): fl += 1
            if len(xa) >= 4: sp.append(spearman(xa, xb)); t1.append(topk(xa, xb, 1))
        rel.sort(); qq = lambda f: rel[int(f * (len(rel) - 1))]
        print(f"| {k} | {qq(.5):.3f} | {qq(.95):.3f} | {rel[-1]:.3f} | {mean(sp):.3f} | {mean(t1):.2f} | {fl} |")

def run_random(n=150, seed=11):
    rnd = random.Random(seed); cats = [gen_cat(rnd, 3, 7) for _ in range(n)]
    base = [vec(c) for c in cats]
    compare("duplicate the LEADER class (A_lead -> A1, A2, identical links, prior halved)",
            [(b, vec(split(c, leader(c.dist())))) for c, b in zip(cats, base)])
    def trailer(c): s = c.dist(); return min(s, key=s.get)
    def second(c): s = c.dist(); return sorted(s, key=s.get)[-2]
    compare("duplicate the RUNNER-UP class", [(b, vec(split(c, second(c)))) for c, b in zip(cats, base)])
    compare("duplicate the LAST class", [(b, vec(split(c, trailer(c)))) for c, b in zip(cats, base)])
    for mode in ("k1", "neutral", "k0"):
        compare(f"add irrelevant class Z, prior 1e-3, kappa_Z={mode}", [(b, vec(add_tiny(c, mode))) for c, b in zip(cats, base)])
    r2 = random.Random(5)
    compare("relabel (random permutation of class names)", [(b, vec(relabel(c, r2))) for c, b in zip(cats, base)])

def hand_split():
    print("\n#### hand case: 3 classes, leader A=.60, B=.25, C=.15; claims: for A, A-over-B contrast, for C (leaf p=.5, s=.9)")
    K = "ABC"
    def mk(Aname=None):
        ns = [Node(.5) for _ in range(3)]
        tops = [Top(ns[0], .9, forX(K, "A")), Top(ns[1], .9, {"A": 1, "B": 0, "C": 1}), Top(ns[2], .9, forX(K, "C"))]
        c = Cat({"A": .6, "B": .25, "C": .15}, tops); return c, ns
    c, ns = mk(); c2 = split(c, "A")
    print(" s =", {k: round(v, 3) for k, v in c.dist().items()}, " split s =", {k: round(v, 3) for k, v in c2.dist().items()})
    print("| claim | " + " | ".join(f"{k} before/after" for k in ["eM_w", "eM_hard", "eTV", "EVSI_log", "EVSI_01", "P(flip)"]) + " |\n|---|---|---|---|---|---|---|")
    for nm, x in zip(["for A", "A over B", "for C"], ns):
        a = all_scores(c, x); b = all_scores(c2, x)
        print(f"| {nm} | " + " | ".join(f"{a[k]:.4f}/{b[k]:.4f}" for k in ["eM_w", "eM_hard", "eTV", "EVSI_log", "EVSI_01", "P(flip)"]) + " |")

def ksweep():
    print("\n#### K sweep 2..10, fixed evidence: leaves p=.6, s=.9: c1 for A; c2 against A (rules out A only); c3 A-over-B contrast; c4 for B. uniform prior")
    ks = ["eM_w", "eM_hard", "eTV", "e|dH|", "P(flip)", "EVSI_log", "EVSI_brier", "EVSI_01"]
    print("| K | claim | s_lead | " + " | ".join(ks) + " |\n|---|---|---|" + "---|" * len(ks))
    for K in range(2, 11):
        cl = "ABCDEFGHIJ"[:K]; ns = [Node(.6) for _ in range(4)]
        tops = [Top(ns[0], .9, forX(cl, "A")), Top(ns[1], .9, {c: (0.0 if c == "A" else 1.0) for c in cl}),
                Top(ns[2], .9, {c: (0.0 if c == "B" else 1.0) for c in cl}), Top(ns[3], .9, forX(cl, "B"))]
        cat = Cat({k: 1 / K for k in cl}, tops); s = cat.dist()
        for nm, x in zip(["for A", "against A", "A over B", "for B"], ns):
            o = all_scores(cat, x)
            print(f"| {K} | {nm} | {max(s.values()):.3f} | " + " | ".join(f"{o[k]:.4f}" for k in ks) + " |")

if __name__ == "__main__":
    hand_split(); ksweep(); run_random()
