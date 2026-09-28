"""Q2 scenarios + random categorical trees."""
import random, math
from cat import *
from q1 import spearman, topk

KEYS = ["eM_hard", "eM_w(t=1)", "eM_w(t=.5)", "eSM(t=.1)", "eTV", "e|dH|", "InfoGain", "P(flip)"]
def leafnode(p=0.5): return Node(p)
def forX(K, X): return {c: (1.0 if c == X else 0.0) for c in K}
def uni(K): return {c: 1 / len(K) for c in K}

def row(name, cat, n):
    o = objectives(cat, n)
    print(f"| {name} | " + " | ".join(f"{o[k]:.4f}" for k in KEYS) + " |")
def hdr(title, cat):
    s = cat.dist(); print(f"\n### {title}\n s = {{{', '.join(f'{k}:{v:.3f}' for k, v in s.items())}}} leader={leader(s)}")
    print("| candidate | " + " | ".join(KEYS) + " |"); print("|---" * (len(KEYS) + 1) + "|")

# ---------- S0: K=2 reduction to the binary exact rule E_q ----------
def s0():
    rnd = random.Random(5); hl = Headline("consensus"); worst = {k: 0 for k in KEYS}; n = 0
    for _ in range(200):
        root, nodes = gen_tree(rnd)
        root.p = min(max(root.p, 0.02), 0.98); evaluate(root)
        ref = {}
        for x in nodes[1:]:
            Rc = hl.of(root.c); R1, R0 = R(hl, x, 1.0), R(hl, x, 0.0); q = node_headline(hl, x)
            ref[id(x)] = 2 * (q * abs(R1 - Rc) + (1 - q) * abs(R0 - Rc))
        tops = []
        for kid in root.kids:
            tops.append(Top(kid, kid.s, {"A": 1.0, "B": 0.0} if kid.pol > 0 else {"A": 0.0, "B": 1.0})); kid.parent = None
        cat = Cat({"A": root.p, "B": 1 - root.p}, tops)
        for x in nodes[1:]:
            o = objectives(cat, x); n += 1
            for k in KEYS: worst[k] = max(worst[k], abs(o[k] - ref[id(x)]))
    print(f"### S0 K=2: max |objective - binary exact E_q| over {n} nodes (consensus headline vs log-pool)")
    print(" " + ", ".join(f"{k}: {v:.1e}" for k, v in worst.items()))

def s1():
    K = "ABCDE"
    for eps in (-1e-6, 0.0, 1e-6):
        ns = [leafnode() for _ in range(3)]
        tops = [Top(ns[0], .9, forX(K, "A")), Top(ns[1], .9, forX(K, "B")), Top(ns[2], .9, forX(K, "E"))]
        tgt = {"A": .2, "B": .2, "C": .2, "D": .2, "E": .2 * (1 + eps)}
        cat = calibrated(K, tops, {k: v / sum(tgt.values()) for k, v in tgt.items()})
        hdr(f"S1 exact 5-way tie (bead c5b), claims for A, B, E; s(E) x(1{eps:+g})", cat)
        for nm, x in zip(["for A", "for B", "for E"], ns): row(nm, cat, x)

def s2():
    K = "ABCDEFGHIJ"
    ns = [leafnode() for _ in range(4)]
    tops = [Top(ns[0], .9, forX(K, "A")), Top(ns[1], .9, forX(K, "J")),
            Top(ns[2], .9, {c: (0.0 if c == "A" else 1.0) for c in K}), Top(ns[3], .9, {c: (0.0 if c == "J" else 1.0) for c in K})]
    tgt = {k: 1.0 for k in K}; tgt["A"] = 1.01; t = sum(tgt.values())
    cat = calibrated(K, tops, {k: v / t for k, v in tgt.items()})
    hdr("S2 K=10 near-uniform s (A +1%)", cat)
    for nm, x in zip(["for leader A", "for J", "against leader A", "rules out J"], ns): row(nm, cat, x)

def s3():
    K = "ABCDE"
    ns = [leafnode() for _ in range(5)]
    tops = [Top(ns[0], .9, forX(K, "A")), Top(ns[1], .9, {"A": 1, "B": 1, "C": 0, "D": 1, "E": 1}),
            Top(ns[2], .9, {"A": 1, "B": 1, "C": 0, "D": 0, "E": 0}),
            Top(ns[3], .9, {"A": 1, "B": 0, "C": 1, "D": 1, "E": 1}), Top(ns[4], .9, {"A": 1, "B": 1, "C": 1, "D": 1, "E": 0})]
    cat = calibrated(K, tops, {"A": .3, "B": .295, "C": .29, "D": .06, "E": .055})
    hdr("S3 3-way near tie A>B>C + tail D,E: broad claims", cat)
    for nm, x in zip(["for A", "broad: rules out C (A,B both up)", "broad: A or B only", "rules out B (A-vs-B)", "tail-only: rules out E"], ns): row(nm, cat, x)

def s4():
    K = "ABCDE"
    ns = [leafnode() for _ in range(3)]
    tops = [Top(ns[0], .9, {"A": 1, "B": 1, "C": 1, "D": 1, "E": 0}), Top(ns[1], .9, {"A": 1, "B": 1, "C": 1, "D": 0, "E": 1}),
            Top(ns[2], .9, {"A": 0, "B": 1, "C": 1, "D": 1, "E": 1})]
    cat = calibrated(K, tops, {"A": .7, "B": .1, "C": .1, "D": .05, "E": .05})
    hdr("S4 clear leader A=.7; tail-only discriminators", cat)
    for nm, x in zip(["rules out E", "rules out D", "rules out leader A"], ns): row(nm, cat, x)

