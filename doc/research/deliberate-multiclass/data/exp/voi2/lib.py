"""voi2: non-circular references for categorical VoI (computenet-1ow0x).
Reuses ../voi/{tree,cat}.py (closed-world LL, log-pooled over wlo/jnb/woe). Run from exp/voi2."""
import math, random, sys
sys.path.insert(0, "../voi")
from tree import *
from cat import Cat, Top, M, leader, weights, H, q_of
import verify as V

HL = Headline("consensus")

def nodes_of(cat):
    out = []
    def go(n): out.append(n); [go(k) for k in n.kids]
    for t in cat.tops: go(t.node)
    return out

def KL(a, b): return sum(v * (math.log(v) - math.log(max(b[k], 1e-300))) for k, v in a.items() if v > 0)

def ends(cat, n):
    return cat.resolved(n, 1.0), cat.resolved(n, 0.0)

# ---------------- candidate objectives + preposterior (model's own predictive) references ----------------
def all_scores(cat, n, s0=None, s1=None, sz=None, q=None):
    s0 = s0 or cat.dist()
    if s1 is None: s1, sz = ends(cat, n)
    q = q_of(n) if q is None else q
    E = lambda f: q * f(s1) + (1 - q) * f(sz)
    k0 = leader(s0); w = weights(s0, 1)
    o = {
        "eM_w": sum(w[k] * E(lambda s: abs(M(s, k) - M(s0, k))) for k in s0),
        "eM_hard": E(lambda s: abs(M(s, k0) - M(s0, k0))),
        "eTV": E(lambda s: sum(abs(s[k] - s0[k]) for k in s0)),
        "e|dH|": E(lambda s: abs(H(s) - H(s0))),
        "InfoGain": H(s0) - E(H),
        "P(flip)": E(lambda s: 1.0 if leader(s) != k0 else 0.0),
        # references: EVSI of resolving n under the model's own predictive (q) for three proper decision losses
        "EVSI_log": E(lambda s: KL(s, s0)),            # log score: expected KL(posterior || current)
        "EVSI_brier": E(lambda s: sum((s[k] - s0[k]) ** 2 for k in s0)),
        "EVSI_01": E(lambda s: max(s.values()) - s[k0]),  # 0-1 loss on the argmax answer
    }
    return o

CANDS = ["eM_w", "eM_hard", "eTV", "e|dH|", "InfoGain", "P(flip)"]
PREPOST = ["EVSI_log", "EVSI_brier", "EVSI_01"]

# ---------------- Monte Carlo to completion: joint generative model consistent with the tree ----------------
def local_vals(node, l, kv):
    f, en = LAY[l]; sup, att = [], []
    for k in node.kids:
        (sup if k.pol > 0 else att).append(en(k.s, kv[id(k)]))
    return f(node.p, att, sup)

def sample_world(cat, rnd):
    """every node resolves bottom-up: leaf ~ Bern(consensus of its base), internal ~ Bern(consensus credence given
    its children's resolutions). Returns (omega: id->0/1, s_final: dist with all tops at their resolution)."""
    om = {}
    def go(n):
        for k in n.kids: go(k)
        c = HL.of({l: local_vals(n, l, om) for l in MEMBERS})
        om[id(n)] = 1.0 if rnd.random() < c else 0.0
    cv = {}
    for i, t in enumerate(cat.tops):
        go(t.node); cv[i] = {l: om[id(t.node)] for l in MEMBERS}
    return om, cat.dist(cv)

def mc_refs(cat, nodes, pre, W, rnd):
    """pre: list of (s1, s0r) per node. Rao-Blackwellised over the true class T ~ s_final.
    MC_log: E[log s^{n:=w_n}_T - log s0_T]; MC_P: E[s^{n}_T - s0_T]; MC_01: E[1{argmax s^n = T} - 1{argmax s0 = T}];
    MC_brier: E[Brier(s0,T) - Brier(s^n,T)]. Also returns half-sample values for a noise ceiling."""
    s0 = cat.dist(); k0 = leader(s0)
    L = [(({k: math.log(max(v, 1e-300)) for k, v in a.items()}, leader(a), a), ({k: math.log(max(v, 1e-300)) for k, v in b.items()}, leader(b), b)) for a, b in pre]
    l0 = {k: math.log(max(v, 1e-300)) for k, v in s0.items()}
    n0 = sum(v * v for v in s0.values())
    names = ["MC_log", "MC_P", "MC_01", "MC_brier"]
    acc = [[{m: 0.0 for m in names} for _ in nodes] for _ in range(2)]
    for w in range(W):
        om, sf = sample_world(cat, rnd); h = w % 2
        base_log = sum(sf[k] * l0[k] for k in sf); base_P = sum(sf[k] * s0[k] for k in sf); base_01 = sf[k0]
        base_b = n0 - 2 * base_P
        for j, n in enumerate(nodes):
            lg, ld, a = L[j][0] if om[id(n)] == 1.0 else L[j][1]
            A = acc[h][j]
            A["MC_log"] += sum(sf[k] * lg[k] for k in sf) - base_log
            sp = sum(sf[k] * a[k] for k in sf)
            A["MC_P"] += sp - base_P
            A["MC_01"] += sf[ld] - base_01
            A["MC_brier"] += base_b - (sum(v * v for v in a.values()) - 2 * sp)
    half = W // 2
    full = [{m: (acc[0][j][m] + acc[1][j][m]) / W for m in names} for j in range(len(nodes))]
    halves = [[{m: acc[h][j][m] / half for m in names} for j in range(len(nodes))] for h in range(2)]
    return full, halves

MCN = ["MC_log", "MC_P", "MC_01", "MC_brier"]

# ---------------- population ----------------
def forX(K, X): return {c: (1.0 if c == X else 0.0) for c in K}
def kappa(rnd, K):
    u = rnd.random(); X = rnd.choice(K)
    if u < .25: return forX(K, X), "narrow-for"
    if u < .45: return {c: (0.0 if c == X else 1.0) for c in K}, "rules-out-one"
    if u < .6:
        Y = rnd.choice([c for c in K if c != X]); return {c: (0.0 if c == Y else 1.0) for c in K}, "contrast"  # X over Y
    if u < .75:
        m = rnd.randint(2, max(2, len(K) - 1)); S = set(rnd.sample(K, m)); return {c: (1.0 if c in S else 0.0) for c in K}, "broad-subset"
    return {c: rnd.choice([0.0, 0.25, 0.5, 0.75, 1.0]) for c in K}, "graded"

def gen_cat(rnd, Kmin=3, Kmax=8, cap=12):
    K = "ABCDEFGHIJ"[: rnd.randint(Kmin, Kmax)]
    raw = {k: rnd.gammavariate(rnd.choice([0.7, 3.0]), 1) + 1e-3 for k in K}; t = sum(raw.values()); pri = {k: v / t for k, v in raw.items()}
    tops = []
    for _ in range(rnd.randint(2, 7)):
        kap, _ = kappa(rnd, K)
        sub, _ = gen_tree(rnd, maxdepth=rnd.randint(1, 3), maxb=3, cap=cap)
        if rnd.random() < 0.25: sub.kids = []
        tops.append(Top(sub, strength(rnd), kap))
    return Cat(pri, tops)

# ---------------- stats ----------------
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
    return num / den if den else float("nan")
def topk(a, b, k=1):
    ta = set(sorted(range(len(a)), key=lambda i: -a[i])[:k]); tb = set(sorted(range(len(b)), key=lambda i: -b[i])[:k])
    return len(ta & tb) / k
def mean(xs): xs = [x for x in xs if x == x]; return sum(xs) / len(xs) if xs else float("nan")
