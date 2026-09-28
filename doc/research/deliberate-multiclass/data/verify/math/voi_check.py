"""C4/C5/C6/C7 checks. Faithful Kotlin layer ports from verify.py."""
import math, itertools
import verify as V
import sys
sys.path.insert(0, "../../combine")
import combine as C

MEMBERS = ["wlo", "jnb", "woe"]
LO, HI = 0.001, 0.999
def consensus(vals): return V.sig(sum(V.logit(min(max(v, LO), HI)) for v in vals) / len(vals))

# ---- tree: node = dict(base, kids=[(polarity, strength, node)]) ; credence per layer ----
def cred(node, layer, override=None):
    if override is not None and node is override[0]: return override[1]
    f, en, *_ = V.LAYERS[layer]
    sup, att = [], []
    for pol, s, kid in node.get("kids", []):
        e = en(s, cred(kid, layer, override))
        (sup if pol > 0 else att).append(e)
    return f(node["base"], att, sup)

def headline(root, override=None): return consensus([cred(root, l, override) for l in MEMBERS])

def sens(root, n, h=1e-6):
    """d headline(root)/d node, perturbing node's credence equally in every layer (= CredenceGraph.scalar)."""
    base = [cred(n, l) for l in MEMBERS]
    def H(dx):
        vals = []
        for l, b in zip(MEMBERS, base):
            vals.append(cred(root, l, (n, min(max(b + dx, 0), 1))))
        return consensus(vals)
    return (H(h) - H(-h)) / (2 * h)

def exact_resolve(root, n, r): return consensus([cred(root, l, (n, r)) for l in MEMBERS])

def chain(depth, s=0.9, leafbase=0.5, midbase=0.6, rootbase=0.5, pol=+1):
    leaf = {"base": leafbase}
    node = leaf
    for _ in range(depth):
        node = {"base": midbase, "kids": [(pol, s, node)]}
    return {"base": rootbase, "kids": [(+1, s, node)]}, leaf

def c4_c7():
    print("## C4 K=2: today's |g|4p(1-p) vs eM (margin M=2*root-1) exact vs linearised")
    print("| case | p | c_node | today |g|4p(1-p) | eM lin Δ=r-p | eM lin Δ=r-c | eM EXACT (r-c) |")
    print("|---|---|---|---|---|---|---|---|")
    cases = [("depth1 s=.5", chain(1, s=0.5)), ("depth1 s=.9", chain(1, s=0.9)), ("depth3 s=.9", chain(3, s=0.9)),
             ("depth2 s=1 midbase .95 (woe clamp)", chain(2, s=1.0, midbase=0.95)),
             ("depth1 attack s=.9", chain(1, s=0.9, pol=-1))]
    for name, (root, leaf) in cases:
        for p in (0.5, 0.2, 0.9):
            leaf["base"] = p  # leaf credence == its plausibility (no kids) -> c=p
            g = sens(root, leaf); r0 = headline(root); c = cred(leaf, "woe")
            today = abs(g) * 4 * p * (1 - p)
            lin_p = 2 * abs(g) * (p * (1 - p) + (1 - p) * p)
            lin_c = 2 * abs(g) * (p * (1 - c) + (1 - p) * c)
            ex = 2 * (p * abs(exact_resolve(root, leaf, 1) - r0) + (1 - p) * abs(exact_resolve(root, leaf, 0) - r0))
            print(f"| {name} | {p} | {c:.3f} | {today:.4f} | {lin_p:.4f} | {lin_c:.4f} | {ex:.4f} |")
    # interior node: credence != plausibility
    print("\n### interior node whose credence != its plausibility (p=0.2 judged, strong support below)")
    mid = {"base": 0.2, "kids": [(+1, 0.95, {"base": 0.95})]}
    root = {"base": 0.5, "kids": [(+1, 0.8, mid)]}
    p = 0.2; c = headline(mid); g = sens(root, mid); r0 = headline(root)
    ex = 2 * (p * abs(exact_resolve(root, mid, 1) - r0) + (1 - p) * abs(exact_resolve(root, mid, 0) - r0))
    print(f"p={p} c(consensus)={c:.3f} today={abs(g)*4*p*(1-p):.4f} eM_lin(r-c)={2*abs(g)*(p*(1-c)+(1-p)*c):.4f} eM_exact={ex:.4f}")

def eM_LL(claims_fn, pi, x0, cand, p, layer="woe"):
    """K-class eM with LL-<layer>, top-level claim credences x (tree part identity), exact combination."""
    f, en, w, pp, k = V.LAYERS[layer]
    def s(x): return V.LL([(w(en(st, x[i])), kp) for i, (st, kp) in enumerate(claims_fn)], pi, pp, k)
    s0 = s(x0); k1 = max(s0, key=s0.get)
    M = lambda d: d[k1] - max(v for kk, v in d.items() if kk != k1)
    out = 0
    for r, pr in ((1.0, p), (0.0, 1 - p)):
        x = list(x0); x[cand] = r; out += pr * abs(M(s(x)) - M(s0))
    return out, s0, k1