def s5():
    K = "ABC"
    ns = [leafnode() for _ in range(3)]
    tops = [Top(ns[0], .9, {"A": 1, "B": .5, "C": 0}), Top(ns[1], .9, forX(K, "B")), Top(ns[2], .6, {"A": 0, "B": 1, "C": 1})]
    cat = calibrated(K, tops, {"A": .34, "B": .33, "C": .33})
    hdr("S5 K=3 near tie, graded multi-class claim", cat)
    for nm, x in zip(["graded A:1 B:.5 C:0", "for B", "rules out A (s=.6)"], ns): row(nm, cat, x)

def s6():
    print("\n### S6 continuity through a 3-way tie (A,B,E tied, C,D lower); s(E) scaled by (1+d)")
    print("| d | leader | cand | eM_hard | eM_w(t=1) | eM_w(t=.5) | eSM(t=.1) |\n|---|---|---|---|---|---|---|")
    K = "ABCDE"
    for d in (-1e-3, -1e-6, 1e-6, 1e-3):
        ns = [leafnode() for _ in range(3)]
        tops = [Top(ns[0], .9, forX(K, "A")), Top(ns[1], .9, forX(K, "B")), Top(ns[2], .9, forX(K, "E"))]
        tgt = {"A": .25, "B": .25, "C": .1, "D": .1, "E": .25 * (1 + d)}; t = sum(tgt.values())
        cat = calibrated(K, tops, {k: v / t for k, v in tgt.items()})
        for nm, x in (("for A", ns[0]), ("for E", ns[2])):
            o = objectives(cat, x)
            print(f"| {d:+g} | {leader(cat.dist())} | {nm} | {o['eM_hard']:.4f} | {o['eM_w(t=1)']:.4f} | {o['eM_w(t=.5)']:.4f} | {o['eSM(t=.1)']:.4f} |")

# ---------- random categorical trees: exact vs linear for eM_w(t=1); objective agreement ----------
def gen_cat(rnd):
    K = "ABCDEFGH"[: rnd.choice([3, 5, 8])]
    raw = {k: rnd.gammavariate(rnd.choice([0.7, 3.0]), 1) + 1e-3 for k in K}; t = sum(raw.values()); pri = {k: v / t for k, v in raw.items()}
    tops = []
    for _ in range(rnd.randint(2, 10)):
        u = rnd.random(); X = rnd.choice(K)
        if u < .35: kap = forX(K, X)
        elif u < .6: kap = {c: (0.0 if c == X else 1.0) for c in K}
        else: kap = {c: rnd.choice([0.0, 0.5, 1.0]) for c in K}
        sub, _ = gen_tree(rnd, maxdepth=rnd.randint(1, 3))
        if rnd.random() < 0.3: sub.kids = []
        tops.append(Top(sub, strength(rnd), kap))
    return Cat(pri, tops)

def nodes_of(cat):
    out = []
    def go(n): out.append(n); [go(k) for k in n.kids]
    for t in cat.tops: go(t.node)
    return out

def random_study(trials=250, seed=3):
    rnd = random.Random(seed); rows = []; per = []
    for _ in range(trials):
        cat = gen_cat(rnd); vs = []
        for n in nodes_of(cat):
            ex = objectives(cat, n); li = objectives(cat, n, lin=True)
            vs.append({**{k: ex[k] for k in KEYS}, "lin_eMw": li["eM_w(t=1)"], "tan_eMw": tangent_eMw(cat, n)})
        rows += vs; per.append(vs)
    ref = "eM_w(t=1)"
    print(f"\n### random categorical trees (n={len(rows)} nodes, {trials} questions, K in 3/5/8): approximations of exact eM_w(t=1)")
    print("| variant | ratio 5% | 50% | 95% | min | premature@.01 | wasted | Spearman | top5 |\n|---|---|---|---|---|---|---|---|---|")
    for v in ["tan_eMw", "lin_eMw"]:
        rat = sorted(r[v] / r[ref] for r in rows if r[ref] >= 1e-4); qq = lambda f: rat[int(f * (len(rat) - 1))]
        prem = sum(1 for r in rows if r[v] < .01 <= r[ref]); waste = sum(1 for r in rows if r[ref] < .01 <= r[v])
        sp = [spearman([r[v] for r in t], [r[ref] for r in t]) for t in per if len(t) >= 6]
        tk = [topk([r[v] for r in t], [r[ref] for r in t]) for t in per if len(t) >= 6]
        print(f"| {v} | {qq(.05):.2f} | {qq(.5):.2f} | {qq(.95):.2f} | {rat[0]:.2f} | {prem} | {waste} | {sum(sp)/len(sp):.3f} | {sum(tk)/len(tk):.2f} |")
    print(f"\n### agreement of each exact objective with eM_w(t=1) (per-question Spearman / top-5 overlap)")
    print("| objective | Spearman | top5 | top1 |\n|---|---|---|---|")
    for v in KEYS:
        sp = [spearman([r[v] for r in t], [r[ref] for r in t]) for t in per if len(t) >= 6]
        tk = [topk([r[v] for r in t], [r[ref] for r in t]) for t in per if len(t) >= 6]
        t1 = [topk([r[v] for r in t], [r[ref] for r in t], 1) for t in per if len(t) >= 6]
        print(f"| {v} | {sum(sp)/len(sp):.3f} | {sum(tk)/len(tk):.2f} | {sum(t1)/len(t1):.2f} |")

if __name__ == "__main__":
    s0(); s1(); s2(); s3(); s4(); s5(); s6(); random_study()
