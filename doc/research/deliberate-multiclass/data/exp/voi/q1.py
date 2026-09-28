"""Q1: binary VoI variants vs exact expected root change, random realistic trees, every headline."""
import math, random, sys
from tree import *

EPS = 0.01

def variants_claim(hl, n):
    root = root_of(n); Rc = hl.of(root.c)
    g = scalar_sens(hl, n); gs = scalar_sens(hl, n, hop="sec"); g1 = scalar_sens(hl, n, first_secant=True)
    R1, R0 = R(hl, n, 1.0), R(hl, n, 0.0)
    p = n.p; q = node_headline(hl, n)
    C1, C0 = curve_R(hl, n, 1.0, 9), curve_R(hl, n, 0.0, 9)
    D1, D0 = curve_R(hl, n, 1.0, 5), curve_R(hl, n, 0.0, 5)
    E = lambda pi, a, b: 2 * (pi * abs(a - Rc) + (1 - pi) * abs(b - Rc))
    return {
        "T_p (today)": abs(g) * unc(p), "T_q": abs(g) * unc(q), "T_pq": 2 * abs(g) * (p * (1 - q) + (1 - p) * q),
        "SecChain_p": abs(gs) * unc(p), "Sec1stHop_p": abs(g1) * unc(p),
        "SecPath_p": abs(R1 - R0) * unc(p), "SecPath_q": abs(R1 - R0) * unc(q),
        "Curve9_q": E(q, C1, C0), "Curve5_q": E(q, D1, D0),
        "E_p": E(p, R1, R0), "E_q": E(q, R1, R0),
    }, n.kids != []

def variants_link(hl, n):
    root = root_of(n); Rc = hl.of(root.c); s = n.s
    g = scalar_sens(hl, n, edge=True)
    R1, R0 = R(hl, n, 1.0, edge=True), R(hl, n, 0.0, edge=True)
    C1, C0 = curve_R(hl, n, 1.0, 9, edge=True), curve_R(hl, n, 0.0, 9, edge=True)
    E = lambda a, b: 2 * (s * abs(a - Rc) + (1 - s) * abs(b - Rc))
    return {"T_s (today)": abs(g) * unc(s), "SecPath_s": abs(R1 - R0) * unc(s), "Curve9_s": E(C1, C0), "E_s": E(R1, R0)}

def rank(xs):
    idx = sorted(range(len(xs)), key=lambda i: xs[i]); r = [0.0] * len(xs); i = 0
    while i < len(xs):
        j = i
        while j + 1 < len(xs) and xs[idx[j + 1]] == xs[idx[i]]: j += 1
        for k in range(i, j + 1): r[idx[k]] = (i + j) / 2
        i = j + 1
    return r
def spearman(a, b):
    ra, rb = rank(a), rank(b); n = len(a); ma, mb = sum(ra) / n, sum(rb) / n
    num = sum((x - ma) * (y - mb) for x, y in zip(ra, rb))
    den = math.sqrt(sum((x - ma) ** 2 for x in ra) * sum((y - mb) ** 2 for y in rb))
    return num / den if den else 1.0
def topk(a, b, k=5):
    ta = set(sorted(range(len(a)), key=lambda i: -a[i])[:k]); tb = set(sorted(range(len(b)), key=lambda i: -b[i])[:k])
    return len(ta & tb) / k
def q(xs, f): xs = sorted(xs); return xs[int(f * (len(xs) - 1))]

def run(hl_kind, trees, seed, ref="E_q", link=False):
    rnd = random.Random(seed); hl = Headline(hl_kind)
    rows = []; per_tree = []
    for _ in range(trees):
        root, nodes = gen_tree(rnd)
        cands = nodes[1:]
        if link: vs = [variants_link(hl, n) for n in cands]
        else: vs = [variants_claim(hl, n)[0] for n in cands]
        rows += vs; per_tree.append(vs)
    names = list(rows[0])
    out = {}
    for v in names:
        ratios = [r[v] / r[ref] for r in rows if r[ref] >= 1e-4]
        prem = sum(1 for r in rows if r[v] < EPS <= r[ref]); waste = sum(1 for r in rows if r[ref] < EPS <= r[v])
        sp = [spearman([r[v] for r in t], [r[ref] for r in t]) for t in per_tree if len(t) >= 6]
        tk = [topk([r[v] for r in t], [r[ref] for r in t]) for t in per_tree if len(t) >= 6]
        t1 = [topk([r[v] for r in t], [r[ref] for r in t], 1) for t in per_tree if len(t) >= 6]
        out[v] = (q(ratios, .05), q(ratios, .5), q(ratios, .95), min(ratios), max(ratios), prem, waste,
                  sum(sp) / len(sp), sum(tk) / len(tk), sum(t1) / len(t1))
    nref = sum(1 for r in rows if r[ref] >= EPS)
    return out, len(rows), nref, sum(1 for t in per_tree if len(t) >= 6)

def table(hl_kind, trees=300, seed=1, ref="E_q", link=False):
    out, n, nref, nt = run(hl_kind, trees, seed, ref, link)
    print(f"\n### headline={hl_kind} ref={ref} {'LINKS' if link else 'claims'}: n={n} nodes, {nref} with ref>=eps, {nt} trees>=6 cands")
    print("| variant | ratio 5% | 50% | 95% | min | max | premature | wasted | Spearman | top5 | top1 |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for v, r in out.items():
        print(f"| {v} | {r[0]:.2f} | {r[1]:.2f} | {r[2]:.2f} | {r[3]:.2f} | {r[4]:.2f} | {r[5]} | {r[6]} | {r[7]:.3f} | {r[8]:.2f} | {r[9]:.2f} |")

if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "main"
    if which == "main":
        table("consensus", ref="E_q"); table("consensus", ref="E_p")
        table("consensus", ref="E_s", link=True)
    elif which == "layers":
        for hk in ["dfquad", "wlo", "jnb", "woe", "euler", "qe", "mlp"]:
            table(hk, trees=150, seed=2, ref="E_q")