def c5():
    print("\n## C5 eM pathologies (LL-woe, closed world unless noted)")
    # (a) exact 5-way tie: structurally identical claims 'for A' (argmax tie-break winner) vs 'for E'
    cls = "ABCDE"; pi = {c: 0.2 for c in cls}
    forX = lambda X: (0.9, {c: (1 if c == X else 0) for c in cls})
    claims = [forX("A"), forX("E")]; x0 = [0.5, 0.5]
    a, s0, k1 = eM_LL(claims, pi, x0, 0, 0.5); e, *_ = eM_LL(claims, pi, x0, 1, 0.5)
    print(f"(a) 5-way tie s={[round(s0[c],3) for c in cls]} leader(tie-break)={k1}: eM(for A)={a:.4f} eM(for E)={e:.4f}")
    # near tie, perturb prior slightly the other way -> leader flips to E, values swap
    pi2 = dict(pi); pi2["E"] = 0.2001; t = sum(pi2.values()); pi2 = {c: v / t for c, v in pi2.items()}
    a2, _, k2 = eM_LL(claims, pi2, x0, 0, 0.5); e2, *_ = eM_LL(claims, pi2, x0, 1, 0.5)
    print(f"    prior E +0.0001 -> leader={k2}: eM(for A)={a2:.4f} eM(for E)={e2:.4f}  (discontinuous in s)")
    # (b) many near-tied classes, K=10: claim for leader vs claim for a 2nd-tier class
    cls10 = "ABCDEFGHIJ"; pi = {c: 0.1 + (0.001 if c == "A" else 0) for c in cls10}; t = sum(pi.values()); pi = {c: v / t for c, v in pi.items()}
    forX = lambda X: (0.9, {c: (1 if c == X else 0) for c in cls10})
    claims = [forX("A"), forX("J"), (0.9, {c: (0 if c == "A" else 1) for c in cls10})]
    vals = [eM_LL(claims, pi, [0.5, 0.5, 0.5], i, 0.5)[0] for i in range(3)]
    print(f"(b) K=10 near-uniform: eM for-leader={vals[0]:.4f} for-J={vals[1]:.4f} against-leader={vals[2]:.4f}")
    # (c) Omega leading in open world after contested binary debate (mlp, unclamped)
    for layer in ("woe", "mlp"):
        f, en, w, pp, k = V.LAYERS[layer]
        pi = {"A": .5 / 1.05, "B": .5 / 1.05, "O": .05 / 1.05}
        claims = [(w(en(0.9, 0.9)), {"A": 1, "B": 0})] * 3 + [(w(en(0.9, 0.9)), {"A": 0, "B": 1})] * 3
        d = V.LL(claims, pi, pp, k)
        print(f"(c) open world, 3 strong pros+3 strong cons on a 50/50 binary, LL-{layer}: A={d['A']:.3f} B={d['B']:.3f} Omega={d['O']:.3f} (binary layer: 0.5 tie)")

def c6():
    print("\n## C6 closed-frame TBM m(empty) on routine MECE debates (all claims coherent with a normal answer)")
    fr = {"A": 1 / 3, "B": 1 / 3, "C": 1 / 3}
    sing = lambda X, e: (e, {c: (1 if c == X else 0) for c in fr})
    elim = lambda X, e: (e, {c: (0 if c == X else 1) for c in fr})
    rows = [
        ("one pro-A (.6) + one pro-B (.6): ordinary two-sided", [sing("A", .6), sing("B", .6)]),
        ("pro-A x2 (.6) + pro-B (.5)", [sing("A", .6), sing("A", .6), sing("B", .5)]),
        ("one pro each for A,B,C (.6)", [sing("A", .6), sing("B", .6), sing("C", .6)]),
        ("pro-A .8 + 'rules out A' .6 (plain contested)", [sing("A", .8), elim("A", .6)]),
        ("rules out B .6 + rules out C .6 (consistent, points to A)", [elim("B", .6), elim("C", .6)]),
        ("reference: (iii-c) each of X,Y,Z ruled out .64 (none-of-these)", [elim("A", .64), elim("B", .64), elim("C", .64)]),
    ]
    for name, cl in rows:
        print(f"  m(∅)={C.conflict(cl, fr):.3f}  {name}")
    # binary analogue: at K=2 m(∅) = es*ea, i.e. any two-sided node
    print("  K=2 identity: m(∅) = probsum(sup)*probsum(att) -> nonzero on EVERY two-sided binary node")

if __name__ == "__main__":
    c4_c7(); c5(); c6()
